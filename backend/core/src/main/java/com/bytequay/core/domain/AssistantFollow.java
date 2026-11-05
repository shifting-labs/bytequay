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

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** A conversation's continuing interest in a task. The event cursor advances only after associated reports are saved. */
public record AssistantFollow(String conversationId, String taskId, Instant since, long afterSequence, String context)
{
    public AssistantFollow
    {
        checkArgument(AssistantConversation.isId(conversationId), "conversationId is invalid");
        checkText(taskId, "taskId");
        requireNonNull(context, "context is null");
        requireNonNull(since, "since is null");
        checkArgument(afterSequence >= 0, "afterSequence is negative");
    }

    public String id()
    {
        return conversationId + ":" + taskId;
    }

    public AssistantFollow readThrough(long sequence)
    {
        return new AssistantFollow(conversationId, taskId, since, Math.max(afterSequence, sequence), context);
    }
}
