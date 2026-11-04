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

import com.bytequay.core.domain.RuleRun;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkText;
import static java.util.Objects.requireNonNull;

/**
 * A saved rule as Settings shows it: the rule itself, what its text says, and how its record counts so far.
 *
 * @param keptObserving when the user last kept it observing or moved it down
 * @param edited suggestions the user kept only after changing them
 */
public record AgentRuleView(String id, String text, AgentRule.Mode mode, boolean paused, Instant recordSince, Optional<Instant> keptObserving, RuleDefinition rule,
        int runs, int agreed, int edited, int disagreed)
{
    public AgentRuleView
    {
        checkText(id, "id");
        checkText(text, "text");
        requireNonNull(mode, "mode is null");
        requireNonNull(recordSince, "recordSince is null");
        requireNonNull(keptObserving, "keptObserving is null");
        requireNonNull(rule, "rule is null");
    }

    static AgentRuleView of(AgentRule saved, List<RuleRun> record)
    {
        return new AgentRuleView(saved.id(), saved.text(), saved.mode(), saved.paused(), saved.recordSince(), saved.keptObserving(), RuleText.parse(saved.text()), record.size(),
                count(record, RuleRun.Verdict.AGREED), count(record, RuleRun.Verdict.EDITED), count(record, RuleRun.Verdict.DISAGREED));
    }

    /** The runs the user answered, one way or another. */
    public int answered()
    {
        return agreed + edited + disagreed;
    }

    private static int count(List<RuleRun> record, RuleRun.Verdict verdict)
    {
        return (int) record.stream().filter(run -> run.verdict() == verdict).count();
    }
}
