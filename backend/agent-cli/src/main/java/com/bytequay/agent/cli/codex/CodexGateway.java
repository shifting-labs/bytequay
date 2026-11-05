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

import java.util.Optional;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/** Gateway credentials are resolved at launch and never written into Codex configuration. */
public record CodexGateway(String baseUrl, Supplier<Optional<String>> token)
{
    static final String TOKEN_VARIABLE = "BYTEQUAY_CODEX_GATEWAY_TOKEN";

    public CodexGateway
    {
        requireNonNull(baseUrl, "baseUrl is null");
        requireNonNull(token, "token is null");
    }

    @Override
    public String toString()
    {
        return "CodexGateway[" + baseUrl + "]";
    }
}
