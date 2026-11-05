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

import com.bytequay.agent.cli.process.ApprovalScopes;
import com.bytequay.agent.spi.ProviderApprovalScope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import java.util.Optional;

/** Only a dedicated network request or a fully recognized GitHub read can be covered by a saved rule. */
final class CodexApprovalScopes
{
    private CodexApprovalScopes() {}

    static Optional<ProviderApprovalScope> command(CodexProtocol.CommandApprovalParams params, ObjectMapper mapper)
    {
        if (params.additionalPermissions().isPresent()) {
            return Optional.empty();
        }
        if (params.networkApprovalContext().isEmpty()) {
            return params.command().flatMap(ApprovalScopes::githubRead);
        }
        try {
            NetworkRequest network = mapper.treeToValue(params.networkApprovalContext().orElseThrow(), NetworkRequest.class);
            if (network == null || !ProviderApprovalScope.validHost(network.host())
                    || !("https".equals(network.protocol()) || "http".equals(network.protocol()))) {
                return Optional.empty();
            }
            return Optional.of(new ProviderApprovalScope(ProviderApprovalScope.Operation.NETWORK_ACCESS, ImmutableList.of(network.host()), Optional.empty()));
        }
        catch (JsonProcessingException | IllegalArgumentException failure) {
            return Optional.empty();
        }
    }

    private record NetworkRequest(String host, String protocol) {}
}
