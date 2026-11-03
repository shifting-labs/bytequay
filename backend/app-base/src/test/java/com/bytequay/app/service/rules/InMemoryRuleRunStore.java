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

import com.bytequay.core.domain.RuleRun;
import com.bytequay.core.store.RuleRunStore;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.google.common.collect.ImmutableList.toImmutableList;

public class InMemoryRuleRunStore
        implements RuleRunStore
{
    private final Map<String, RuleRun> runs = new ConcurrentHashMap<>();

    @Override
    public void save(RuleRun run)
    {
        runs.put(run.ruleId() + ":" + run.key(), run);
    }

    @Override
    public List<RuleRun> findByRule(String ruleId)
    {
        return runs.values().stream().filter(run -> run.ruleId().equals(ruleId)).sorted(Comparator.comparing(RuleRun::occurredAt).reversed())
                .collect(toImmutableList());
    }

    @Override
    public void deleteBefore(Instant cutoff)
    {
        runs.values().removeIf(run -> run.occurredAt().isBefore(cutoff));
    }
}
