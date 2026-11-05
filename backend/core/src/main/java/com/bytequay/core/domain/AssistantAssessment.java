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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.time.Instant;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** A recoverable, read-only assessment for a chat or the Assistant inbox. Its ID is the idempotent publication key. */
public record AssistantAssessment(String id, Optional<String> conversationId, String taskId, String fingerprint, Instant at, String sourceLine,
        AssistantReport report, String context, boolean interpret, boolean requested, State state)
{
    public enum State { QUEUED, PUBLISHED, QUIET, SUPERSEDED }

    public AssistantAssessment
    {
        checkText(id, "id");
        requireNonNull(conversationId, "conversationId is null");
        conversationId.ifPresent(value -> checkText(value, "conversationId"));
        checkText(taskId, "taskId");
        checkText(fingerprint, "fingerprint");
        requireNonNull(at, "at is null");
        requireNonNull(sourceLine, "sourceLine is null");
        requireNonNull(report, "report is null");
        requireNonNull(context, "context is null");
        requireNonNull(state, "state is null");
    }

    public AssistantAssessment(String id, String conversationId, String taskId, String fingerprint, AssistantItem item, String context,
            boolean interpret, boolean requested, State state)
    {
        this(id, Optional.of(conversationId), taskId, fingerprint, item.at(), item.text(), item.report().orElseThrow(), context, interpret, requested, state);
        checkArgument(item.id().equals(id) && item.conversationId().equals(conversationId) && item.kind() == AssistantItem.Kind.REPORT, "assessment item does not match");
    }

    /** Only an explicitly followed chat receives a conversation item; inbox results have no synthetic conversation. */
    public AssistantItem item()
    {
        return new AssistantItem(id, conversationId.orElseThrow(), at, AssistantItem.Kind.REPORT, sourceLine, ImmutableList.of(), Optional.empty(), Optional.empty(),
                ImmutableMap.of(), false, Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(report));
    }

    public AssistantAssessment ended(State result)
    {
        return assessed(report, result);
    }

    public AssistantAssessment published(AssistantItem item)
    {
        checkArgument(item.id().equals(id) && conversationId.equals(Optional.of(item.conversationId())), "assessment item does not match");
        return assessed(item.report().orElseThrow(), State.PUBLISHED);
    }

    public AssistantAssessment assessed(AssistantReport result, State resultState)
    {
        return new AssistantAssessment(id, conversationId, taskId, fingerprint, at, sourceLine, result, context, interpret, requested, resultState);
    }

    public boolean ruleReceipt()
    {
        return fingerprint.startsWith("event:") && !report.ruleActions().isEmpty();
    }
}
