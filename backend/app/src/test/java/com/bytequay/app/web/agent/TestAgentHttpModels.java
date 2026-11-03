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
package com.bytequay.app.web.agent;

import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventPayload.AssistantMessage;
import com.bytequay.agent.core.AgentEventSource;
import com.bytequay.agent.core.AgentHistoryPage;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.spi.ProviderAgentEvent.MessageKind;
import com.bytequay.agent.spi.ProviderAgentEvent.MessagePhase;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

class TestAgentHttpModels
{
    private static final TaskId TASK = TaskId.random();
    private static final TurnId TURN = TurnId.random();

    @Test
    void historyLeavesOutOnlyPiecesTheirCompletedMessageDirectlyReplaces()
    {
        AgentHistoryPage page = new AgentHistoryPage(ImmutableList.of(
                piece(11, "item-a", "Look"), piece(12, "item-a", "ing"), completed(13, "item-a", "Looking", MessagePhase.FINAL),
                piece(14, "item-b", "Run"), piece(15, "item-c", "Other"), completed(16, "item-b", "Running", MessagePhase.FINAL),
                piece(17, "item-d", "Answ"), completed(18, "item-d", "Answer", MessagePhase.PARTIAL),
                piece(19, "item-e", "Still streaming")), OptionalLong.of(400), OptionalLong.empty(), 19);

        AgentHttpModels.HistoryResponse history = AgentHttpModels.history(page);

        assertThat(history.events()).extracting(AgentHttpModels.EventResponse::sequence).containsExactly(13L, 14L, 15L, 16L, 18L, 19L);
        assertThat(history.firstSequence()).hasValue(11);
        assertThat(history.lastSequence()).hasValue(19);
        assertThat(history.beforeCursor()).contains("400");
        assertThat(AgentHttpModels.history(new AgentHistoryPage(ImmutableList.of(), OptionalLong.empty(), OptionalLong.empty(), 0)).firstSequence()).isEmpty();
    }

    private static AgentEventEnvelope piece(long sequence, String item, String text)
    {
        return envelope(sequence, item, Optional.empty(), new AssistantMessage(MessageKind.COMMENTARY, MessagePhase.PARTIAL, text));
    }

    private static AgentEventEnvelope completed(long sequence, String item, String text, MessagePhase phase)
    {
        return envelope(sequence, item, Optional.of("event-" + sequence), new AssistantMessage(MessageKind.COMMENTARY, phase, text));
    }

    private static AgentEventEnvelope envelope(long sequence, String item, Optional<String> providerEventId, AssistantMessage message)
    {
        AgentEventSource source = new AgentEventSource(Optional.of("thread"), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(item), providerEventId);
        return new AgentEventEnvelope(AgentEventEnvelope.CURRENT_SCHEMA_VERSION, EventId.random(), sequence, TASK, Optional.of(TURN), source,
                Instant.parse("2026-11-03T00:00:00Z"), message);
    }
}
