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
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * A saved rule: the file text the user wrote, which {@link RuleText} reads, its selected mode, whether it is paused, and
 * when its record began. Only a direct activation grants Auto; observation history can offer Suggest.
 *
 * @param keptObserving when the user last chose to keep it observing, or moved it down; it is not offered a move up again until ten
 *         more of its runs are answered after that
 */
public record AgentRule(String id, String text, Mode mode, boolean paused, Instant recordSince, Optional<Instant> keptObserving, long version)
{
    public AgentRule
    {
        checkText(id, "id");
        checkText(text, "text", RuleText.MAXIMUM_LENGTH);
        requireNonNull(mode, "mode is null");
        requireNonNull(recordSince, "recordSince is null");
        requireNonNull(keptObserving, "keptObserving is null");
        checkArgument(version >= 0, "version is negative");
    }

    /** Observe only records what the rule would have done; Suggest does the work for the user to accept; Auto acts on it. */
    public enum Mode
    {
        OBSERVE,
        SUGGEST,
        AUTO
    }
}
