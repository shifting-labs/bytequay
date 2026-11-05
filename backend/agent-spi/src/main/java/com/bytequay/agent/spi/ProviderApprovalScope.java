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
package com.bytequay.agent.spi;

import com.google.common.collect.ImmutableList;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import static com.bytequay.core.domain.Checks.checkOptionalText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** A provider-validated operation; displayed command text never grants this scope. */
public record ProviderApprovalScope(Operation operation, List<String> hosts, Optional<String> repository)
{
    private static final Pattern HOST = Pattern.compile("(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?");

    public ProviderApprovalScope
    {
        requireNonNull(operation, "operation is null");
        hosts = ImmutableList.copyOf(hosts);
        checkArgument(!hosts.isEmpty() && hosts.stream().allMatch(ProviderApprovalScope::validHost), "hosts must contain exact DNS names");
        checkOptionalText(repository, "repository");
        checkArgument(operation != Operation.GITHUB_READ || repository.isPresent(), "GitHub reads require a repository");
    }

    public static boolean validHost(String host)
    {
        return host != null && host.equals(host.toLowerCase(Locale.ROOT)) && HOST.matcher(host).matches();
    }

    public enum Operation
    {
        NETWORK_ACCESS("network-access"),
        GITHUB_READ("github-read");

        private final String key;

        Operation(String key)
        {
            this.key = key;
        }

        public String key()
        {
            return key;
        }

        public static Optional<Operation> fromKey(String key)
        {
            return Arrays.stream(values()).filter(operation -> operation.key.equals(key)).findFirst();
        }
    }
}
