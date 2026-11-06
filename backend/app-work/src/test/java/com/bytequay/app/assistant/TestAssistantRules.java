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
package com.bytequay.app.assistant;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.ReviewTool;
import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.rules.AgentRuleService;
import com.bytequay.app.service.rules.InMemoryRuleRunStore;
import com.bytequay.core.backport.BackportChain;
import com.bytequay.core.backport.BackportChainStore;
import com.bytequay.core.domain.AssistantItem;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.HandledAction;
import com.bytequay.core.domain.HostedIssueSource;
import com.bytequay.core.domain.IssueTask;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.StoredPrDiff;
import com.bytequay.core.store.IssueDevelopmentStore;
import com.bytequay.core.store.JiraIssueTaskStore;
import com.bytequay.core.store.PrDiffStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.PullRequestTaskStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.bytequay.agent.core.AgentTaskFixtures.created;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestAssistantRules
{
    private static final Instant NOW = Instant.parse("2026-11-06T09:00:00Z");
    private static final String CONVERSATION = "0c6a3f0e-5b1d-4c39-9d43-7f1f3a2b9e10";
    private static final String STORAGE = rule("Review pull requests that touch storage", "review-requested", "review", "reviewed", "paths: [src/storage/**]");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AgentRuleService rules = new AgentRuleService(new InMemoryAppSettingsStore(), new InMemoryRuleRunStore(), mapper, clock);
    private final PullRequestStore pullRequests = mock(PullRequestStore.class);
    private final PrDiffStore diffs = mock(PrDiffStore.class);
    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final AgentTurnStore turns = mock(AgentTurnStore.class);
    private final IssueDevelopmentStore issueTasks = mock(IssueDevelopmentStore.class);
    private final BackportChainStore backports = mock(BackportChainStore.class);
    private final CredentialService credentials = mock(CredentialService.class);
    private final AssistantRules assistantRules = new AssistantRules(rules, pullRequests, diffs, tasks, turns, issueTasks, mock(JiraIssueTaskStore.class),
            mock(PullRequestTaskStore.class), backports, credentials, mapper, clock);
    private final List<AssistantItem> shown = new ArrayList<>();

    @Test
    void takesOnlyAMessageThatStartsWithTheCommand()
    {
        assertThat(AssistantRules.command("@rules")).isTrue();
        assertThat(AssistantRules.command("  @RULES  mostly my reviews")).isTrue();
        assertThat(AssistantRules.command("@rules\nand backports")).isTrue();
        assertThat(AssistantRules.command("Tell me about @rules")).isFalse();
        assertThat(AssistantRules.command("@rulesmith")).isFalse();

        AssistantItem question = AssistantItem.user(CONVERSATION, NOW, "what's next?", ImmutableList.of(), Optional.empty(), "k1");
        AssistantItem reply = AssistantItem.reply(CONVERSATION, NOW, "@rules writes rules", ImmutableMap.of());
        assertThat(AssistantRules.asked(ImmutableList.of(question, reply))).isFalse();
        assertThat(AssistantRules.asked(ImmutableList.of(question, reply, AssistantItem.user(CONVERSATION, NOW, "@rules", ImmutableList.of(), Optional.empty(), "k2"))))
                .isTrue();
    }

    @Test
    void briefOffersEveryEventForWorkButNoPower()
    {
        assertThat(AssistantRules.BRIEF)
                .contains("at most 3", "- review-requested: does review or task; agrees-when reviewed", "- issue-assigned: does task; agrees-when started-task",
                        "- pull-request-merged: does backport or task; agrees-when opened-backport")
                .doesNotContain("pull-request-ready", "permission-requested");
    }

    @Test
    void offersOnlyARuleTheParserReadsAndNoSavedRuleStarts()
            throws Exception
    {
        ReviewTool tool = assistantRules.tool(message(), shown::add);
        assertThat(call(tool, "{\"op\":\"offer\",\"rule\":\"review my PRs\",\"why\":\"Often\"}"))
                .isEqualTo(reply("error", "A rule starts with a line of three dashes, then its header"));
        assertThat(call(tool, "{\"op\":\"offer\",\"rule\":" + mapper.writeValueAsString(STORAGE) + "}"))
                .isEqualTo(reply("error", "offer needs why: one line of the data behind the rule"));
        assertThat(call(tool, "{\"op\":\"offer\",\"ref\":\"pr:acme/warehouse#1\"}")).isEqualTo(reply("error", "offer takes no ref"));
        assertThat(call(tool, "{\"op\":\"save\"}")).isEqualTo(reply("error", "op must be one of history, offer"));
        assertThat(shown).isEmpty();

        assertThat(call(tool, offer(STORAGE, "You reviewed 9 of the 11 pull requests that touched src/storage")))
                .isEqualTo(reply("done", "Showed “Review pull requests that touch storage”; nothing is saved until the user chooses Save"));
        assertThat(shown).singleElement().satisfies(item -> {
            assertThat(item.kind()).isEqualTo(AssistantItem.Kind.RULE);
            assertThat(item.text()).isEqualTo("You reviewed 9 of the 11 pull requests that touched src/storage");
            assertThat(item.rule()).contains(STORAGE.strip());
        });

        rules.add(STORAGE);
        assertThat(call(tool, offer(STORAGE.replace("name: Review pull requests that touch storage", "name: Storage reviews"), "Again")))
                .isEqualTo(reply("refused", "The saved rule “Review pull requests that touch storage” already does this"));
        assertThat(call(tool, offer(rule("Start assigned issues", "issue-assigned", "task", "started-task", ""), "You started 6 of 8"))).contains("\"done\"");
        assertThat(call(tool, offer(rule("Backport merged fixes", "pull-request-merged", "backport", "opened-backport", ""), "You backported 4"))).contains("\"done\"");
        assertThat(call(tool, offer(rule("Rebase my branches", "pull-request-behind", "rebase", "updated-branch", ""), "You rebased 5")))
                .isEqualTo(reply("refused", "Offer at most 3 rules in one reply"));
        assertThat(shown).hasSize(3);
        assertThat(rules.list()).hasSize(1);
    }

    @Test
    void readsTheUsersOwnWorkOfTheLastThirtyDays()
            throws Exception
    {
        when(credentials.cachedLogin()).thenReturn(Optional.of("viewer"));
        PullRequest reviewed = pullRequest(1, "octocat", days(2), days(1), HandledAction.APPROVED, ImmutableList.of("storage"));
        PullRequest waiting = pullRequest(2, "octocat", days(3), null, null, ImmutableList.of());
        PullRequest unasked = pullRequest(3, "hubot", null, days(5), HandledAction.COMMENTED, ImmutableList.of());
        PullRequest old = pullRequest(4, "octocat", days(40), null, null, ImmutableList.of());
        PullRequest mergedInApp = pullRequest(6, "octocat", null, days(2), HandledAction.MERGED, ImmutableList.of());
        PullRequest mine = merged(pullRequest(5, "viewer", null, null, null, ImmutableList.of("backport-1.2")), days(4));
        when(pullRequests.findAttentionCandidates()).thenReturn(ImmutableList.of(waiting, reviewed, unasked, old, mergedInApp, mine));
        when(diffs.find(anyLong(), any(), any())).thenReturn(Optional.empty());
        when(diffs.find(1, null, null)).thenReturn(Optional.of(new StoredPrDiff(ImmutableList.of(file("src/storage/io/Pages.java"), file("src/storage/Store.java"),
                file("docs/storage.md"), file("README.md")), ImmutableList.of(), ImmutableMap.of(), Optional.empty(), Optional.empty(), Optional.empty())));

        AgentTask fix = task("Fix the importer", true, days(1));
        AgentTask byRule = task("Review acme/warehouse#2", true, days(2));
        when(tasks.listRecent(200)).thenReturn(ImmutableList.of(fix, byRule, task("Context build", false, days(1)), task("Old work", true, days(31))));
        when(turns.list(any())).thenReturn(ImmutableList.of());
        AgentTurn ruleTurn = mock(AgentTurn.class, withSettings().mockMaker(MockMakers.INLINE));
        when(ruleTurn.idempotencyKey()).thenReturn("rule:r1:pr:2:head:1");
        when(turns.list(byRule.id())).thenReturn(ImmutableList.of(ruleTurn));
        when(issueTasks.findByTaskId(fix.id().toString())).thenReturn(Optional.of(new IssueTask(fix.id().toString(), "ws1",
                new HostedIssueSource("github.com", 900, "acme/warehouse", 12, "https://github.com/acme/warehouse/issues/12", "Importer crash", "", days(3)),
                Optional.of("@fix the importer crash"), days(1))));
        BackportChain chain = backport(new PullRequestRef("acme", "warehouse", 5), "release-1.2");
        when(backports.list()).thenReturn(ImmutableList.of(chain));
        rules.add(STORAGE);

        AssistantRules.History history = assistantRules.history();

        assertThat(history.since()).isEqualTo(days(30));
        assertThat(history.reviews()).containsExactly(
                new AssistantRules.Review("acme/warehouse#1", "Change 1", Optional.of("octocat"), ImmutableList.of("storage"),
                        Optional.of(ImmutableList.of("src/storage/", "docs/")), true, Optional.of(days(1)), Optional.of("approved")),
                new AssistantRules.Review("acme/warehouse#2", "Change 2", Optional.of("octocat"), ImmutableList.of(), Optional.empty(), true, Optional.empty(), Optional.empty()),
                new AssistantRules.Review("acme/warehouse#3", "Change 3", Optional.of("hubot"), ImmutableList.of(), Optional.empty(), false, Optional.of(days(5)),
                        Optional.of("commented")));
        assertThat(history.counts()).containsExactly(
                new AssistantRules.Count("repository", "acme/warehouse", 3, 2, 2),
                new AssistantRules.Count("author", "octocat", 2, 2, 1),
                new AssistantRules.Count("author", "hubot", 1, 0, 1),
                new AssistantRules.Count("label", "storage", 1, 1, 1),
                new AssistantRules.Count("folder", "src/storage/", 1, 1, 1),
                new AssistantRules.Count("folder", "docs/", 1, 1, 1));
        assertThat(history.tasks()).containsExactly(new AssistantRules.Started("issue", Optional.of("acme/warehouse#12"), "owner/repository", days(1), "@fix the importer crash"));
        assertThat(history.merged()).containsExactly(new AssistantRules.Merged("acme/warehouse#5", "Change 5", ImmutableList.of("backport-1.2"), days(4),
                ImmutableList.of("acme/warehouse release-1.2")));
        assertThat(history.rules()).containsExactly(new AssistantRules.Saved("Review pull requests that touch storage", "review-requested", "review",
                ImmutableList.of("acme/warehouse"), ImmutableList.of("src/storage/**"), ImmutableList.of(), ImmutableList.of(), "observe", false));

        JsonNode read = mapper.readTree(assistantRules.tool(message(), shown::add).call().apply("{\"op\":\"history\"}"));
        assertThat(read.path("reviews").get(0).path("folders").get(0).asText()).isEqualTo("src/storage/");
        assertThat(read.path("tasks").get(0).path("words").asText()).isEqualTo("@fix the importer crash");
        assertThat(shown).isEmpty();
    }

    private static String rule(String name, String when, String does, String agrees, String extra)
    {
        return """
                ---
                name: %s
                when: %s
                repositories: [acme/warehouse]
                %s
                does: %s
                agrees-when: %s
                ---
                Do the work the way I would.
                """.formatted(name, when, extra, does, agrees);
    }

    private String offer(String rule, String why)
            throws Exception
    {
        return mapper.writeValueAsString(ImmutableMap.of("op", "offer", "rule", rule, "why", why));
    }

    private static String call(ReviewTool tool, String arguments)
    {
        return tool.call().apply(arguments);
    }

    private String reply(String outcome, String message)
            throws Exception
    {
        return mapper.writeValueAsString(ImmutableMap.of("outcome", outcome, "message", message));
    }

    private static AssistantItem message()
    {
        return AssistantItem.user(CONVERSATION, NOW, "@rules", ImmutableList.of(), Optional.empty(), "key");
    }

    private static Instant days(int ago)
    {
        return NOW.minus(Duration.ofDays(ago));
    }

    private static DiffFile file(String name)
    {
        return new DiffFile(name, "modified", 1, 0, "");
    }

    private static AgentTask task(String title, boolean interactive, Instant at)
    {
        return created(TaskId.random(), Optional.of("ws1"), interactive, title, new AgentLaneId("claude"), new RepositoryIdentity("owner", "repository"),
                Path.of("/tmp/assistant-task"), AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), at);
    }

    private static PullRequest pullRequest(long id, String author, Instant requested, Instant reviewed, HandledAction answer, List<String> labels)
    {
        return new PullRequest(id, "acme/warehouse", (int) id, "Change " + id, author, "https://github.com/acme/warehouse/pull/" + id, days(10), NOW,
                PullRequest.Origin.OTHER, labels, ImmutableMap.of(), false, null, reviewed, answer, ImmutableList.of(), PullRequestDetail.CiStatus.NONE,
                1, 0, 0, "open", null, null, true, "clean", days(10), "feature", null, null, requested, null);
    }

    private static PullRequest merged(PullRequest pr, Instant at)
    {
        return new PullRequest(pr.id(), pr.repo(), pr.number(), pr.title(), pr.author(), pr.htmlUrl(), pr.createdAt(), pr.updatedAt(), PullRequest.Origin.AUTHORED,
                pr.labels(), pr.labelColors(), false, null, null, null, ImmutableList.of(), pr.ciStatus(), 1, 0, 0, "closed", at, at, null, null, pr.headPushedAt(),
                pr.headRef(), null, null, null, null);
    }

    private static BackportChain backport(PullRequestRef source, String branch)
    {
        BackportChain.Route route = mock(BackportChain.Route.class, withSettings().mockMaker(MockMakers.INLINE));
        when(route.repository()).thenReturn(new RepoRef("acme", "warehouse"));
        when(route.branch()).thenReturn(branch);
        BackportChain.Hop hop = mock(BackportChain.Hop.class, withSettings().mockMaker(MockMakers.INLINE));
        when(hop.route()).thenReturn(route);
        BackportChain chain = mock(BackportChain.class, withSettings().mockMaker(MockMakers.INLINE));
        when(chain.sourcePullRequest()).thenReturn(Optional.of(source));
        when(chain.hops()).thenReturn(ImmutableList.of(hop, hop));
        return chain;
    }
}
