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

import com.bytequay.agent.spi.ProviderApprovalScope;
import com.bytequay.core.domain.RuleAuthorization;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

import static com.bytequay.core.domain.Checks.checkText;
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
    // A rule may move up from Observe once the user answered this many of its runs and agreed with this share of them.
    static final int MOVE_UP_RUNS = 10;
    private static final int MOVE_UP_PERCENT = 80;

    private final AppSettingsStore store;
    private final RuleRunStore runs;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final AtomicLong revision = new AtomicLong();

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

    /** Durable history survives edits, pauses and deletion; learning uses only the current observation record below. */
    public synchronized List<RuleRun> record(String id)
    {
        checkText(id, "id");
        return runs.findByRule(id);
    }

    public List<RuleRun> recentRuns()
    {
        return runs.recent();
    }

    public List<RuleRun> pendingRuns()
    {
        return runs.pendingAutomatic();
    }

    /** The rules whose record lets them move up to Suggest now, which the Assistant asks about. */
    public synchronized List<AgentRuleView> movable()
    {
        return saved().stream().filter(this::movable).map(rule -> AgentRuleView.of(rule, record(rule))).collect(toImmutableList());
    }

    /** Moves a rule up to Suggest, as the Assistant's card offers, when its record allows it. */
    public synchronized List<AgentRuleView> moveUp(String id)
    {
        checkArgument(movable(rule(id)), "That rule's record does not let it move up yet");
        return replace(id, rule -> new AgentRule(id, rule.text(), AgentRule.Mode.SUGGEST, rule.paused(), rule.recordSince(), rule.keptObserving(), rule.version() + 1));
    }

    /** A direct user action authorizes the displayed revision without relying on observation history. */
    public synchronized List<AgentRuleView> activate(String id, long expectedVersion)
    {
        AgentRule current = rule(id);
        checkArgument(current.version() == expectedVersion, "The rule changed; review its current scope before activating it");
        RuleDefinition definition = RuleText.parse(current.text());
        checkArgument(definition.action() == RuleDefinition.Action.ALLOW || definition.action() == RuleDefinition.Action.MERGE
                || definition.action() == RuleDefinition.Action.REVIEW, "This rule's action cannot be activated yet");
        return replace(id, rule -> new AgentRule(id, rule.text(), AgentRule.Mode.AUTO, false, rule.recordSince(), rule.keptObserving(), rule.version() + 1));
    }

    /** The current authorization, checked immediately before an action accepts the rule's scope. */
    public synchronized Optional<RuleAuthorization> authorization(String id, long expectedVersion)
    {
        return saved().stream().filter(rule -> rule.id().equals(id) && rule.version() == expectedVersion && rule.mode() == AgentRule.Mode.AUTO && !rule.paused())
                .map(rule -> new RuleAuthorization(rule.id(), RuleText.parse(rule.text()).name(), rule.version())).findFirst();
    }

    /** Only typed provider facts participate; a command's text and model-written explanations cannot grant access. */
    public synchronized Optional<RuleAuthorization> authorizePermission(ProviderApprovalScope scope, String taskRepository)
    {
        String repository = scope.repository().orElse(taskRepository);
        return saved().stream()
                .filter(rule -> rule.mode() == AgentRule.Mode.AUTO && !rule.paused())
                .filter(rule -> {
                    RuleDefinition definition = RuleText.parse(rule.text());
                    return definition.event() == RuleDefinition.Event.PERMISSION_REQUESTED && definition.action() == RuleDefinition.Action.ALLOW
                            && definition.operations().contains(scope.operation().key()) && definition.hosts().containsAll(scope.hosts())
                            && (definition.repositories().isEmpty() || definition.repositories().contains(repository));
                })
                .map(rule -> new RuleAuthorization(rule.id(), RuleText.parse(rule.text()).name(), rule.version())).findFirst();
    }

    /** Moves a rule back to Observe; it is offered a move up again only once more of its runs are answered. */
    public synchronized List<AgentRuleView> moveDown(String id)
    {
        return replace(id, rule -> new AgentRule(id, rule.text(), AgentRule.Mode.OBSERVE, rule.paused(), rule.recordSince(), Optional.of(clock.instant()), rule.version() + 1));
    }

    /** Keeps a rule observing for now; the Assistant asks again once more of its runs are answered. */
    public synchronized List<AgentRuleView> keepObserving(String id)
    {
        return replace(id, rule -> new AgentRule(id, rule.text(), rule.mode(), rule.paused(), rule.recordSince(), Optional.of(clock.instant()), rule.version() + 1));
    }

    /** The user dismissed the work a suggestion did, so its run disagrees. */
    public synchronized void dismiss(String id, String key)
    {
        RuleRun run = record(rule(id)).stream().filter(each -> each.key().equals(key)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown run: " + key));
        checkArgument(run.suggestion() && run.work().isPresent() && run.verdict() == RuleRun.Verdict.PENDING, "Only a suggestion waiting for you can be dismissed");
        runs.save(run.decide(RuleRun.Verdict.DISAGREED));
    }

    /** Changes whenever a rule is saved, for caches that writes to the rules' own setting do not invalidate. */
    public long revision()
    {
        return revision.get();
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
                .add(new AgentRule(UUID.randomUUID().toString(), text, AgentRule.Mode.OBSERVE, false, clock.instant(), Optional.empty(), 0))
                .build());
    }

    /** A changed header starts a new observation record; editing an active rule always withdraws its authorization. */
    public synchronized List<AgentRuleView> update(String id, String text)
    {
        RuleDefinition changed = RuleText.parse(text);
        return replace(id, rule -> RuleText.parse(rule.text()).sameTrigger(changed)
                ? new AgentRule(id, text, rule.mode() == AgentRule.Mode.AUTO ? AgentRule.Mode.OBSERVE : rule.mode(),
                        rule.paused(), rule.recordSince(), rule.keptObserving(), rule.version() + 1)
                : new AgentRule(id, text, AgentRule.Mode.OBSERVE, rule.paused(), clock.instant(), Optional.empty(), rule.version() + 1));
    }

    public synchronized List<AgentRuleView> pause(String id, boolean paused)
    {
        return replace(id, rule -> new AgentRule(id, rule.text(), rule.mode(), paused, rule.recordSince(), rule.keptObserving(), rule.version() + 1));
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

    private AgentRule rule(String id)
    {
        return saved().stream().filter(rule -> rule.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown rule: " + id));
    }

    private boolean movable(AgentRule rule)
    {
        List<RuleRun> record = record(rule);
        AgentRuleView view = AgentRuleView.of(rule, record);
        long answeredSince = rule.keptObserving()
                .map(at -> record.stream().filter(run -> run.authorization().isEmpty() && run.verdict() != RuleRun.Verdict.PENDING && run.occurredAt().isAfter(at)).count())
                .orElse((long) view.answered());
        return rule.mode() == AgentRule.Mode.OBSERVE && !rule.paused() && view.rule().suggests() && answeredSince >= MOVE_UP_RUNS
                && view.answered() >= MOVE_UP_RUNS && view.agreed() * 100 >= MOVE_UP_PERCENT * view.answered();
    }

    private List<AgentRule> saved()
    {
        return store.get(SETTINGS_KEY).map(this::decode).orElse(ImmutableList.of());
    }

    private List<AgentRuleView> save(List<AgentRule> rules)
    {
        try {
            store.put(SETTINGS_KEY, mapper.writeValueAsString(rules));
            revision.incrementAndGet();
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
