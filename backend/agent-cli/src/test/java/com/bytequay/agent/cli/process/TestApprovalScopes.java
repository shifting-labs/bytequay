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
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestApprovalScopes
{
    @Test
    void recognizesOnlyCompleteGithubReadsWithAnExplicitHostAndRepository()
    {
        assertThat(ApprovalScopes.githubRead("gh pr view 42 --repo github.com/acme/warehouse --json title,body")).hasValueSatisfying(scope -> {
            assertThat(scope.operation()).isEqualTo(ProviderApprovalScope.Operation.GITHUB_READ);
            assertThat(scope.hosts()).containsExactly("api.github.com");
            assertThat(scope.repository()).contains("acme/warehouse");
        });
        assertThat(ApprovalScopes.githubRead("gh issue view 42 --repo github.com/acme/warehouse --comments")).isPresent();
        for (String command : ImmutableList.of(
                "gh pr merge 42 --repo github.com/acme/warehouse",
                "gh pr view 42 --repo acme/warehouse",
                "gh pr view 42 --repo github.com/acme/warehouse; touch /tmp/result",
                "gh pr view 42 --repo github.com/acme/warehouse --template {{exec}}",
                "gh pr view 42 --repo github.com/acme/warehouse > output",
                "gh pr view 42 --repo github.com/acme/warehouse $(whoami)",
                "echo gh pr view 42 --repo github.com/acme/warehouse",
                "gh pr view 42 --repo github.com/acme/warehouse\ngh pr merge 42")) {
            assertThat(ApprovalScopes.githubRead(command)).as(command).isEmpty();
        }
    }

    @Test
    void nativeFetchScopeUsesOnlyTheActualUrlHost()
    {
        assertThat(ApprovalScopes.webFetch("https://api.github.com/repos/acme/warehouse")).hasValueSatisfying(scope -> {
            assertThat(scope.operation()).isEqualTo(ProviderApprovalScope.Operation.NETWORK_ACCESS);
            assertThat(scope.hosts()).containsExactly("api.github.com");
        });
        assertThat(ApprovalScopes.webFetch("https://github.com.attacker.example/page")).hasValueSatisfying(scope ->
                assertThat(scope.hosts()).containsExactly("github.com.attacker.example"));
        for (String url : ImmutableList.of("file:///tmp/private", "https://api.github.com@attacker.example/", "https://api.github.com:8443/",
                "https://*.github.com/", "https://127.0.0.1/", "not a URL")) {
            assertThat(ApprovalScopes.webFetch(url)).as(url).isEmpty();
        }
    }
}
