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
package com.bytequay.app.service.settings;

import com.bytequay.agent.spi.AgentLaneId;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.UUID;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** A named Codex browser login or gateway; Codex owns browser credentials, while gateway tokens are encrypted by ByteQuay. */
public record CodexAccount(String id, String label, Optional<String> email, Optional<String> gatewayUrl, Optional<String> workspaceId)
{
    public CodexAccount
    {
        checkArgument(UUID.fromString(requireNonNull(id, "id is null")).toString().equals(id), "invalid Codex account ID");
        label = requireNonNull(label, "label is null").trim();
        checkArgument(!label.isEmpty() && label.length() <= 80 && label.chars().noneMatch(Character::isISOControl),
                "Codex account names must contain 1 to 80 characters without control characters");
        email = requireNonNull(email, "email is null").map(String::trim);
        email.ifPresent(value -> checkArgument(value.length() <= 254 && value.matches("[^\\s@]+@[^\\s@]+") && value.chars().noneMatch(Character::isISOControl),
                "Enter a valid Codex account email"));
        gatewayUrl = requireNonNull(gatewayUrl, "gatewayUrl is null").map(String::trim);
        gatewayUrl.ifPresent(value -> checkArgument(isGatewayUrl(value), "Enter the gateway's http or https base URL without credentials, query, or fragment"));
        checkArgument(email.isPresent() != gatewayUrl.isPresent(), "A Codex account needs either an email or a gateway URL");
        requireNonNull(workspaceId, "workspaceId is null").ifPresent(value -> checkArgument(!value.isBlank(), "workspaceId is blank"));
    }

    public boolean availableIn(Optional<String> workspace)
    {
        return workspaceId.isEmpty() || workspaceId.equals(workspace);
    }

    public AgentLaneId laneId()
    {
        return new AgentLaneId(AgentLaneId.CODEX_ACCOUNT_PREFIX + id);
    }

    private static boolean isGatewayUrl(String value)
    {
        try {
            URI uri = new URI(value);
            boolean web = "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
            // A user-info part would show its password wherever the URL is displayed.
            return value.length() <= 2048 && web && uri.getHost() != null && uri.getUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null;
        }
        catch (URISyntaxException invalid) {
            return false;
        }
    }
}
