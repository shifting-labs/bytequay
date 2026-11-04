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

import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.spi.BridgeResolver;
import com.bytequay.agent.spi.TaskPullRequestReplacementBridge;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Component
public class TaskPullRequestReplacementBridgeResolver
        implements BridgeResolver<TaskPullRequestReplacementBridge>
{
    private final ObjectProvider<TaskPullRequestReplacementService> replacements;
    private final AgentTurnStore turns;

    public TaskPullRequestReplacementBridgeResolver(ObjectProvider<TaskPullRequestReplacementService> replacements, AgentTurnStore turns)
    {
        this.replacements = requireNonNull(replacements, "replacements is null");
        this.turns = requireNonNull(turns, "turns is null");
    }

    @Override
    public Optional<TaskPullRequestReplacementBridge> resolve(String taskId)
    {
        TaskId id = TaskId.valueOf(taskId);
        return replacements.getObject().eligible(id)
                .filter(task -> task.laneId().usesClaude() || task.sessionReference().stream().noneMatch(reference -> reference.contractVersion() == 1))
                .flatMap(task -> turns.findActive(id))
                .filter(turn -> turn.developmentOrigin().isEmpty() && turn.cancellationRequestedAt().isEmpty())
                .map(turn -> new TaskPullRequestReplacementBridge(branch -> replacements.getObject().prepare(id, turn.id(), branch)));
    }
}
