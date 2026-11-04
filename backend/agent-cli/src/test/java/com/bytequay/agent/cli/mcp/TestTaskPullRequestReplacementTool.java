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
package com.bytequay.agent.cli.mcp;

import com.bytequay.agent.cli.process.SecretRedactor;
import com.bytequay.agent.spi.AgentApprovalHandler;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.ProviderApprovalDecision;
import com.bytequay.agent.spi.ProviderApprovalRequest;
import com.bytequay.agent.spi.ReviewBridge;
import com.bytequay.agent.spi.SessionAccess;
import com.bytequay.agent.spi.TaskPullRequestReplacementBridge;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.bytequay.agent.spi.ProviderApprovalRequest.Decision.ALLOW_ONCE;
import static com.bytequay.agent.spi.ProviderApprovalRequest.Decision.DENY;
import static com.bytequay.agent.spi.TaskPullRequestReplacementBridge.TOOL_NAME;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class TestTaskPullRequestReplacementTool
{
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger prepared = new AtomicInteger();
    private final AtomicInteger writes = new AtomicInteger();
    private final SecretRedactor redactor = SecretRedactor.fromEnvironment(ImmutableMap.of("OPENAI_API_KEY", "known-secret"));
    private final TaskPullRequestReplacementBridge replacement = new TaskPullRequestReplacementBridge(branch -> {
        prepared.incrementAndGet();
        return new TaskPullRequestReplacementBridge.Prepared("Replace PR #7 at revision abc with branch " + branch + " known-secret", () -> {
            writes.incrementAndGet();
            return "Replaced PR #7 with PR #8 known-secret";
        });
    });

    @TempDir
    private Path directory;

    @Test
    void waitsForExactOneTimeApprovalBeforeWritingAndKeepsHttpCallersReadOnly()
            throws Exception
    {
        PermissionBroker broker = broker();
        CompletableFuture<ProviderApprovalRequest> asked = new CompletableFuture<>();
        CompletableFuture<ProviderApprovalDecision> decision = new CompletableFuture<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
                var registration = register(broker, (request, timeout) -> {
                    asked.complete(request);
                    return decision;
                })) {
            var guard = broker.guardCodexRootTools(registration);
            assertThat(guard.mcpNames()).doesNotContain(TOOL_NAME, PermissionBroker.CALL_TOOL);
            assertThat(registration.instruction()).hasValueSatisfying(value -> assertThat(value).contains("in-app Allow once / Deny", "Do not ask the user to run"));
            assertThat(httpCall(broker, registration, TOOL_NAME, "{\"branch\":\"fix-value\"}").at("/error/code").asInt()).isEqualTo(-32602);
            assertThat(prepared).hasValue(0);

            var result = executor.submit(() -> call(broker, registration, "{\"branch\":\"fix-value\"}"));
            ProviderApprovalRequest request = asked.get(10, TimeUnit.SECONDS);
            assertThat(request.title()).isEqualTo("Replace task pull request");
            assertThat(request.detail()).isEqualTo("Replace PR #7 at revision abc with branch fix-value [REDACTED]");
            assertThat(request.availableDecisions()).containsExactly(ALLOW_ONCE, DENY);
            assertThat(request.taskScope()).isEmpty();
            assertThat(request.sandboxed()).isFalse();
            assertThat(writes).hasValue(0);
            assertThat(result.isDone()).isFalse();
            decision.complete(ProviderApprovalDecision.allow(Optional.empty()));
            assertThat(result.get(10, TimeUnit.SECONDS).at("/result/content/0/text").asText()).isEqualTo("Replaced PR #7 with PR #8 [REDACTED]");
            assertThat(writes).hasValue(1);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void refusalOrExpiryNeverExecutesReplacement(boolean expired)
            throws Exception
    {
        PermissionBroker broker = broker();
        try (var registration = register(broker, (request, timeout) -> CompletableFuture.completedFuture(
                expired ? ProviderApprovalDecision.expired() : ProviderApprovalDecision.deny()))) {
            assertThat(call(broker, registration, "{\"branch\":\"fix-value\"}").at("/result/content/0/text").asText())
                    .contains(expired ? "expired" : "not approved", "No replacement was performed", "Do not retry");
            assertThat(writes).hasValue(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "{\"branch\":false}", "{\"branch\":\" \"}", "{\"branch\":\"fix-value\",\"authorized\":true}"})
    void invalidInputCannotPrepareOrApproveAnOperation(String arguments)
            throws Exception
    {
        PermissionBroker broker = broker();
        try (var registration = register(broker, (request, timeout) -> {
            throw new AssertionError("Invalid input must not ask for approval");
        })) {
            assertThat(call(broker, registration, arguments).at("/error/code").asInt()).isEqualTo(-32602);
            assertThat(prepared).hasValue(0);
            assertThat(writes).hasValue(0);
        }
    }

    @Test
    void restrictedReviewAndUnattendedSessionsDoNotReceiveReplacement()
    {
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/mcp"), json, (task, query) -> "")
                .pullRequestReplacements(task -> {
                    throw new AssertionError("A restricted session must not resolve the replacement capability");
                })
                .reviews(task -> task.equals("review") ? Optional.of(new ReviewBridge(ImmutableList.of(), "Review privately")) : Optional.empty()).build();
        for (SessionAccess access : SessionAccess.values()) {
            if (access != SessionAccess.FULL) {
                assertThat(broker.registerTools("task", directory, AgentPermissionPolicy.AUTO, denying(), redactor, Optional.empty(), access, Optional.empty(), ImmutableList.of()))
                        .isEmpty();
            }
        }
        for (AgentPermissionPolicy policy : ImmutableList.of(AgentPermissionPolicy.CI_AUTOFIX, AgentPermissionPolicy.CHERRY_PICK)) {
            var registration = broker.registerTools("task", directory, policy, denying(), redactor, Optional.empty(), SessionAccess.FULL, Optional.empty(), ImmutableList.of());
            registration.ifPresent(value -> {
                assertThat(value.toolNames()).doesNotContain(TOOL_NAME);
                value.close();
            });
        }
        try (var registration = broker.registerTools("review", directory, AgentPermissionPolicy.AUTO, denying(), redactor,
                Optional.empty(), SessionAccess.FULL, Optional.empty(), ImmutableList.of()).orElseThrow()) {
            assertThat(registration.toolNames()).doesNotContain(TOOL_NAME);
        }
    }

    @Test
    void claudeHookKeepsDirectAndDispatchedReplacementCallsWithRoot()
            throws Exception
    {
        PermissionBroker broker = broker();
        try (var registration = broker.register("task", directory, directory, directory.resolve("provider"), AgentPermissionPolicy.AUTO,
                SessionAccess.FULL, denying(), redactor, Optional.empty(), ImmutableMap.of(), Optional.empty(), ImmutableList.of())) {
            String hook = json.readTree(Files.readString(registration.settingsFile().orElseThrow())).at("/hooks/PreToolUse/0/hooks/0/command").asText();
            assertThat(gate(hook, "{\"tool_name\":\"mcp__bytequay__replace_task_pull_request\",\"tool_input\":{}}")).isZero();
            assertThat(gate(hook, "{\"agent_id\":\"child\",\"tool_name\":\"mcp__bytequay__replace_task_pull_request\",\"tool_input\":{}}")).isEqualTo(2);
            assertThat(gate(hook, """
                    {"agent_id":"child","tool_name":"mcp__bytequay__bytequay_call_tool","tool_input":{"name":"replace_task_pull_request"}}
                    """)).isEqualTo(2);
            assertThat(gate(hook, "{\"tool_name\":\"Agent\",\"tool_input\":{\"subagent_type\":\"general-purpose\"}}")).isZero();
            assertThat(httpCall(broker, registration, TOOL_NAME, "{\"branch\":\"fix-value\"}").at("/result/content/0/text").asText()).contains("not approved");
        }
    }

    private PermissionBroker broker()
    {
        return PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/mcp"), json, (task, query) -> "")
                .pullRequestReplacements(task -> Optional.of(replacement)).build();
    }

    private McpRegistration register(PermissionBroker broker, AgentApprovalHandler approvals)
    {
        return broker.registerTools("task", directory, AgentPermissionPolicy.AUTO, approvals, redactor, Optional.empty(),
                SessionAccess.FULL, Optional.empty(), ImmutableList.of()).orElseThrow();
    }

    private static AgentApprovalHandler denying()
    {
        return (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny());
    }

    private JsonNode call(PermissionBroker broker, McpRegistration registration, String arguments)
            throws Exception
    {
        return broker.callCodexRootTool(registration.token(), PermissionBroker.CALL_TOOL,
                json.valueToTree(ImmutableMap.of("name", TOOL_NAME, "arguments", json.readTree(arguments)))).body().orElseThrow();
    }

    private JsonNode httpCall(PermissionBroker broker, McpRegistration registration, String name, String arguments)
            throws Exception
    {
        return broker.handle(registration.token(), json.valueToTree(ImmutableMap.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                "params", ImmutableMap.of("name", name, "arguments", json.readTree(arguments))))).body().orElseThrow();
    }

    private static int gate(String command, String payload)
            throws Exception
    {
        Process process = new ProcessBuilder("/bin/sh", "-c", command).redirectErrorStream(true).start();
        try (var stdin = process.getOutputStream()) {
            stdin.write(payload.getBytes(UTF_8));
        }
        process.getInputStream().readAllBytes();
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        return process.exitValue();
    }
}
