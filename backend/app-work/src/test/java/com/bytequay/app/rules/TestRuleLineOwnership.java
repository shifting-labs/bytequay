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
package com.bytequay.app.rules;

import com.bytequay.app.git.command.GitCommandRunner;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.pr.PullRequestRead;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.workspace.WorkspacePaths;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.hosting.HostingRepository;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockMakers;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestRuleLineOwnership
{
    private static final String HEAD = "a".repeat(40);
    private static final String BASE = "b".repeat(40);
    private static final String URL = "https://github.com/owner/repository.git";
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAXIMUM_OUTPUT = 2 * 1024 * 1024;
    private static final List<String> FETCH = ImmutableList.of("fetch", "--no-tags", "--no-recurse-submodules", "--no-write-fetch-head", URL,
            "+refs/pull/7/head:refs/bytequay/rules/7/head", "+refs/heads/main:refs/bytequay/rules/7/base");
    private static final List<String> HEAD_READ = ImmutableList.of("rev-parse", "refs/bytequay/rules/7/head");
    private static final List<String> BASE_READ = ImmutableList.of("merge-base", "refs/bytequay/rules/7/base", HEAD);
    private static final List<String> BLAME_READ = ImmutableList.of("blame", "--line-porcelain", "--no-textconv", "--encoding=UTF-8", BASE, "--", "-source file.java");
    private static final List<DiffFile> FILES = ImmutableList.of(new DiffFile("-source file.java", "modified", 1, 1, "@@ -1 +1 @@\n-old\n+new\n"));
    private static final String BLAME = BASE + " 1 1 1\nauthor-mail <me@example.com>\n\told\n";

    private final GitCommandRunner git = mock(GitCommandRunner.class);
    private final WorkspacePaths paths = mock(WorkspacePaths.class, withSettings().mockMaker(MockMakers.INLINE));
    private final CredentialService credentials = mock(CredentialService.class);
    private final PullRequestService reads = mock(PullRequestService.class);
    private final Workspace workspace = mock(Workspace.class, withSettings().mockMaker(MockMakers.INLINE));
    private final PullRequestRead route = mock(PullRequestRead.class, withSettings().mockMaker(MockMakers.INLINE));

    @Test
    void fetchesOnlyOwnedRefsAndReadsBoundedBlameAtTheVerifiedMergeBase(@TempDir Path repository)
    {
        RuleLineOwnership ownership = reader(repository);
        assertThat(ownership.touches(workspace, route, HEAD, "main", FILES)).isTrue();
        verify(git).run(repository, "check-ref-format", "refs/heads/main");
        verify(git).run(repository, Duration.ofMinutes(2), ImmutableMap.of(), FETCH);
        verify(git).runLocalBoundedStdout(repository, READ_TIMEOUT, MAXIMUM_OUTPUT, HEAD_READ);
        verify(git).runLocalBoundedStdout(repository, READ_TIMEOUT, MAXIMUM_OUTPUT, BASE_READ);
        verify(git).runLocalBoundedStdout(repository, READ_TIMEOUT, 4096, ImmutableList.of("config", "--get", "user.email"));
        verify(git).runLocalBoundedStdout(repository, READ_TIMEOUT, MAXIMUM_OUTPUT, BLAME_READ);
        verifyNoMoreInteractions(git);
    }

    @Test
    void aMovedHeadStopsBeforeReadingAttribution(@TempDir Path repository)
    {
        RuleLineOwnership ownership = reader(repository);
        when(git.runLocalBoundedStdout(repository, READ_TIMEOUT, MAXIMUM_OUTPUT, HEAD_READ)).thenReturn(new GitCommandRunner.Result(0, "c".repeat(40), false));
        assertThatThrownBy(() -> ownership.touches(workspace, route, HEAD, "main", FILES)).hasMessageContaining("changed while checking");
        verify(git).run(repository, "check-ref-format", "refs/heads/main");
        verify(git).run(repository, Duration.ofMinutes(2), ImmutableMap.of(), FETCH);
        verify(git).runLocalBoundedStdout(repository, READ_TIMEOUT, MAXIMUM_OUTPUT, HEAD_READ);
        verifyNoMoreInteractions(git);
    }

    @Test
    void aPartialBlameCannotAuthorizeEvenWhenItsPrefixNamesTheUser(@TempDir Path repository)
    {
        RuleLineOwnership ownership = reader(repository);
        when(git.runLocalBoundedStdout(repository, READ_TIMEOUT, MAXIMUM_OUTPUT, BLAME_READ)).thenReturn(new GitCommandRunner.Result(0, BLAME, true));
        assertThatThrownBy(() -> ownership.touches(workspace, route, HEAD, "main", FILES)).hasMessageContaining("could not be read completely");
    }

    private RuleLineOwnership reader(Path repository)
    {
        when(workspace.id()).thenReturn("workspace");
        when(paths.repository(workspace)).thenReturn(repository);
        when(route.number()).thenReturn(7);
        when(route.login()).thenReturn("viewer");
        when(route.repository()).thenReturn(new HostingRepository(1, 1, "1", "owner/repository", URI.create("https://github.com/owner/repository"),
                URI.create(URL), Optional.empty(), Optional.of("main")));
        when(reads.requestHeadRef(route)).thenReturn("refs/pull/7/head");
        when(credentials.gitAuthenticationEnvironment("workspace", ImmutableList.of(URL))).thenReturn(ImmutableMap.of());
        when(git.run(repository, "check-ref-format", "refs/heads/main")).thenReturn(new GitCommandRunner.Result(0, "", false));
        when(git.run(repository, Duration.ofMinutes(2), ImmutableMap.of(), FETCH)).thenReturn(new GitCommandRunner.Result(0, "", false));
        when(git.runLocalBoundedStdout(repository, READ_TIMEOUT, MAXIMUM_OUTPUT, HEAD_READ)).thenReturn(new GitCommandRunner.Result(0, HEAD, false));
        when(git.runLocalBoundedStdout(repository, READ_TIMEOUT, MAXIMUM_OUTPUT, BASE_READ)).thenReturn(new GitCommandRunner.Result(0, BASE, false));
        when(git.runLocalBoundedStdout(repository, READ_TIMEOUT, 4096, ImmutableList.of("config", "--get", "user.email")))
                .thenReturn(new GitCommandRunner.Result(0, "me@example.com", false));
        when(git.runLocalBoundedStdout(repository, READ_TIMEOUT, MAXIMUM_OUTPUT, BLAME_READ)).thenReturn(new GitCommandRunner.Result(0, BLAME, false));
        return new RuleLineOwnership(git, paths, credentials, reads);
    }

    @Test
    void matchesChangedOldLinesRatherThanContextOrNewInsertions()
    {
        assertThat(RuleLineOwnership.removedLines("""
                @@ -20,3 +20,4 @@
                 context
                -old value
                +new value
                +inserted
                 context
                @@ -50 +51 @@
                -second value
                +replacement
                """)).containsExactlyInAnyOrder(21, 50);
        assertThat(RuleLineOwnership.removedLines("@@ -0,0 +1 @@\n+new file\n")).isEmpty();
        assertThat(RuleLineOwnership.removedLines(null)).isEmpty();
    }

    @Test
    void authorIdentityMustBelongToTheChangedLine()
    {
        String blame = """
                %s 1 1 1
                author-mail <me@example.com>
                \tunchanged
                %s 2 2 1
                author-mail <someone@example.com>
                \tchanged
                """.formatted("a".repeat(40), "b".repeat(40));
        assertThat(RuleLineOwnership.attributed(blame, ImmutableSet.of(2), "me@example.com", "viewer")).isFalse();
        assertThat(RuleLineOwnership.attributed(blame, ImmutableSet.of(1), "me@example.com", "viewer")).isTrue();
    }

    @Test
    void recognizesBothGitHubNoreplyFormatsWithoutMatchingSimilarLogins()
    {
        String blame = "a".repeat(40) + " 1 1 1\nauthor-mail <123+viewer@users.noreply.github.com>\n\tchanged\n";
        assertThat(RuleLineOwnership.attributed(blame, ImmutableSet.of(1), "", "viewer")).isTrue();
        assertThat(RuleLineOwnership.attributed(blame, ImmutableSet.of(1), "", "view")).isFalse();
        assertThat(RuleLineOwnership.attributed(blame.replace("123+viewer", "viewer"), ImmutableSet.of(1), "", "VIEWER")).isTrue();
    }
}
