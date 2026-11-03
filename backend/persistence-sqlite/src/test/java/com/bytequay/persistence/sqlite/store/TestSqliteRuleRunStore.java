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

import com.bytequay.core.domain.RuleRun;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static com.bytequay.core.domain.RuleRun.Verdict.AGREED;
import static com.bytequay.core.domain.RuleRun.Verdict.PENDING;
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
        RuleRun first = new RuleRun("rule-1", "pr:7:head:1", "acme/warehouse#7", "Fix the flaky test", time, PENDING);
        RuleRun second = new RuleRun("rule-1", "pr:8:head:1", "acme/warehouse#8", "Add a cache", time.plusSeconds(60), PENDING);
        store.save(first);
        store.save(second);
        store.save(new RuleRun("rule-2", "pr:7:head:1", "acme/warehouse#7", "Fix the flaky test", time, PENDING));
        store.save(first.decide(AGREED));

        assertThat(store.findByRule("rule-1")).containsExactly(second, first.decide(AGREED));
        assertThat(store.findByRule("rule-3")).isEmpty();
        store.deleteBefore(time.plusSeconds(1));
        assertThat(store.findByRule("rule-1")).containsExactly(second);
        assertThat(store.findByRule("rule-2")).isEmpty();
    }
}
