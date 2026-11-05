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
import com.bytequay.core.store.RuleRunStore;
import com.bytequay.persistence.sqlite.entity.RuleRunEntity;
import com.bytequay.persistence.sqlite.repository.RuleRunJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteRuleRunStore
        implements RuleRunStore
{
    private final RuleRunJpaRepository runs;

    public SqliteRuleRunStore(RuleRunJpaRepository runs)
    {
        this.runs = requireNonNull(runs, "runs is null");
    }

    @Override
    @Transactional
    public void save(RuleRun run)
    {
        runs.save(RuleRunEntity.of(run));
    }

    @Override
    public List<RuleRun> findByRule(String ruleId)
    {
        return runs.findByRuleIdOrderByOccurredAtDesc(ruleId).stream().map(RuleRunEntity::toDomain).collect(toImmutableList());
    }

    @Override
    public List<RuleRun> recent()
    {
        return runs.findTop1000ByOrderByOccurredAtDesc().stream().map(RuleRunEntity::toDomain).collect(toImmutableList());
    }

    @Override
    public List<RuleRun> pendingAutomatic()
    {
        return runs.findByNeedsReconciliationTrueOrderByOccurredAtAsc().stream().map(RuleRunEntity::toDomain).collect(toImmutableList());
    }

    @Override
    @Transactional
    public void deleteBefore(Instant cutoff)
    {
        runs.deleteBefore(cutoff);
    }
}
