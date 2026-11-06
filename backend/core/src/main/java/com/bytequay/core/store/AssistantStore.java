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

import com.bytequay.core.domain.AssistantAction;
import com.bytequay.core.domain.AssistantConversation;
import com.bytequay.core.domain.AssistantItem;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AssistantStore
{
    Optional<AssistantConversation> find(String conversationId);

    void save(AssistantConversation conversation);

    /** The most recently updated conversations, newest first. */
    List<AssistantConversation> conversations(int limit);

    /** The conversation's items in the order they were added. */
    List<AssistantItem> items(String conversationId);

    void add(AssistantItem item);

    /** Saves a direct command and its receipt together, without entering the foreground reply queue. */
    void addCommand(AssistantItem message, AssistantItem receipt);

    /** Saves the user's message and its pending delivery together; an explicit retry requeues the same saved message. */
    void enqueue(AssistantItem message);

    /** Definitely not started, ordered within each conversation. */
    List<AssistantItem> queued();

    /** Claimed messages without a saved outcome; these must never be replayed automatically. */
    List<AssistantItem> interrupted();

    boolean begin(String itemId);

    /** Saves the outcome and settles its claimed message atomically. Repeating completion adds nothing. */
    void complete(String itemId, AssistantItem outcome);

    /** Cancels messages that have not started in this conversation. */
    void cancelQueued(String conversationId);

    void addAction(AssistantAction action);

    /** The action tool's calls on one message, in the order they were made. */
    List<AssistantAction> actions(String itemId);

    /** Removes the conversations last updated before {@code updatedBefore} with their items, and the actions made before {@code actionsBefore}. */
    void deleteBefore(Instant updatedBefore, LocalDate actionsBefore);
}
