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
package com.bytequay.persistence.sqlite.store;

import com.bytequay.core.domain.RuleAuthorization;
import com.bytequay.core.domain.RuleRun;
import com.bytequay.persistence.sqlite.converter.JsonColumns;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.bytequay.core.domain.RuleRun.Verdict.AGREED;
import static com.bytequay.core.domain.RuleRun.Verdict.EDITED;
import static org.assertj.core.api.Assertions.assertThat;

class TestSqliteRuleRunStore
        extends AbstractTestTransactionalSqliteStore
{
    @Autowired
    private SqliteRuleRunStore store;

    @Test
    void keepsOneRunPerRuleAndEventNewestFirstUntilItAgesOut()
    {
        Instant time = Instant.parse("2026-11-03T08:00:00Z");
        RuleRun first = RuleRun.observed("rule-1", "pr:7:head:1", "acme/warehouse#7", "Fix the flaky test", time);
        RuleRun second = RuleRun.observed("rule-1", "pr:8:head:1", "acme/warehouse#8", "Add a cache", time.plusSeconds(60));
        store.save(first);
        store.save(second);
        store.save(RuleRun.observed("rule-2", "pr:7:head:1", "acme/warehouse#7", "Fix the flaky test", time));
        store.save(first.decide(AGREED));

        assertThat(store.findByRule("rule-1")).containsExactly(second, first.decide(AGREED));
        assertThat(store.findByRule("rule-3")).isEmpty();
        store.deleteBefore(time.plusSeconds(1));
        assertThat(store.findByRule("rule-1")).containsExactly(second);
        assertThat(store.findByRule("rule-2")).isEmpty();
    }

    @Test
    void keepsASuggestionsWorkAndVerdict()
    {
        RuleRun suggestion = RuleRun.suggested("rule-1", "issue:900:github.com", "acme/warehouse#12", "Fix the importer", Instant.parse("2026-11-03T08:00:00Z"))
                .started("task:3f2c8a62-6f0e-4d3e-9a64-9b3a3c1f0e21").decide(EDITED);
        store.save(suggestion);
        assertThat(store.findByRule("rule-1")).containsExactly(suggestion);
    }

    @Test
    void unfinishedActionsSurviveHistoryLimitsAndAgeUntilTheirResultIsKnown()
    {
        Instant now = Instant.parse("2026-11-03T08:00:00Z");
        Instant old = now.minus(Duration.ofDays(40));
        RuleAuthorization authorization = new RuleAuthorization("removed-rule", "Merge approved changes", 2);
        RuleRun initial = RuleRun.automatic(authorization, "pr:7:ready:head", "acme/warehouse#7", "Initial", old);
        RuleRun waiting = RuleRun.automatic(authorization, "pr:8:ready:head", "acme/warehouse#8", "Waiting", old).started("pr:8").finished("WAITING");
        RuleRun queued = RuleRun.automatic(authorization, "pr:9:ready:head", "acme/warehouse#9", "Queued", old).started("pr:9").finished("QUEUED");
        RuleRun unknown = RuleRun.automatic(authorization, "pr:10:ready:head", "acme/warehouse#10", "Unknown", old).started("pr:10").finished("UNKNOWN");
        RuleRun review = RuleRun.automatic(authorization, "pr:11:head:head", "acme/warehouse#11", "Review", old).finished("STARTING");
        RuleRun unknownReview = RuleRun.automatic(authorization, "pr:12:head:head", "acme/warehouse#12", "Uncertain review", old)
                .skip("Review startup was not confirmed").finished("UNKNOWN");
        List<RuleRun> unfinished = ImmutableList.of(initial, waiting, queued, unknown, review, unknownReview);
        unfinished.forEach(store::save);
        store.save(RuleRun.observed("old-observation", "pr:1:head:head", "acme/warehouse#1", "Old", old));
        for (int i = 0; i < 1001; i++) {
            store.save(RuleRun.observed("observations", "pr:" + i + ":head:head", "acme/warehouse#" + i, "Recent", now.plusSeconds(i)));
        }

        assertThat(store.recent()).hasSize(1000).doesNotContainAnyElementsOf(unfinished);
        store.deleteBefore(now.minus(Duration.ofDays(30)));
        assertThat(store.pendingAutomatic()).containsExactlyInAnyOrderElementsOf(unfinished);
        assertThat(store.findByRule("old-observation")).isEmpty();

        unfinished.forEach(run -> store.save(run.finished("CANCELLED")));
        assertThat(store.pendingAutomatic()).isEmpty();
        store.deleteBefore(now.minus(Duration.ofDays(30)));
        assertThat(store.findByRule("removed-rule")).isEmpty();
    }

    @Test
    void aRunSavedBeforeSuggestionsReadsAsAnObservedRun()
            throws Exception
    {
        String saved = """
                {"ruleId":"rule-1","key":"pr:7:head:1","subject":"acme/warehouse#7","title":"Fix the flaky test","occurredAt":"2026-11-03T08:00:00Z","verdict":"AGREED"}
                """;
        assertThat(JsonColumns.mapper().readValue(saved, RuleRun.class))
                .isEqualTo(RuleRun.observed("rule-1", "pr:7:head:1", "acme/warehouse#7", "Fix the flaky test", Instant.parse("2026-11-03T08:00:00Z")).decide(AGREED));
    }
}
