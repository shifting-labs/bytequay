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

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.app.agenda.ItemTaskStarter;
import com.bytequay.app.merge.AutoMergeService;
import com.bytequay.app.review.AgentReviewProjection;
import com.bytequay.app.review.AgentReviewService;
import com.bytequay.app.service.pr.PullRequestRead;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.rules.AgentRule;
import com.bytequay.app.service.rules.AgentRuleService;
import com.bytequay.app.service.rules.AgentRuleView;
import com.bytequay.app.service.rules.InMemoryRuleRunStore;
import com.bytequay.app.service.rules.RuleText;
import com.bytequay.app.workspace.TaskPullRequestOwnership;
import com.bytequay.core.domain.AssistantFollow;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RuleAuthorization;
import com.bytequay.core.domain.RuleRun;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.hosting.HostingServer;
import com.bytequay.core.merge.AutoMerge;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.store.AssistantFollowStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.MoreExecutors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockMakers;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import static com.bytequay.agent.core.AgentTaskFixtures.created;
import static com.bytequay.core.domain.RuleRun.Verdict.AGREED;
import static com.bytequay.core.domain.RuleRun.Verdict.DISAGREED;
import static com.bytequay.core.domain.RuleRun.Verdict.EDITED;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestRuleWork
{
    private static final Instant AT = Instant.parse("2026-11-03T08:00:00Z");
    private static final String REVIEW_RULE = """
            ---
            name: Review PRs that need me
            when: review-requested
            does: review
            agrees-when: reviewed
            ---
            Review it for correctness first.
            """;
    private static final String TASK_RULE = """
            ---
            name: Start work on assigned issues
            when: issue-assigned
            does: task
            agrees-when: started-task
            ---
            Draft a plan.
            """;

    private final AgentReviewService reviewer = mock(AgentReviewService.class);
    private final PullRequestService reads = mock(PullRequestService.class);
    private final PullRequestStore pullRequests = mock(PullRequestStore.class);
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final ItemTaskStarter starter = mock(ItemTaskStarter.class);
    private final InMemoryRuleRunStore runs = new InMemoryRuleRunStore();
    private final AgentRuleService rules = mock(AgentRuleService.class);
    private final AutoMergeService merges = mock(AutoMergeService.class);
    private final RuleLineOwnership lineOwnership = mock(RuleLineOwnership.class);
    private final AssistantFollowStore follows = mock(AssistantFollowStore.class);
    private final RuleWork work = work(MoreExecutors.newDirectExecutorService());
    private final RuleRun run = RuleRun.suggested("rule-1", "pr:21:requested:0", "acme/warehouse#21", "Fix the importer", AT).started("review:review-21");

    @Test
    void aPausedRuleCannotStartAfterReadPreparation()
    {
        RuleRun automatic = automatic("pr:21:head:head-1");
        prepareRepository();
        when(reads.prepareRead("ws1", "acme/warehouse", 21)).thenAnswer(invocation -> {
            when(rules.authorization("rule-1", 1)).thenReturn(Optional.empty());
            return null;
        });
        work.start(view("rule-1", REVIEW_RULE), automatic);
        assertThat(runs.findByRule("rule-1")).singleElement().extracting(RuleRun::outcome).isEqualTo(Optional.of("CANCELLED"));
        verify(reviewer, never()).startIfAbsent(anyString(), anyString(), anyInt(), anyString(), any());
        verifyNoInteractions(follows);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void onlyAcceptedAutomaticReviewsEnrollForAssistantAssessment(boolean automatic)
    {
        RuleRun requested = automatic ? automatic("pr:21:head:head-1") : RuleRun.suggested("rule-1", "pr:21:requested:0", "acme/warehouse#21", "Fix the importer", AT);
        prepareRepository();
        AgentReviewProjection projection = projection(false);
        when(reviewer.startIfAbsent(anyString(), anyString(), anyInt(), anyString(), any())).thenReturn(Optional.of(projection));
        RuleRun started = requested.started("review:review-21").finished("STARTED");
        doAnswer(invocation -> {
            assertThat(runs.findByRule("rule-1")).containsExactly(requested.finished("STARTING"));
            return null;
        }).when(follows).save(any(AssistantFollow.class));

        work.start(view("rule-1", REVIEW_RULE), requested);

        assertThat(runs.findByRule("rule-1")).containsExactly(started);
        if (automatic) {
            verify(follows).save(AssistantFollow.ruleReview(projection.review().taskId(), AT, "Review it for correctness first.", started));
        }
        else {
            verifyNoInteractions(follows);
        }
    }

    @Test
    void aReviewCancelledAtAdmissionDoesNotEnrollForAssessment()
    {
        RuleRun automatic = automatic("pr:21:head:head-1");
        prepareRepository();
        when(reviewer.startIfAbsent(anyString(), anyString(), anyInt(), anyString(), any())).thenReturn(Optional.empty());

        work.start(view("rule-1", REVIEW_RULE), automatic);

        assertThat(runs.findByRule("rule-1")).singleElement().extracting(RuleRun::outcome).isEqualTo(Optional.of("CANCELLED"));
        verifyNoInteractions(follows);
    }

    @Test
    void anUncertainReviewStartIsDurableAndNeverReplayed()
    {
        RuleRun automatic = automatic("pr:21:head:head-1");
        prepareRepository();
        when(reviewer.startIfAbsent(anyString(), anyString(), anyInt(), anyString(), any())).thenAnswer(invocation -> {
            assertThat(runs.findByRule("rule-1")).singleElement().extracting(RuleRun::outcome).isEqualTo(Optional.of("STARTING"));
            assertThat(invocation.<BooleanSupplier>getArgument(4).getAsBoolean()).isTrue();
            throw new IllegalStateException("Startup result was not saved");
        });
        work.start(view("rule-1", REVIEW_RULE), automatic);
        RuleRun unknown = runs.findByRule("rule-1").getFirst();
        assertThat(unknown.outcome()).contains("UNKNOWN");
        assertThat(unknown.skipped()).hasValueSatisfying(reason -> assertThat(reason).contains("may have started"));
        work.start(view("rule-1", REVIEW_RULE), automatic);
        verify(reviewer, times(1)).startIfAbsent(anyString(), anyString(), anyInt(), anyString(), any());
        assertThat(work.starting(automatic)).isFalse();
        verify(follows, never()).save(any(AssistantFollow.class));
    }

    @Test
    void automaticUpdatesRequireAnUnpausedReviewPreviouslyStartedByThisRule()
    {
        RuleRun automatic = automatic("pr:21:head:head-2");
        AgentRuleView updated = view("rule-1", REVIEW_RULE.replace("review-requested", "pull-request-updated"));
        prepareRepository();
        AgentReviewProjection projection = projection(false);
        when(reviewer.find("ws1", "acme/warehouse", 21)).thenReturn(Optional.of(projection));
        assertThat(work.cannotStart(updated, automatic)).contains("Already has a review");

        runs.save(run);
        assertThat(work.cannotStart(updated, automatic)).isEmpty();
        when(reviewer.updateIfUnpaused(eq("review-21"), any())).thenAnswer(invocation -> {
            assertThat(runs.findByRule("rule-1")).anySatisfy(saved -> {
                assertThat(saved.key()).isEqualTo(automatic.key());
                assertThat(saved.work()).contains("review:review-21");
                assertThat(saved.outcome()).contains("STARTING");
            });
            assertThat(invocation.<BooleanSupplier>getArgument(1).getAsBoolean()).isTrue();
            return Optional.of(projection);
        });
        work.start(updated, automatic);
        assertThat(runs.findByRule("rule-1")).contains(automatic.started("review:review-21").finished("STARTED"));
        verify(follows).save(AssistantFollow.ruleReview(projection.review().taskId(), AT, "Review it for correctness first.",
                automatic.started("review:review-21").finished("STARTED")));

        AgentReviewProjection paused = projection(true);
        when(reviewer.find("ws1", "acme/warehouse", 21)).thenReturn(Optional.of(paused));
        assertThat(work.cannotStart(updated, automatic("pr:21:head:head-3"))).contains("Already has a review");
        verify(reviewer, times(1)).updateIfUnpaused(eq("review-21"), any());
    }

    @Test
    void interruptedReviewUpdatesBecomeUnknownWithoutRunningAgain()
    {
        RuleRun automatic = automatic("pr:21:head:head-2").started("review:review-21").finished("STARTING");
        runs.save(automatic);
        work.start(view("rule-1", REVIEW_RULE), automatic);
        assertThat(runs.findByRule("rule-1")).containsExactly(automatic.finished("UNKNOWN"));
        verify(reviewer, never()).updateIfUnpaused(anyString(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anEnrolledReviewRecoversItsExactStartReceiptWithoutReplaying(boolean update)
    {
        RuleRun requested = automatic("pr:21:head:head-2");
        RuleRun pending = (update ? requested.started("review:review-21") : requested).finished("STARTING");
        RuleRun started = requested.started("review:review-21").finished("STARTED");
        RuleRun otherHead = automatic("pr:21:head:head-1").started("review:review-21").finished("STARTED");
        runs.save(pending);
        when(follows.follows()).thenReturn(ImmutableList.of(AssistantFollow.ruleReview("other-task", AT, "Earlier head", otherHead),
                AssistantFollow.ruleReview("review-task", AT, "Review it for correctness first.", started)));

        work.reconcileInterruptedReview(pending);

        assertThat(runs.findByRule("rule-1")).containsExactly(started);
        verifyNoInteractions(reviewer);
    }

    @Test
    void aRejectedDispatchDoesNotLeaveTheRunStartingForever()
    {
        ExecutorService executor = mock(ExecutorService.class);
        doThrow(new RejectedExecutionException("Stopping")).when(executor).execute(any());
        RuleWork rejected = work(executor);
        assertThatThrownBy(() -> rejected.start(view("rule-1", REVIEW_RULE), run)).isInstanceOf(RejectedExecutionException.class);
        assertThat(rejected.starting(run)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void acceptedMergesReconcileWithoutAnyCurrentRuleOrPermissionToSendAgain(boolean receiptSaved)
    {
        RuleRun queued = RuleRun.automatic(new RuleAuthorization("removed-rule", "Merge approved PRs", 4),
                "pr:21:rule:4:ready:head-1", "acme/warehouse#21", "Fix the importer", AT).started("pr:21").finished("QUEUED");
        prepareRepository();
        when(merges.mergeAuthorized(any(), any(), eq("head-1"), eq("removed-rule:" + queued.key()), any(), any())).thenAnswer(invocation -> {
            assertThat(invocation.<BooleanSupplier>getArgument(5).getAsBoolean()).isFalse();
            return AutoMerge.waiting("pr:21", "head-1", "Checking").started(AT).ended(AutoMerge.Outcome.MERGED, "Merged");
        });

        // Even a stale caller passing an edited rule cannot reinterpret the accepted merge as a review.
        if (receiptSaved) {
            work.start(view("removed-rule", REVIEW_RULE), queued);
        }
        else {
            work.reconcileMerge(RuleRun.automatic(queued.authorization().orElseThrow(), queued.key(), queued.subject(), queued.title(), AT));
        }

        assertThat(runs.findByRule("removed-rule")).containsExactly(queued.finished("MERGED"));
        verifyNoInteractions(rules, reviewer);
    }

    @Test
    void aMergeRechecksFreshMetadataFilesAndOwnedLinesAtTheProviderBoundary()
    {
        RuleRun automatic = automatic("pr:21:rule:1:ready:head-1");
        AgentRuleView merge = view("rule-1", """
                ---
                name: Merge approved storage changes
                when: pull-request-ready
                repositories: [acme/warehouse]
                labels: [ready]
                authors: [@me]
                paths: [src/storage/**]
                owned-lines: true
                does: merge
                agrees-when: merged
                ---
                Merge after approval and passing checks.
                """);
        prepareRepository();
        PullRequestRead route = mock(PullRequestRead.class, withSettings().mockMaker(MockMakers.INLINE));
        when(route.login()).thenReturn("viewer");
        when(reads.prepareRead("ws1", "acme/warehouse", 21)).thenReturn(route);
        PullRequestDetail fresh = mock(PullRequestDetail.class, withSettings().mockMaker(MockMakers.INLINE));
        when(fresh.repo()).thenReturn("acme/warehouse");
        when(fresh.author()).thenReturn("viewer");
        when(fresh.labels()).thenReturn(ImmutableList.of(new PullRequestDetail.Label("ready", "000000")));
        when(fresh.headSha()).thenReturn("head-1");
        when(fresh.baseRef()).thenReturn("main");
        DiffFile storage = new DiffFile("src/storage/Pages.java", "modified", 1, 1, "@@ -1 +1 @@\n-old\n+new\n");
        when(merges.mergeAuthorized(eq(route), any(), eq("head-1"), eq("rule-1:" + automatic.key()), any(), any())).thenAnswer(invocation -> {
            Predicate<PullRequestDetail> matches = invocation.getArgument(4);
            when(reads.getValidatedPullRequestDiffFiles(route, "head-1")).thenReturn(ImmutableList.of(new DiffFile("docs/readme.md", "modified", 1, 0, "")));
            assertThat(matches.test(fresh)).isFalse();
            when(reads.getValidatedPullRequestDiffFiles(route, "head-1")).thenReturn(ImmutableList.of(storage));
            assertThat(matches.test(fresh)).isFalse();
            when(lineOwnership.touches(workspace(), route, "head-1", "main", ImmutableList.of(storage))).thenReturn(true);
            assertThat(matches.test(fresh)).isTrue();
            when(fresh.labels()).thenReturn(ImmutableList.of());
            assertThat(matches.test(fresh)).isFalse();
            assertThat(invocation.<BooleanSupplier>getArgument(5).getAsBoolean()).isTrue();
            return AutoMerge.waiting("pr:21", "head-1", "Scope no longer matches");
        });

        work.start(merge, automatic);

        assertThat(runs.findByRule("rule-1")).containsExactly(automatic.started("pr:21").finished("WAITING"));
        verify(reads, times(3)).getValidatedPullRequestDiffFiles(route, "head-1");
    }

    @Test
    void aWatchedRepositoryMustMatchThePullRequestsHostingServer()
    {
        assertThat(RuleWork.watchedWorkspace(pullRequest(), workspace())).isTrue();
        HostingServer another = new HostingServer(HostingServer.GITHUB_COM.provider(), URI.create("https://code.example.com"), URI.create("https://code.example.com/api/v3"));
        assertThat(RuleWork.watchedWorkspace(pullRequest(), workspace(another))).isFalse();
    }

    @Test
    void aMergeBoundaryFailureKeepsTheOutcomeUnknownAndRecoverable()
    {
        RuleRun automatic = automatic("pr:21:rule:1:ready:head-1");
        AgentRuleView merge = view("rule-1", REVIEW_RULE.replace("review-requested", "pull-request-ready")
                .replace("does: review", "does: merge").replace("agrees-when: reviewed", "agrees-when: merged"));
        prepareRepository();
        when(merges.mergeAuthorized(any(), any(), anyString(), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("The accepted response could not be saved"));

        work.start(merge, automatic);

        assertThat(runs.pendingAutomatic()).containsExactly(automatic.started("pr:21").finished("UNKNOWN"));
    }

    @Test
    void aReviewAgreesWhenAFindingIsPostedAsWrittenAndIsEditedWhenTheUserReviewsThemselvesAfterIt()
    {
        Optional<Instant> finished = Optional.of(AT.plusSeconds(600));
        assertThat(RuleWork.judge(run, new RuleWork.ReviewSeen(true, true, 3, finished, true, Optional.empty())).verdict()).isEqualTo(AGREED);
        assertThat(RuleWork.judge(run, new RuleWork.ReviewSeen(true, false, 3, finished, true, Optional.of(AT.plusSeconds(900)))).verdict()).isEqualTo(EDITED);
        assertThat(RuleWork.judge(run, new RuleWork.ReviewSeen(true, false, 3, finished, true, Optional.of(AT.plusSeconds(300))))).isEqualTo(run);
        assertThat(RuleWork.judge(run, new RuleWork.ReviewSeen(false, false, 3, finished, true, Optional.empty())).verdict()).isEqualTo(DISAGREED);
        assertThat(RuleWork.judge(run, new RuleWork.ReviewSeen(true, false, 0, Optional.empty(), false, Optional.empty()))).isEqualTo(run);
        assertThat(RuleWork.judge(run, new RuleWork.ReviewSeen(true, false, 0, finished, true, Optional.empty())).skipped()).contains("Found nothing");
        assertThat(RuleWork.judge(run, new RuleWork.ReviewSeen(true, false, 0, finished, false, Optional.empty())).skipped()).contains("The review did not finish");
    }

    @Test
    void aTaskAgreesWhenKeptAsItIsIsEditedWhenSteeredAndDisagreesWhenDropped()
    {
        assertThat(RuleWork.judge(run, new RuleWork.TaskSeen(false, false, true)).verdict()).isEqualTo(AGREED);
        assertThat(RuleWork.judge(run, new RuleWork.TaskSeen(false, true, true)).verdict()).isEqualTo(EDITED);
        assertThat(RuleWork.judge(run, new RuleWork.TaskSeen(true, true, true)).verdict()).isEqualTo(DISAGREED);
        assertThat(RuleWork.judge(run, new RuleWork.TaskSeen(false, false, false))).isEqualTo(run);
    }

    @Test
    void startsALocalCommitTaskForAnAssignedIssueUnderAnIdItCanFindAgain()
    {
        RuleRun issue = RuleRun.suggested("rule-2", "issue:900:github.com", "acme/warehouse#12", "Fix the importer", AT);
        String key = "rule:rule-2:issue:900:github.com";
        TaskId taskId = new TaskId(UUID.nameUUIDFromBytes(key.getBytes(UTF_8)));
        ItemTaskStarter.Start start = new ItemTaskStarter.Start("ws1", "Implement Fix the importer", Optional.empty(), Optional.empty(), Optional.empty());
        AgentTask task = created(taskId, Optional.of("ws1"), true, "Fix the importer", new AgentLaneId("claude"), new RepositoryIdentity("acme", "warehouse"),
                Path.of("/tmp/rule-work"), AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), AT);
        when(starter.item("issue:acme/warehouse#12")).thenReturn(start);
        when(starter.start(any(), any(), any(), anyString(), any(DevelopmentAutomation.class)))
                .thenReturn(Optional.of(new ItemTaskStarter.Started(task, true, "Claude", DevelopmentAutomation.DEFAULT)));

        work.start(view("rule-2", TASK_RULE), issue);
        verify(starter).start(eq(start.followedBy("\n\nDraft a plan.")), eq(Optional.empty()), eq(taskId), eq(key), eq(DevelopmentAutomation.DEFAULT));
        assertThat(runs.findByRule("rule-2")).containsExactly(issue.started("task:" + taskId));
        assertThat(work.starting(issue)).isFalse();

        when(starter.start(any(), any(), any(), anyString(), any(DevelopmentAutomation.class)))
                .thenReturn(Optional.of(new ItemTaskStarter.Started(task, false, "Claude", DevelopmentAutomation.DEFAULT)));
        work.start(view("rule-2", TASK_RULE), issue);
        assertThat(runs.findByRule("rule-2")).containsExactly(issue.skip("Already has a task"));
        when(starter.start(any(), any(), any(), anyString(), any(DevelopmentAutomation.class))).thenReturn(Optional.empty());
        work.start(view("rule-2", TASK_RULE), issue);
        assertThat(runs.findByRule("rule-2")).containsExactly(issue.skip("The agent is not ready"));
    }

    @Test
    void aReviewStartsOnlyInAWatchedRepositoryAndSaysWhyItCouldNotStart()
    {
        RuleRun requested = RuleRun.suggested("rule-1", "pr:21:requested:0", "acme/warehouse#21", "Fix the importer", AT);
        when(pullRequests.findById(21)).thenReturn(Optional.of(pullRequest()));
        when(workspaces.findAll()).thenReturn(ImmutableList.of());
        work.start(view("rule-1", REVIEW_RULE), requested);
        assertThat(runs.findByRule("rule-1")).containsExactly(requested.skip("Not in a watched repository"));

        when(workspaces.findAll()).thenReturn(ImmutableList.of(workspace()));
        when(reviewer.find(anyString(), anyString(), anyInt())).thenReturn(Optional.empty());
        when(reads.prepareRead("ws1", "acme/warehouse", 21)).thenThrow(new IllegalStateException("GitHub is offline"));
        work.start(view("rule-1", REVIEW_RULE), requested);
        assertThat(runs.findByRule("rule-1")).containsExactly(requested.skip("Could not start: GitHub is offline").finished("FAILED"));
    }

    private RuleWork work(ExecutorService executor)
    {
        return new RuleWork(reviewer, mock(AgentReviewStore.class), reads, pullRequests, workspaces, starter, mock(AgentTaskStore.class),
                mock(AgentTurnStore.class), mock(TaskPullRequestOwnership.class), runs, executor, rules, merges, lineOwnership, follows);
    }

    private RuleRun automatic(String key)
    {
        RuleAuthorization authorization = new RuleAuthorization("rule-1", "Review PRs that need me", 1);
        when(rules.authorization("rule-1", 1)).thenReturn(Optional.of(authorization));
        return RuleRun.automatic(authorization, key, "acme/warehouse#21", "Fix the importer", AT);
    }

    private void prepareRepository()
    {
        when(pullRequests.findById(21)).thenReturn(Optional.of(pullRequest()));
        when(workspaces.findAll()).thenReturn(ImmutableList.of(workspace()));
    }

    private static AgentReviewProjection projection(boolean paused)
    {
        AgentReviewProjection projection = mock(AgentReviewProjection.class, withSettings().mockMaker(MockMakers.INLINE));
        when(projection.review()).thenReturn(new AgentReview("review-21", "ws1", "acme/warehouse", OptionalInt.of(21), OptionalLong.of(21),
                UUID.randomUUID().toString(), Optional.empty(), AgentReview.State.OPEN, paused, Optional.empty(), Optional.empty(), Optional.empty(),
                0, AT, AT, Optional.empty(), Optional.empty()));
        return projection;
    }

    private static AgentRuleView view(String id, String text)
    {
        return new AgentRuleView(id, text, AgentRule.Mode.SUGGEST, false, AT, Optional.empty(), RuleText.parse(text), 0, 0, 0, 0, 0);
    }

    private static PullRequest pullRequest()
    {
        return new PullRequest(21, "acme/warehouse", 21, "Fix the importer", "octocat", "https://github.com/acme/warehouse/pull/21", AT, AT,
                PullRequest.Origin.REVIEW_REQUESTED, ImmutableList.of(), ImmutableMap.of(), false, null, null, null, ImmutableList.of(), PullRequestDetail.CiStatus.NONE,
                1, 0, 0, "open", null, null, true, "clean", AT, "feature", null, null, AT, null);
    }

    private static Workspace workspace()
    {
        return workspace(HostingServer.GITHUB_COM);
    }

    private static Workspace workspace(HostingServer server)
    {
        RepoRef repository = RepoRef.of("acme", "warehouse");
        return new Workspace("ws1", "Warehouse", 1, repository, Optional.empty(), Optional.empty(), 1, repository, Workspace.Topology.DIRECT, "main",
                "https://github.com/acme/warehouse.git", Optional.empty(), Optional.empty(), "/tmp/rule-work/ws1", Optional.of(AT), Optional.of(AT), 0, AT, AT,
                server);
    }
}
