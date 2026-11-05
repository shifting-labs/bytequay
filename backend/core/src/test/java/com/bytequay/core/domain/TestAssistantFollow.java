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

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestAssistantFollow
{
    private static final Instant AT = Instant.parse("2026-10-17T01:02:00Z");

    @Test
    void ruleReviewRequiresAnAuthorizedPrivateReview()
    {
        RuleRun suggested = RuleRun.suggested("rule-1", "event-1", "owner/repository#42", "Review changes", AT).started("review:review-1");
        assertThatThrownBy(() -> AssistantFollow.ruleReview("task-1", AT, "Review changes", suggested))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("rule follow requires authorization");

        RuleRun automatic = RuleRun.automatic(new RuleAuthorization("rule-1", "Review changes", 1), "event-1", "owner/repository#42", "Review changes", AT);
        assertThatThrownBy(() -> AssistantFollow.ruleReview("task-1", AT, "Review changes", automatic.started("pr:42")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("rule follow requires a private review");
        assertThatThrownBy(() -> AssistantFollow.ruleReview("task-1", AT, "Review changes", automatic.started("review:")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("rule follow requires a private review");
    }

    @Test
    void followRequiresExactlyOneSource()
    {
        assertThatThrownBy(() -> new AssistantFollow(Optional.empty(), "task-1", AT, 0, "Follow this work", Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("follow requires one source");
        RuleRun run = RuleRun.automatic(new RuleAuthorization("rule-1", "Review changes", 1), "event-1", "owner/repository#42", "Review changes", AT)
                .started("review:review-1");
        assertThatThrownBy(() -> new AssistantFollow(Optional.of("0c6a3f0e-5b1d-4c39-9d43-7f1f3a2b9e10"), "task-1", AT, 0, "Follow this work", Optional.of(run)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("follow requires one source");
    }
}
