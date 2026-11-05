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
package com.bytequay.agent.cli.codex;

import com.bytequay.agent.spi.ProviderApprovalScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestCodexApprovalScopes
{
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void aDedicatedNetworkRequestHasAnExactHostButExtraPermissionsStayManual()
            throws Exception
    {
        String network = "\"networkApprovalContext\":{\"host\":\"api.github.com\",\"protocol\":\"https\"}";
        assertThat(CodexApprovalScopes.command(params(network), mapper)).hasValueSatisfying(scope -> {
            assertThat(scope.operation()).isEqualTo(ProviderApprovalScope.Operation.NETWORK_ACCESS);
            assertThat(scope.hosts()).containsExactly("api.github.com");
        });
        assertThat(CodexApprovalScopes.command(params(network + ",\"additionalPermissions\":{\"fileSystem\":{}}"), mapper)).isEmpty();
        assertThat(CodexApprovalScopes.command(params("\"additionalPermissions\":{\"network\":{\"enabled\":true}}"), mapper)).isEmpty();
        assertThat(CodexApprovalScopes.command(params(network.replace("api.github.com", "*.github.com")), mapper)).isEmpty();
        assertThat(CodexApprovalScopes.command(params(network.replace("https", "tcp")), mapper)).isEmpty();
        assertThat(CodexApprovalScopes.command(params("\"reason\":\"Please allow GitHub network access\""), mapper)).isEmpty();
    }

    private CodexProtocol.CommandApprovalParams params(String extra)
            throws Exception
    {
        return mapper.readValue("""
                {"command":"curl https://api.github.com", "itemId":"item", "startedAtMs":1, "threadId":"thread", "turnId":"turn", %s}
                """.formatted(extra), CodexProtocol.CommandApprovalParams.class);
    }
}
