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

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.ApprovalId;
import com.bytequay.agent.core.ApprovalRequest;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.spi.ProviderApprovalRequest;
import com.bytequay.agent.spi.ProviderApprovalScope;
import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.bytequay.agent.spi.ProviderAgentEventSource.root;
import static com.bytequay.agent.spi.ProviderApprovalRequest.Decision.ALLOW_ONCE;
import static com.bytequay.agent.spi.ProviderApprovalRequest.Decision.DENY;
import static com.bytequay.app.service.rules.TestRuleText.NETWORK_PERMISSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestRuleApprovalPolicy
{
    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final AgentTask task = mock(AgentTask.class, withSettings().mockMaker(MockMakers.INLINE));
    private final AgentRuleService rules = new AgentRuleService(new InMemoryAppSettingsStore(), new InMemoryRuleRunStore(),
            new ObjectMapper().findAndRegisterModules(), Clock.systemUTC());
    private final RuleApprovalPolicy policy = new RuleApprovalPolicy(rules, tasks);

    @Test
    void usesTheLiveTaskRepositoryAndWithdrawsAuthorityWhenPaused()
    {
        AgentRuleView draft = rules.add(NETWORK_PERMISSION).getFirst();
        rules.activate(draft.id(), draft.version());
        ProviderApprovalScope scope = new ProviderApprovalScope(ProviderApprovalScope.Operation.NETWORK_ACCESS,
                ImmutableList.of("api.github.com"), Optional.empty());
        ApprovalRequest request = request(Optional.of(scope), ImmutableList.of(ALLOW_ONCE, DENY));
        when(tasks.find(request.taskId())).thenReturn(Optional.of(task));
        when(task.state()).thenReturn(TaskState.OPEN);
        when(task.repository()).thenReturn(new RepositoryIdentity("acme", "warehouse"));
        assertThat(policy.authorize(request)).isPresent();

        when(task.repository()).thenReturn(new RepositoryIdentity("acme", "other"));
        assertThat(policy.authorize(request)).isEmpty();
        when(task.repository()).thenReturn(new RepositoryIdentity("acme", "warehouse"));
        rules.pause(draft.id(), true);
        assertThat(policy.authorize(request)).isEmpty();
    }

    @Test
    void githubReadMatchesTheExplicitTargetInsteadOfTheTasksRepository()
    {
        AgentRuleView draft = rules.add(NETWORK_PERMISSION.replace("network-access", "github-read")).getFirst();
        rules.activate(draft.id(), draft.version());
        ProviderApprovalScope scope = new ProviderApprovalScope(ProviderApprovalScope.Operation.GITHUB_READ,
                ImmutableList.of("api.github.com"), Optional.of("acme/warehouse"));
        ApprovalRequest request = request(Optional.of(scope), ImmutableList.of(ALLOW_ONCE, DENY));
        when(tasks.find(request.taskId())).thenReturn(Optional.of(task));
        when(task.state()).thenReturn(TaskState.OPEN);
        when(task.repository()).thenReturn(new RepositoryIdentity("acme", "other"));
        assertThat(policy.authorize(request)).isPresent();

        when(task.state()).thenReturn(TaskState.COMPLETED);
        assertThat(policy.authorize(request)).isEmpty();
        when(tasks.find(request.taskId())).thenReturn(Optional.empty());
        assertThat(policy.authorize(request)).isEmpty();
    }

    @Test
    void neverInfersPermissionFromDisplayTextOrARequestThatCannotBeAllowed()
    {
        AgentRuleView draft = rules.add(NETWORK_PERMISSION).getFirst();
        rules.activate(draft.id(), draft.version());
        assertThat(policy.authorize(request(Optional.empty(), ImmutableList.of(ALLOW_ONCE, DENY)))).isEmpty();
        ProviderApprovalScope scope = new ProviderApprovalScope(ProviderApprovalScope.Operation.NETWORK_ACCESS,
                ImmutableList.of("api.github.com"), Optional.empty());
        assertThat(policy.authorize(request(Optional.of(scope), ImmutableList.of(DENY)))).isEmpty();
    }

    private static ApprovalRequest request(Optional<ProviderApprovalScope> scope, List<ProviderApprovalRequest.Decision> decisions)
    {
        Instant now = Instant.now();
        return new ApprovalRequest(ApprovalId.random(), TaskId.random(), TurnId.random(), new ProviderApprovalRequest(
                ProviderApprovalRequest.Kind.COMMAND, "Access api.github.com", "network-access for acme/warehouse", ImmutableList.of("api.github.com"), decisions,
                Optional.empty(), false, root(), now, scope), now);
    }
}
