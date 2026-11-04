/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.bytequay.app.workspace;

import com.bytequay.app.service.hosting.HostingIntegrationFixtures;
import com.bytequay.app.service.hosting.HostingIntegrations;
import com.bytequay.app.service.hosting.HostingReadSession;
import com.bytequay.app.workspace.WorkspaceRelationships.LineNode;
import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingIntegration;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.HostingTarget;
import com.bytequay.core.hosting.IssueClient;
import com.bytequay.core.hosting.PullRequestContentReader;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.bytequay.app.workspace.ComposerSuggestionService.Command.BACKPORT;
import static com.bytequay.app.workspace.ComposerSuggestionService.Command.FIX;
import static com.bytequay.app.workspace.ComposerSuggestionService.Command.REVIEW;
import static com.bytequay.core.domain.PullRequest.Origin.OTHER;
import static com.bytequay.core.hosting.HostingFailure.Kind.FORBIDDEN;
import static com.bytequay.core.hosting.HostingProvider.GITHUB;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestComposerSuggestionService
{
    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");
    private final WorkspaceHostingService hosting = mock(WorkspaceHostingService.class);
    private final HostingIntegrations integrations = mock(HostingIntegrations.class);
    private final PullRequestContentReader contents = mock(PullRequestContentReader.class);
    private final IssueClient issues = mock(IssueClient.class);
    private final WorkspaceRelationships relationships = mock(WorkspaceRelationships.class);
    private final ComposerSuggestionService service = new ComposerSuggestionService(hosting, integrations, relationships);

    @BeforeEach
    void setUp()
    {
        when(hosting.storedRead("workspace")).thenReturn(route(1, "account", 10, "alice"));
        HostingIntegration base = HostingIntegrationFixtures.issues(GITHUB, issues);
        HostingIntegration integration = new HostingIntegration(base.provider(), base.since(), base.capabilities(), base.accounts(), base.repositories(), base.pullRequests(),
                contents, base.writer(), issues, base.metadata(), base.discovery(), base.ci(), base.team());
        when(integrations.of(any(HostingTarget.class))).thenReturn(integration);
    }

    @Test
    void backportListsOneSearchPageOfMergedRequestsInProviderOrder()
    {
        when(contents.search(any(), eq(true), eq(Optional.empty()), eq(""), eq(1), eq(20))).thenReturn(ImmutableList.of(pr(2, "Merged later"), pr(1, "Merged first")));
        when(contents.search(any(), eq(true), eq(Optional.empty()), eq(""), eq(2), eq(20))).thenReturn(ImmutableList.of(pr(0, "Merged long ago")));

        assertThat(service.list("workspace", BACKPORT, "", 1)).extracting(ComposerSuggestion::number).containsExactly(2, 1);
        assertThat(service.list("workspace", BACKPORT, "", 2)).extracting(ComposerSuggestion::number).containsExactly(0);
        verifyNoInteractions(issues);
    }

    @Test
    void reviewLeavesMyRequestsOutOfTheSearchAndPutsANumberFirst()
    {
        when(contents.search(any(), eq(false), eq(Optional.of("alice")), eq("sact"), eq(1), eq(20))).thenReturn(ImmutableList.of(pr(123, "Improve transactional handling")));
        when(contents.search(any(), eq(false), eq(Optional.of("alice")), eq("#123"), eq(1), eq(20)))
                .thenReturn(ImmutableList.of(pr(77, "Mentions it in its body"), pr(123, "Improve transactional handling")));

        assertThat(service.list("workspace", REVIEW, "  SACT  ", 1)).extracting(ComposerSuggestion::number).containsExactly(123);
        assertThat(service.list("workspace", REVIEW, "#123", 1)).extracting(ComposerSuggestion::number).containsExactly(123, 77);
    }

    @Test
    void fixSearchesOpenIssues()
    {
        when(issues.search(any(), eq("sact"), eq(2), eq(20))).thenReturn(ImmutableList.of(issue(123, "Older transactional bug")));

        assertThat(service.list("workspace", FIX, "SACT", 2)).singleElement().satisfies(found -> {
            assertThat(found.number()).isEqualTo(123);
            assertThat(found.url()).isEqualTo("https://github.com/owner/project/issues/123");
        });
        verifyNoInteractions(contents);
    }

    @Test
    void textWithoutALetterOrDigitReadsNothing()
    {
        assertThat(service.list("workspace", REVIEW, "%", 1)).isEmpty();
        assertThat(service.list("workspace", REVIEW, "a".repeat(101), 1)).isEmpty();
        verifyNoInteractions(hosting, integrations);
    }

    @Test
    void backportSearchesTheRepoLineUpstreamFromEitherEnd()
    {
        List<LineNode> line = ImmutableList.of(new LineNode("upstream", Optional.empty()), new LineNode("fork", Optional.of("upstream")));
        when(relationships.line("fork")).thenReturn(line);
        when(relationships.line("upstream")).thenReturn(line);
        when(hosting.storedRead("upstream")).thenReturn(route(1, "account", 11, "alice"));
        when(hosting.storedRead("fork")).thenReturn(route(1, "account", 12, "alice"));
        when(contents.search(any(), eq(true), any(), anyString(), anyInt(), anyInt())).thenReturn(ImmutableList.of(pr(7, "Upstream fix")));
        when(issues.search(any(), anyString(), anyInt(), anyInt())).thenReturn(ImmutableList.of(issue(8, "Fork bug")));

        assertThat(service.list("fork", BACKPORT, "", 1)).extracting(ComposerSuggestion::number).containsExactly(7);
        assertThat(service.list("upstream", BACKPORT, "", 1)).extracting(ComposerSuggestion::number).containsExactly(7);
        assertThat(service.list("fork", FIX, "", 1)).extracting(ComposerSuggestion::number).containsExactly(8);
        verify(hosting, times(2)).storedRead("upstream");
        verify(hosting).storedRead("fork");
        verify(contents).search(any(), eq(true), any(), anyString(), anyInt(), anyInt());
    }

    @Test
    void cacheKeepsAccountsRepositoriesCredentialScopesQueriesAndPagesSeparate()
    {
        when(issues.search(any(), anyString(), anyInt(), eq(20))).thenReturn(
                ImmutableList.of(issue(1, "Account one")),
                ImmutableList.of(issue(2, "Account two")),
                ImmutableList.of(issue(3, "Other credential scope")),
                ImmutableList.of(issue(4, "Other repository")),
                ImmutableList.of(issue(5, "Other query")),
                ImmutableList.of(issue(6, "Other page")));
        assertThat(service.list("workspace", FIX, "", 1)).extracting(ComposerSuggestion::number).containsExactly(1);
        when(hosting.storedRead("workspace")).thenReturn(route(2, "account", 10, "alice"));
        assertThat(service.list("workspace", FIX, "", 1)).extracting(ComposerSuggestion::number).containsExactly(2);
        when(hosting.storedRead("workspace")).thenReturn(route(2, "other-scope", 10, "alice"));
        assertThat(service.list("workspace", FIX, "", 1)).extracting(ComposerSuggestion::number).containsExactly(3);
        when(hosting.storedRead("workspace")).thenReturn(route(2, "other-scope", 11, "alice"));
        assertThat(service.list("workspace", FIX, "", 1)).extracting(ComposerSuggestion::number).containsExactly(4);
        assertThat(service.list("workspace", FIX, "Query", 1)).extracting(ComposerSuggestion::number).containsExactly(5);
        assertThat(service.list("workspace", FIX, "query", 1)).extracting(ComposerSuggestion::number).containsExactly(5);
        assertThat(service.list("workspace", FIX, "query", 2)).extracting(ComposerSuggestion::number).containsExactly(6);
        when(hosting.storedRead("workspace")).thenReturn(route(1, "account", 10, "alice"));
        assertThat(service.list("workspace", FIX, "", 1)).extracting(ComposerSuggestion::number).containsExactly(1);
        verify(issues, times(6)).search(any(), anyString(), anyInt(), eq(20));
    }

    @Test
    void failedLoadsKeepTheirOriginalFailureAndAreRetried()
    {
        HostingFailure failure = new HostingFailure(FORBIDDEN, "Repository is inaccessible");
        when(contents.search(any(), anyBoolean(), any(), anyString(), anyInt(), anyInt())).thenThrow(failure).thenReturn(ImmutableList.of(pr(1, "Change")));

        assertThatThrownBy(() -> service.list("workspace", REVIEW, "", 1)).isSameAs(failure);
        assertThat(service.list("workspace", REVIEW, "", 1)).extracting(ComposerSuggestion::number).containsExactly(1);
        verify(contents, times(2)).search(any(), eq(false), any(), eq(""), eq(1), eq(20));
    }

    @Test
    void rejectsUnboundedSearchesAndPagesBeforeReading()
    {
        assertThatThrownBy(() -> service.list("workspace", FIX, "x".repeat(501), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list("workspace", FIX, "", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list("workspace", FIX, "", 51)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(hosting, integrations, relationships);
    }

    private static WorkspaceHostingRead route(long account, String scope, long repository, String login)
    {
        HostingInstance instance = new HostingInstance(1, GITHUB, URI.create("https://github.com"), URI.create("https://api.github.com"));
        HostingRepository repo = new HostingRepository(repository, 1, "100", "owner/project", URI.create("https://github.com/owner/project"),
                URI.create("https://github.com/owner/project.git"), Optional.empty(), Optional.of("main"));
        return new WorkspaceHostingRead(new HostingReadSession(account, scope, new HostingAccess(instance, "token")), repo, login);
    }

    private static FetchedPullRequest pr(int number, String title)
    {
        return new FetchedPullRequest((long) number, "owner/project", number, title, "bob", "https://github.com/owner/project/pull/" + number,
                NOW, NOW, OTHER, ImmutableList.of(), ImmutableMap.of(), false, "open", null, null, ImmutableList.of(), null, null, null);
    }

    private static RepositoryIssue issue(int number, String title)
    {
        return new RepositoryIssue(number, number, title, "body", "alice", "", "https://github.com/owner/project/issues/" + number,
                ImmutableList.of(), ImmutableList.of(), 0, NOW, NOW);
    }
}
