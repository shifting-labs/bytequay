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

import com.bytequay.core.domain.AgendaTarget;
import com.bytequay.core.domain.AssistantReport;
import com.bytequay.core.domain.AssistantRuleAction;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** Reads retained conversation cards without changing the current public report contract. */
final class AssistantReportDeserializer
        extends StdDeserializer<AssistantReport>
{
    AssistantReportDeserializer()
    {
        super(AssistantReport.class);
    }

    @Override
    public AssistantReport deserialize(JsonParser parser, DeserializationContext context)
            throws IOException
    {
        StoredReport stored = parser.readValueAs(StoredReport.class);
        AssistantReport.State state = switch (stored.state()) {
            case "READY" -> AssistantReport.State.RESULT_READY;
            case "DECISION", "FAILED" -> AssistantReport.State.ATTENTION;
            default -> AssistantReport.State.valueOf(stored.state());
        };
        return new AssistantReport(state, stored.noticeId(), stored.target(), stored.sourceTurnId(), stored.assessment(),
                Optional.ofNullable(stored.evidence()).orElse(ImmutableList.of()), Optional.ofNullable(stored.ruleActions()).orElse(ImmutableList.of()), stored.outcome(),
                Optional.ofNullable(stored.delivery()).orElseGet(() -> AssistantReport.deliveryFor(state)),
                Optional.ofNullable(stored.deliveryReason()).orElseGet(() -> AssistantReport.reasonFor(state)), stored.offer());
    }

    private record StoredReport(String state, String noticeId, AgendaTarget target, Optional<String> sourceTurnId, Optional<String> assessment,
            List<String> evidence, List<AssistantRuleAction> ruleActions, Optional<String> outcome, AssistantReport.Delivery delivery, String deliveryReason,
            Optional<AssistantReport.Offer> offer) {}
}
