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

import static com.bytequay.core.domain.Checks.checkText;
import static java.util.Objects.requireNonNull;

/**
 * One time a saved rule's event happened, and whether the user then did what the rule would have done.
 *
 * @param key names the event within its rule, so a rule fires once per event
 * @param subject the pull request or issue it was about, such as "acme/warehouse#4521"
 * @param occurredAt when the event happened; the user's action counts only after it
 */
public record RuleRun(String ruleId, String key, String subject, String title, Instant occurredAt, Verdict verdict)
{
    public RuleRun
    {
        checkText(ruleId, "ruleId", 100);
        checkText(key, "key", 300);
        checkText(subject, "subject", 300);
        requireNonNull(title, "title is null");
        requireNonNull(occurredAt, "occurredAt is null");
        requireNonNull(verdict, "verdict is null");
    }

    public RuleRun decide(Verdict decided)
    {
        return new RuleRun(ruleId, key, subject, title, occurredAt, decided);
    }

    public enum Verdict
    {
        PENDING,
        AGREED,
        DISAGREED
    }
}
