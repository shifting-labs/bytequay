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
package com.bytequay.app.assistant;

import com.bytequay.app.review.ReviewPublicationService;
import com.bytequay.app.service.hosting.HostingIntegrationFixtures;
import com.bytequay.app.service.hosting.HostingIntegrations;
import com.bytequay.app.service.hosting.HostingReadSession;
import com.bytequay.app.service.pr.PullRequestRead;
import com.bytequay.app.service.pr.PullRequestRoute;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.core.domain.AgendaTarget;
import com.bytequay.core.domain.CreatedPullRequest;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.PullRequestIdentity;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.PullRequestTask;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepoUser;
import com.bytequay.core.domain.RepositoryLabel;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.core.hosting.HostingPullRequest;
import com.bytequay.core.hosting.HostingServer;
import com.bytequay.core.hosting.HostingTarget;
import com.bytequay.core.hosting.PullRequestContentReader;
import com.bytequay.core.hosting.PullRequestWriter;
import com.bytequay.core.review.ReviewDraft;
import com.bytequay.core.review.ReviewDraftItem;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.PullRequestTaskStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestAssistantPullRequests
{
    private static final Instant NOW = Instant.parse("2026-11-06T09:00:00Z");
    private static final String REPO = "owner/repository";
    private static final String REF = "pr:owner/repository#58";
    private static final String HEAD = "a".repeat(40);
    private static final AgendaTarget TARGET = AgendaTarget.pull(5, REPO, 58, Optional.of("ws1"));

    private final PullRequestService service = mock(PullRequestService.class);
    private final ReviewPublicationService reviews = mock(ReviewPublicationService.class);
    private final PullRequestStore pullRequests = mock(PullRequestStore.class);
    private final PrDetailStore details = mock(PrDetailStore.class);
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final PullRequestRead route = mock(PullRequestRead.class, withSettings().mockMaker(MockMakers.INLINE));
    private final PullRequestRead moved = mock(PullRequestRead.class, withSettings().mockMaker(MockMakers.INLINE));
    private final Workspace repository = new Workspace("ws1", "Repository", 1, RepoRef.of("owner", "repository"), Optional.empty(), Optional.empty(), 1,
            RepoRef.of("owner", "repository"), Workspace.Topology.DIRECT, "master", "https://github.com/owner/repository.git", Optional.empty(), Optional.empty(),
            "/managed/repository", Optional.empty(), Optional.empty(), 0, NOW, NOW, HostingServer.GITHUB_COM);
    private final PullRequestTaskStore tasks = mock(PullRequestTaskStore.class);
    private final HostingIntegrations integrations = mock(HostingIntegrations.class);
    private final PullRequestContentReader contents = mock(PullRequestContentReader.class);
    private final PullRequestWriter writer = mock(PullRequestWriter.class);
    private final AssistantPullRequests pulls = new AssistantPullRequests(service, reviews, pullRequests, details, workspaces, tasks, integrations);

    @BeforeEach
    void setUp()
    {
        when(pullRequests.findIdByRepoAndNumber(REPO, 58)).thenReturn(Optional.of(5L));
        when(pullRequests.findHostingIdentity(5)).thenReturn(Optional.of(new HostingPullRequest(5, 20, 58)));
        when(workspaces.findByHostingRepositoryId(20)).thenReturn(Optional.of(repository));
        when(workspaces.isWatched("ws1")).thenReturn(true);
        when(service.prepareRead("ws1", REPO, 58)).thenReturn(route);
        when(route.login()).thenReturn("viewer");
    }

    @Test
    void closesAnOpenPullRequestAfterReadingItAgain()
    {
        fresh("open", false, "octocat", HEAD, ImmutableList.of());
        AssistantWork.Done done = pulls.close(REF);

        verify(service).closePullRequest(route);
        assertThat(done).isEqualTo(new AssistantWork.Done("Closed “Fix the cache”", Optional.of("owner/repository#58"), Optional.of(TARGET), "owner/repository#58"));

        fresh("closed", false, "octocat", HEAD, ImmutableList.of());
        assertThatThrownBy(() -> pulls.close(REF)).hasMessage("That pull request is not open");
        assertThatThrownBy(() -> pulls.close("pr:owner/repository#59")).hasMessage("ByteQuay no longer holds that pull request");
        when(workspaces.isWatched("ws1")).thenReturn(false);
        assertThatThrownBy(() -> pulls.close(REF)).hasMessage("That pull request's repository is not watched");
    }

    @Test
    void marksOnlyADraftReady()
    {
        fresh("open", true, "octocat", HEAD, ImmutableList.of());
        assertThat(pulls.markReady(REF).line()).isEqualTo("Marked ready for review: “Fix the cache”");
        verify(service).markReadyForReview(route);

        fresh("open", false, "octocat", HEAD, ImmutableList.of());
        assertThatThrownBy(() -> pulls.markReady(REF)).hasMessage("It's already ready for review");
    }

    @Test
    void reopensOnlyAClosedPullRequestThatWasNotMerged()
    {
        fresh("closed", false, "octocat", HEAD, ImmutableList.of());
        assertThat(pulls.reopen(REF).line()).isEqualTo("Reopened “Fix the cache”");
        verify(service).reopenPullRequest(route);

        fresh("open", false, "octocat", HEAD, ImmutableList.of());
        assertThatThrownBy(() -> pulls.reopen(REF)).hasMessage("It's already open");
        fresh("closed", false, "octocat", HEAD, ImmutableList.of());
        when(service.refreshForAction(route).mergedAt()).thenReturn(NOW);
        assertThatThrownBy(() -> pulls.reopen(REF)).hasMessage("It was merged, so it can't be reopened");
        verify(service, times(1)).reopenPullRequest(route);
    }

    @Test
    void turnsOnlyAnOpenPullRequestBackIntoADraft()
    {
        fresh("open", false, "octocat", HEAD, ImmutableList.of());
        assertThat(pulls.markDraft(REF).line()).isEqualTo("Turned back into a draft: “Fix the cache”");
        verify(service).markDraft(route);

        fresh("open", true, "octocat", HEAD, ImmutableList.of());
        assertThatThrownBy(() -> pulls.markDraft(REF)).hasMessage("It's already a draft");
        fresh("closed", false, "octocat", HEAD, ImmutableList.of());
        assertThatThrownBy(() -> pulls.markDraft(REF)).hasMessage("That pull request is not open");
        verify(service, times(1)).markDraft(route);
    }

    @Test
    void updatesTheBranchFromTheHeadTheUserSaw()
    {
        hosted(HostingServer.GITHUB_COM);
        stored(HEAD);
        fresh("open", false, "octocat", HEAD, ImmutableList.of());
        when(service.refreshForAction(route).baseRef()).thenReturn("master");

        assertThat(pulls.updateBranch(REF, "update #58 with master").line()).isEqualTo("Merging master into “Fix the cache”");
        verify(service).updateBranch(route, HEAD, false);
        assertThat(pulls.updateBranch(REF, "rebase #58").line()).isEqualTo("Rebasing onto master: “Fix the cache”");
        verify(service).updateBranch(route, HEAD, true);

        stored("b".repeat(40));
        assertThatThrownBy(() -> pulls.updateBranch(REF, "rebase #58")).hasMessage("New commits arrived since you last saw it; open the pull request to check them");
        verify(service, times(2)).updateBranch(any(), anyString(), anyBoolean());
    }

    @Test
    void rebasesAGitLabBranchOnlyWhenTheWordsSaySo()
    {
        hosted(new HostingServer(HostingProvider.GITLAB, URI.create("https://gitlab.example.com"), URI.create("https://gitlab.example.com/api/v4")));
        fresh("open", false, "octocat", HEAD, ImmutableList.of());
        when(service.refreshForAction(route).baseRef()).thenReturn("main");

        assertThatThrownBy(() -> pulls.updateBranch(REF, "update #58 with main"))
                .hasMessage("GitLab can only rebase it, which rewrites its commits; ask to rebase it if you want that");
        assertThatThrownBy(() -> pulls.updateBranch(REF, "don't rebase #58")).hasMessageContaining("GitLab can only rebase it");
        assertThat(pulls.updateBranch(REF, "rebase #58 on main").line()).isEqualTo("Rebasing onto main: “Fix the cache”");
        verify(service, times(1)).updateBranch(route, HEAD, true);
    }

    @Test
    void movesTheUsersOwnPullRequestToANewBranch()
    {
        renamable(ImmutableList.of());
        when(writer.create(any(), eq(RepoRef.of("owner", "repository")), eq("fix-cache-v2"), eq("master"), eq("Fix the cache"), eq("Cache fix"), eq(false)))
                .thenReturn(new CreatedPullRequest(900, 59, "https://github.com/owner/repository/pull/59"));

        AssistantWork.Done done = pulls.renameBranch(REF, "fix-cache-v2");

        var order = inOrder(writer, service);
        order.verify(writer).createBranch(any(), eq(RepoRef.of("owner", "repository")), eq("fix-cache-v2"), eq(HEAD));
        order.verify(writer).create(any(), any(), any(), any(), any(), any(), eq(false));
        order.verify(writer).close(any(), eq(58));
        order.verify(service).refreshForAction(moved);
        assertThat(done).isEqualTo(new AssistantWork.Done("Moved “Fix the cache” to fix-cache-v2", Optional.of("owner/repository#59 · #58 closed, its branch kept"),
                Optional.of(AgendaTarget.pull(6, REPO, 59, Optional.of("ws1"))), "owner/repository#59"));
    }

    @Test
    void aRetryReusesThePullRequestAnEarlierAttemptOpened()
    {
        renamable(ImmutableList.of(identity(59, "fix-cache-v2", HEAD, PullRequestIdentity.State.OPEN)));

        assertThat(pulls.renameBranch(REF, "fix-cache-v2").detail()).contains("owner/repository#59 · #58 closed, its branch kept");

        verify(writer, never()).createBranch(any(), any(), any(), any());
        verify(writer, never()).create(any(), any(), any(), any(), any(), any(), anyBoolean());
        verify(writer).close(any(), eq(58));
    }

    @Test
    void movesOnlyTheUsersOwnPullRequestThatNoTaskFollows()
    {
        renamable(ImmutableList.of());
        assertThatThrownBy(() -> pulls.renameBranch(REF, "fix cache")).hasMessage("“fix cache” isn't a branch name ByteQuay can use");
        assertThatThrownBy(() -> pulls.renameBranch(REF, "fix..cache")).hasMessage("“fix..cache” isn't a branch name ByteQuay can use");
        assertThatThrownBy(() -> pulls.renameBranch(REF, "fix-cache")).hasMessage("It's already on fix-cache");
        fresh("open", false, "octocat", HEAD, ImmutableList.of());
        assertThatThrownBy(() -> pulls.renameBranch(REF, "fix-cache-v2")).hasMessage("Only your own pull request can move to a new branch");
        when(tasks.find("ws1", REPO, 58)).thenReturn(Optional.of(mock(PullRequestTask.class, withSettings().mockMaker(MockMakers.INLINE))));
        assertThatThrownBy(() -> pulls.renameBranch(REF, "fix-cache-v2")).hasMessageContaining("A task follows this pull request");
        verify(writer, never()).createBranch(any(), any(), any(), any());
        verify(writer, never()).close(any(), anyInt());
    }

    @Test
    void saysWhatWasDoneWhenClosingTheOldPullRequestFails()
    {
        renamable(ImmutableList.of());
        when(writer.create(any(), any(), any(), any(), any(), any(), anyBoolean())).thenReturn(new CreatedPullRequest(900, 59, "https://github.com/owner/repository/pull/59"));
        doThrow(new IllegalStateException("GitHub is down")).when(writer).close(any(), eq(58));

        assertThatThrownBy(() -> pulls.renameBranch(REF, "fix-cache-v2")).hasMessage("Opened #59 from fix-cache-v2, but closing #58 failed: GitHub is down. Ask again to finish");
    }

    @Test
    void addsTheRepositorysLabelsAndKeepsTheRest()
    {
        fresh("open", false, "octocat", HEAD, ImmutableList.of(new PullRequestDetail.Label("bug", "d73a4a"), new PullRequestDetail.Label("wip", "ededed")));
        when(service.listRepositoryLabels(REPO, PullRequestRoute.of("ws1", null))).thenReturn(ImmutableList.of(new RepositoryLabel(1, "bug", "d73a4a"),
                new RepositoryLabel(2, "wip", "ededed"), new RepositoryLabel(3, "ready-to-merge", "0e8a16")));

        AssistantWork.Done done = pulls.setLabels(REF, ImmutableList.of("Ready-To-Merge", "bug"), ImmutableList.of("WIP"));

        verify(service).updateMetadata(route, Optional.empty(), Optional.of(ImmutableList.of("bug", "ready-to-merge")));
        assertThat(done.line()).isEqualTo("Labeled “Fix the cache”");
        assertThat(done.detail()).contains("owner/repository#58 · +ready-to-merge −wip");
        assertThatThrownBy(() -> pulls.setLabels(REF, ImmutableList.of("nope"), ImmutableList.of())).hasMessage("owner/repository has no label nope");
    }

    @Test
    void asksOnlyPeopleWhoCanReview()
    {
        fresh("open", false, "octocat", HEAD, ImmutableList.of());
        when(service.listRepositoryUsers(REPO, PullRequestRoute.of("ws1", null))).thenReturn(ImmutableList.of(new RepoUser("octocat", null, false),
                new RepoUser("hubot", null, true)));
        when(service.listRepositoryTeams(REPO, PullRequestRoute.of("ws1", null))).thenReturn(ImmutableList.of("owner/storage"));

        AssistantWork.Done done = pulls.requestReview(REF, ImmutableList.of("Octocat", "owner/storage"), ImmutableList.of("hubot"));

        verify(service).updateReviewerRequest(route, "Octocat", true);
        verify(service).updateReviewerRequest(route, "owner/storage", true);
        verify(service).updateReviewerRequest(route, "hubot", false);
        assertThat(done.line()).isEqualTo("Asked Octocat, owner/storage to review; took back hubot on “Fix the cache”");
        assertThatThrownBy(() -> pulls.requestReview(REF, ImmutableList.of("sam"), ImmutableList.of())).hasMessage("sam can't review owner/repository");
    }

    @Test
    void approvesOnlyTheHeadTheUserSawWithAnEmptyDraft()
    {
        stored(HEAD);
        fresh("open", false, "octocat", HEAD, ImmutableList.of());
        ReviewDraft draft = ReviewDraft.empty("d1", REPO, 58, NOW).withVersion(3);
        when(reviews.draft(route)).thenReturn(draft);
        when(reviews.saveReview(route, 3, "", "APPROVE")).thenReturn(draft.withReview("", "APPROVE", NOW).withVersion(4));

        assertThat(pulls.approve(REF).line()).isEqualTo("Approved “Fix the cache”");
        verify(reviews).submit(route, 4, "", "APPROVE", HEAD);

        fresh("open", false, "viewer", HEAD, ImmutableList.of());
        assertThatThrownBy(() -> pulls.approve(REF)).hasMessage("You can't approve your own pull request");
        fresh("open", false, "octocat", "b".repeat(40), ImmutableList.of());
        assertThatThrownBy(() -> pulls.approve(REF)).hasMessage("New commits arrived since you last saw it; open the pull request to check them");
        fresh("open", false, "octocat", HEAD, ImmutableList.of());
        ReviewDraftItem comment = mock(ReviewDraftItem.class, withSettings().mockMaker(MockMakers.INLINE));
        when(reviews.draft(route)).thenReturn(draft.withItems(ImmutableList.of(comment), NOW));
        assertThatThrownBy(() -> pulls.approve(REF)).hasMessage("Your review draft has comments; send it from the pull request");
        verify(reviews, times(1)).submit(any(), anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void commentsAndRetitlesWithTheGivenWords()
    {
        fresh("closed", false, "octocat", HEAD, ImmutableList.of());
        assertThat(pulls.comment(REF, "Superseded by #61").detail()).contains("owner/repository#58 · “Superseded by #61”");
        verify(service).commentOnPullRequest(route, "Superseded by #61");

        AssistantWork.Done renamed = pulls.edit(REF, Optional.of("Fix cache invalidation"), Optional.empty());
        verify(service).updateTitle(route, "Fix cache invalidation");
        assertThat(renamed.line()).isEqualTo("Renamed “Fix the cache” to “Fix cache invalidation”");
    }

    private void fresh(String state, boolean draft, String author, String head, List<PullRequestDetail.Label> labels)
    {
        PullRequestDetail detail = mock(PullRequestDetail.class, withSettings().mockMaker(MockMakers.INLINE));
        when(detail.title()).thenReturn("Fix the cache");
        when(detail.state()).thenReturn(state);
        when(detail.draft()).thenReturn(draft);
        when(detail.author()).thenReturn(author);
        when(detail.headSha()).thenReturn(head);
        when(detail.labels()).thenReturn(labels);
        when(service.refreshForAction(route)).thenReturn(detail);
    }

    // The user's own open pull request #58 from branch fix-cache, at the head they last saw, with the given pull requests already from fix-cache-v2.
    private void renamable(List<PullRequestIdentity> earlier)
    {
        HostingTarget target = mock(HostingTarget.class, withSettings().mockMaker(MockMakers.INLINE));
        when(route.target()).thenReturn(target);
        when(integrations.of(target)).thenReturn(HostingIntegrationFixtures.pullRequests(HostingProvider.GITHUB, contents, writer));
        stored(HEAD);
        fresh("open", false, "viewer", HEAD, ImmutableList.of());
        when(contents.identity(target, 58)).thenReturn(identity(58, "fix-cache", HEAD, PullRequestIdentity.State.OPEN));
        when(contents.identitiesByHead(target, RepoRef.of("owner", "repository"), "fix-cache-v2")).thenReturn(earlier);
        when(contents.description(target, 58)).thenReturn("Cache fix");
        when(service.prepareRead("ws1", REPO, 59)).thenReturn(moved);
        when(pullRequests.findIdByRepoAndNumber(REPO, 59)).thenReturn(Optional.of(6L));
    }

    private static PullRequestIdentity identity(int number, String branch, String head, PullRequestIdentity.State state)
    {
        return new PullRequestIdentity(PullRequestRef.of("owner", "repository", number), "https://github.com/owner/repository/pull/" + number,
                RepoRef.of("owner", "repository"), branch, head, RepoRef.of("owner", "repository"), "master", "b".repeat(40), state, false, "viewer");
    }

    private void hosted(HostingServer server)
    {
        when(route.session()).thenReturn(new HostingReadSession(2, "selected", new HostingAccess(server, "token")));
    }

    private void stored(String head)
    {
        PrRawDetail raw = mock(PrRawDetail.class, withSettings().mockMaker(MockMakers.INLINE));
        when(raw.headSha()).thenReturn(head);
        StoredPrDetail stored = mock(StoredPrDetail.class, withSettings().mockMaker(MockMakers.INLINE));
        when(stored.raw()).thenReturn(raw);
        when(details.find(5)).thenReturn(Optional.of(stored));
    }
}
