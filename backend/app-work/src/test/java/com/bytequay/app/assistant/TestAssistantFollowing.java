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

import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventSource;
import com.bytequay.agent.core.AgentHistoryPage;
import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskResources;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.store.AgentApprovalStore;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.TaskLogStore;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentSession;
import com.bytequay.agent.spi.AgentSessionContext;
import com.bytequay.agent.spi.AgentSessionRef;
import com.bytequay.agent.spi.AgentTurnCommand;
import com.bytequay.agent.spi.AgentTurnResult;
import com.bytequay.agent.spi.DevelopmentResultReport;
import com.bytequay.agent.spi.RunningTurn;
import com.bytequay.agent.spi.SessionAccess;
import com.bytequay.app.agent.TaskPublicationService;
import com.bytequay.app.notices.AppNotice;
import com.bytequay.app.notices.AppNoticeService;
import com.bytequay.app.service.settings.SetupPreferences;
import com.bytequay.app.service.settings.SetupSettingsService;
import com.bytequay.core.domain.AssistantAssessment;
import com.bytequay.core.domain.AssistantConversation;
import com.bytequay.core.domain.AssistantFollow;
import com.bytequay.core.domain.AssistantReport;
import com.bytequay.core.domain.RuleAuthorization;
import com.bytequay.core.domain.RuleRun;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.review.ReviewSummary;
import com.bytequay.core.store.AssistantFollowStore;
import com.bytequay.core.store.WorkItemStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.MockMakers.INLINE;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestAssistantFollowing
{
    private static final Instant NOW = Instant.parse("2026-10-17T01:00:00Z");
    private static final String CONVERSATION = UUID.randomUUID().toString();
    private static final TaskId TASK = TaskId.random();
    private static final TurnId TURN = TurnId.random();
    private final MemoryAssistantStore conversations = new MemoryAssistantStore();
    private final MemoryFollows follows = new MemoryFollows();
    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final AgentTurnStore turns = mock(AgentTurnStore.class);
    private final TaskLogStore logs = mock(TaskLogStore.class);
    private final WorkItemStore workItems = mock(WorkItemStore.class);
    private final AgentApprovalStore approvals = mock(AgentApprovalStore.class);
    private final AgentReviewStore reviews = mock(AgentReviewStore.class);
    private final AssistantContinuation continuation = mock(AssistantContinuation.class);
    private final AppNoticeService notices = mock(AppNoticeService.class);
    private final SetupSettingsService setup = mock(SetupSettingsService.class);
    private final AgentDriverRegistry drivers = mock(AgentDriverRegistry.class);
    private final AgentResourceService resources = mock(AgentResourceService.class);
    private final ObjectProvider<TaskPublicationService> publications = mock();
    private final TaskPublicationService publication = mock(TaskPublicationService.class);
    private final ExecutorService executor = mock(ExecutorService.class);
    private final List<Runnable> queued = new ArrayList<>();
    private final AgentTask task = mock(AgentTask.class, withSettings().mockMaker(INLINE));
    private final AgentTurn turn = mock(AgentTurn.class, withSettings().mockMaker(INLINE));
    private final AgentDriver driver = mock(AgentDriver.class);
    private final AgentSession session = mock(AgentSession.class);
    private final RunningTurn running = mock(RunningTurn.class);
    private AssistantFollowing following;

    @TempDir
    private Path directory;

    @BeforeEach
    void setUp()
            throws Exception
    {
        when(task.id()).thenReturn(TASK);
        when(task.laneId()).thenReturn(new AgentLaneId("claude"));
        when(task.defaults()).thenReturn(new AgentTaskDefaults(Optional.of("sonnet"), Optional.empty(), AgentPermissionPolicy.AUTO));
        when(task.workspaceId()).thenReturn(Optional.of("workspace"));
        when(task.title()).thenReturn("Fix the test");
        when(task.state()).thenReturn(TaskState.OPEN);
        when(task.terminalReason()).thenReturn(Optional.empty());
        when(turn.id()).thenReturn(TURN);
        when(turn.state()).thenReturn(TurnState.COMPLETED);
        when(tasks.find(TASK)).thenReturn(Optional.of(task));
        when(turns.findLatest(any())).thenReturn(ImmutableMap.of(TASK, turn));
        when(logs.readAfter(any(), anyLong(), anyInt())).thenReturn(ImmutableList.of());
        when(logs.readHistory(any(), any(), any())).thenReturn(new AgentHistoryPage(ImmutableList.of(), OptionalLong.empty(), OptionalLong.empty(), 0));
        when(notices.current(anyCollection())).thenReturn(ImmutableList.of(notice(AppNotice.Kind.TURN_FINISHED, "Result ready")));
        SetupPreferences preferences = mock(SetupPreferences.class, withSettings().mockMaker(INLINE));
        when(preferences.assistantEnabled()).thenReturn(true);
        when(setup.preferences()).thenReturn(preferences);
        when(drivers.require(any())).thenReturn(driver);
        when(resources.allocate(any())).thenAnswer(call -> {
            Path scratch = Files.createDirectories(directory.resolve(call.getArgument(0).toString()));
            return new TaskResources(scratch, scratch.resolve("events.jsonl"), scratch.resolve("blobs"), scratch.resolve("provider"));
        });
        when(driver.open(any(), any())).thenReturn(session);
        when(session.start(any())).thenReturn(running);
        when(session.reference()).thenReturn(new AgentSessionRef(new AgentLaneId("claude"), 1, "background-session"));
        when(running.completion()).thenReturn(CompletableFuture.completedFuture(answer("The observed checks passed.", true)));
        doAnswer(call -> queued.add(call.getArgument(0))).when(executor).execute(any());
        when(publications.getObject()).thenReturn(publication);
        conversations.save(AssistantConversation.start(CONVERSATION, "Fix the test", NOW.minus(Duration.ofDays(40))));
        follows.save(new AssistantFollow(CONVERSATION, TASK.toString(), NOW.minus(Duration.ofDays(40)), 0, "Fix the test"));
        following = create();
    }

    private AssistantFollowing create()
    {
        return new AssistantFollowing(follows, conversations, tasks, turns, logs, workItems, approvals, reviews, continuation, new ObjectMapper(), notices, setup,
                drivers, resources, publications, Clock.fixed(NOW, ZoneOffset.UTC), executor);
    }

    @Test
    void keepsEveryConversationFollowingAcrossRestartAndMoreThanFiftyChats()
    {
        when(notices.current(anyCollection())).thenReturn(ImmutableList.of(notice(AppNotice.Kind.APPROVAL, "Allow command")));
        for (int index = 0; index < 60; index++) {
            String id = UUID.randomUUID().toString();
            conversations.save(AssistantConversation.start(id, "Another conversation", NOW));
            follows.save(new AssistantFollow(id, TASK.toString(), NOW.minus(Duration.ofDays(2)), 0, "Watch this task"));
        }
        following.poll(conversations::add);
        assertThat(follows.jobs).hasSize(61);
        assertThat(conversations.items(CONVERSATION)).hasSize(1);
        assertThat(conversations.items(CONVERSATION).getFirst().report().orElseThrow().state()).isEqualTo(AssistantReport.State.APPROVAL_REQUIRED);

        create().poll(conversations::add);
        assertThat(conversations.items(CONVERSATION)).hasSize(1);
        assertThat(follows.jobs.values()).allMatch(job -> job.state() == AssistantAssessment.State.PUBLISHED);
        assertThat(queued).isEmpty();
    }

    @Test
    void replayAfterPublicationFailureDoesNotDuplicateTheCard()
    {
        when(notices.current(anyCollection())).thenReturn(ImmutableList.of(notice(AppNotice.Kind.APPROVAL, "Allow command")));
        assertThatThrownBy(() -> following.poll(item -> {
            conversations.add(item);
            throw new IllegalStateException("Stopped after publication");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(follows.jobs.values()).singleElement().extracting(AssistantAssessment::state).isEqualTo(AssistantAssessment.State.QUEUED);

        create().poll(conversations::add);
        assertThat(conversations.items(CONVERSATION)).hasSize(1);
        assertThat(follows.jobs.values()).singleElement().extracting(AssistantAssessment::state).isEqualTo(AssistantAssessment.State.PUBLISHED);
    }

    @Test
    void boundsBackgroundConcurrencyAndLeavesTheRemainingJobsDurable()
    {
        for (int index = 0; index < 2; index++) {
            TaskId id = TaskId.random();
            AgentTask another = mock(AgentTask.class, withSettings().mockMaker(INLINE));
            when(another.id()).thenReturn(id);
            when(another.workspaceId()).thenReturn(Optional.of("workspace"));
            when(another.title()).thenReturn("Another result");
            when(another.state()).thenReturn(TaskState.COMPLETED);
            when(tasks.find(id)).thenReturn(Optional.of(another));
            follows.save(new AssistantFollow(CONVERSATION, id.toString(), NOW, 0, "Explain the result"));
        }
        following.poll(conversations::add);
        assertThat(queued).hasSize(2);
        assertThat(follows.queued()).hasSize(3);
        queued.removeFirst().run();
        following.poll(conversations::add);
        assertThat(queued).hasSize(2);
    }

    @Test
    void interpretsInAnIndependentToolFreeSessionWithoutClaimingCompletion()
    {
        following.poll(conversations::add);
        assertThat(conversations.items(CONVERSATION)).isEmpty();
        assertThat(following.assessing(CONVERSATION)).isEqualTo(1);
        verify(driver, never()).open(any(), any());
        doAnswer(call -> {
            AgentSessionContext context = call.getArgument(0);
            assertThat(context.access()).isEqualTo(SessionAccess.TEXT_ONLY);
            assertThat(context.sessionReference()).isEmpty();
            assertThat(context.sessionTools()).isEmpty();
            return session;
        }).when(driver).open(any(), any());

        queued.removeFirst().run();
        AssistantReport report = conversations.items(CONVERSATION).getFirst().report().orElseThrow();
        assertThat(report.state()).isEqualTo(AssistantReport.State.RESULT_READY);
        assertThat(report.assessment()).contains("The observed checks passed.");
        assertThat(following.assessing(CONVERSATION)).isZero();
        assertThat(follows.follows()).hasSize(1);
    }

    @Test
    void discardsAnInterpretationWhenTheSourceChangesDuringInference()
    {
        following.poll(conversations::add);
        when(running.completion()).thenAnswer(call -> {
            when(turn.version()).thenReturn(2L);
            return CompletableFuture.completedFuture(answer("Old result", true));
        });
        queued.removeFirst().run();
        assertThat(conversations.items(CONVERSATION)).isEmpty();
        assertThat(follows.jobs.values()).singleElement().extracting(AssistantAssessment::state).isEqualTo(AssistantAssessment.State.SUPERSEDED);
        following.poll(conversations::add);
        assertThat(queued).hasSize(1);
    }

    @Test
    void directCompletionIsVisibleWithoutAnInterruptionAndExplicitQuestionsAreAnswered()
    {
        when(running.completion()).thenReturn(CompletableFuture.completedFuture(answer("No useful change", false)));
        following.poll(conversations::add);
        queued.removeFirst().run();
        assertThat(conversations.items(CONVERSATION)).singleElement().satisfies(item ->
                assertThat(item.report().orElseThrow().delivery()).isEqualTo(AssistantReport.Delivery.PASSIVE));
        assertThat(follows.jobs.values()).singleElement().extracting(AssistantAssessment::state).isEqualTo(AssistantAssessment.State.QUIET);

        following.request(CONVERSATION, "task:" + TASK, "Explain the result", conversations::add);
        queued.removeFirst().run();
        assertThat(conversations.items(CONVERSATION)).hasSize(2);
        assertThat(conversations.items(CONVERSATION).getFirst().report().orElseThrow().assessment()).contains("No useful change");
    }

    @Test
    void distinctQuestionsAboutTheSameResultHaveDistinctReadOnlyJobs()
    {
        following.request(CONVERSATION, "task:" + TASK, "Explain the tests", conversations::add);
        queued.removeFirst().run();
        following.request(CONVERSATION, "task:" + TASK, "Explain the tests", conversations::add);
        assertThat(queued).isEmpty();
        following.request(CONVERSATION, "task:" + TASK, "Explain the remaining limitations", conversations::add);
        assertThat(queued).hasSize(1);
        assertThat(follows.jobs).hasSize(2);
    }

    @Test
    void unresolvedRecoveryTakesPrecedenceOverAnOldTerminalTaskResult()
    {
        when(task.state()).thenReturn(TaskState.COMPLETED);
        when(notices.current(anyCollection())).thenReturn(ImmutableList.of(notice(AppNotice.Kind.RECOVERY, "Automatic action outcome is unknown")));
        following.poll(conversations::add);
        assertThat(queued).isEmpty();
        assertThat(conversations.items(CONVERSATION)).singleElement()
                .satisfies(item -> assertThat(item.report().orElseThrow().state()).isEqualTo(AssistantReport.State.ATTENTION));
    }

    @Test
    void aTaskEndingDoesNotClaimTheWorkIsComplete()
    {
        when(task.state()).thenReturn(TaskState.COMPLETED);
        WorkItem work = mock(WorkItem.class, withSettings().mockMaker(INLINE));
        when(work.id()).thenReturn("work-1");
        when(work.title()).thenReturn("Accepted outcome");
        when(work.state()).thenReturn(WorkItem.State.WORKING);
        when(workItems.findByIdentity(any())).thenReturn(Optional.of(work));
        following.poll(conversations::add);
        queued.removeFirst().run();
        assertThat(conversations.items(CONVERSATION).getFirst().report().orElseThrow().state()).isEqualTo(AssistantReport.State.RESULT_READY);
        assertThat(follows.follows()).hasSize(1);

        when(work.state()).thenReturn(WorkItem.State.DONE);
        when(work.revision()).thenReturn(1L);
        when(work.completionRule()).thenReturn(WorkItem.CompletionRule.ACCEPTED_RESULT);
        following.poll(conversations::add);
        assertThat(conversations.items(CONVERSATION).getLast().report().orElseThrow().state()).isEqualTo(AssistantReport.State.COMPLETED);
        assertThat(follows.follows()).isEmpty();
    }

    @Test
    void automaticApprovalsStayQuietAndTheirThreeReceiptsReachTheFinalResultAfterRestart()
    {
        RuleAuthorization authorization = new RuleAuthorization("rule-1", "Task documentation reads", 3);
        ImmutableList.Builder<AgentEventEnvelope> events = ImmutableList.builder();
        for (int index = 1; index <= 3; index++) {
            AgentEventPayload.ApprovalResolved resolved = new AgentEventPayload.ApprovalResolved(UUID.randomUUID().toString(), AgentEventPayload.ApprovalOutcome.ALLOWED,
                    false, Optional.of(authorization));
            events.add(new AgentEventEnvelope(AgentEventEnvelope.CURRENT_SCHEMA_VERSION, EventId.random(), index, TASK, Optional.of(TURN), AgentEventSource.root(), NOW, resolved));
        }
        when(logs.readAfter(TASK, 0, 200)).thenReturn(events.build());
        following.poll(conversations::add);
        assertThat(conversations.items(CONVERSATION)).isEmpty();
        assertThat(follows.ruleReceipts(Optional.of(CONVERSATION), TASK.toString())).hasSize(3)
                .allMatch(receipt -> receipt.state() == AssistantAssessment.State.QUIET);
        assertThat(follows.follows().getFirst().afterSequence()).isEqualTo(3);
        queued.clear();
        create().poll(conversations::add);
        assertThat(conversations.items(CONVERSATION)).isEmpty();
        queued.removeFirst().run();
        assertThat(conversations.items(CONVERSATION)).singleElement().satisfies(item -> {
            AssistantReport report = item.report().orElseThrow();
            assertThat(report.state()).isEqualTo(AssistantReport.State.RESULT_READY);
            assertThat(report.ruleActions()).hasSize(3).allSatisfy(action -> {
                assertThat(action.ruleName()).isEqualTo("Task documentation reads");
                assertThat(action.ruleVersion()).isEqualTo(3);
                assertThat(action.outcome()).isEqualTo("ALLOWED");
            });
            assertThat(report.ruleActions().stream().map(action -> action.detail()).distinct()).hasSize(3);
        });
        create().poll(conversations::add);
        assertThat(conversations.items(CONVERSATION)).hasSize(1);
    }

    @Test
    void aTerminalResultWaitsForBoundedReplayToReachItsLastApproval()
    {
        when(task.state()).thenReturn(TaskState.COMPLETED);
        ImmutableList.Builder<AgentEventEnvelope> firstPage = ImmutableList.builder();
        for (int index = 1; index <= 200; index++) {
            firstPage.add(new AgentEventEnvelope(AgentEventEnvelope.CURRENT_SCHEMA_VERSION, EventId.random(), index, TASK, Optional.of(TURN), AgentEventSource.root(), NOW,
                    new AgentEventPayload.Lifecycle(AgentEventPayload.LifecyclePhase.TURN_STARTED, "Working")));
        }
        AgentEventPayload.ApprovalResolved resolved = new AgentEventPayload.ApprovalResolved(UUID.randomUUID().toString(), AgentEventPayload.ApprovalOutcome.ALLOWED, false,
                Optional.of(new RuleAuthorization("rule-1", "Task documentation reads", 3)));
        AgentEventEnvelope last = new AgentEventEnvelope(AgentEventEnvelope.CURRENT_SCHEMA_VERSION, EventId.random(), 201, TASK, Optional.of(TURN),
                AgentEventSource.root(), NOW, resolved);
        when(logs.readAfter(TASK, 0, 200)).thenReturn(firstPage.build());
        when(logs.readAfter(TASK, 200, 200)).thenReturn(ImmutableList.of(last));
        following.poll(conversations::add);
        assertThat(conversations.items(CONVERSATION)).isEmpty();
        assertThat(queued).isEmpty();
        assertThat(follows.follows()).singleElement().extracting(AssistantFollow::afterSequence).isEqualTo(200L);
        following.poll(conversations::add);
        assertThat(queued).hasSize(1);
        queued.removeFirst().run();
        assertThat(conversations.items(CONVERSATION)).singleElement().satisfies(item -> {
            assertThat(item.report().orElseThrow().state()).isEqualTo(AssistantReport.State.RESULT_READY);
            assertThat(item.report().orElseThrow().ruleActions()).hasSize(1);
        });
        assertThat(follows.follows()).isEmpty();
    }

    @Test
    void disablingTheAssistantKeepsItsAppOwnedResultAndNeverStrandsAlerts()
    {
        when(setup.preferences().assistantEnabled()).thenReturn(false);
        following.poll(conversations::add);
        assertThat(queued).isEmpty();
        assertThat(conversations.items(CONVERSATION)).singleElement().satisfies(item -> {
            assertThat(item.report().orElseThrow().state()).isEqualTo(AssistantReport.State.RESULT_READY);
            assertThat(item.report().orElseThrow().assessment()).isEmpty();
        });
    }

    @Test
    void readsDurableRuleReviewEvidenceWithoutCreatingAChatAndKeepsTheReviewAccount()
    {
        ruleReview();
        following.poll(conversations::add);
        assertThat(follows.queued()).singleElement().satisfies(job -> assertThat(job.conversationId()).isEmpty());
        assertThat(conversations.items(CONVERSATION)).isEmpty();
        queued.removeFirst().run();

        ArgumentCaptor<AgentTurnCommand> input = ArgumentCaptor.forClass(AgentTurnCommand.class);
        verify(session).start(input.capture());
        assertThat(input.getValue().message()).contains("Avoid duplicate rows", "The join now groups by key", "F-1 [OPEN; P1]", "Rows can be lost", "RESOLVED")
                .doesNotContain("Outdated finding");
        verify(logs, never()).readHistory(any(), any(), any());
        verify(setup, never()).requireUsableDevelopmentAgent();
        verify(drivers).checkWorkspaceAccess(new AgentLaneId("codex-account-review"), Optional.of("workspace"));
        verify(drivers).require(new AgentLaneId("codex-account-review"));
        ArgumentCaptor<AgentSessionContext> context = ArgumentCaptor.forClass(AgentSessionContext.class);
        verify(driver).open(context.capture(), any());
        assertThat(context.getValue().model()).contains("review-model");
        assertThat(context.getValue().access()).isEqualTo(SessionAccess.TEXT_ONLY);
        assertThat(follows.jobs.values()).singleElement().satisfies(job -> {
            assertThat(job.state()).isEqualTo(AssistantAssessment.State.PUBLISHED);
            assertThat(job.report().assessment()).contains("The observed checks passed.");
            assertThat(job.report().ruleActions()).singleElement().satisfies(action -> assertThat(action.ruleVersion()).isEqualTo(3));
        });
        assertThat(conversations.items(CONVERSATION)).isEmpty();
        create().poll(conversations::add);
        assertThat(queued).isEmpty();
        assertThat(follows.follows()).hasSize(1);
    }

    @Test
    void ordinaryDirectResultsStayPassiveEvenWhenAReaderRequestsARuleNotification()
    {
        following.poll(conversations::add);
        queued.removeFirst().run();
        assertThat(conversations.items(CONVERSATION)).singleElement().satisfies(item -> {
            assertThat(item.report().orElseThrow().delivery()).isEqualTo(AssistantReport.Delivery.PASSIVE);
            assertThat(item.report().orElseThrow().assessment()).contains("The observed checks passed.");
        });
        verify(continuation, never()).continueWork(any());
    }

    @Test
    void seriousResultsInterruptAndNeverAutomaticallyContinue()
    {
        when(running.completion()).thenReturn(CompletableFuture.completedFuture(interpretation("Publication outcome is uncertain", "RISK", "SAFE_CONTINUE")));
        following.poll(conversations::add);
        queued.removeFirst().run();
        assertThat(conversations.items(CONVERSATION)).singleElement().satisfies(item ->
                assertThat(item.report().orElseThrow().delivery()).isEqualTo(AssistantReport.Delivery.INTERRUPT));
        verify(continuation, never()).continueWork(any());
    }

    @Test
    void aStepLeftToTheUserByHandIsABlockerThatByteQuayOffersToDo()
    {
        when(running.completion()).thenReturn(CompletableFuture.completedFuture(handedBack("Run git push to publish the branch", "PUSH")));
        when(publication.offered(TASK)).thenReturn(Optional.of(task));
        following.poll(conversations::add);
        queued.removeFirst().run();
        assertThat(conversations.items(CONVERSATION)).singleElement().satisfies(item -> {
            assertThat(item.report().orElseThrow().delivery()).isEqualTo(AssistantReport.Delivery.INTERRUPT);
            assertThat(item.report().orElseThrow().offer()).contains(AssistantReport.Offer.PUSH);
        });
        verify(continuation, never()).continueWork(any());
    }

    @Test
    void aHandedBackStepByteQuayCannotDoStillInterruptsWithoutAnOffer()
    {
        when(running.completion()).thenReturn(CompletableFuture.completedFuture(handedBack("Close the pull request with gh", "CLOSE")));
        when(publication.offered(TASK)).thenReturn(Optional.empty());
        following.poll(conversations::add);
        queued.removeFirst().run();
        AssistantReport report = conversations.items(CONVERSATION).getLast().report().orElseThrow();
        assertThat(report.delivery()).isEqualTo(AssistantReport.Delivery.INTERRUPT);
        assertThat(report.offer()).isEmpty();

        when(turn.version()).thenReturn(2L);
        when(running.completion()).thenReturn(CompletableFuture.completedFuture(handedBack("Install the CLI and log in", "OTHER")));
        when(publication.offered(TASK)).thenReturn(Optional.of(task));
        following.poll(conversations::add);
        queued.removeFirst().run();
        report = conversations.items(CONVERSATION).getLast().report().orElseThrow();
        assertThat(report.delivery()).isEqualTo(AssistantReport.Delivery.INTERRUPT);
        assertThat(report.offer()).isEmpty();
        verify(continuation, never()).continueWork(any());
    }

    @Test
    void aQuestionThatNamesOneTaskToolOffersItsOneClickAnswer()
    {
        when(turns.find(TURN)).thenReturn(Optional.of(turn));
        when(publication.offered(TASK)).thenReturn(Optional.of(task));
        assertThat(askedOffer("Nothing changed. Should I push it with push_task_branch?")).contains(AssistantReport.Offer.PUSH);
        assertThat(askedOffer("Should I close it with close_task_pull_request?")).contains(AssistantReport.Offer.CLOSE);
        assertThat(askedOffer("Should I keep going?")).isEmpty();
        assertThat(askedOffer("Don't use push_task_branch yet; should I wait?")).isEmpty();
        assertThat(askedOffer("Should I use push_task_branch or close_task_pull_request?")).isEmpty();
        when(publication.offered(TASK)).thenReturn(Optional.empty());
        assertThat(askedOffer("Should I push it with push_task_branch?")).isEmpty();
        assertThat(queued).isEmpty();
    }

    @Test
    void routineUnfinishedWorkContinuesQuietlyAndExhaustedRecoveryNeedsAttention()
    {
        when(running.completion()).thenReturn(CompletableFuture.completedFuture(interpretation("The requested regression check is still missing", "NONE", "SAFE_CONTINUE")));
        when(continuation.continueWork(any())).thenAnswer(_ -> {
            assertThat(Thread.holdsLock(following)).isFalse();
            return AssistantContinuation.Outcome.STARTED;
        });
        following.poll(conversations::add);
        queued.removeFirst().run();
        assertThat(conversations.items(CONVERSATION)).singleElement().satisfies(item -> {
            assertThat(item.report().orElseThrow().state()).isEqualTo(AssistantReport.State.PROGRESS);
            assertThat(item.report().orElseThrow().delivery()).isEqualTo(AssistantReport.Delivery.PASSIVE);
            assertThat(item.report().orElseThrow().outcome()).contains("CONTINUED");
        });
        assertThat(follows.jobs.values()).singleElement().extracting(AssistantAssessment::state).isEqualTo(AssistantAssessment.State.SUPERSEDED);
        when(turn.version()).thenReturn(2L);
        when(continuation.continueWork(any())).thenReturn(AssistantContinuation.Outcome.LIMIT_REACHED);
        following.poll(conversations::add);
        queued.removeFirst().run();
        AssistantReport latest = conversations.items(CONVERSATION).getLast().report().orElseThrow();
        assertThat(latest.state()).isEqualTo(AssistantReport.State.ATTENTION);
        assertThat(latest.delivery()).isEqualTo(AssistantReport.Delivery.INTERRUPT);
        assertThat(latest.outcome()).contains("RECOVERY_LIMIT");
    }

    @Test
    void disablingAssistantDuringAssessmentPreventsContinuation()
    {
        when(running.completion()).thenAnswer(_ -> {
            when(setup.preferences().assistantEnabled()).thenReturn(false);
            return CompletableFuture.completedFuture(interpretation("A regression check remains", "NONE", "SAFE_CONTINUE"));
        });
        following.poll(conversations::add);
        queued.removeFirst().run();
        verify(continuation, never()).continueWork(any());
        assertThat(conversations.items(CONVERSATION)).singleElement().satisfies(item ->
                assertThat(item.report().orElseThrow().assessment()).contains("A regression check remains"));
    }

    @Test
    void aTerminalTaskKeepsTheInboxSourceIdentityWhileLinkedWorkRemainsOpen()
    {
        when(task.state()).thenReturn(TaskState.COMPLETED);
        WorkItem work = mock(WorkItem.class, withSettings().mockMaker(INLINE));
        when(work.state()).thenReturn(WorkItem.State.WORKING);
        when(workItems.findByIdentity(any())).thenReturn(Optional.of(work));
        AppNotice notice = notices.current(ImmutableList.of(TASK)).getFirst();
        following.poll(conversations::add);
        queued.removeFirst().run();
        assertThat(follows.jobs.values()).singleElement().satisfies(job -> {
            assertThat(job.report().noticeId()).isEqualTo(notice.id());
            assertThat(job.sourceLine()).isEqualTo(notice.summaryLine());
            assertThat(job.report().assessment()).contains("The observed checks passed.");
        });
        assertThat(follows.follows()).hasSize(1);
    }

    @Test
    void keepsUnhelpfulRuleReviewsQuietAcrossRestartAndReplaysPendingAssessment()
    {
        ruleReview();
        when(running.completion()).thenReturn(CompletableFuture.completedFuture(answer("No useful change since the previous review", false)));
        following.poll(conversations::add);
        queued.clear();
        AssistantFollowing restarted = create();
        restarted.poll(conversations::add);
        queued.removeFirst().run();
        assertThat(follows.jobs.values()).singleElement().satisfies(job -> {
            assertThat(job.state()).isEqualTo(AssistantAssessment.State.QUIET);
            assertThat(job.report().assessment()).contains("No useful change since the previous review");
        });
        restarted.poll(conversations::add);
        assertThat(queued).isEmpty();
        assertThat(conversations.items(CONVERSATION)).isEmpty();
    }

    @Test
    void discardsRuleReviewSummaryIfANewerHeadArrivesDuringAssessment()
    {
        ruleReview();
        when(running.completion()).thenAnswer(call -> {
            when(notices.current(anyCollection())).thenReturn(ImmutableList.of());
            return CompletableFuture.completedFuture(answer("This result belongs to the previous head", true));
        });
        following.poll(conversations::add);
        queued.removeFirst().run();
        assertThat(follows.jobs.values()).singleElement().extracting(AssistantAssessment::state).isEqualTo(AssistantAssessment.State.SUPERSEDED);
        create().poll(conversations::add);
        assertThat(queued).isEmpty();
    }

    @Test
    void retriesTheSameReviewIfAConversationTemporarilyHidItDuringAssessment()
    {
        ruleReview();
        AppNotice original = notices.current(ImmutableList.of(TASK)).getFirst();
        when(running.completion()).thenAnswer(call -> {
            when(notices.current(anyCollection())).thenReturn(ImmutableList.of());
            return CompletableFuture.completedFuture(answer("Conversation is active", true));
        });
        following.poll(conversations::add);
        queued.removeFirst().run();
        when(running.completion()).thenReturn(CompletableFuture.completedFuture(answer("Review findings need inspection", true)));
        when(notices.current(anyCollection())).thenReturn(ImmutableList.of(original));
        create().poll(conversations::add);
        queued.removeFirst().run();
        assertThat(follows.jobs.values()).singleElement().satisfies(job -> {
            assertThat(job.state()).isEqualTo(AssistantAssessment.State.PUBLISHED);
            assertThat(job.report().assessment()).contains("Review findings need inspection");
        });
    }

    @Test
    void rechecksFindingStateBeforePublishingAndEnrolsRetainedStartsWithoutAChat()
    {
        ruleReview();
        RuleRun origin = follows.follow(TASK.toString()).orElseThrow().ruleRun().orElseThrow();
        follows.watching.clear();
        when(notices.ruleReviews()).thenReturn(ImmutableMap.of(TASK.toString(), origin));
        AppNotice original = notices.current(ImmutableList.of(TASK)).getFirst();
        ReviewFinding finding = reviews.listFindings("review-1").getFirst();
        when(running.completion()).thenAnswer(call -> {
            when(finding.state()).thenReturn(ReviewFinding.State.RESOLVED);
            when(finding.version()).thenReturn(1L);
            AppNotice changed = new AppNotice(original.id() + ":resolved", original.kind(), original.taskId(), original.workspaceId(), original.turnId(), original.reviewId(),
                    original.title(), original.pullRequest(), original.pullRequestTitle(), original.branch(), original.changes(), original.message(), original.repository(),
                    original.laneId(), original.workItemId(), original.occurredAt(), original.expiresAt(), original.ruleRun());
            when(notices.current(anyCollection())).thenReturn(ImmutableList.of(changed));
            return CompletableFuture.completedFuture(answer("Inspect the open finding", true));
        });
        following.poll(conversations::add);
        assertThat(follows.follows()).singleElement().satisfies(follow -> assertThat(follow.conversationId()).isEmpty());
        queued.removeFirst().run();
        assertThat(follows.jobs.values()).singleElement().extracting(AssistantAssessment::state).isEqualTo(AssistantAssessment.State.SUPERSEDED);
        when(running.completion()).thenReturn(CompletableFuture.completedFuture(answer("The finding was already resolved", false)));
        following.poll(conversations::add);
        queued.removeFirst().run();
        assertThat(follows.jobs.values()).filteredOn(job -> job.state() == AssistantAssessment.State.QUIET).hasSize(1);
        assertThat(conversations.items(CONVERSATION)).isEmpty();
    }

    @Test
    void unavailableReviewAccountPublishesSourceFactsWithoutSendingEvidenceElsewhere()
    {
        ruleReview();
        doThrow(new IllegalArgumentException("The review account no longer permits this repository")).when(drivers).checkWorkspaceAccess(any(), any());
        following.poll(conversations::add);
        queued.removeFirst().run();
        verify(driver, never()).open(any(), any());
        verify(setup, never()).requireUsableDevelopmentAgent();
        assertThat(follows.jobs.values()).singleElement().satisfies(job -> {
            assertThat(job.state()).isEqualTo(AssistantAssessment.State.PUBLISHED);
            assertThat(job.report().assessment()).isEmpty();
            assertThat(job.sourceLine()).contains("The private review is ready");
        });
    }

    @Test
    void invalidRuleReviewAssessmentFallsBackAndDisabledAssistantDoesNotRunAModel()
    {
        ruleReview();
        when(running.completion()).thenReturn(CompletableFuture.completedFuture(new AgentTurnResult(AgentTurnResult.State.COMPLETED, Optional.of("Invalid JSON"),
                Optional.empty(), Duration.ofSeconds(1), true)));
        following.poll(conversations::add);
        queued.removeFirst().run();
        assertThat(follows.jobs.values()).singleElement().satisfies(job -> {
            assertThat(job.state()).isEqualTo(AssistantAssessment.State.PUBLISHED);
            assertThat(job.report().assessment()).isEmpty();
        });
        follows.jobs.clear();
        when(setup.preferences().assistantEnabled()).thenReturn(false);
        create().poll(conversations::add);
        assertThat(queued).isEmpty();
        assertThat(follows.jobs.values()).singleElement().extracting(AssistantAssessment::state).isEqualTo(AssistantAssessment.State.PUBLISHED);
    }

    private void ruleReview()
    {
        follows.watching.clear();
        RuleRun rule = RuleRun.automatic(new RuleAuthorization("rule-1", "Review changes to my lines", 3), "pr:1:head:head-a", "owner/repository#1", "Review joins", NOW)
                .started("review:review-1").finished("STARTED");
        follows.save(AssistantFollow.ruleReview(TASK.toString(), NOW, "Read changes to my lines and summarize useful findings", rule));
        AgentReview review = mock(AgentReview.class, withSettings().mockMaker(INLINE));
        when(review.state()).thenReturn(AgentReview.State.OPEN);
        when(reviews.findReviewByTask(TASK.toString())).thenReturn(Optional.of(review));
        ReviewRun run = mock(ReviewRun.class, withSettings().mockMaker(INLINE));
        when(run.id()).thenReturn("review-run-1");
        when(run.reviewId()).thenReturn("review-1");
        when(run.headSha()).thenReturn("head-a");
        when(run.sourceRevision()).thenReturn("head-a");
        when(run.scope()).thenReturn(ReviewRun.Scope.INCREMENTAL);
        when(run.state()).thenReturn(ReviewRun.State.ACCEPTED);
        when(run.turnIds()).thenReturn(ImmutableList.of(TURN.toString()));
        when(run.summary()).thenReturn(Optional.of(new ReviewSummary(1, "Avoid duplicate rows", ImmutableList.of(), "The join now groups by key",
                Optional.of("Focused tests passed"), ImmutableList.of("finding-1"))));
        when(reviews.listRuns("review-1")).thenReturn(ImmutableList.of(run));
        when(reviews.findRun("review-run-1")).thenReturn(Optional.of(run));
        ReviewFinding finding = mock(ReviewFinding.class, withSettings().mockMaker(INLINE));
        when(finding.label()).thenReturn("F-1");
        when(finding.state()).thenReturn(ReviewFinding.State.OPEN);
        when(finding.severity()).thenReturn(Optional.of("P1"));
        when(finding.title()).thenReturn("Rows can be lost");
        when(finding.body()).thenReturn("Null keys need preserving");
        when(finding.validatedHeadSha()).thenReturn("head-a");
        when(finding.runId()).thenReturn("previous-run");
        ReviewFinding outdated = mock(ReviewFinding.class, withSettings().mockMaker(INLINE));
        when(outdated.outdated()).thenReturn(true);
        when(outdated.title()).thenReturn("Outdated finding");
        when(reviews.listFindings("review-1")).thenReturn(ImmutableList.of(finding, outdated));
        when(task.laneId()).thenReturn(new AgentLaneId("codex-account-next"));
        when(task.defaults()).thenReturn(new AgentTaskDefaults(Optional.of("next-model"), Optional.empty(), AgentPermissionPolicy.AUTO));
        when(turns.find(TURN)).thenReturn(Optional.of(turn));
        when(turn.settings()).thenReturn(Optional.of(new AgentTurnSettings(Optional.of("review-model"), Optional.of("high"), AgentPermissionPolicy.AUTO, Duration.ZERO,
                ImmutableList.of(), Optional.empty(), Optional.empty()).withLaneId(new AgentLaneId("codex-account-review"))));
        AppNotice notice = new AppNotice("review-result:review-run-1:0", AppNotice.Kind.TURN_FINISHED, TASK.toString(), "workspace", Optional.of(TURN.toString()),
                Optional.of("review-1"), "Review joins", Optional.of("owner/repository#1"), Optional.of("Review joins"), Optional.empty(), Optional.empty(),
                "The private review is ready. Read the full review for its findings and verification.", "owner/repository", "codex-account-review", Optional.empty(), NOW,
                Optional.empty(), Optional.of(rule.finished("RESULT_READY")));
        when(notices.current(anyCollection())).thenReturn(ImmutableList.of(notice));
    }

    @Test
    void manualApprovalOutcomesRemainVisibleImmediately()
    {
        AgentEventPayload.ApprovalResolved resolved = new AgentEventPayload.ApprovalResolved(UUID.randomUUID().toString(), AgentEventPayload.ApprovalOutcome.DENIED, false);
        AgentEventEnvelope event = new AgentEventEnvelope(AgentEventEnvelope.CURRENT_SCHEMA_VERSION, EventId.random(), 1, TASK, Optional.of(TURN),
                AgentEventSource.root(), NOW, resolved);
        when(logs.readAfter(TASK, 0, 200)).thenReturn(ImmutableList.of(event));
        following.poll(conversations::add);
        assertThat(conversations.items(CONVERSATION)).singleElement().satisfies(item -> {
            assertThat(item.report().orElseThrow().state()).isEqualTo(AssistantReport.State.PROGRESS);
            assertThat(item.report().orElseThrow().outcome()).contains("DENIED");
        });
    }

    @Test
    void replaysAnUnpublishedReadOnlyJobAfterRestart()
    {
        following.poll(conversations::add);
        assertThat(queued).hasSize(1);
        queued.clear();
        create().poll(conversations::add);
        assertThat(queued).hasSize(1);
        queued.removeFirst().run();
        create().poll(conversations::add);
        assertThat(conversations.items(CONVERSATION)).hasSize(1);
    }

    private static AgentTurnResult interpretation(String text, String attention, String nextStep)
    {
        return new AgentTurnResult(AgentTurnResult.State.COMPLETED, Optional.of("{\"summary\":\"" + text + "\",\"attention\":\"" + attention
                + "\",\"reason\":\"Delivery reflects the observed outcome.\",\"nextStep\":\"" + nextStep + "\"}"), Optional.empty(), Duration.ofSeconds(1), true);
    }

    // An assessor that misses the blocker rule still says NONE and SAFE_CONTINUE; the app treats the hand-back as the blocker itself.
    private static AgentTurnResult handedBack(String text, String step)
    {
        return new AgentTurnResult(AgentTurnResult.State.COMPLETED, Optional.of("{\"summary\":\"" + text + "\",\"attention\":\"NONE\","
                + "\"reason\":\"The result asks the user to act.\",\"nextStep\":\"SAFE_CONTINUE\",\"handBack\":\"" + step + "\"}"), Optional.empty(),
                Duration.ofSeconds(1), true);
    }

    private static AgentTurnResult answer(String text, boolean notifyUser)
    {
        return new AgentTurnResult(AgentTurnResult.State.COMPLETED, Optional.of("{\"summary\":\"" + text + "\",\"attention\":\""
                + (notifyUser ? "REQUESTED_NOTIFICATION" : "NONE") + "\",\"reason\":\"Result delivery follows the request.\",\"nextStep\":\"NONE\"}"), Optional.empty(),
                Duration.ofSeconds(1), true);
    }

    // A finished turn's question, as a report that is not ready for delivery asks it.
    private Optional<AssistantReport.Offer> askedOffer(String question)
    {
        when(turn.developmentResult()).thenReturn(Optional.of(new DevelopmentResultReport(false, Optional.of(question))));
        when(notices.current(anyCollection())).thenReturn(ImmutableList.of(new AppNotice("question:" + TURN, AppNotice.Kind.QUESTION, TASK.toString(), "workspace",
                Optional.of(TURN.toString()), Optional.empty(), "Fix the test", Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), question,
                "owner/repository", "claude", Optional.empty(), NOW, Optional.empty())));
        following.poll(conversations::add);
        AssistantReport report = conversations.items(CONVERSATION).getLast().report().orElseThrow();
        assertThat(report.state()).isEqualTo(AssistantReport.State.ANSWER_REQUIRED);
        return report.offer();
    }

    private static AppNotice notice(AppNotice.Kind kind, String message)
    {
        return new AppNotice("notice:" + kind, kind, TASK.toString(), "workspace", Optional.of(TURN.toString()), Optional.empty(), "Fix the test", Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), message, "owner/repository", "claude", Optional.empty(), NOW, Optional.empty());
    }

    private static final class MemoryFollows
            implements AssistantFollowStore
    {
        private final Map<String, AssistantFollow> watching = new LinkedHashMap<>();
        private final Map<String, AssistantAssessment> jobs = new LinkedHashMap<>();

        @Override
        public List<AssistantFollow> follows()
        {
            return ImmutableList.copyOf(watching.values());
        }

        @Override
        public Optional<AssistantFollow> follow(String conversationId, String taskId)
        {
            return Optional.ofNullable(watching.get(conversationId + ":" + taskId));
        }

        @Override
        public Optional<AssistantFollow> follow(String taskId)
        {
            return Optional.ofNullable(watching.get("rule:" + taskId));
        }

        @Override
        public void remove(AssistantFollow follow)
        {
            watching.remove(follow.id());
        }

        @Override
        public void save(AssistantFollow follow)
        {
            watching.put(follow.id(), follow);
        }

        @Override
        public void remove(String conversationId, String taskId)
        {
            watching.remove(conversationId + ":" + taskId);
        }

        @Override
        public Optional<AssistantAssessment> assessment(String id)
        {
            return Optional.ofNullable(jobs.get(id));
        }

        @Override
        public List<AssistantAssessment> queued()
        {
            return jobs.values().stream().filter(job -> job.state() == AssistantAssessment.State.QUEUED).collect(toImmutableList());
        }

        @Override
        public boolean followsTask(String taskId)
        {
            return watching.values().stream().anyMatch(follow -> follow.taskId().equals(taskId));
        }

        @Override
        public Optional<AssistantAssessment> result(String taskId, String noticeId, String sourceLine)
        {
            return jobs.values().stream().filter(job -> job.taskId().equals(taskId) && job.report().noticeId().equals(noticeId)
                    && job.sourceLine().equals(sourceLine) && !job.requested()).findFirst();
        }

        @Override
        public List<AssistantAssessment> ruleReceipts(Optional<String> conversationId, String taskId)
        {
            return jobs.values().stream().filter(job -> job.conversationId().equals(conversationId) && job.taskId().equals(taskId) && job.ruleReceipt())
                    .collect(toImmutableList());
        }

        @Override
        public void save(AssistantAssessment job)
        {
            jobs.put(job.id(), job);
        }
    }
}
