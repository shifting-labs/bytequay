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

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.bytequay.core.domain.Checks.checkText;
import static java.util.Objects.requireNonNull;

/**
 * ByteQuay's own push of a development task's branch and close of its pull request, with ByteQuay's credentials, so an agent never asks
 * the user to run git or gh by hand. Each is prepared first, from the words the agent quotes; it runs at once when they are the user's
 * own request in the current turn, and otherwise waits for an in-app Allow once / Deny card.
 */
public record TaskPublicationBridge(Function<Optional<String>, Prepared> push, Function<Optional<String>, Prepared> close)
{
    public static final String PUSH_TOOL_NAME = "push_task_branch";
    public static final String CLOSE_TOOL_NAME = "close_task_pull_request";
    public static final String INSTRUCTION = """
            To push this task's committed work, use push_task_branch; to close this task's own pull request, use
            close_task_pull_request. Do not run git push, gh or glab for them, and never ask the user to run a command by hand.
            When the user's message in this turn asks for it, pass their exact words, including the word push or close, in
            words, and ByteQuay acts at once; otherwise ByteQuay shows an in-app Allow once / Deny card and waits for the answer. ByteQuay pushes the task
            branch's committed head with its own credentials, replacing only commits this task once had, and closes only
            this task's own open pull request. Report the result faithfully. A refusal, denial or expired approval does
            nothing; do not retry without a new user request. Only the root agent may call these tools.
            """;

    public TaskPublicationBridge
    {
        requireNonNull(push, "push is null");
        requireNonNull(close, "close is null");
    }

    /**
     * What a prepared action will do, and how it runs once authorized.
     *
     * @param authorization why it may run without a card, such as the user's words in this turn; empty when the user must allow it
     */
    public record Prepared(String description, Supplier<String> execute, Optional<String> authorization)
    {
        public Prepared
        {
            checkText(description, "description");
            requireNonNull(execute, "execute is null");
            requireNonNull(authorization, "authorization is null");
        }
    }
}
