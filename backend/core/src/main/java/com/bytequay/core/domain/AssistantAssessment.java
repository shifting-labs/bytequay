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

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** A recoverable, read-only assessment. Its item ID is also the publication key, so a crash cannot duplicate a report. */
public record AssistantAssessment(String id, String conversationId, String taskId, String fingerprint, AssistantItem item, String context,
        boolean interpret, boolean requested, State state)
{
    public enum State { QUEUED, PUBLISHED, QUIET, SUPERSEDED }

    public AssistantAssessment
    {
        checkText(id, "id");
        checkText(conversationId, "conversationId");
        checkText(taskId, "taskId");
        checkText(fingerprint, "fingerprint");
        requireNonNull(item, "item is null");
        checkArgument(item.id().equals(id) && item.conversationId().equals(conversationId) && item.kind() == AssistantItem.Kind.REPORT, "assessment item does not match");
        requireNonNull(context, "context is null");
        requireNonNull(state, "state is null");
    }

    public AssistantAssessment ended(State result)
    {
        return new AssistantAssessment(id, conversationId, taskId, fingerprint, item, context, interpret, requested, result);
    }

    public AssistantAssessment published(AssistantItem report)
    {
        return new AssistantAssessment(id, conversationId, taskId, fingerprint, report, context, interpret, requested, State.PUBLISHED);
    }

    public boolean ruleReceipt()
    {
        return fingerprint.startsWith("event:") && !item.report().orElseThrow().ruleActions().isEmpty();
    }
}
