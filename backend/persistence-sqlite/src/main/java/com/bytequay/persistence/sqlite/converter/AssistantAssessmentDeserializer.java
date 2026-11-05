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
package com.bytequay.persistence.sqlite.converter;

import com.bytequay.core.domain.AssistantAssessment;
import com.bytequay.core.domain.AssistantItem;
import com.bytequay.core.domain.AssistantReport;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

/** Reads retained chat jobs whose result was stored as a conversation item before inbox assessments existed. */
final class AssistantAssessmentDeserializer
        extends StdDeserializer<AssistantAssessment>
{
    AssistantAssessmentDeserializer()
    {
        super(AssistantAssessment.class);
    }

    @Override
    public AssistantAssessment deserialize(JsonParser parser, DeserializationContext context)
            throws IOException
    {
        Stored stored = parser.readValueAs(Stored.class);
        return stored.item().map(item -> new AssistantAssessment(stored.id(), stored.conversationId().orElseThrow(), stored.taskId(), stored.fingerprint(), item,
                        stored.context(), stored.interpret(), stored.requested(), stored.state()))
                .orElseGet(() -> new AssistantAssessment(stored.id(), stored.conversationId(), stored.taskId(), stored.fingerprint(), stored.at().orElseThrow(),
                        stored.sourceLine().orElseThrow(), stored.report().orElseThrow(), stored.context(), stored.interpret(), stored.requested(), stored.state()));
    }

    private record Stored(String id, Optional<String> conversationId, String taskId, String fingerprint, Optional<AssistantItem> item, Optional<Instant> at,
            Optional<String> sourceLine, Optional<AssistantReport> report, String context, boolean interpret, boolean requested, AssistantAssessment.State state) {}
}
