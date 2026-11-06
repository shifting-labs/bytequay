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
import com.bytequay.agent.spi.TaskPublicationBridge;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.bytequay.agent.spi.ProviderApprovalRequest.Decision.ALLOW_ONCE;
import static com.bytequay.agent.spi.ProviderApprovalRequest.Decision.DENY;
import static com.bytequay.agent.spi.TaskPublicationBridge.CLOSE_TOOL_NAME;
import static com.bytequay.agent.spi.TaskPublicationBridge.PUSH_TOOL_NAME;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class TestTaskPublicationTool
{
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger writes = new AtomicInteger();
    private final List<Optional<String>> quoted = new CopyOnWriteArrayList<>();
    private final SecretRedactor redactor = SecretRedactor.fromEnvironment(ImmutableMap.of("OPENAI_API_KEY", "known-secret"));
    private final TaskPublicationBridge publication = new TaskPublicationBridge(
            said -> prepared("Push abc to owner/repo:fix-value known-secret", "Pushed abc known-secret", said),
            said -> prepared("Close owner/repo#7", "Closed PR #7", said));

    @TempDir
    private Path directory;

    @Test
    void theUsersOwnWordsActWithoutACard()
            throws Exception
    {
        PermissionBroker broker = broker();
        try (var registration = register(broker, (request, timeout) -> {
            throw new AssertionError("The user's own words must not ask again");
        })) {
            assertThat(call(broker, registration, PUSH_TOOL_NAME, "{\"words\":\"push it\"}").at("/result/content/0/text").asText()).isEqualTo("Pushed abc [REDACTED]");
            assertThat(call(broker, registration, CLOSE_TOOL_NAME, "{\"words\":\"close it\"}").at("/result/content/0/text").asText()).isEqualTo("Closed PR #7");
            assertThat(quoted).containsExactly(Optional.of("push it"), Optional.of("close it"));
            assertThat(writes).hasValue(2);
        }
    }

    @Test
    void otherwiseWaitsForOneTimeApprovalBeforeWriting()
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
            var result = executor.submit(() -> call(broker, registration, PUSH_TOOL_NAME, "{\"words\":\"looks good\"}"));
            ProviderApprovalRequest request = asked.get(10, TimeUnit.SECONDS);
            assertThat(request.title()).isEqualTo("Push task branch");
            assertThat(request.detail()).isEqualTo("Push abc to owner/repo:fix-value [REDACTED]");
            assertThat(request.availableDecisions()).containsExactly(ALLOW_ONCE, DENY);
            assertThat(request.sandboxed()).isFalse();
            assertThat(writes).hasValue(0);
            assertThat(result.isDone()).isFalse();
            decision.complete(ProviderApprovalDecision.allow(Optional.empty()));
            assertThat(result.get(10, TimeUnit.SECONDS).at("/result/content/0/text").asText()).isEqualTo("Pushed abc [REDACTED]");
            assertThat(writes).hasValue(1);
        }
    }

    @ParameterizedTest
    @CsvSource({"push_task_branch,false,pushed", "push_task_branch,true,pushed", "close_task_pull_request,false,closed", "close_task_pull_request,true,closed"})
    void refusalOrExpiryDoesNothing(String tool, boolean expired, String verb)
            throws Exception
    {
        PermissionBroker broker = broker();
        try (var registration = register(broker, (request, timeout) -> CompletableFuture.completedFuture(
                expired ? ProviderApprovalDecision.expired() : ProviderApprovalDecision.deny()))) {
            assertThat(call(broker, registration, tool, "{}").at("/result/content/0/text").asText())
                    .contains(expired ? "expired" : "Not approved", "Nothing was " + verb, "Do not retry");
            assertThat(writes).hasValue(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "{\"words\":\" \"}", "{\"words\":false}", "{\"words\":\"push it\",\"force\":true}", "{\"branch\":\"main\"}"})
    void invalidInputPreparesNothing(String arguments)
            throws Exception
    {
        PermissionBroker broker = broker();
        try (var registration = register(broker, (request, timeout) -> {
            throw new AssertionError("Invalid input must not ask for approval");
        })) {
            for (String tool : ImmutableList.of(PUSH_TOOL_NAME, CLOSE_TOOL_NAME)) {
                assertThat(call(broker, registration, tool, arguments).at("/error/code").asInt()).isEqualTo(-32602);
            }
            assertThat(quoted).isEmpty();
            assertThat(writes).hasValue(0);
        }
    }

    @Test
    void onlyTheRootAgentReachesThem()
            throws Exception
    {
        PermissionBroker broker = broker();
        try (var registration = register(broker, denying())) {
            assertThat(registration.instruction()).hasValueSatisfying(value -> assertThat(value).contains(PUSH_TOOL_NAME, CLOSE_TOOL_NAME,
                    "never ask the user to run a command by hand"));
            assertThat(broker.guardCodexRootTools(registration).mcpNames()).doesNotContain(PUSH_TOOL_NAME, CLOSE_TOOL_NAME);
            assertThat(httpCall(broker, registration, PUSH_TOOL_NAME, "{}").at("/error/code").asInt()).isEqualTo(-32602);
        }
        try (var registration = broker.register("task", directory, directory, directory.resolve("provider"), AgentPermissionPolicy.AUTO,
                SessionAccess.FULL, denying(), redactor, Optional.empty(), ImmutableMap.of(), Optional.empty(), ImmutableList.of())) {
            String hook = json.readTree(Files.readString(registration.settingsFile().orElseThrow())).at("/hooks/PreToolUse/0/hooks/0/command").asText();
            for (String tool : ImmutableList.of(PUSH_TOOL_NAME, CLOSE_TOOL_NAME)) {
                assertThat(gate(hook, "{\"tool_name\":\"mcp__bytequay__" + tool + "\",\"tool_input\":{}}")).isZero();
                assertThat(gate(hook, "{\"agent_id\":\"child\",\"tool_name\":\"mcp__bytequay__" + tool + "\",\"tool_input\":{}}")).isEqualTo(2);
                assertThat(gate(hook, "{\"agent_id\":\"child\",\"tool_name\":\"mcp__bytequay__bytequay_call_tool\",\"tool_input\":{\"name\":\"" + tool + "\"}}"))
                        .isEqualTo(2);
            }
        }
    }

    @Test
    void restrictedReviewAndUnattendedSessionsDoNotReceiveThem()
    {
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/mcp"), json, (task, query) -> "")
                .taskPublications(task -> {
                    throw new AssertionError("A restricted session must not resolve push or close");
                })
                .reviews(task -> task.equals("review") ? Optional.of(new ReviewBridge(ImmutableList.of(), "Review privately")) : Optional.empty()).build();
        for (SessionAccess access : SessionAccess.values()) {
            if (access != SessionAccess.FULL) {
                assertThat(broker.registerTools("task", directory, AgentPermissionPolicy.AUTO, denying(), redactor, Optional.empty(), access, Optional.empty(), ImmutableList.of()))
                        .isEmpty();
            }
        }
        for (AgentPermissionPolicy policy : ImmutableList.of(AgentPermissionPolicy.CI_AUTOFIX, AgentPermissionPolicy.CHERRY_PICK)) {
            broker.registerTools("task", directory, policy, denying(), redactor, Optional.empty(), SessionAccess.FULL, Optional.empty(), ImmutableList.of())
                    .ifPresent(value -> {
                        assertThat(value.toolNames()).doesNotContain(PUSH_TOOL_NAME, CLOSE_TOOL_NAME);
                        value.close();
                    });
        }
        try (var registration = broker.registerTools("review", directory, AgentPermissionPolicy.AUTO, denying(), redactor,
                Optional.empty(), SessionAccess.FULL, Optional.empty(), ImmutableList.of()).orElseThrow()) {
            assertThat(registration.toolNames()).doesNotContain(PUSH_TOOL_NAME, CLOSE_TOOL_NAME);
        }
    }

    // The fake authorizes only words that name the action, as the app's words check does.
    private TaskPublicationBridge.Prepared prepared(String description, String result, Optional<String> said)
    {
        quoted.add(said);
        return new TaskPublicationBridge.Prepared(description, () -> {
            writes.incrementAndGet();
            return result;
        }, said.filter(words -> words.equals("push it") || words.equals("close it")));
    }

    private PermissionBroker broker()
    {
        return PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/mcp"), json, (task, query) -> "")
                .taskPublications(task -> Optional.of(publication)).build();
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

    private JsonNode call(PermissionBroker broker, McpRegistration registration, String tool, String arguments)
            throws Exception
    {
        return broker.callCodexRootTool(registration.token(), PermissionBroker.CALL_TOOL,
                json.valueToTree(ImmutableMap.of("name", tool, "arguments", json.readTree(arguments)))).body().orElseThrow();
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
