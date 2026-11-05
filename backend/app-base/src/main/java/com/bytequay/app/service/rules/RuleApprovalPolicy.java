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
package com.bytequay.app.service.rules;

import com.bytequay.agent.core.AgentApprovalPolicy;
import com.bytequay.agent.core.ApprovalRequest;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.core.domain.RuleAuthorization;
import org.springframework.stereotype.Service;

import java.util.Optional;

import static com.bytequay.agent.spi.ProviderApprovalRequest.Decision.ALLOW_ONCE;
import static java.util.Objects.requireNonNull;

@Service
public final class RuleApprovalPolicy
        implements AgentApprovalPolicy
{
    private final AgentRuleService rules;
    private final AgentTaskStore tasks;

    public RuleApprovalPolicy(AgentRuleService rules, AgentTaskStore tasks)
    {
        this.rules = requireNonNull(rules, "rules is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
    }

    @Override
    public Optional<RuleAuthorization> authorize(ApprovalRequest request)
    {
        if (!request.providerRequest().availableDecisions().contains(ALLOW_ONCE)) {
            return Optional.empty();
        }
        return request.providerRequest().scope().flatMap(scope -> tasks.find(request.taskId())
                .filter(task -> !task.state().terminal())
                .flatMap(task -> rules.authorizePermission(scope, task.repository().toString())));
    }
}
