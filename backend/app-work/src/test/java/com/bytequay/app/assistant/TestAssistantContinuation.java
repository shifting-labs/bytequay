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

import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.spi.DevelopmentResultReport;
import com.bytequay.core.domain.AgendaTarget;
import com.bytequay.core.domain.AssistantAssessment;
import com.bytequay.core.domain.AssistantFollow;
import com.bytequay.core.domain.AssistantReport;
import com.bytequay.core.store.AssistantFollowStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.MockMakers.INLINE;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestAssistantContinuation
{
    private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");
    private final String conversationId = UUID.randomUUID().toString();
    private final TaskId taskId = TaskId.random();
    private final TurnId turnId = TurnId.random();
    private final AssistantFollowStore follows = mock(AssistantFollowStore.class);
    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final AgentTurnStore turns = mock(AgentTurnStore.class);
    private final AgentTurnService executor = mock(AgentTurnService.class);
    private final AgentTask task = mock(AgentTask.class, withSettings().mockMaker(INLINE));
    private final AgentTurn turn = mock(AgentTurn.class, withSettings().mockMaker(INLINE));
    private final AssistantContinuation continuation = new AssistantContinuation(follows, tasks, turns, executor);

    @BeforeEach
    void setUp()
    {
        when(follows.follow(conversationId, taskId.toString())).thenReturn(Optional.of(new AssistantFollow(conversationId, taskId.toString(), NOW, 0, "Original request")));
        when(tasks.find(taskId)).thenReturn(Optional.of(task));
        when(task.interactive()).thenReturn(true);
        when(task.state()).thenReturn(TaskState.OPEN);
        when(task.version()).thenReturn(7L);
        when(turns.find(turnId)).thenReturn(Optional.of(turn));
        when(turn.taskId()).thenReturn(taskId);
        when(turn.id()).thenReturn(turnId);
        when(turn.idempotencyKey()).thenReturn("original-user-request");
        when(turn.state()).thenReturn(TurnState.COMPLETED);
        when(turn.developmentResult()).thenReturn(Optional.of(new DevelopmentResultReport(true, Optional.empty())));
    }

    @Test
    void sendsOnlyAnObservedTurnToTheExistingExecutor()
    {
        assertThat(continuation.continueWork(assessment(false, Optional.of(conversationId)))).isEqualTo(AssistantContinuation.Outcome.STARTED);
        verify(executor).continueDevelopment(taskId, 7, turnId);
    }

    @Test
    void explicitResultQuestionsRuleInboxAndRemovedFollowsCannotStartWork()
    {
        assertThat(continuation.continueWork(assessment(true, Optional.of(conversationId)))).isEqualTo(AssistantContinuation.Outcome.NOT_ELIGIBLE);
        assertThat(continuation.continueWork(assessment(false, Optional.empty()))).isEqualTo(AssistantContinuation.Outcome.NOT_ELIGIBLE);
        when(follows.follow(conversationId, taskId.toString())).thenReturn(Optional.empty());
        assertThat(continuation.continueWork(assessment(false, Optional.of(conversationId)))).isEqualTo(AssistantContinuation.Outcome.NOT_ELIGIBLE);
        verifyNoInteractions(executor);
    }

    @Test
    void oldFollowContextOrHeldResultCannotStartWork()
    {
        when(follows.follow(conversationId, taskId.toString())).thenReturn(Optional.of(new AssistantFollow(conversationId, taskId.toString(), NOW, 0, "New request")));
        assertThat(continuation.continueWork(assessment(false, Optional.of(conversationId)))).isEqualTo(AssistantContinuation.Outcome.NOT_ELIGIBLE);
        when(follows.follow(conversationId, taskId.toString())).thenReturn(Optional.of(new AssistantFollow(conversationId, taskId.toString(), NOW, 0, "Original request")));
        when(turn.developmentResult()).thenReturn(Optional.of(new DevelopmentResultReport(false, Optional.of("A decision remains"))));
        assertThat(continuation.continueWork(assessment(false, Optional.of(conversationId)))).isEqualTo(AssistantContinuation.Outcome.NOT_ELIGIBLE);
        verifyNoInteractions(executor);
    }

    @Test
    void duplicateAssessmentReusesAcceptanceAndNeverStartsASecondAttempt()
    {
        when(turns.findByIdempotencyKey(taskId, AgentTurn.ASSISTANT_CONTINUATION_PREFIX + turnId)).thenReturn(Optional.of(turn));
        assertThat(continuation.continueWork(assessment(false, Optional.of(conversationId)))).isEqualTo(AssistantContinuation.Outcome.ALREADY_ACCEPTED);
        when(turn.idempotencyKey()).thenReturn(AgentTurn.ASSISTANT_CONTINUATION_PREFIX + TurnId.random());
        assertThat(continuation.continueWork(assessment(false, Optional.of(conversationId)))).isEqualTo(AssistantContinuation.Outcome.LIMIT_REACHED);
        verifyNoInteractions(executor);
    }

    @Test
    void newerWorkWinningAdmissionIsNotRetried()
    {
        when(executor.continueDevelopment(taskId, 7, turnId)).thenThrow(new AgentConflictException("New work owns this task"));
        assertThat(continuation.continueWork(assessment(false, Optional.of(conversationId)))).isEqualTo(AssistantContinuation.Outcome.NOT_ELIGIBLE);
        verify(executor).continueDevelopment(taskId, 7, turnId);
        verify(executor, never()).start(any());
    }

    private AssistantAssessment assessment(boolean requested, Optional<String> conversation)
    {
        AssistantReport report = new AssistantReport(AssistantReport.State.RESULT_READY, "result:" + turnId,
                AgendaTarget.of(AgendaTarget.Kind.TASK, "workspace-a", taskId.toString()), Optional.of(turnId.toString()), Optional.empty(),
                ImmutableList.of("task:" + taskId), ImmutableList.of(), Optional.empty());
        return new AssistantAssessment("assessment", conversation, taskId.toString(), "fingerprint", NOW, "Result ready", report, "Original request",
                true, requested, AssistantAssessment.State.QUEUED);
    }
}
