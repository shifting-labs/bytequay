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

import java.time.Instant;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * One time a saved rule's event happened, and whether the user then did what the rule would have done, or, for a suggestion, what they
 * did with the work the rule did.
 *
 * @param key names the event within its rule, so a rule fires once per event
 * @param subject the pull request or issue it was about, such as "acme/warehouse#4521"
 * @param occurredAt when the event happened; the user's action counts only after it
 * @param suggestion whether the rule did the work itself, in Suggest, rather than only recording it
 * @param work the work a suggestion started, "review:" and the review's id or "task:" and the task's id
 * @param skipped why a suggestion has no work to judge, such as the daily limit
 */
public record RuleRun(String ruleId, String key, String subject, String title, Instant occurredAt, Verdict verdict, boolean suggestion, Optional<String> work,
        Optional<String> skipped, Optional<RuleAuthorization> authorization, Optional<String> outcome)
{
    public RuleRun
    {
        checkText(ruleId, "ruleId", 100);
        checkText(key, "key", 300);
        checkText(subject, "subject", 300);
        requireNonNull(title, "title is null");
        requireNonNull(occurredAt, "occurredAt is null");
        requireNonNull(verdict, "verdict is null");
        requireNonNull(work, "work is null");
        requireNonNull(skipped, "skipped is null");
        requireNonNull(authorization, "authorization is null");
        requireNonNull(outcome, "outcome is null");
        checkArgument(suggestion || work.isEmpty() && skipped.isEmpty(), "only a suggestion has work or a reason it was skipped");
        checkArgument(work.isEmpty() || skipped.isEmpty(), "a suggestion either has work or was skipped");
        checkArgument(verdict != Verdict.EDITED || suggestion, "only a suggestion can be edited");
    }

    public RuleRun(String ruleId, String key, String subject, String title, Instant occurredAt, Verdict verdict, boolean suggestion, Optional<String> work,
            Optional<String> skipped)
    {
        this(ruleId, key, subject, title, occurredAt, verdict, suggestion, work, skipped, Optional.empty(), Optional.empty());
    }

    public static RuleRun automatic(RuleAuthorization authorization, String key, String subject, String title, Instant occurredAt)
    {
        return new RuleRun(authorization.ruleId(), key, subject, title, occurredAt, Verdict.PENDING, true, Optional.empty(), Optional.empty(),
                Optional.of(authorization), Optional.empty());
    }

    /** A run that only records what the rule would have done. */
    public static RuleRun observed(String ruleId, String key, String subject, String title, Instant occurredAt)
    {
        return new RuleRun(ruleId, key, subject, title, occurredAt, Verdict.PENDING, false, Optional.empty(), Optional.empty());
    }

    /** A run whose rule starts the work, before it has started. */
    public static RuleRun suggested(String ruleId, String key, String subject, String title, Instant occurredAt)
    {
        return new RuleRun(ruleId, key, subject, title, occurredAt, Verdict.PENDING, true, Optional.empty(), Optional.empty());
    }

    public RuleRun decide(Verdict decided)
    {
        return new RuleRun(ruleId, key, subject, title, occurredAt, decided, suggestion, work, skipped, authorization, outcome);
    }

    public RuleRun started(String startedWork)
    {
        return new RuleRun(ruleId, key, subject, title, occurredAt, verdict, suggestion, Optional.of(startedWork), Optional.empty(), authorization, outcome);
    }

    public RuleRun skip(String reason)
    {
        return new RuleRun(ruleId, key, subject, title, occurredAt, verdict, suggestion, Optional.empty(), Optional.of(reason), authorization, outcome);
    }

    public RuleRun finished(String result)
    {
        checkText(result, "result");
        return new RuleRun(ruleId, key, subject, title, occurredAt, verdict, suggestion, work, skipped, authorization, Optional.of(result));
    }

    /** A suggestion still starting: neither its work nor a reason it was skipped is known yet. */
    public boolean starting()
    {
        return suggestion && work.isEmpty() && skipped.isEmpty();
    }

    /** Pending action state is queried directly; bounded display history never determines whether execution is unfinished. */
    public boolean needsReconciliation()
    {
        return authorization.isPresent() && (outcome.filter(value -> value.equals("STARTING") || value.equals("UNKNOWN")).isPresent()
                || key.contains(":ready:") && (starting() && outcome.isEmpty()
                        || outcome.filter(value -> value.equals("WAITING") || value.equals("QUEUED") || value.equals("UNKNOWN")).isPresent()));
    }

    public enum Verdict
    {
        PENDING,
        AGREED,
        /** The user kept a suggestion only after changing it. */
        EDITED,
        DISAGREED
    }
}
