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
package com.bytequay.core.store;

import com.bytequay.core.domain.AssistantAssessment;
import com.bytequay.core.domain.AssistantFollow;

import java.util.List;
import java.util.Optional;

/** Durable chat and rule following with independent read-only assessment jobs. */
public interface AssistantFollowStore
{
    List<AssistantFollow> follows();

    Optional<AssistantFollow> follow(String conversationId, String taskId);

    /** The rule-owned subscription to this task's results in the Assistant inbox. */
    Optional<AssistantFollow> follow(String taskId);

    void save(AssistantFollow follow);

    void remove(String conversationId, String taskId);

    void remove(AssistantFollow follow);

    Optional<AssistantAssessment> assessment(String id);

    List<AssistantAssessment> queued();

    void save(AssistantAssessment assessment);

    boolean followsTask(String taskId);

    /** The automatic interpretation for exactly these displayed source facts, preferring a useful result from any following conversation. */
    Optional<AssistantAssessment> result(String taskId, String noticeId, String sourceLine);

    /** Quiet automatic permission receipts retained after the event cursor has advanced. */
    List<AssistantAssessment> ruleReceipts(Optional<String> conversationId, String taskId);
}
