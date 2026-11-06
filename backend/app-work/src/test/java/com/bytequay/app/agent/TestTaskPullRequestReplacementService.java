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
import com.bytequay.agent.core.AutomationLevel;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.QueuedAgentInput;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.impl.AgentRuntimeRegistry;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.AgentUnitOfWork;
import com.bytequay.agent.core.store.QueuedInputStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.agent.spi.TaskPullRequestReplacementBridge.Prepared;
import com.bytequay.app.ci.CiAutofixService;
import com.bytequay.app.discussion.ReviewFixService;
import com.bytequay.app.merge.AutoMergeService;
import com.bytequay.app.service.hosting.HostingIntegrations;
import com.bytequay.app.service.hosting.HostingReadSession;
import com.bytequay.app.service.pr.PullRequestRead;
import com.bytequay.app.service.pr.PullRequestRefreshService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.workspace.TaskPullRequestOwnership;
import com.bytequay.app.workspace.WorkspaceHostingRead;
import com.bytequay.app.workspace.WorkspaceHostingService;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.domain.CreatedPullRequest;
import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.PullRequestIdentity;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.PullRequestReplacement;
import com.bytequay.core.domain.PullRequestTask;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.core.hosting.HostingPullRequestSnapshot;
import com.bytequay.core.hosting.HostingTarget;
import com.bytequay.core.hosting.PullRequestContentReader;
import com.bytequay.core.hosting.PullRequestWriter;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.store.PullRequestReplacementStore;
import com.bytequay.core.store.PullRequestTaskStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static com.bytequay.agent.core.AgentTaskFixtures.created;
import static com.bytequay.app.service.credentials.CredentialService.GITHUB_SERVER;
import static com.bytequay.app.service.hosting.HostingIntegrationFixtures.pullRequests;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestTaskPullRequestReplacementService
{
    private static final String BASE = "a".repeat(40);
    private static final String HEAD = "b".repeat(40);
    private static final String BRANCH = "fix-ordering";
    private static final RepoRef HEAD_REPOSITORY = RepoRef.of("contributor", "repo");
    private static final RepoRef BASE_REPOSITORY = RepoRef.of("owner", "repo");
    private static final DevelopmentAutomation AUTOMATION = new DevelopmentAutomation(true, AutomationLevel.CI_FIX, Optional.empty());
    private final TaskId id = TaskId.random();
    private final TaskWorktree worktree = new TaskWorktree(id, "workspace", new RepositoryIdentity("contributor", "repo"),
            Path.of("/tmp", id.toString()), "contributor/bytequay/old-branch", BASE);
    private final AgentTask task = created(id, true, "Fix ordering", TitleSource.USER, AgentLaneId.CODEX_CLI, worktree,
            AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO).withDevelopmentAutomation(AUTOMATION), Instant.EPOCH);
    private final AgentTurnSettings settings = new AgentTurnSettings(Optional.of("model"), Optional.empty(), AgentPermissionPolicy.AUTO,
            Duration.ofMinutes(10), ImmutableList.of(), Optional.empty(), Optional.of(AUTOMATION));
    private final AgentTurn turn = AgentTurn.accepted(TurnId.random(), id, "replace-request", settings, Instant.EPOCH)
            .markDeliveryStarted(Instant.EPOCH).running(new ProviderTurnRef("provider"), Optional.empty());
    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final AgentTurnStore turns = mock(AgentTurnStore.class);
    private final QueuedInputStore queue = mock(QueuedInputStore.class);
    private final AgentReviewStore reviews = mock(AgentReviewStore.class);
    private final TaskPullRequestOwnership ownership = mock(TaskPullRequestOwnership.class);
    private final PullRequestTaskStore assignments = mock(PullRequestTaskStore.class);
    private final PullRequestReplacementStore replacements = mock(PullRequestReplacementStore.class);
    private final DevelopmentGitInspector git = mock(DevelopmentGitInspector.class);
    private final WorkspaceHostingService hosting = mock(WorkspaceHostingService.class);
    private final HostingIntegrations integrations = mock(HostingIntegrations.class);
    private final PullRequestService requests = mock(PullRequestService.class);
    private final PullRequestRefreshService refresh = mock(PullRequestRefreshService.class);
    private final CiAutofixService ci = mock(CiAutofixService.class);
    private final ReviewFixService reviewFixes = mock(ReviewFixService.class);
    private final AutoMergeService autoMerge = mock(AutoMergeService.class);
    private final ObjectProvider<DevelopmentFollowupCoordinator> followups = mock();
    private final DevelopmentFollowupCoordinator followup = mock(DevelopmentFollowupCoordinator.class);
    private final AgentRuntimeRegistry runtime = mock(AgentRuntimeRegistry.class, withSettings().mockMaker(MockMakers.INLINE));
    private final AgentUnitOfWork unitOfWork = mock(AgentUnitOfWork.class);
    private final PullRequestContentReader contents = mock(PullRequestContentReader.class);
    private final PullRequestWriter writer = mock(PullRequestWriter.class);
    private final PullRequestDetail detail = mock(PullRequestDetail.class, withSettings().mockMaker(MockMakers.INLINE));
    private final TaskTurnWords words = mock(TaskTurnWords.class);
    private final TaskPullRequestReplacementService service = new TaskPullRequestReplacementService(tasks, turns, queue, reviews, ownership,
            assignments, replacements, git, hosting, integrations, requests, refresh, ci, reviewFixes, autoMerge, followups, runtime, unitOfWork, words,
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    private Optional<PullRequestReplacement> saved = Optional.empty();
    private Optional<PullRequestTask> assigned = Optional.empty();
    private Optional<PullRequestIdentity> remoteReplacement = Optional.empty();
    private Optional<String> remoteHead = Optional.empty();
    private PullRequestIdentity original = identity(11, "contributor/bytequay/old-branch", PullRequestIdentity.State.OPEN);
    private WorkspaceHostingRead selected = HostingTestFixture.route("owner/repo", "token");
    private String localHead = HEAD;

    @BeforeEach
    void setUp()
    {
        when(tasks.find(id)).thenReturn(Optional.of(task));
        when(turns.findActive(id)).thenReturn(Optional.of(turn));
        when(runtime.taskLock(id)).thenReturn(new Object());
        when(followups.getObject()).thenReturn(followup);
        when(unitOfWork.execute(any())).thenAnswer(call -> call.<AgentUnitOfWork.Work<?>>getArgument(0).run());
        when(replacements.find(id.toString())).thenAnswer(ignored -> saved);
        doAnswer(call -> {
            saved = Optional.of(call.getArgument(0));
            return null;
        }).when(replacements).save(any());
        when(assignments.findByTaskId(id.toString())).thenAnswer(ignored -> assigned);
        when(assignments.reassign(any(), any())).thenAnswer(call -> {
            Optional<PullRequestTask> expected = call.getArgument(1);
            if (!assigned.equals(expected)) {
                return false;
            }
            assigned = Optional.of(call.getArgument(0));
            return true;
        });
        doAnswer(call -> {
            call.<Runnable>getArgument(3).run();
            return null;
        }).when(reviewFixes).pullRequestReplaced(eq(id), eq("owner/repo"), eq(11), any());
        when(git.capture(task)).thenAnswer(ignored -> baseline());
        when(git.inspectLocal(eq(worktree), any())).thenAnswer(ignored -> local());
        when(git.inspectReplacement(eq(worktree), any(), eq(BRANCH))).thenAnswer(ignored ->
                new DevelopmentPublicationFacts(remoteHead, remoteReplacement.map(ImmutableList::of).orElse(ImmutableList.of())));
        doAnswer(ignored -> {
            remoteHead = Optional.of(HEAD);
            return null;
        }).when(git).pushReplacementHead(eq(worktree), any(), eq(BRANCH), eq(HEAD));
        when(hosting.captureRead("workspace")).thenAnswer(ignored -> selected);
        when(integrations.of(any(HostingTarget.class))).thenReturn(pullRequests(HostingProvider.GITHUB, contents, writer));
        when(contents.identity(any(), anyInt())).thenAnswer(call -> call.<Integer>getArgument(1) == 11 ? original : remoteReplacement.orElseThrow());
        when(contents.description(any(), eq(11))).thenReturn("Existing PR description");
        HostingPullRequestSnapshot snapshot = mock(HostingPullRequestSnapshot.class, withSettings().mockMaker(MockMakers.INLINE));
        FetchedPullRequest fetched = mock(FetchedPullRequest.class, withSettings().mockMaker(MockMakers.INLINE));
        when(fetched.title()).thenReturn("Fix ordering");
        when(snapshot.pullRequest()).thenReturn(fetched);
        when(hosting.readPullRequest(any(WorkspaceHostingRead.class), eq(11))).thenReturn(snapshot);
        PullRequest linked = mock(PullRequest.class, withSettings().mockMaker(MockMakers.INLINE));
        when(linked.number()).thenReturn(11);
        when(ownership.linkedPullRequest(task)).thenReturn(Optional.of(linked));
        when(writer.create(any(), eq(HEAD_REPOSITORY), eq(BRANCH), eq("main"), eq("Fix ordering"), eq("Existing PR description"), eq(true)))
                .thenAnswer(ignored -> createRemote());
        doAnswer(ignored -> {
            assertThat(assigned.orElseThrow().number()).as("replacement is bound before old PR closes").isEqualTo(12);
            original = identity(11, "contributor/bytequay/old-branch", PullRequestIdentity.State.CLOSED);
            return null;
        }).when(writer).close(any(), eq(11));
        when(requests.prepareRead(eq("workspace"), eq("owner/repo"), anyInt())).thenAnswer(call ->
                new PullRequestRead(selected.session(), selected.repository(), call.getArgument(2), selected.login()));
        when(refresh.refreshAllDetail(any())).thenReturn(detail);
        when(detail.number()).thenReturn(12);
        when(detail.repo()).thenReturn("owner/repo");
        when(detail.title()).thenReturn("Fix ordering");
        when(detail.body()).thenReturn("Existing PR description");
        when(detail.author()).thenReturn("contributor");
        when(detail.htmlUrl()).thenReturn("https://github.com/owner/repo/pull/12");
        when(detail.draft()).thenReturn(true);
        when(detail.headRef()).thenReturn(BRANCH);
        when(detail.baseRef()).thenReturn("main");
        when(detail.headSha()).thenReturn(HEAD);
        when(detail.state()).thenReturn("open");
    }

    @Test
    void createsAndVerifiesBeforeRebindingClosingAndResumingTracking()
    {
        Prepared prepared = service.prepare(id, turn.id(), BRANCH, Optional.empty());
        assertThat(prepared.description()).contains("owner/repo#11", BRANCH, HEAD, "keep this conversation");
        assertThat(prepared.authorization()).isEmpty();
        verifyNoInteractions(writer, ci);
        verify(replacements, never()).save(any());
        assertThat(prepared.execute().get()).contains("closed PR #11", "CI/review tracking");
        assertThat(assigned.orElseThrow().number()).isEqualTo(12);
        assertThat(saved.orElseThrow().completed()).isTrue();
        var order = inOrder(git, writer, assignments, ci);
        order.verify(ci).stopDevelopment(id);
        order.verify(git).pushReplacementHead(eq(worktree), any(), eq(BRANCH), eq(HEAD));
        order.verify(writer).create(any(), eq(HEAD_REPOSITORY), eq(BRANCH), eq("main"), eq("Fix ordering"), eq("Existing PR description"), eq(true));
        order.verify(assignments).reassign(any(), eq(Optional.empty()));
        order.verify(writer).close(any(), eq(11));
        order.verify(ci).startDevelopment(any(), eq(id), eq(remoteReplacement.orElseThrow()), eq(Instant.EPOCH));
        verify(autoMerge).pullRequestReplaced(id.toString(), HEAD);
        verify(followup).pullRequestReplaced(task, turn.id());
    }

    @Test
    void theUsersWordsNamingTheBranchAuthorizeTheReplacement()
    {
        Optional<String> said = Optional.of("rename the branch to fix-ordering");
        when(words.authorize(id, turn.id(), said, BRANCH)).thenReturn(said);
        assertThat(service.prepare(id, turn.id(), BRANCH, said).authorization()).contains("the user asked: “rename the branch to fix-ordering”");
        verifyNoInteractions(writer);
    }

    @Test
    void failedCreateKeepsTheOriginalAndNeverBlindlyCreatesAgain()
    {
        when(writer.create(any(), any(), anyString(), anyString(), anyString(), anyString(), anyBoolean())).thenThrow(new IllegalStateException("Connection lost"));
        assertThatThrownBy(() -> execute()).hasMessageContaining("previous PR was kept");
        assertThat(saved.orElseThrow().createStarted()).isTrue();
        assertThat(saved.orElseThrow().replacementNumber()).isEmpty();
        assertThat(original.state()).isEqualTo(PullRequestIdentity.State.OPEN);
        assertThatThrownBy(() -> execute()).hasMessageContaining("uncertain outcome");
        verify(writer, times(1)).create(any(), any(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
        verify(writer, never()).close(any(), anyInt());
        verify(assignments, never()).reassign(any(), any());
    }

    @Test
    void reconcilesAResponseLostAfterCreationWithoutDuplicatingThePr()
    {
        when(writer.create(any(), any(), anyString(), anyString(), anyString(), anyString(), anyBoolean())).thenAnswer(ignored -> {
            createRemote();
            throw new IllegalStateException("Connection lost after creation");
        });
        assertThatThrownBy(() -> execute()).hasMessageContaining("Connection lost after creation");
        assertThat(saved.orElseThrow().replacementNumber()).isEmpty();
        assertThat(execute()).contains("closed PR #11");
        verify(writer, times(1)).create(any(), any(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
        assertThat(saved.orElseThrow().completed()).isTrue();
    }

    @Test
    void retriesClosingWithTheReplacementAlreadyBound()
    {
        doThrow(new IllegalStateException("Close failed")).doAnswer(ignored -> {
            original = identity(11, "contributor/bytequay/old-branch", PullRequestIdentity.State.CLOSED);
            return null;
        }).when(writer).close(any(), eq(11));
        assertThatThrownBy(() -> execute()).hasMessageContaining("Replacement PR #12 was recorded");
        assertThat(assigned.orElseThrow().number()).isEqualTo(12);
        assertThat(saved.orElseThrow().completed()).isFalse();
        verify(ci, never()).startDevelopment(any(), any(), any(), any());
        assertThat(execute()).contains("closed PR #11");
        verify(writer, times(1)).create(any(), any(), anyString(), anyString(), anyString(), anyString(), anyBoolean());
        verify(git, times(1)).pushReplacementHead(eq(worktree), any(), eq(BRANCH), eq(HEAD));
        verify(ci, times(1)).stopDevelopment(id);
        verify(writer, times(2)).close(any(), eq(11));
    }

    @Test
    void completedReplacementIsIdempotentWithoutStoppingItsNewCi()
    {
        execute();
        clearInvocations(writer, git, ci, assignments, replacements);
        assertThat(execute()).contains("already completed", "#12");
        verifyNoInteractions(writer);
        verify(ci, never()).stopDevelopment(any());
        verify(ci).startDevelopment(any(), eq(id), eq(remoteReplacement.orElseThrow()), eq(Instant.EPOCH));
        verify(assignments, never()).reassign(any(), any());
        verify(replacements, never()).save(any());
        verify(git, never()).pushReplacementHead(any(), any(), anyString(), anyString());
    }

    @Test
    void reconnectsCiAfterCompletionWithoutRepeatingRemoteWrites()
    {
        when(ci.startDevelopment(any(), any(), any(), any())).thenThrow(new IllegalStateException("CI unavailable"))
                .thenReturn(mock(CiAutofixRun.class, withSettings().mockMaker(MockMakers.INLINE)));
        assertThatThrownBy(() -> execute()).hasMessageContaining("replacement completed", "reconnecting CI");
        assertThat(saved.orElseThrow().completed()).isTrue();
        clearInvocations(writer, git, ci, assignments);
        assertThat(execute()).contains("already completed");
        verifyNoInteractions(writer);
        verify(ci, never()).stopDevelopment(any());
        verify(ci).startDevelopment(any(), eq(id), eq(remoteReplacement.orElseThrow()), eq(Instant.EPOCH));
        verify(assignments, never()).reassign(any(), any());
        verify(git, never()).pushReplacementHead(any(), any(), anyString(), anyString());
    }

    @Test
    void stopDuringTheFinalRefreshKeepsCompletionWithoutStartingCi()
    {
        doAnswer(ignored -> {
            when(turns.findActive(id)).thenReturn(Optional.of(turn.requestCancellation(Instant.EPOCH)));
            return null;
        }).when(refresh).refreshDetail(any(PullRequestRead.class));
        assertThatThrownBy(() -> execute()).hasMessageContaining("replacement completed", "ended or was stopped");
        assertThat(saved.orElseThrow().completed()).isTrue();
        assertThat(original.state()).isEqualTo(PullRequestIdentity.State.CLOSED);
        assertThat(assigned.orElseThrow().number()).isEqualTo(12);
        verify(ci, never()).startDevelopment(any(), any(), any(), any());

        AgentTurn resumed = AgentTurn.accepted(TurnId.random(), id, "resume-request", settings, Instant.EPOCH)
                .markDeliveryStarted(Instant.EPOCH).running(new ProviderTurnRef("resumed-provider"), Optional.empty());
        when(turns.findActive(id)).thenReturn(Optional.of(resumed));
        clearInvocations(writer, ci, assignments);
        assertThat(service.prepare(id, resumed.id(), BRANCH, Optional.empty()).execute().get()).contains("already completed");
        verifyNoInteractions(writer);
        verify(ci, never()).stopDevelopment(any());
        verify(ci).startDevelopment(any(), eq(id), eq(remoteReplacement.orElseThrow()), eq(Instant.EPOCH));
        verify(assignments, never()).reassign(any(), any());
    }

    @Test
    void stopDuringCompletedRetryIdentityReadPreventsCiUntilAFreshTurn()
    {
        execute();
        Prepared prepared = service.prepare(id, turn.id(), BRANCH, Optional.empty());
        doAnswer(ignored -> {
            when(turns.findActive(id)).thenReturn(Optional.of(turn.requestCancellation(Instant.EPOCH)));
            return remoteReplacement.orElseThrow();
        }).when(contents).identity(any(), eq(12));
        clearInvocations(writer, ci, assignments);
        assertThatThrownBy(() -> prepared.execute().get()).hasMessageContaining("ended or was stopped");
        assertThat(saved.orElseThrow().completed()).isTrue();
        verifyNoInteractions(writer, ci);

        AgentTurn resumed = AgentTurn.accepted(TurnId.random(), id, "resume-request", settings, Instant.EPOCH)
                .markDeliveryStarted(Instant.EPOCH).running(new ProviderTurnRef("resumed-provider"), Optional.empty());
        when(turns.findActive(id)).thenReturn(Optional.of(resumed));
        doReturn(remoteReplacement.orElseThrow()).when(contents).identity(any(), eq(12));
        assertThat(service.prepare(id, resumed.id(), BRANCH, Optional.empty()).execute().get()).contains("already completed");
        verifyNoInteractions(writer);
        verify(ci, never()).stopDevelopment(any());
        verify(ci).startDevelopment(any(), eq(id), eq(remoteReplacement.orElseThrow()), eq(Instant.EPOCH));
        verify(assignments, never()).reassign(any(), any());
    }

    @Test
    void rejectsAStoppedTurnOrChangedRevisionBeforeAnyWrite()
    {
        Prepared prepared = service.prepare(id, turn.id(), BRANCH, Optional.empty());
        when(turns.findActive(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> prepared.execute().get()).hasMessageContaining("ended or was stopped");
        when(turns.findActive(id)).thenReturn(Optional.of(turn));
        localHead = "c".repeat(40);
        assertThatThrownBy(() -> prepared.execute().get()).hasMessageContaining("clean checkout");
        verifyNoInteractions(writer, ci);
        verify(replacements, never()).save(any());
    }

    @Test
    void changedAccountRequiresAFreshApproval()
    {
        Prepared prepared = service.prepare(id, turn.id(), BRANCH, Optional.empty());
        selected = new WorkspaceHostingRead(new HostingReadSession(9, "selected", selected.session().access()), selected.repository(), selected.login());
        assertThatThrownBy(() -> prepared.execute().get()).hasMessageContaining("hosting account");
        verifyNoInteractions(writer, ci);
        verify(replacements, never()).save(any());
    }

    @Test
    void refusesAnExistingPrOrAnotherCommitOnTheProposedBranch()
    {
        remoteReplacement = Optional.of(identity(12, BRANCH, PullRequestIdentity.State.OPEN));
        assertThatThrownBy(() -> service.prepare(id, turn.id(), BRANCH, Optional.empty())).hasMessageContaining("already has a pull request");
        remoteReplacement = Optional.empty();
        remoteHead = Optional.of("c".repeat(40));
        assertThatThrownBy(() -> service.prepare(id, turn.id(), BRANCH, Optional.empty())).hasMessageContaining("different commit");
        verifyNoInteractions(writer, ci);
    }

    @Test
    void queuedInputInvalidatesThePreparedOperation()
    {
        Prepared prepared = service.prepare(id, turn.id(), BRANCH, Optional.empty());
        QueuedAgentInput input = mock(QueuedAgentInput.class, withSettings().mockMaker(MockMakers.INLINE));
        when(input.state()).thenReturn(QueuedAgentInput.State.QUEUED);
        when(queue.listQueued(id)).thenReturn(ImmutableList.of(input));
        assertThatThrownBy(() -> prepared.execute().get()).hasMessageContaining("queued messages");
        verifyNoInteractions(writer, ci);
        verify(replacements, never()).save(any());
    }

    @Test
    void anotherAcceptedOperationInvalidatesThePreparedApproval()
    {
        Prepared prepared = service.prepare(id, turn.id(), BRANCH, Optional.empty());
        saved = Optional.of(new PullRequestReplacement(id.toString(), "workspace", selected.session().accountId(), selected.repository().id(),
                HEAD_REPOSITORY, BASE_REPOSITORY, 11, "contributor/bytequay/old-branch", "main", HEAD, BRANCH, "Fix ordering", "Existing PR description",
                true, true, Optional.empty(), false));
        assertThatThrownBy(() -> prepared.execute().get()).hasMessageContaining("changed while awaiting approval");
        verifyNoInteractions(writer, ci);
        verify(assignments, never()).reassign(any(), any());
    }

    @Test
    void keepsOriginalOpenWhenTheReplacementClosesBeforeTheHandoffFinishes()
    {
        when(refresh.refreshAllDetail(any())).thenAnswer(ignored -> {
            remoteReplacement = Optional.of(identity(12, BRANCH, PullRequestIdentity.State.CLOSED));
            return detail;
        });
        assertThatThrownBy(() -> execute()).hasMessageContaining("replacement PR does not match");
        assertThat(original.state()).isEqualTo(PullRequestIdentity.State.OPEN);
        assertThat(saved.orElseThrow().completed()).isFalse();
        verify(writer, never()).close(any(), anyInt());
        verify(ci, never()).startDevelopment(any(), any(), any(), any());
    }

    @Test
    void doesNotStealTheReplacementFromAnotherTask()
    {
        when(assignments.find("workspace", "owner/repo", 12)).thenReturn(Optional.of(
                new PullRequestTask("another-task", "workspace", "owner/repo", 12, "Another task owns this PR")));
        assertThatThrownBy(() -> execute()).hasMessageContaining("another task");
        assertThat(original.state()).isEqualTo(PullRequestIdentity.State.OPEN);
        assertThat(saved.orElseThrow().replacementNumber()).contains(12);
        assertThat(assigned).isEmpty();
        verify(assignments, never()).reassign(any(), any());
        verify(writer, never()).close(any(), anyInt());
    }

    private String execute()
    {
        return service.prepare(id, turn.id(), BRANCH, Optional.empty()).execute().get();
    }

    private CreatedPullRequest createRemote()
    {
        assertThat(saved.orElseThrow().createStarted()).as("attempt recorded before sending create").isTrue();
        remoteReplacement = Optional.of(identity(12, BRANCH, PullRequestIdentity.State.OPEN));
        return new CreatedPullRequest(120, 12, remoteReplacement.orElseThrow().url());
    }

    private DevelopmentBaseline baseline()
    {
        Optional<PullRequestRef> linked = assigned.map(value -> PullRequestRef.of("owner", "repo", value.number()));
        DevelopmentPublicationRoute route = new DevelopmentPublicationRoute(GITHUB_SERVER, HEAD_REPOSITORY, BASE_REPOSITORY,
                "https://github.com/contributor/repo.git", "upstream", "main", BASE, linked.isPresent() ? BRANCH : "contributor/bytequay/old-branch", linked);
        return new DevelopmentBaseline(worktree, route, local());
    }

    private DevelopmentLocalFacts local()
    {
        return new DevelopmentLocalFacts("contributor/bytequay/old-branch", localHead, localHead, 0, 1, true, false);
    }

    private static PullRequestIdentity identity(int number, String branch, PullRequestIdentity.State state)
    {
        return new PullRequestIdentity(PullRequestRef.of("owner", "repo", number), "https://github.com/owner/repo/pull/" + number,
                HEAD_REPOSITORY, branch, HEAD, BASE_REPOSITORY, "main", BASE, state, true, "contributor");
    }
}
