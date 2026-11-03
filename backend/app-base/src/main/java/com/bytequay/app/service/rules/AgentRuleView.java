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
package com.bytequay.app.service.rules;

import java.time.Instant;

import static com.bytequay.core.domain.Checks.checkText;
import static java.util.Objects.requireNonNull;

/** A saved rule as Settings shows it: the rule itself and what its text says. */
public record AgentRuleView(String id, String text, AgentRule.Mode mode, boolean paused, Instant recordSince, RuleDefinition rule)
{
    public AgentRuleView
    {
        checkText(id, "id");
        checkText(text, "text");
        requireNonNull(mode, "mode is null");
        requireNonNull(recordSince, "recordSince is null");
        requireNonNull(rule, "rule is null");
    }

    static AgentRuleView of(AgentRule saved)
    {
        return new AgentRuleView(saved.id(), saved.text(), saved.mode(), saved.paused(), saved.recordSince(), RuleText.parse(saved.text()));
    }
}
