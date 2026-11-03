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
import com.bytequay.core.store.AppSettingsStore;
import com.bytequay.core.store.RuleRunStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** The user's rules, kept like skills as one app setting, and their records. */
@Service
public class AgentRuleService
{
    private static final String SETTINGS_KEY = "agent.rules";
    private static final TypeReference<List<AgentRule>> RULE_LIST = new TypeReference<>() {};
    private static final int MAXIMUM_RULES = 50;

    private final AppSettingsStore store;
    private final RuleRunStore runs;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AgentRuleService(AppSettingsStore store, RuleRunStore runs, ObjectMapper mapper, Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.runs = requireNonNull(runs, "runs is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public synchronized List<AgentRuleView> list()
    {
        return views(saved());
    }

    /** The rule's runs since its record began, newest first; runs from before a header change judged a different rule. */
    public synchronized List<RuleRun> record(String id)
    {
        return record(saved().stream().filter(rule -> rule.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown rule: " + id)));
    }

    /** What the text says, or why it cannot be a rule, without saving it. */
    public RuleDefinition check(String text)
    {
        return RuleText.parse(text);
    }

    /** A new rule starts in Observe, with an empty record. */
    public synchronized List<AgentRuleView> add(String text)
    {
        RuleText.parse(text);
        List<AgentRule> current = saved();
        checkArgument(current.size() < MAXIMUM_RULES, "At most %s rules can be saved", MAXIMUM_RULES);
        return save(ImmutableList.<AgentRule>builder()
                .addAll(current)
                .add(new AgentRule(UUID.randomUUID().toString(), text, AgentRule.Mode.OBSERVE, false, clock.instant()))
                .build());
    }

    /** A changed header is a different rule, so it starts a new record in Observe; a new name or new instructions keep the record. */
    public synchronized List<AgentRuleView> update(String id, String text)
    {
        RuleDefinition changed = RuleText.parse(text);
        return replace(id, rule -> RuleText.parse(rule.text()).sameTrigger(changed)
                ? new AgentRule(id, text, rule.mode(), rule.paused(), rule.recordSince())
                : new AgentRule(id, text, AgentRule.Mode.OBSERVE, rule.paused(), clock.instant()));
    }

    public synchronized List<AgentRuleView> pause(String id, boolean paused)
    {
        return replace(id, rule -> new AgentRule(id, rule.text(), rule.mode(), paused, rule.recordSince()));
    }

    public synchronized List<AgentRuleView> remove(String id)
    {
        return save(saved().stream().filter(rule -> !rule.id().equals(id)).collect(toImmutableList()));
    }

    private List<AgentRuleView> replace(String id, UnaryOperator<AgentRule> change)
    {
        List<AgentRule> current = saved();
        checkArgument(current.stream().anyMatch(rule -> rule.id().equals(id)), "Unknown rule: %s", id);
        return save(current.stream().map(rule -> rule.id().equals(id) ? change.apply(rule) : rule).collect(toImmutableList()));
    }

    private List<AgentRule> saved()
    {
        return store.get(SETTINGS_KEY).map(this::decode).orElse(ImmutableList.of());
    }

    private List<AgentRuleView> save(List<AgentRule> rules)
    {
        try {
            store.put(SETTINGS_KEY, mapper.writeValueAsString(rules));
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("Rules could not be encoded", failure);
        }
        return views(rules);
    }

    private List<AgentRuleView> views(List<AgentRule> rules)
    {
        return rules.stream().map(rule -> AgentRuleView.of(rule, record(rule))).collect(toImmutableList());
    }

    private List<RuleRun> record(AgentRule rule)
    {
        return runs.findByRule(rule.id()).stream().filter(run -> !run.occurredAt().isBefore(rule.recordSince())).collect(toImmutableList());
    }

    private List<AgentRule> decode(String json)
    {
        try {
            return ImmutableList.copyOf(mapper.readValue(json, RULE_LIST));
        }
        catch (JsonProcessingException | IllegalArgumentException failure) {
            throw new IllegalStateException("Saved rules could not be read", failure);
        }
    }
}
