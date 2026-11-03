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

/**
 * A saved rule: the file text the user wrote, which {@link RuleText} reads, the mode its record has earned, whether it is paused, and
 * when its record began. The mode lives here rather than in the text, so only the record can raise it.
 */
public record AgentRule(String id, String text, Mode mode, boolean paused, Instant recordSince)
{
    public AgentRule
    {
        checkText(id, "id");
        checkText(text, "text", RuleText.MAXIMUM_LENGTH);
        requireNonNull(mode, "mode is null");
        requireNonNull(recordSince, "recordSince is null");
    }

    /** Observe only records what the rule would have done; Suggest does the work for the user to accept; Auto acts on it. */
    public enum Mode
    {
        OBSERVE,
        SUGGEST,
        AUTO
    }
}
