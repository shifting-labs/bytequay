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

import java.util.function.Function;
import java.util.function.Supplier;

import static com.bytequay.core.domain.Checks.checkText;
import static java.util.Objects.requireNonNull;

/** Prepares one task-owned replacement for exact, one-time user approval before any remote write. */
public record TaskPullRequestReplacementBridge(Function<String, Prepared> prepare)
{
    public static final String TOOL_NAME = "replace_task_pull_request";
    public static final String INSTRUCTION = """
            When the user asks to replace this task's pull request under another branch name, use replace_task_pull_request.
            Supply the exact new branch name. ByteQuay prepares the replacement from this task's linked pull request and
            verified revision, shows an in-app Allow once / Deny card, and waits for the answer. Do not ask the user to run
            git or gh commands or approve an action only in prose. Do not duplicate this operation through shell or HTTP.
            ByteQuay creates and verifies the replacement before closing the old pull request and keeps the replacement
            connected to this task. Report the returned result faithfully, including any partial completion.
            A denial or expired approval performs no replacement; preserve it and do not retry without a new user request.
            If execution reports a partial completion, retry only as instructed with the same branch so completed writes
            can be reconciled. Only the root agent may call this tool; helpers report their findings to the root.
            """;

    public TaskPullRequestReplacementBridge
    {
        requireNonNull(prepare, "prepare is null");
    }

    /** The application captures the target and revision here and rechecks them when the approved action executes. */
    public record Prepared(String description, Supplier<String> execute)
    {
        public Prepared
        {
            checkText(description, "description");
            requireNonNull(execute, "execute is null");
        }
    }
}
