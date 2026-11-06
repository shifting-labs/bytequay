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
import com.bytequay.agent.spi.TaskPublicationBridge;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Offers push and close to the active user turn of an open, attended development task, as pull request replacement is offered. */
@Component
public class TaskPublicationBridgeResolver
        implements BridgeResolver<TaskPublicationBridge>
{
    private final ObjectProvider<TaskPublicationService> publications;
    private final AgentTurnStore turns;

    public TaskPublicationBridgeResolver(ObjectProvider<TaskPublicationService> publications, AgentTurnStore turns)
    {
        this.publications = requireNonNull(publications, "publications is null");
        this.turns = requireNonNull(turns, "turns is null");
    }

    @Override
    public Optional<TaskPublicationBridge> resolve(String taskId)
    {
        TaskId id = TaskId.valueOf(taskId);
        return publications.getObject().offered(id)
                .flatMap(task -> turns.findActive(id))
                .filter(turn -> turn.developmentOrigin().isEmpty() && turn.cancellationRequestedAt().isEmpty())
                .map(turn -> new TaskPublicationBridge(said -> publications.getObject().push(id, turn.id(), said),
                        said -> publications.getObject().close(id, turn.id(), said)));
    }
}
