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
package com.bytequay.agent.cli.process;

import com.bytequay.agent.spi.ProviderApprovalScope;
import com.google.common.collect.ImmutableList;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Recognizes complete read-only operations; shell syntax and arbitrary flags never inherit a rule's authority. */
public final class ApprovalScopes
{
    private static final Pattern GITHUB_READ = Pattern.compile(
            "gh (?:pr (?:view|diff|checks)|issue view) [1-9][0-9]* --repo github\\.com/([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)(?: --(?:comments|json [A-Za-z][A-Za-z0-9,]*))?");

    private ApprovalScopes() {}

    public static Optional<ProviderApprovalScope> githubRead(String command)
    {
        Matcher match = GITHUB_READ.matcher(command);
        if (!match.matches()) {
            return Optional.empty();
        }
        return Optional.of(new ProviderApprovalScope(ProviderApprovalScope.Operation.GITHUB_READ, ImmutableList.of("api.github.com"), Optional.of(match.group(1))));
    }

    /** WebFetch supplies a URL to its native read operation, not a shell command. */
    public static Optional<ProviderApprovalScope> webFetch(String url)
    {
        try {
            URI uri = new URI(url);
            String scheme = Optional.ofNullable(uri.getScheme()).orElse("").toLowerCase(Locale.ROOT);
            String host = Optional.ofNullable(uri.getHost()).orElse("").toLowerCase(Locale.ROOT);
            boolean standardPort = uri.getPort() == -1 || scheme.equals("https") && uri.getPort() == 443 || scheme.equals("http") && uri.getPort() == 80;
            if (!(scheme.equals("https") || scheme.equals("http")) || uri.getUserInfo() != null || !standardPort || !ProviderApprovalScope.validHost(host)) {
                return Optional.empty();
            }
            return Optional.of(new ProviderApprovalScope(ProviderApprovalScope.Operation.NETWORK_ACCESS, ImmutableList.of(host), Optional.empty()));
        }
        catch (URISyntaxException failure) {
            return Optional.empty();
        }
    }
}
