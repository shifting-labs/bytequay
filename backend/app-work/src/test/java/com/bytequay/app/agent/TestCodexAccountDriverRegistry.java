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

import com.bytequay.agent.cli.codex.CodexCliDriver;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.bytequay.app.service.settings.CodexAccount;
import com.bytequay.app.service.settings.CodexAccounts;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;

class TestCodexAccountDriverRegistry
{
    @Test
    void rechecksAccountExistenceAndScopeForCachedDrivers()
    {
        CodexAccounts accounts = new CodexAccounts(new InMemoryAppSettingsStore(), new ObjectMapper());
        CodexAccount account = accounts.addGateway("Work", "https://llm.example.com/v1", Optional.empty());
        AgentDriverRegistry cli = mock(AgentDriverRegistry.class);
        CodexCliDriver driver = mock(CodexCliDriver.class, withSettings().mockMaker(MockMakers.INLINE));
        CodexAccountDriverRegistry registry = new CodexAccountDriverRegistry(cli, accounts, true, ignored -> driver);
        assertThat(registry.require(account.laneId())).isSameAs(driver);
        accounts.restrictToWorkspace(account.laneId().value(), "repository");
        registry.checkWorkspaceAccess(account.laneId(), Optional.of("repository"));
        assertThatThrownBy(() -> registry.checkWorkspaceAccess(account.laneId(), Optional.of("other"))).isInstanceOf(AgentUnavailableException.class);
        assertThatThrownBy(() -> registry.checkWorkspaceAccess(account.laneId(), Optional.empty())).isInstanceOf(AgentUnavailableException.class);
        registry.checkWorkspaceAccess(AgentLaneId.CLAUDE_CLI, Optional.empty());
        verify(cli).checkWorkspaceAccess(AgentLaneId.CLAUDE_CLI, Optional.empty());
        assertThatThrownBy(() -> new CodexAccountDriverRegistry(cli, accounts, false, ignored -> driver).require(account.laneId()))
                .isInstanceOf(AgentUnavailableException.class);
        accounts.remove(account.id());
        assertThatThrownBy(() -> registry.require(account.laneId())).isInstanceOf(AgentUnavailableException.class);
        registry.require(AgentLaneId.CODEX_CLI);
        verify(cli).require(AgentLaneId.CODEX_CLI);
    }
}
