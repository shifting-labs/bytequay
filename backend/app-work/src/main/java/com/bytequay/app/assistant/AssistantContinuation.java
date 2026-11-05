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
import com.bytequay.agent.core.DevelopmentTurnOrigin;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.spi.DevelopmentResultReport;
import com.bytequay.core.domain.AssistantAssessment;
import com.bytequay.core.domain.AssistantReport;
import com.bytequay.core.store.AssistantFollowStore;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** The reader may recommend routine completion; only the existing task executor can admit it under accepted authority. */
@Component
class AssistantContinuation
{
    enum Outcome { STARTED, ALREADY_ACCEPTED, NOT_ELIGIBLE, LIMIT_REACHED }

    private final AssistantFollowStore follows;
    private final AgentTaskStore tasks;
    private final AgentTurnStore turns;
    private final AgentTurnService executor;

    AssistantContinuation(AssistantFollowStore follows, AgentTaskStore tasks, AgentTurnStore turns, AgentTurnService executor)
    {
        this.follows = requireNonNull(follows, "follows is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.executor = requireNonNull(executor, "executor is null");
    }

    Outcome continueWork(AssistantAssessment assessment)
    {
        if (assessment.requested() || !assessment.interpret() || assessment.state() != AssistantAssessment.State.QUEUED
                || assessment.report().state() != AssistantReport.State.RESULT_READY || assessment.conversationId().isEmpty()
                || assessment.report().sourceTurnId().isEmpty()
                || follows.follow(assessment.conversationId().orElseThrow(), assessment.taskId())
                        .filter(follow -> follow.ruleRun().isEmpty() && follow.context().equals(assessment.context())).isEmpty()) {
            return Outcome.NOT_ELIGIBLE;
        }
        TaskId taskId = TaskId.valueOf(assessment.taskId());
        Optional<AgentTask> task = tasks.find(taskId).filter(value -> value.interactive() && value.state() == TaskState.OPEN
                && value.archivedAt().isEmpty() && !value.developmentContinuationUnfinished() && !value.developmentDeliveryPending());
        Optional<AgentTurn> observed = turns.find(TurnId.valueOf(assessment.report().sourceTurnId().orElseThrow())).filter(turn -> turn.taskId().equals(taskId));
        if (task.isEmpty() || observed.isEmpty()) {
            return Outcome.NOT_ELIGIBLE;
        }
        AgentTurn last = observed.orElseThrow();
        AgentTurn source = last.developmentOrigin().map(DevelopmentTurnOrigin::parentUserTurnId).flatMap(turns::find).orElse(last);
        if (source.idempotencyKey().startsWith(AgentTurn.ASSISTANT_CONTINUATION_PREFIX)) {
            return Outcome.LIMIT_REACHED;
        }
        if (turns.findByIdempotencyKey(taskId, AgentTurn.ASSISTANT_CONTINUATION_PREFIX + source.id()).isPresent()) {
            return Outcome.ALREADY_ACCEPTED;
        }
        if (source.state() != TurnState.COMPLETED || source.cancellationRequestedAt().isPresent()
                || source.developmentResult().filter(DevelopmentResultReport::readyForDelivery).isEmpty()
                || last.state() != TurnState.COMPLETED || last.cancellationRequestedAt().isPresent()
                || last.developmentResult().filter(DevelopmentResultReport::readyForDelivery).isEmpty()) {
            return Outcome.NOT_ELIGIBLE;
        }
        try {
            executor.continueDevelopment(taskId, task.orElseThrow().version(), last.id());
            return Outcome.STARTED;
        }
        catch (AgentConflictException | IllegalArgumentException ignored) {
            // A newer request, stop or held action wins even when the assessment finished just before it.
            return Outcome.NOT_ELIGIBLE;
        }
    }
}
