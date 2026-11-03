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

import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static com.bytequay.app.service.rules.TestRuleText.STORAGE_REVIEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestAgentRuleService
{
    private static final Instant SAVED = Instant.parse("2026-11-03T08:00:00Z");
    private static final Instant EDITED = SAVED.plusSeconds(3600);

    private final InMemoryAppSettingsStore store = new InMemoryAppSettingsStore();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void aSavedRuleStartsInObserveWithAnEmptyRecordAndSurvivesARestart()
    {
        AgentRuleView saved = service(SAVED).add(STORAGE_REVIEW).getFirst();

        assertThat(saved.mode()).isEqualTo(AgentRule.Mode.OBSERVE);
        assertThat(saved.paused()).isFalse();
        assertThat(saved.recordSince()).isEqualTo(SAVED);
        assertThat(saved.rule().name()).isEqualTo("Review PRs that touch storage");
        assertThat(service(EDITED).list()).containsExactly(saved);
    }

    @Test
    void aNewNameOrNewInstructionsKeepTheRecordAndAChangedHeaderStartsANewOneInObserve()
            throws Exception
    {
        store.put("agent.rules", mapper.writeValueAsString(ImmutableList.of(new AgentRule("rule-1", STORAGE_REVIEW, AgentRule.Mode.SUGGEST, true, SAVED))));
        AgentRuleService later = service(EDITED);

        AgentRuleView renamed = later.update("rule-1", STORAGE_REVIEW.replace("touch storage", "change storage").replace("post nothing", "never post")).getFirst();
        assertThat(renamed.rule().name()).isEqualTo("Review PRs that change storage");
        assertThat(renamed.mode()).isEqualTo(AgentRule.Mode.SUGGEST);
        assertThat(renamed.recordSince()).isEqualTo(SAVED);

        AgentRuleView narrowed = later.update("rule-1", STORAGE_REVIEW.replace(", docs/storage.md", "")).getFirst();
        assertThat(narrowed.mode()).isEqualTo(AgentRule.Mode.OBSERVE);
        assertThat(narrowed.recordSince()).isEqualTo(EDITED);
        assertThat(narrowed.paused()).isTrue();
    }

    @Test
    void pausesRemovesAndRefusesWhatIsNotARule()
    {
        AgentRuleService rules = service(SAVED);
        String id = rules.add(STORAGE_REVIEW).getFirst().id();

        assertThat(rules.pause(id, true).getFirst().paused()).isTrue();
        assertThat(rules.pause(id, false).getFirst().paused()).isFalse();
        assertThatThrownBy(() -> rules.add(STORAGE_REVIEW.replace("does: review", "does: merge")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("\"does: merge\"");
        assertThatThrownBy(() -> rules.update(id, "not a rule")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rules.pause("missing", true)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown rule");
        assertThat(rules.remove(id)).isEmpty();
        assertThat(rules.list()).isEmpty();
    }

    @Test
    void keepsAtMostFiftyRules()
    {
        AgentRuleService rules = service(SAVED);
        for (int i = 0; i < 50; i++) {
            rules.add(STORAGE_REVIEW);
        }
        assertThatThrownBy(() -> rules.add(STORAGE_REVIEW)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("At most 50 rules");
    }

    private AgentRuleService service(Instant now)
    {
        return new AgentRuleService(store, mapper, Clock.fixed(now, ZoneOffset.UTC));
    }
}
