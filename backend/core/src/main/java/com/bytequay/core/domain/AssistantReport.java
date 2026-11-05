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

import java.util.List;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkOptionalText;
import static com.bytequay.core.domain.Checks.checkText;
import static java.util.Objects.requireNonNull;

/** App-owned card facts with an optional read-only interpretation. Interpretation never changes the card's state or authority. */
public record AssistantReport(State state, String noticeId, AgendaTarget target, Optional<String> sourceTurnId, Optional<String> assessment,
        List<String> evidence, List<AssistantRuleAction> ruleActions, Optional<String> outcome)
{
    public enum State
    {
        APPROVAL_REQUIRED, ANSWER_REQUIRED, PROPOSAL, RESULT_READY, COMPLETED, ATTENTION, PROGRESS
    }

    public AssistantReport
    {
        requireNonNull(state, "state is null");
        checkText(noticeId, "noticeId");
        requireNonNull(target, "target is null");
        checkOptionalText(sourceTurnId, "sourceTurnId");
        checkOptionalText(assessment, "assessment", 4000);
        checkOptionalText(outcome, "outcome");
        evidence = ImmutableList.copyOf(evidence);
        ruleActions = ImmutableList.copyOf(ruleActions);
    }

    public AssistantReport(State state, String noticeId, AgendaTarget target)
    {
        this(state, noticeId, target, Optional.empty(), Optional.empty(), ImmutableList.of(), ImmutableList.of(), Optional.empty());
    }

    public AssistantReport assessed(String text)
    {
        return new AssistantReport(state, noticeId, target, sourceTurnId, Optional.of(text), evidence, ruleActions, outcome);
    }
}
