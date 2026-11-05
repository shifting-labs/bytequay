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
import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.bytequay.core.domain.RuleRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static com.bytequay.app.service.rules.TestRuleText.NETWORK_PERMISSION;
import static com.bytequay.app.service.rules.TestRuleText.STORAGE_REVIEW;
import static com.bytequay.core.domain.RuleRun.Verdict.AGREED;
import static com.bytequay.core.domain.RuleRun.Verdict.DISAGREED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestAgentRuleService
{
    private static final Instant SAVED = Instant.parse("2026-11-03T08:00:00Z");
    private static final Instant EDITED = SAVED.plusSeconds(3600);

    private final InMemoryAppSettingsStore store = new InMemoryAppSettingsStore();
    private final InMemoryRuleRunStore runs = new InMemoryRuleRunStore();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void activationBindsTheReviewedRevisionAndEveryEditWithdrawsItsAuthority()
    {
        AgentRuleService rules = service(SAVED);
        AgentRuleView draft = rules.add(NETWORK_PERMISSION).getFirst();
        assertThat(rules.authorization(draft.id(), draft.version())).isEmpty();
        AgentRuleView active = rules.activate(draft.id(), draft.version()).getFirst();
        assertThat(active.mode()).isEqualTo(AgentRule.Mode.AUTO);
        assertThat(active.version()).isEqualTo(draft.version() + 1);
        assertThat(service(EDITED).authorization(active.id(), active.version())).hasValueSatisfying(authorization -> {
            assertThat(authorization.ruleId()).isEqualTo(active.id());
            assertThat(authorization.ruleName()).isEqualTo(active.rule().name());
            assertThat(authorization.ruleVersion()).isEqualTo(active.version());
        });
        assertThatThrownBy(() -> rules.activate(draft.id(), draft.version())).hasMessageContaining("rule changed");
        rules.pause(active.id(), true);
        assertThat(rules.authorization(active.id(), active.version())).isEmpty();
        AgentRuleView resumed = rules.pause(active.id(), false).getFirst();
        assertThat(rules.authorization(active.id(), resumed.version())).isPresent();
        AgentRuleView edited = rules.update(active.id(), NETWORK_PERMISSION.replace("selected repository", "current repository")).getFirst();
        assertThat(edited.mode()).isEqualTo(AgentRule.Mode.OBSERVE);
        assertThat(rules.authorization(edited.id(), edited.version())).isEmpty();
        assertThat(edited.recordSince()).isEqualTo(SAVED);
    }

    @Test
    void permissionRulesRequireExactHostsOperationsAndRepositoryAndDoNotLearnAuthority()
    {
        AgentRuleService rules = service(SAVED);
        AgentRuleView draft = rules.add(NETWORK_PERMISSION).getFirst();
        ProviderApprovalScope network = new ProviderApprovalScope(ProviderApprovalScope.Operation.NETWORK_ACCESS, ImmutableList.of("api.github.com"), Optional.empty());
        assertThat(rules.authorizePermission(network, "acme/warehouse")).isEmpty();
        answer(draft.id(), 0, 12, AGREED);
        assertThat(rules.authorizePermission(network, "acme/warehouse")).isEmpty();
        AgentRuleView active = rules.activate(draft.id(), draft.version()).getFirst();
        assertThat(rules.authorizePermission(network, "acme/warehouse")).isPresent();
        assertThat(rules.authorizePermission(network, "acme/other")).isEmpty();
        assertThat(rules.authorizePermission(new ProviderApprovalScope(ProviderApprovalScope.Operation.NETWORK_ACCESS,
                ImmutableList.of("api.github.com", "other.example"), Optional.empty()), "acme/warehouse")).isEmpty();
        assertThat(rules.authorizePermission(new ProviderApprovalScope(ProviderApprovalScope.Operation.NETWORK_ACCESS,
                ImmutableList.of("api.github.com.attacker.example"), Optional.empty()), "acme/warehouse")).isEmpty();
        assertThat(rules.authorizePermission(new ProviderApprovalScope(ProviderApprovalScope.Operation.GITHUB_READ,
                ImmutableList.of("api.github.com"), Optional.of("acme/warehouse")), "acme/warehouse")).isEmpty();
        rules.pause(active.id(), true);
        assertThat(rules.authorizePermission(network, "acme/warehouse")).isEmpty();
    }

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
        store.put("agent.rules", mapper.writeValueAsString(ImmutableList.of(new AgentRule("rule-1", STORAGE_REVIEW, AgentRule.Mode.SUGGEST, true, SAVED, Optional.empty(), 0))));
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
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not merge");
        assertThatThrownBy(() -> rules.update(id, "not a rule")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rules.pause("missing", true)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown rule");
        assertThat(rules.remove(id)).isEmpty();
        assertThat(rules.list()).isEmpty();
    }

    @Test
    void theRecordCountsOnlyRunsSinceItBegan()
    {
        AgentRuleService rules = service(SAVED);
        String id = rules.add(STORAGE_REVIEW).getFirst().id();
        RuleRun before = RuleRun.observed(id, "pr:1:head:1", "acme/warehouse#1", "Before", SAVED.minusSeconds(60)).decide(AGREED);
        RuleRun agreed = RuleRun.observed(id, "pr:2:head:1", "acme/warehouse#2", "Agreed", SAVED.plusSeconds(60)).decide(AGREED);
        RuleRun disagreed = RuleRun.observed(id, "pr:3:head:1", "acme/warehouse#3", "Disagreed", SAVED.plusSeconds(120)).decide(DISAGREED);
        RuleRun pending = RuleRun.observed(id, "pr:4:head:1", "acme/warehouse#4", "Pending", SAVED.plusSeconds(180));
        for (RuleRun run : ImmutableList.of(before, agreed, disagreed, pending)) {
            runs.save(run);
        }

        AgentRuleView view = rules.list().getFirst();
        assertThat(view.runs()).isEqualTo(3);
        assertThat(view.agreed()).isEqualTo(1);
        assertThat(view.disagreed()).isEqualTo(1);
        assertThat(rules.record(id)).containsExactly(pending, disagreed, agreed, before);
        assertThat(rules.record("missing")).isEmpty();
        rules.remove(id);
        assertThat(rules.record(id)).containsExactly(pending, disagreed, agreed, before);
    }

    @Test
    void aRuleMovesUpOnlyWhenItsRecordAllowsAndAsksAgainAfterTenMoreRunsWhenKeptObserving()
    {
        AgentRuleService rules = service(SAVED);
        String id = rules.add(STORAGE_REVIEW).getFirst().id();
        answer(id, 0, 8, AGREED);
        answer(id, 8, 1, DISAGREED);
        assertThat(rules.movable()).isEmpty();
        assertThatThrownBy(() -> rules.moveUp(id)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not let it move up yet");

        answer(id, 9, 2, AGREED);
        AgentRuleView ready = rules.movable().getFirst();
        assertThat(ready.agreed()).isEqualTo(10);
        assertThat(ready.answered()).isEqualTo(11);

        AgentRuleService later = service(SAVED.plusSeconds(3600));
        later.keepObserving(id);
        assertThat(later.movable()).isEmpty();
        answer(id, 3700, 10, AGREED);
        assertThat(later.movable()).extracting(AgentRuleView::id).containsExactly(id);
        assertThat(later.moveUp(id).getFirst().mode()).isEqualTo(AgentRule.Mode.SUGGEST);
        assertThat(later.movable()).isEmpty();

        AgentRuleView down = service(SAVED.plusSeconds(7200)).moveDown(id).getFirst();
        assertThat(down.mode()).isEqualTo(AgentRule.Mode.OBSERVE);
        assertThat(down.recordSince()).isEqualTo(SAVED);
        assertThat(down.keptObserving()).contains(SAVED.plusSeconds(7200));
        assertThat(later.movable()).isEmpty();
    }

    @Test
    void aRuleWhoseWorkCannotBeSuggestedYetKeepsObserving()
    {
        AgentRuleService rules = service(SAVED);
        String id = rules.add(STORAGE_REVIEW.replace("does: review", "does: task")).getFirst().id();
        answer(id, 0, 10, AGREED);
        assertThat(rules.movable()).isEmpty();
    }

    @Test
    void dismissingASuggestionDisagreesWithItOnce()
    {
        AgentRuleService rules = service(SAVED);
        String id = rules.add(STORAGE_REVIEW).getFirst().id();
        runs.save(RuleRun.suggested(id, "pr:7:head:1", "acme/warehouse#7", "Fix the flaky test", SAVED.plusSeconds(60)).started("review:review-1"));
        runs.save(RuleRun.observed(id, "pr:8:head:1", "acme/warehouse#8", "Add a cache", SAVED.plusSeconds(60)));

        rules.dismiss(id, "pr:7:head:1");
        assertThat(rules.record(id)).filteredOn(RuleRun::suggestion).singleElement().extracting(RuleRun::verdict).isEqualTo(DISAGREED);
        assertThatThrownBy(() -> rules.dismiss(id, "pr:7:head:1")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("waiting for you");
        assertThatThrownBy(() -> rules.dismiss(id, "pr:8:head:1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rules.dismiss(id, "pr:9:head:1")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown run");
    }

    @Test
    void savingARuleChangesTheRevision()
    {
        AgentRuleService rules = service(SAVED);
        long before = rules.revision();
        String id = rules.add(STORAGE_REVIEW).getFirst().id();
        rules.pause(id, true);
        assertThat(rules.revision()).isEqualTo(before + 2);
    }

    /** Saves {@code count} answered runs of the rule, a second apart, from {@code fromSecond} seconds after it was saved. */
    private void answer(String id, int fromSecond, int count, RuleRun.Verdict verdict)
    {
        for (int i = 0; i < count; i++) {
            int second = fromSecond + i + 1;
            runs.save(RuleRun.observed(id, "pr:" + second + ":head:1", "acme/warehouse#" + second, "Change " + second, SAVED.plusSeconds(second)).decide(verdict));
        }
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
        return new AgentRuleService(store, runs, mapper, Clock.fixed(now, ZoneOffset.UTC));
    }
}
