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
package com.bytequay.core.domain;

import java.time.Instant;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** A chat or activated rule's continuing interest in a task. The cursor advances only after associated reports are saved. */
public record AssistantFollow(Optional<String> conversationId, String taskId, Instant since, long afterSequence, String context, Optional<RuleRun> ruleRun)
{
    public AssistantFollow
    {
        requireNonNull(conversationId, "conversationId is null");
        conversationId.ifPresent(id -> checkArgument(AssistantConversation.isId(id), "conversationId is invalid"));
        requireNonNull(ruleRun, "ruleRun is null");
        checkArgument(conversationId.isPresent() != ruleRun.isPresent(), "follow requires one source");
        ruleRun.ifPresent(run -> {
            checkArgument(run.authorization().isPresent(), "rule follow requires authorization");
            checkArgument(run.work().filter(work -> work.startsWith("review:") && !work.substring("review:".length()).isBlank()).isPresent(),
                    "rule follow requires a private review");
        });
        checkText(taskId, "taskId");
        requireNonNull(context, "context is null");
        requireNonNull(since, "since is null");
        checkArgument(afterSequence >= 0, "afterSequence is negative");
    }

    public AssistantFollow(String conversationId, String taskId, Instant since, long afterSequence, String context)
    {
        this(Optional.of(conversationId), taskId, since, afterSequence, context, Optional.empty());
    }

    public static AssistantFollow ruleReview(String taskId, Instant since, String context, RuleRun run)
    {
        return new AssistantFollow(Optional.empty(), taskId, since, 0, context, Optional.of(run));
    }

    public String id()
    {
        return conversationId.map(id -> id + ":" + taskId).orElse("rule:" + taskId);
    }

    public AssistantFollow readThrough(long sequence)
    {
        return new AssistantFollow(conversationId, taskId, since, Math.max(afterSequence, sequence), context, ruleRun);
    }
}
