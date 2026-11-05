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
package com.bytequay.app.merge;

import com.bytequay.agent.core.AgentMetrics;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.AutomationLevel;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.QueuedInputStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.DevelopmentResultReport;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.app.agent.runtime.AgentServingGate;
import com.bytequay.app.discussion.ReviewFixService;
import com.bytequay.app.service.pr.PullRequestRead;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.settings.AppPreferences;
import com.bytequay.app.service.settings.AppSettingsService;
import com.bytequay.app.service.settings.AppTheme;
import com.bytequay.app.workspace.TaskPullRequestOwnership;
import com.bytequay.core.ci.CiAutofixStore;
import com.bytequay.core.domain.MergeResult;
import com.bytequay.core.domain.PrMergeQueueRemoval;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PrTimelinePayload;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.PullRequestReplacement;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.merge.AutoMerge;
import com.bytequay.core.merge.AutoMergeStore;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.core.store.PullRequestReplacementStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static com.bytequay.agent.core.AgentTaskFixtures.created;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestAutoMergeService
{
    private static final Instant CREATED = Instant.parse("2026-10-01T00:00:00Z");
    private static final String REPO = "owner/repo";
    private static final String HEAD = "c".repeat(40);

    private final MutableClock clock = new MutableClock(CREATED.plus(Duration.ofDays(1)));
    private final TaskPullRequestOwnership ownership = mock(TaskPullRequestOwnership.class);
    private final PullRequestService pullRequests = mock(PullRequestService.class);
    private final PrDetailStore details = mock(PrDetailStore.class);
    private final CiAutofixStore ciRuns = mock(CiAutofixStore.class);
    private final ReviewFixService reviewFixes = mock(ReviewFixService.class);
    private final AppSettingsService settings = mock(AppSettingsService.class);
    private final Map<String, AutoMerge> rows = new HashMap<>();
    private final AgentTurnStore turns = mock(AgentTurnStore.class);
    private final PullRequestReplacementStore replacements = mock(PullRequestReplacementStore.class);
    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final AutoMergeService service = new AutoMergeService(tasks, turns, mock(QueuedInputStore.class),
            mock(AgentServingGate.class, withSettings().mockMaker(MockMakers.INLINE)), ownership, pullRequests, details, ciRuns, reviewFixes, new MemoryStore(),
            replacements, settings, clock);
    private final AgentTask task = task();

    @Test
    void activeRuleMergesTheCurrentApprovedHeadOnceWithoutTheTaskDelay()
    {
        PullRequestRead route = ruleReady(HEAD);
        assertThat(service.mergeAuthorized(route, pullRequest(), HEAD, "rule-run", fresh -> true, () -> true).outcome()).contains(AutoMerge.Outcome.MERGED);
        assertThat(service.mergeAuthorized(route, pullRequest(), HEAD, "rule-run", fresh -> true, () -> true).outcome()).contains(AutoMerge.Outcome.MERGED);
        verify(pullRequests, times(1)).mergePullRequest(route, 12L, "rebase", HEAD);
    }

    @Test
    void activeRuleWaitsWhenApprovalBelongsToAnOlderCommit()
    {
        PullRequestRead route = ruleReady("b".repeat(40));
        assertThat(service.mergeAuthorized(route, pullRequest(), HEAD, "rule-run", fresh -> true, () -> true).startedAt()).isEmpty();
        verify(pullRequests, never()).mergePullRequest(eq(route), anyLong(), anyString(), anyString());
    }

    @Test
    void pausingTheRuleDuringRefreshPreventsTheWrite()
    {
        PullRequestRead route = ruleReady(HEAD);
        AtomicInteger checks = new AtomicInteger();
        assertThat(service.mergeAuthorized(route, pullRequest(), HEAD, "rule-run", fresh -> true, () -> checks.incrementAndGet() == 1).outcome())
                .contains(AutoMerge.Outcome.CANCELLED);
        verify(pullRequests, never()).mergePullRequest(eq(route), anyLong(), anyString(), anyString());
    }

    @Test
    void ruleCannotOverrideAnExplicitStopForTheAssociatedTask()
    {
        PullRequestRead route = ruleReady(HEAD);
        when(tasks.listLive()).thenReturn(ImmutableList.of(task));
        rows.put(task.id().toString(), AutoMerge.waiting(task.id().toString(), HEAD, "Ready").ended(AutoMerge.Outcome.CANCELLED, "Stopped"));
        assertThat(service.mergeAuthorized(route, pullRequest(), HEAD, "rule-run", fresh -> true, () -> true).outcome()).contains(AutoMerge.Outcome.CANCELLED);
        verify(pullRequests, never()).mergePullRequest(eq(route), anyLong(), anyString(), anyString());
    }

    @Test
    void uncertainRuleMergeIsRecordedAndNeverResent()
    {
        PullRequestRead route = ruleReady(HEAD);
        when(pullRequests.mergePullRequest(route, 12L, "rebase", HEAD)).thenThrow(new IllegalStateException("Connection ended"));
        assertThat(service.mergeAuthorized(route, pullRequest(), HEAD, "rule-run", fresh -> true, () -> true).outcome()).contains(AutoMerge.Outcome.UNKNOWN);
        assertThat(service.mergeAuthorized(route, pullRequest(), HEAD, "rule-run", fresh -> true, () -> true).outcome()).contains(AutoMerge.Outcome.UNKNOWN);
        verify(pullRequests, times(1)).mergePullRequest(route, 12L, "rebase", HEAD);
    }

    @Test
    void uncertainMergeStaysHeldEvenWhenTheHeadChanges()
    {
        PullRequestRead route = ruleReady(HEAD);
        when(pullRequests.mergePullRequest(route, 12L, "rebase", HEAD)).thenThrow(new IllegalStateException("Connection ended"));
        service.mergeAuthorized(route, pullRequest(), HEAD, "rule-run", fresh -> true, () -> true);
        String next = "d".repeat(40);
        when(pullRequests.refreshForAction(route).headSha()).thenReturn(next);

        assertThat(service.mergeAuthorized(route, pullRequest(), next, "rule-run", fresh -> true, () -> true).outcome()).contains(AutoMerge.Outcome.UNKNOWN);
        verify(pullRequests, times(1)).mergePullRequest(eq(route), anyLong(), anyString(), anyString());
    }

    @Test
    void pausedRuleStillReportsTheCompletedMergeItAlreadyStarted()
    {
        PullRequestRead route = ruleReady(HEAD);
        when(pullRequests.mergePullRequest(route, 12L, "rebase", HEAD)).thenReturn(MergeResult.enqueued("Queued"));
        assertThat(service.mergeAuthorized(route, pullRequest(), HEAD, "rule-run", fresh -> true, () -> true).outcome()).contains(AutoMerge.Outcome.QUEUED);
        when(pullRequests.refreshForAction(route).mergedAt()).thenReturn(clock.instant());

        assertThat(service.mergeAuthorized(route, pullRequest(), HEAD, "rule-run", fresh -> true, () -> false).outcome()).contains(AutoMerge.Outcome.MERGED);
        verify(pullRequests, times(1)).mergePullRequest(eq(route), anyLong(), anyString(), anyString());
    }

    @Test
    void taskAndRuleShareOneMergeOperation()
    {
        PullRequestRead route = ruleReady(HEAD);
        service.mergeAuthorized(route, pullRequest(), HEAD, "rule-run", fresh -> true, () -> true);
        when(settings.preferences()).thenReturn(new AppPreferences(7, 14, AppTheme.SYSTEM, 30, 0));

        service.advance(task);

        assertThat(summary().state()).isEqualTo("MERGED");
        verify(pullRequests, never()).mergePullRequest(anyString(), anyInt(), anyLong(), anyString(), anyString());
    }

    @Test
    void refreshedMetadataMustStillMatchTheRuleScope()
    {
        PullRequestRead route = ruleReady(HEAD);

        assertThat(service.mergeAuthorized(route, pullRequest(), HEAD, "rule-run", fresh -> false, () -> true).outcome()).contains(AutoMerge.Outcome.CANCELLED);

        verify(pullRequests, never()).mergePullRequest(eq(route), anyLong(), anyString(), anyString());
    }

    @Test
    void aRuleDoesNotClaimAMergeAlreadyCompletedBySomeoneElse()
    {
        PullRequestRead route = ruleReady(HEAD);
        when(pullRequests.refreshForAction(route).mergedAt()).thenReturn(clock.instant());

        assertThat(service.mergeAuthorized(route, pullRequest(), HEAD, "rule-run", fresh -> true, () -> true).outcome()).contains(AutoMerge.Outcome.CANCELLED);

        verify(pullRequests, never()).mergePullRequest(eq(route), anyLong(), anyString(), anyString());
    }

    @Test
    void twoRulesCannotClaimTheSameMerge()
    {
        PullRequestRead route = ruleReady(HEAD);
        assertThat(service.mergeAuthorized(route, pullRequest(), HEAD, "first-run", fresh -> true, () -> true).outcome()).contains(AutoMerge.Outcome.MERGED);

        assertThat(service.mergeAuthorized(route, pullRequest(), HEAD, "second-run", fresh -> true, () -> true).outcome()).contains(AutoMerge.Outcome.CANCELLED);

        verify(pullRequests, times(1)).mergePullRequest(route, 12L, "rebase", HEAD);
    }

    private PullRequestRead ruleReady(String approvedHead)
    {
        PullRequestRead route = mock(PullRequestRead.class, withSettings().mockMaker(MockMakers.INLINE));
        when(route.login()).thenReturn("me");
        PullRequestDetail fresh = mock(PullRequestDetail.class, withSettings().mockMaker(MockMakers.INLINE));
        when(fresh.id()).thenReturn(12L);
        when(fresh.number()).thenReturn(12);
        when(fresh.repo()).thenReturn(REPO);
        when(fresh.headSha()).thenReturn(HEAD);
        when(fresh.author()).thenReturn("me");
        when(fresh.state()).thenReturn("open");
        when(fresh.mergeable()).thenReturn(true);
        when(fresh.mergeableState()).thenReturn("clean");
        when(fresh.viewerCanWrite()).thenReturn(true);
        when(fresh.ciStatus()).thenReturn(PullRequestDetail.CiStatus.PASSING);
        when(pullRequests.refreshForAction(route)).thenReturn(fresh);
        when(pullRequests.mergePullRequest(route, 12L, "rebase", HEAD)).thenReturn(new MergeResult(HEAD, true, "Merged"));
        StoredPrDetail cached = details.find(12L).orElseThrow();
        when(details.find(12L)).thenReturn(Optional.of(new StoredPrDetail(cached.raw(),
                ImmutableList.of(new PrReviewState(1L, "reviewer", null, false, "APPROVED", CREATED, "", "", approvedHead)),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), true)));
        return route;
    }

    @BeforeEach
    void setUp()
    {
        when(turns.list(task.id())).thenReturn(ImmutableList.of(turn().withDevelopmentResult(new DevelopmentResultReport(true, Optional.empty()))
                .finish(TurnState.COMPLETED, AgentMetrics.empty(), Optional.empty(), CREATED)));
        when(ownership.linkedPullRequest(any())).thenReturn(Optional.of(pullRequest()));
        when(reviewFixes.settled(any(), any())).thenReturn(true);
        when(settings.preferences()).thenReturn(new AppPreferences(7, 14, AppTheme.SYSTEM, 30, 60));
        when(pullRequests.mergePullRequest(anyString(), anyInt(), anyLong(), anyString(), anyString())).thenReturn(new MergeResult(HEAD, true, "Merged"));
        cache(HEAD, false, "clean");
    }

    @Test
    void waitsForTheDelayAfterThePullRequestBecomesMergeableThenRebaseMergesTheCheckedHead()
    {
        service.advance(task);
        assertThat(summary().state()).isEqualTo("READY");
        assertThat(summary().mergeAt()).contains(clock.instant().plus(Duration.ofHours(1)));

        clock.advance(Duration.ofMinutes(59));
        service.advance(task);
        verify(pullRequests, never()).mergePullRequest(anyString(), anyInt(), anyLong(), anyString(), anyString());

        clock.advance(Duration.ofMinutes(1));
        service.advance(task);
        verify(pullRequests).mergePullRequest(REPO, 12, 12L, "rebase", HEAD);
        assertThat(summary().state()).isEqualTo("MERGED");

        service.advance(task);
        verify(pullRequests, times(1)).mergePullRequest(anyString(), anyInt(), anyLong(), anyString(), anyString());
    }

    @Test
    void joinsTheMergeQueueWhenTheBaseBranchHasOne()
    {
        when(settings.preferences()).thenReturn(new AppPreferences(7, 14, AppTheme.SYSTEM, 30, 0));
        when(pullRequests.hasMergeQueue(REPO, "main")).thenReturn(true);
        when(pullRequests.mergePullRequest(REPO, 12, 12L, "queue", HEAD)).thenReturn(MergeResult.enqueued("Queued at position 2"));

        service.advance(task);

        assertThat(summary()).isEqualTo(new AutoMergeService.AutoMergeSummary("QUEUED", Optional.empty(), Optional.of("Queued at position 2")));
    }

    @Test
    void aRebaseGitHubTurnsIntoAQueueEntryIsQueued()
    {
        when(settings.preferences()).thenReturn(new AppPreferences(7, 14, AppTheme.SYSTEM, 30, 0));
        when(pullRequests.mergePullRequest(REPO, 12, 12L, "rebase", HEAD)).thenReturn(MergeResult.enqueued("Queued at position 1"));

        service.advance(task);

        assertThat(summary()).isEqualTo(new AutoMergeService.AutoMergeSummary("QUEUED", Optional.empty(), Optional.of("Queued at position 1")));
    }

    @Test
    void aQueuedMergeStaysUnderwayUntilGitHubMergesThePullRequest()
    {
        joinQueue();
        cache(HEAD, false, "clean", queueEvent("added_to_merge_queue", clock.instant().plusSeconds(5), PrTimelinePayload.empty()));
        clock.advance(Duration.ofHours(2));

        service.advance(task);

        verify(pullRequests, times(1)).mergePullRequest(anyString(), anyInt(), anyLong(), anyString(), anyString());
        assertThat(summary().state()).isEqualTo("QUEUED");
        assertThat(service.summary(task.id().toString(), Optional.of(pullRequest("merged"))).orElseThrow().state()).isEqualTo("MERGED");
        // A closed pull request is never linked.
        assertThat(service.summary(task.id().toString(), Optional.empty()).orElseThrow().state()).isEqualTo("CANCELLED");
    }

    @Test
    void aPullRequestTheQueueDropsWaitsForANewHeadAndThenQueuesAgain()
    {
        Instant sent = joinQueue();
        PrMergeQueueRemoval checksFailed = new PrMergeQueueRemoval(Optional.of("checks failed"), Optional.empty(), ImmutableList.of());
        PrTimelineEvent added = queueEvent("added_to_merge_queue", sent.plusSeconds(5), PrTimelinePayload.empty());
        cache(HEAD, false, "clean", added, queueEvent("removed_from_merge_queue", sent.plusSeconds(60), PrTimelinePayload.builder().mergeQueueRemoval(checksFailed).build()));

        service.advance(task);
        service.advance(task);

        assertThat(summary().state()).isEqualTo("FAILED");
        assertThat(summary().message()).contains("The merge queue removed the pull request (checks failed); a new push starts the wait again");
        verify(pullRequests, times(1)).mergePullRequest(anyString(), anyInt(), anyLong(), anyString(), anyString());

        String next = "d".repeat(40);
        cache(next, false, "clean");
        when(pullRequests.mergePullRequest(REPO, 12, 12L, "queue", next)).thenReturn(MergeResult.enqueued("Queued at position 1"));
        service.advance(task);
        verify(pullRequests).mergePullRequest(REPO, 12, 12L, "queue", next);
        assertThat(summary().state()).isEqualTo("QUEUED");
    }

    @Test
    void turningAutoMergeOffBeforeThePullRequestJoinsTheQueueStopsWaitingForIt()
    {
        Instant sent = joinQueue();
        cache(HEAD, false, "clean", queueEvent("auto_merge_disabled", sent.plusSeconds(30), PrTimelinePayload.empty()));

        service.advance(task);

        assertThat(summary().state()).isEqualTo("FAILED");
        assertThat(summary().message().orElseThrow()).startsWith("Auto-merge was turned off before the pull request joined the merge queue");
    }

    @Test
    void aRemovalFromBeforeTheMergeWasSentIsNotADrop()
    {
        Instant sent = joinQueue();
        cache(HEAD, false, "clean", queueEvent("removed_from_merge_queue", sent.minus(Duration.ofHours(1)), PrTimelinePayload.empty()));

        service.advance(task);

        assertThat(summary().state()).isEqualTo("QUEUED");
    }

    /** Sends the merge to the base branch's queue at once and returns when it was sent. */
    private Instant joinQueue()
    {
        when(settings.preferences()).thenReturn(new AppPreferences(7, 14, AppTheme.SYSTEM, 30, 0));
        when(pullRequests.hasMergeQueue(REPO, "main")).thenReturn(true);
        when(pullRequests.mergePullRequest(REPO, 12, 12L, "queue", HEAD)).thenReturn(MergeResult.enqueued("Queued at position 2"));
        Instant sent = clock.instant();
        service.advance(task);
        assertThat(summary().state()).isEqualTo("QUEUED");
        return sent;
    }

    @Test
    void aTaskOpenedOnAPullRequestMergesNothingUntilItDelivers()
    {
        when(turns.list(task.id())).thenReturn(ImmutableList.of());
        clock.advance(Duration.ofDays(1));
        service.advance(task);

        assertThat(summary().message()).contains("Waiting for the task to deliver its latest request");
        verify(pullRequests, never()).mergePullRequest(anyString(), anyInt(), anyLong(), anyString(), anyString());
    }

    @Test
    void waitsWhileTheTaskIsStillWorking()
    {
        service.advance(task);
        when(turns.findActive(task.id())).thenReturn(Optional.of(turn()));
        clock.advance(Duration.ofDays(1));
        service.advance(task);

        assertThat(summary().message()).contains("Waiting for the task to finish its current work");
        verify(pullRequests, never()).mergePullRequest(anyString(), anyInt(), anyLong(), anyString(), anyString());
    }

    @Test
    void waitsWhileTheTaskContinuesDeliveryAfterItsTurnStops()
    {
        service.advance(task);
        clock.advance(Duration.ofHours(2));

        service.advance(task.withDevelopmentContinuation(true, clock.instant()));

        assertThat(summary().message()).contains("Waiting for the task to finish its current work");
        verify(pullRequests, never()).mergePullRequest(anyString(), anyInt(), anyLong(), anyString(), anyString());
    }

    @Test
    void anyBlockerRestartsTheWaitAndSaysWhy()
    {
        service.advance(task);
        Instant firstReady = summary().mergeAt().orElseThrow();

        cache(HEAD, false, "blocked");
        clock.advance(Duration.ofMinutes(30));
        service.advance(task);
        assertThat(summary()).isEqualTo(new AutoMergeService.AutoMergeSummary("WAITING", Optional.empty(), Optional.of("Waiting until the pull request can merge (blocked)")));

        cache(HEAD, true, "clean");
        service.advance(task);
        assertThat(summary().message()).contains("Waiting for the pull request to leave draft");

        cache(HEAD, false, "clean");
        when(reviewFixes.settled(any(), any())).thenReturn(false);
        service.advance(task);
        assertThat(summary().message()).contains("Waiting for open review threads");

        when(reviewFixes.settled(any(), any())).thenReturn(true);
        service.advance(task);
        assertThat(summary().mergeAt()).contains(clock.instant().plus(Duration.ofHours(1))).isNotEqualTo(Optional.of(firstReady));
    }

    @Test
    void unavailableReviewThreadsBlockMergeAndRestartTheDelay()
    {
        service.advance(task);
        clock.advance(Duration.ofHours(1));
        StoredPrDetail complete = details.find(12L).orElseThrow();
        when(details.find(12L)).thenReturn(Optional.of(new StoredPrDetail(complete.raw(), complete.reviews(), complete.timeline(), complete.checkRuns(),
                ImmutableList.of(StoredPrDetail.REVIEW_THREADS), complete.viewerCanWrite())));

        service.advance(task);

        assertThat(summary()).isEqualTo(new AutoMergeService.AutoMergeSummary("WAITING", Optional.empty(), Optional.of("Waiting for review threads to refresh")));
        verify(pullRequests, never()).mergePullRequest(anyString(), anyInt(), anyLong(), anyString(), anyString());

        when(details.find(12L)).thenReturn(Optional.of(complete));
        service.advance(task);
        assertThat(summary().mergeAt()).contains(clock.instant().plus(Duration.ofHours(1)));
        clock.advance(Duration.ofMinutes(59));
        service.advance(task);
        verify(pullRequests, never()).mergePullRequest(anyString(), anyInt(), anyLong(), anyString(), anyString());

        clock.advance(Duration.ofMinutes(1));
        service.advance(task);
        verify(pullRequests).mergePullRequest(REPO, 12, 12L, "rebase", HEAD);
    }

    @Test
    void aFailedMergeWaitsForANewHeadAndAnUnfinishedOneIsNeverSentAgain()
    {
        when(settings.preferences()).thenReturn(new AppPreferences(7, 14, AppTheme.SYSTEM, 30, 0));
        when(pullRequests.mergePullRequest(REPO, 12, 12L, "rebase", HEAD)).thenReturn(new MergeResult(HEAD, false, "Head branch was modified"));

        service.advance(task);
        service.advance(task);

        verify(pullRequests, times(1)).mergePullRequest(anyString(), anyInt(), anyLong(), anyString(), anyString());
        assertThat(summary().state()).isEqualTo("FAILED");
        assertThat(summary().message().orElseThrow()).contains("Head branch was modified");

        String next = "d".repeat(40);
        cache(next, false, "clean");
        when(pullRequests.mergePullRequest(REPO, 12, 12L, "rebase", next)).thenReturn(new MergeResult(next, true, "Merged"));
        service.advance(task);
        assertThat(summary().state()).isEqualTo("MERGED");

        // A merge interrupted before its outcome was saved is reported, never repeated.
        rows.put(task.id().toString(), AutoMerge.waiting(task.id().toString(), HEAD, "ready").ready(clock.instant()).started(clock.instant()));
        cache(HEAD, false, "clean");
        service.advance(task);
        assertThat(summary().state()).isEqualTo("UNKNOWN");
        assertThat(summary().message().orElseThrow()).startsWith("The merge's outcome is unknown");
        verify(pullRequests, times(1)).mergePullRequest(REPO, 12, 12L, "rebase", HEAD);
    }

    @Test
    void stoppingAWaitingMergeKeepsItStopped()
    {
        service.advance(task);
        service.cancel(task.id().toString());
        clock.advance(Duration.ofHours(2));

        service.advance(task);

        verify(pullRequests, never()).mergePullRequest(anyString(), anyInt(), anyLong(), anyString(), anyString());
        assertThat(summary().state()).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> service.cancel(task.id().toString())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void replacingThePullRequestAtTheSameHeadRestartsTheFullMergeDelay()
    {
        service.advance(task);
        clock.advance(Duration.ofMinutes(59));

        service.preparePullRequestReplacement(task.id().toString());
        service.pullRequestReplaced(task.id().toString(), HEAD);
        service.pullRequestReplaced(task.id().toString(), HEAD);

        assertThat(summary().mergeAt()).isEmpty();
        service.advance(task);
        assertThat(summary().mergeAt()).contains(clock.instant().plus(Duration.ofHours(1)));
        clock.advance(Duration.ofMinutes(1));
        service.advance(task);
        verify(pullRequests, never()).mergePullRequest(anyString(), anyInt(), anyLong(), anyString(), anyString());
    }

    @Test
    void incompleteReplacementHoldsTheMergeDelayAfterTheTurnStops()
    {
        service.advance(task);
        service.preparePullRequestReplacement(task.id().toString());
        PullRequestReplacement operation = new PullRequestReplacement(task.id().toString(), "w1", 1, 1, new RepoRef("owner", "repo"), new RepoRef("owner", "repo"),
                11, "old-branch", "main", HEAD, "branch", "Task", "Body", true, true, Optional.of(12), false);
        when(replacements.find(task.id().toString())).thenReturn(Optional.of(operation));
        clock.advance(Duration.ofHours(2));

        service.advance(task);

        assertThat(summary().mergeAt()).isEmpty();
        verify(pullRequests, never()).mergePullRequest(anyString(), anyInt(), anyLong(), anyString(), anyString());

        when(replacements.find(task.id().toString())).thenReturn(Optional.of(operation.complete()));
        service.advance(task);

        assertThat(summary().mergeAt()).contains(clock.instant().plus(Duration.ofHours(1)));
        verify(pullRequests, never()).mergePullRequest(anyString(), anyInt(), anyLong(), anyString(), anyString());
    }

    @Test
    void replacementPreservesAnExplicitAutoMergeStop()
    {
        service.advance(task);
        service.cancel(task.id().toString());

        service.preparePullRequestReplacement(task.id().toString());
        service.pullRequestReplaced(task.id().toString(), HEAD);
        clock.advance(Duration.ofHours(2));
        service.advance(task);

        assertThat(summary().state()).isEqualTo("CANCELLED");
        verify(pullRequests, never()).mergePullRequest(anyString(), anyInt(), anyLong(), anyString(), anyString());
    }

    @Test
    void replacementCannotDiscardAnUncertainOrQueuedMerge()
    {
        AutoMerge started = AutoMerge.waiting(task.id().toString(), HEAD, "Ready").started(clock.instant());
        for (AutoMerge current : ImmutableList.of(started, started.ended(AutoMerge.Outcome.FAILED, "Unknown outcome"),
                started.ended(AutoMerge.Outcome.QUEUED, "Queued"), started.ended(AutoMerge.Outcome.MERGED, "Merged"))) {
            rows.put(task.id().toString(), current);

            assertThatThrownBy(() -> service.preparePullRequestReplacement(task.id().toString())).hasMessageContaining("merge has started");
            assertThatThrownBy(() -> service.pullRequestReplaced(task.id().toString(), HEAD)).hasMessageContaining("must not be discarded");
            assertThat(rows.get(task.id().toString())).isEqualTo(current);
        }
    }

    private AutoMergeService.AutoMergeSummary summary()
    {
        return service.summary(task.id().toString(), Optional.of(pullRequest())).orElseThrow();
    }

    private void cache(String head, boolean draft, String mergeableState, PrTimelineEvent... timeline)
    {
        PrRawDetail raw = new PrRawDetail(null, null, "open", "me", null, null, null, null, "Body", ImmutableList.of(), null, ImmutableList.of(), null, draft, true,
                mergeableState, 0, 0, 0, 0, ImmutableList.of(), head, "branch", "main", "b".repeat(40));
        when(details.find(12L)).thenReturn(Optional.of(new StoredPrDetail(raw, ImmutableList.of(), ImmutableList.copyOf(timeline), ImmutableList.of(), ImmutableList.of(), false)));
    }

    private static AgentTask task()
    {
        TaskId id = TaskId.random();
        TaskWorktree worktree = new TaskWorktree(id, "w1", new RepositoryIdentity("owner", "repo"), Path.of("/managed/worktrees", id.toString()), "bytequay/task/" + id,
                "a".repeat(40));
        AgentTask created = created(id, true, "Task", TitleSource.USER, AgentLaneId.CLAUDE_CLI, worktree,
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), CREATED);
        return created.withDefaults(created.defaults().withDevelopmentAutomation(new DevelopmentAutomation(true, AutomationLevel.MERGE, Optional.empty())), CREATED);
    }

    private AgentTurn turn()
    {
        AgentTurnSettings turnSettings = new AgentTurnSettings(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO, Duration.ZERO, ImmutableList.of(),
                Optional.empty(), Optional.empty());
        return AgentTurn.accepted(TurnId.random(), task.id(), "request", turnSettings, CREATED).markDeliveryStarted(CREATED)
                .running(new ProviderTurnRef("provider-turn"), Optional.empty());
    }

    private static PrTimelineEvent queueEvent(String event, Instant at, PrTimelinePayload payload)
    {
        return new PrTimelineEvent(1L, event, "github", at, null, payload, Optional.empty());
    }

    private static PullRequest pullRequest()
    {
        return pullRequest("open");
    }

    private static PullRequest pullRequest(String state)
    {
        return new PullRequest(12, REPO, 12, "Task", "me", "https://github.com/owner/repo/pull/12", CREATED, CREATED, PullRequest.Origin.AUTHORED, ImmutableList.of(),
                ImmutableMap.of(), false, null, null, null, ImmutableList.of(), null, 1, 1, 0, state, null, null, null, null, CREATED, "branch", null, null, null, null);
    }

    private final class MemoryStore
            implements AutoMergeStore
    {
        @Override
        public Optional<AutoMerge> find(String taskId)
        {
            return Optional.ofNullable(rows.get(taskId));
        }

        @Override
        public void save(AutoMerge autoMerge)
        {
            rows.put(autoMerge.taskId(), autoMerge);
        }
    }

    private static final class MutableClock
            extends Clock
    {
        private Instant now;

        MutableClock(Instant now)
        {
            this.now = now;
        }

        void advance(Duration duration)
        {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone()
        {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone)
        {
            return this;
        }

        @Override
        public Instant instant()
        {
            return now;
        }
    }
}
