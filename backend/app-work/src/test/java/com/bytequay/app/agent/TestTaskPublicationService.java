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
package com.bytequay.app.agent;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.QueuedAgentInput;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.QueuedInputStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.agent.spi.TaskPublicationBridge.Prepared;
import com.bytequay.app.service.hosting.HostingIntegrations;
import com.bytequay.app.service.pr.PullRequestRead;
import com.bytequay.app.service.pr.PullRequestRefreshService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.workspace.TaskPullRequestOwnership;
import com.bytequay.app.workspace.WorkspaceHostingRead;
import com.bytequay.app.workspace.WorkspaceHostingService;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestIdentity;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.core.hosting.HostingTarget;
import com.bytequay.core.hosting.PullRequestContentReader;
import com.bytequay.core.hosting.PullRequestWriter;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static com.bytequay.agent.core.AgentTaskFixtures.created;
import static com.bytequay.app.service.credentials.CredentialService.GITHUB_SERVER;
import static com.bytequay.app.service.hosting.HostingIntegrationFixtures.pullRequests;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestTaskPublicationService
{
    private static final String BASE = "a".repeat(40);
    private static final String HEAD = "b".repeat(40);
    private static final String OLD = "c".repeat(40);
    private static final String BRANCH = "contributor/bytequay/fix-ordering";
    private static final String WHERE = "contributor/repo:" + BRANCH;
    private static final RepoRef HEAD_REPOSITORY = RepoRef.of("contributor", "repo");
    private static final RepoRef BASE_REPOSITORY = RepoRef.of("owner", "repo");
    private final TaskId id = TaskId.random();
    private final TaskWorktree worktree = new TaskWorktree(id, "workspace", new RepositoryIdentity("contributor", "repo"), Path.of("/tmp", id.toString()), BRANCH, BASE);
    private final AgentTask task = created(id, true, "Fix ordering", TitleSource.USER, AgentLaneId.CODEX_CLI, worktree,
            AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), Instant.EPOCH);
    private final AgentTurnSettings settings = new AgentTurnSettings(Optional.of("model"), Optional.empty(), AgentPermissionPolicy.AUTO,
            Duration.ofMinutes(10), ImmutableList.of(), Optional.empty(), Optional.empty());
    private final AgentTurn turn = AgentTurn.accepted(TurnId.random(), id, "push-request", settings, Instant.EPOCH)
            .markDeliveryStarted(Instant.EPOCH).running(new ProviderTurnRef("provider"), Optional.empty());
    private final TaskPullRequestReplacementService replacements = mock(TaskPullRequestReplacementService.class);
    private final AgentTurnStore turns = mock(AgentTurnStore.class);
    private final QueuedInputStore queue = mock(QueuedInputStore.class);
    private final DevelopmentGitInspector git = mock(DevelopmentGitInspector.class);
    private final WorkspaceHostingService hosting = mock(WorkspaceHostingService.class);
    private final HostingIntegrations integrations = mock(HostingIntegrations.class);
    private final TaskPullRequestOwnership ownership = mock(TaskPullRequestOwnership.class);
    private final PullRequestService requests = mock(PullRequestService.class);
    private final PullRequestRefreshService refresh = mock(PullRequestRefreshService.class);
    private final TaskTurnWords words = mock(TaskTurnWords.class);
    private final PullRequestContentReader contents = mock(PullRequestContentReader.class);
    private final PullRequestWriter writer = mock(PullRequestWriter.class);
    private final PullRequest linked = mock(PullRequest.class, withSettings().mockMaker(MockMakers.INLINE));
    private final TaskPublicationService service = new TaskPublicationService(replacements, turns, queue, git, hosting, integrations, ownership, requests,
            refresh, words);
    private final WorkspaceHostingRead selected = HostingTestFixture.route("owner/repo", "token");
    private Optional<PullRequestRef> assigned = Optional.empty();
    private Optional<String> remoteHead = Optional.empty();
    private String localHead = HEAD;
    private int uncommitted;
    private PullRequestIdentity.State state = PullRequestIdentity.State.OPEN;
    private String author = "contributor";

    @BeforeEach
    void setUp()
    {
        when(replacements.eligible(id)).thenReturn(Optional.of(task));
        when(turns.findActive(id)).thenReturn(Optional.of(turn));
        when(git.capture(task)).thenAnswer(ignored -> new DevelopmentBaseline(worktree, route(),
                new DevelopmentLocalFacts(BRANCH, localHead, localHead, uncommitted, 1, true, false)));
        when(git.inspectPublication(eq(worktree), any())).thenAnswer(ignored -> new DevelopmentPublicationFacts(remoteHead, ImmutableList.of()));
        doAnswer(call -> {
            remoteHead = Optional.of(call.getArgument(2));
            return null;
        }).when(git).pushDevelopmentHead(eq(worktree), any(), any(), any());
        when(hosting.captureRead("workspace")).thenReturn(selected);
        when(integrations.of(any(HostingTarget.class))).thenReturn(pullRequests(HostingProvider.GITHUB, contents, writer));
        when(contents.identity(any(), eq(11))).thenAnswer(ignored -> new PullRequestIdentity(PullRequestRef.of("owner", "repo", 11),
                "https://github.com/owner/repo/pull/11", HEAD_REPOSITORY, BRANCH, HEAD, BASE_REPOSITORY, "main", BASE, state, true, author));
        doAnswer(ignored -> {
            state = PullRequestIdentity.State.CLOSED;
            return null;
        }).when(writer).close(any(), eq(11));
        when(linked.number()).thenReturn(11);
        when(linked.repo()).thenReturn("owner/repo");
        when(linked.title()).thenReturn("Fix ordering");
        when(ownership.linkedPullRequest(task)).thenReturn(Optional.of(linked));
        when(requests.prepareRead(eq("workspace"), eq("owner/repo"), anyInt())).thenAnswer(call ->
                new PullRequestRead(selected.session(), selected.repository(), call.getArgument(2), selected.login()));
    }

    @Test
    void pushesOverCommitsTheTaskHadAndVerifiesTheRemote()
    {
        assigned = Optional.of(PullRequestRef.of("owner", "repo", 11));
        remoteHead = Optional.of(OLD);
        when(git.branchHeld(worktree, OLD)).thenReturn(true);
        Prepared prepared = service.push(id, turn.id(), Optional.empty());
        assertThat(prepared.description()).isEqualTo("Push bbbbbbbbbbbb to " + WHERE + ", moving it from cccccccccccc\nUpdates PR #11");
        assertThat(prepared.authorization()).isEmpty();
        verify(git, never()).pushDevelopmentHead(any(), any(), any(), any());
        assertThat(prepared.execute().get()).isEqualTo("Pushed bbbbbbbbbbbb to " + WHERE + ".");
        verify(git).pushDevelopmentHead(eq(worktree), eq(route()), eq(HEAD), eq(Optional.of(OLD)));
    }

    @Test
    void aNewBranchIsPushedWithoutALease()
    {
        Prepared prepared = service.push(id, turn.id(), Optional.empty());
        assertThat(prepared.description()).isEqualTo("Push bbbbbbbbbbbb to " + WHERE + " as a new branch");
        prepared.execute().get();
        verify(git).pushDevelopmentHead(eq(worktree), any(), eq(HEAD), eq(Optional.empty()));
    }

    @Test
    void anUpToDateBranchHasNothingToPush()
    {
        remoteHead = Optional.of(HEAD);
        Prepared prepared = service.push(id, turn.id(), Optional.empty());
        assertThat(prepared.authorization()).isPresent();
        assertThat(prepared.execute().get()).contains("already at bbbbbbbbbbbb", "nothing was pushed");
        verify(git, never()).pushDevelopmentHead(any(), any(), any(), any());
    }

    @Test
    void refusesUncommittedWorkAndCommitsTheTaskNeverHad()
    {
        uncommitted = 2;
        assertThatThrownBy(() -> service.push(id, turn.id(), Optional.empty())).hasMessageContaining("Commit the work first");
        uncommitted = 0;
        remoteHead = Optional.of(OLD);
        assertThatThrownBy(() -> service.push(id, turn.id(), Optional.empty())).hasMessageContaining("commits this task never had");
        verify(git, never()).pushDevelopmentHead(any(), any(), any(), any());
    }

    @Test
    void rechecksTheTaskAndTheRemoteRightBeforePushing()
    {
        Prepared moved = service.push(id, turn.id(), Optional.empty());
        remoteHead = Optional.of(OLD);
        assertThatThrownBy(() -> moved.execute().get()).hasMessageContaining("remote branch moved");
        remoteHead = Optional.empty();
        Prepared changed = service.push(id, turn.id(), Optional.empty());
        localHead = OLD;
        assertThatThrownBy(() -> changed.execute().get()).hasMessageContaining("branch or commit changed");
        localHead = HEAD;
        Prepared queued = service.push(id, turn.id(), Optional.empty());
        QueuedAgentInput input = mock(QueuedAgentInput.class, withSettings().mockMaker(MockMakers.INLINE));
        when(input.state()).thenReturn(QueuedAgentInput.State.QUEUED);
        when(queue.listQueued(id)).thenReturn(ImmutableList.of(input));
        assertThatThrownBy(() -> queued.execute().get()).hasMessageContaining("queued messages");
        when(queue.listQueued(id)).thenReturn(ImmutableList.of());
        Prepared ended = service.push(id, turn.id(), Optional.empty());
        when(turns.findActive(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> ended.execute().get()).hasMessageContaining("turn ended");
        verify(git, never()).pushDevelopmentHead(any(), any(), any(), any());
    }

    @Test
    void anUnverifiedPushIsReportedAsUnknown()
    {
        doNothing().when(git).pushDevelopmentHead(any(), any(), any(), any());
        Prepared prepared = service.push(id, turn.id(), Optional.empty());
        assertThatThrownBy(() -> prepared.execute().get()).hasMessageContaining("Remote result unknown");
    }

    @Test
    void closesTheTasksOwnPullRequestOnceAndKeepsItsBranch()
    {
        Prepared prepared = service.close(id, turn.id(), Optional.empty());
        assertThat(prepared.description()).isEqualTo("Close owner/repo#11 “Fix ordering”. Its branch is kept.");
        assertThat(prepared.authorization()).isEmpty();
        verifyNoInteractions(writer);
        assertThat(prepared.execute().get()).isEqualTo("Closed PR #11. Its branch was kept.");
        verify(refresh).refreshDetail(any(PullRequestRead.class));
        assertThat(prepared.execute().get()).isEqualTo("PR #11 is already closed.");
        verify(writer, times(1)).close(any(), eq(11));
    }

    @Test
    void aFailedRefreshDoesNotUndoAVerifiedClose()
    {
        when(refresh.refreshDetail(any(PullRequestRead.class))).thenThrow(new IllegalStateException("offline"));
        assertThat(service.close(id, turn.id(), Optional.empty()).execute().get()).isEqualTo("Closed PR #11. Its branch was kept.");
    }

    @Test
    void closesOnlyAnOpenPullRequestTheUserOpened()
    {
        author = "someone-else";
        assertThatThrownBy(() -> service.close(id, turn.id(), Optional.empty())).hasMessageContaining("Only your own");
        author = "contributor";
        state = PullRequestIdentity.State.MERGED;
        assertThatThrownBy(() -> service.close(id, turn.id(), Optional.empty())).hasMessageContaining("not open");
        when(ownership.linkedPullRequest(task)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.close(id, turn.id(), Optional.empty())).hasMessageContaining("no linked pull request");
        verifyNoInteractions(writer);
    }

    @Test
    void theUsersOwnWordsAuthorizeEachAction()
    {
        when(words.authorize(id, turn.id(), Optional.of("push it"), "push")).thenReturn(Optional.of("push it"));
        when(words.authorize(id, turn.id(), Optional.of("close the PR"), "close")).thenReturn(Optional.of("close the PR"));
        assertThat(service.push(id, turn.id(), Optional.of("push it")).authorization()).contains("the user asked: “push it”");
        assertThat(service.close(id, turn.id(), Optional.of("close the PR")).authorization()).contains("the user asked: “close the PR”");
        assertThat(service.close(id, turn.id(), Optional.of("push it")).authorization()).isEmpty();
    }

    @Test
    void offersTheToolsOnlyToAnEligibleTask()
    {
        assertThat(service.offered(id)).contains(task);
        when(replacements.eligible(id)).thenReturn(Optional.empty());
        assertThat(service.offered(id)).isEmpty();
    }

    @Test
    void onlyTheTasksOpenConversationMayAct()
    {
        when(replacements.eligible(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.push(id, turn.id(), Optional.empty())).hasMessageContaining("open development conversation");
        when(replacements.eligible(id)).thenReturn(Optional.of(task));
        assertThatThrownBy(() -> service.close(id, TurnId.random(), Optional.empty())).hasMessageContaining("turn ended");
    }

    private DevelopmentPublicationRoute route()
    {
        return new DevelopmentPublicationRoute(GITHUB_SERVER, HEAD_REPOSITORY, BASE_REPOSITORY, "https://github.com/contributor/repo.git", "upstream", "main", BASE,
                BRANCH, assigned);
    }
}
