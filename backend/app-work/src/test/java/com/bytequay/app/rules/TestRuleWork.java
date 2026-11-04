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
package com.bytequay.app.rules;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.app.agenda.ItemTaskStarter;
import com.bytequay.app.review.AgentReviewService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.rules.AgentRule;
import com.bytequay.app.service.rules.AgentRuleView;
import com.bytequay.app.service.rules.InMemoryRuleRunStore;
import com.bytequay.app.service.rules.RuleText;
import com.bytequay.app.workspace.TaskPullRequestOwnership;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RuleRun;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.hosting.HostingServer;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.MoreExecutors;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static com.bytequay.agent.core.AgentTaskFixtures.created;
import static com.bytequay.core.domain.RuleRun.Verdict.AGREED;
import static com.bytequay.core.domain.RuleRun.Verdict.DISAGREED;
import static com.bytequay.core.domain.RuleRun.Verdict.EDITED;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestRuleWork
{
    private static final Instant AT = Instant.parse("2026-11-03T08:00:00Z");
    private static final String REVIEW_RULE = """
            ---
            name: Review PRs that need me
            when: review-requested
            does: review
            agrees-when: reviewed
            ---
            Review it for correctness first.
            """;
    private static final String TASK_RULE = """
            ---
            name: Start work on assigned issues
            when: issue-assigned
            does: task
            agrees-when: started-task
            ---
            Draft a plan.
            """;

    private final AgentReviewService reviewer = mock(AgentReviewService.class);
    private final PullRequestService reads = mock(PullRequestService.class);
    private final PullRequestStore pullRequests = mock(PullRequestStore.class);
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final ItemTaskStarter starter = mock(ItemTaskStarter.class);
    private final InMemoryRuleRunStore runs = new InMemoryRuleRunStore();
    private final RuleWork work = new RuleWork(reviewer, mock(AgentReviewStore.class), reads, pullRequests, workspaces, starter, mock(AgentTaskStore.class),
            mock(AgentTurnStore.class), mock(TaskPullRequestOwnership.class), runs, MoreExecutors.newDirectExecutorService());
    private final RuleRun run = RuleRun.suggested("rule-1", "pr:21:requested:0", "acme/warehouse#21", "Fix the importer", AT).started("review:review-21");

    @Test
    void aReviewAgreesWhenAFindingIsPostedAsWrittenAndIsEditedWhenTheUserReviewsThemselvesAfterIt()
    {
        Optional<Instant> finished = Optional.of(AT.plusSeconds(600));
        assertThat(RuleWork.judge(run, new RuleWork.ReviewSeen(true, true, 3, finished, true, Optional.empty())).verdict()).isEqualTo(AGREED);
        assertThat(RuleWork.judge(run, new RuleWork.ReviewSeen(true, false, 3, finished, true, Optional.of(AT.plusSeconds(900)))).verdict()).isEqualTo(EDITED);
        assertThat(RuleWork.judge(run, new RuleWork.ReviewSeen(true, false, 3, finished, true, Optional.of(AT.plusSeconds(300))))).isEqualTo(run);
        assertThat(RuleWork.judge(run, new RuleWork.ReviewSeen(false, false, 3, finished, true, Optional.empty())).verdict()).isEqualTo(DISAGREED);
        assertThat(RuleWork.judge(run, new RuleWork.ReviewSeen(true, false, 0, Optional.empty(), false, Optional.empty()))).isEqualTo(run);
        assertThat(RuleWork.judge(run, new RuleWork.ReviewSeen(true, false, 0, finished, true, Optional.empty())).skipped()).contains("Found nothing");
        assertThat(RuleWork.judge(run, new RuleWork.ReviewSeen(true, false, 0, finished, false, Optional.empty())).skipped()).contains("The review did not finish");
    }

    @Test
    void aTaskAgreesWhenKeptAsItIsIsEditedWhenSteeredAndDisagreesWhenDropped()
    {
        assertThat(RuleWork.judge(run, new RuleWork.TaskSeen(false, false, true)).verdict()).isEqualTo(AGREED);
        assertThat(RuleWork.judge(run, new RuleWork.TaskSeen(false, true, true)).verdict()).isEqualTo(EDITED);
        assertThat(RuleWork.judge(run, new RuleWork.TaskSeen(true, true, true)).verdict()).isEqualTo(DISAGREED);
        assertThat(RuleWork.judge(run, new RuleWork.TaskSeen(false, false, false))).isEqualTo(run);
    }

    @Test
    void startsALocalCommitTaskForAnAssignedIssueUnderAnIdItCanFindAgain()
    {
        RuleRun issue = RuleRun.suggested("rule-2", "issue:900:github.com", "acme/warehouse#12", "Fix the importer", AT);
        String key = "rule:rule-2:issue:900:github.com";
        TaskId taskId = new TaskId(UUID.nameUUIDFromBytes(key.getBytes(UTF_8)));
        ItemTaskStarter.Start start = new ItemTaskStarter.Start("ws1", "Implement Fix the importer", Optional.empty(), Optional.empty(), Optional.empty());
        AgentTask task = created(taskId, Optional.of("ws1"), true, "Fix the importer", new AgentLaneId("claude"), new RepositoryIdentity("acme", "warehouse"),
                Path.of("/tmp/rule-work"), AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), AT);
        when(starter.item("issue:acme/warehouse#12")).thenReturn(start);
        when(starter.start(any(), any(), any(), anyString(), any(DevelopmentAutomation.class)))
                .thenReturn(Optional.of(new ItemTaskStarter.Started(task, true, "Claude", DevelopmentAutomation.DEFAULT)));

        work.start(view("rule-2", TASK_RULE), issue);
        verify(starter).start(eq(start.followedBy("\n\nDraft a plan.")), eq(Optional.empty()), eq(taskId), eq(key), eq(DevelopmentAutomation.DEFAULT));
        assertThat(runs.findByRule("rule-2")).containsExactly(issue.started("task:" + taskId));
        assertThat(work.starting(issue)).isFalse();

        when(starter.start(any(), any(), any(), anyString(), any(DevelopmentAutomation.class)))
                .thenReturn(Optional.of(new ItemTaskStarter.Started(task, false, "Claude", DevelopmentAutomation.DEFAULT)));
        work.start(view("rule-2", TASK_RULE), issue);
        assertThat(runs.findByRule("rule-2")).containsExactly(issue.skip("Already has a task"));
        when(starter.start(any(), any(), any(), anyString(), any(DevelopmentAutomation.class))).thenReturn(Optional.empty());
        work.start(view("rule-2", TASK_RULE), issue);
        assertThat(runs.findByRule("rule-2")).containsExactly(issue.skip("The agent is not ready"));
    }

    @Test
    void aReviewStartsOnlyInAWatchedRepositoryAndSaysWhyItCouldNotStart()
    {
        RuleRun requested = RuleRun.suggested("rule-1", "pr:21:requested:0", "acme/warehouse#21", "Fix the importer", AT);
        when(pullRequests.findById(21)).thenReturn(Optional.of(pullRequest()));
        when(workspaces.findAll()).thenReturn(ImmutableList.of());
        work.start(view("rule-1", REVIEW_RULE), requested);
        assertThat(runs.findByRule("rule-1")).containsExactly(requested.skip("Not in a watched repository"));

        when(workspaces.findAll()).thenReturn(ImmutableList.of(workspace()));
        when(reviewer.find(anyString(), anyString(), anyInt())).thenReturn(Optional.empty());
        when(reads.prepareRead("ws1", "acme/warehouse", 21)).thenThrow(new IllegalStateException("GitHub is offline"));
        work.start(view("rule-1", REVIEW_RULE), requested);
        assertThat(runs.findByRule("rule-1")).containsExactly(requested.skip("Could not start: GitHub is offline"));
    }

    private static AgentRuleView view(String id, String text)
    {
        return new AgentRuleView(id, text, AgentRule.Mode.SUGGEST, false, AT, Optional.empty(), RuleText.parse(text), 0, 0, 0, 0);
    }

    private static PullRequest pullRequest()
    {
        return new PullRequest(21, "acme/warehouse", 21, "Fix the importer", "octocat", "https://github.com/acme/warehouse/pull/21", AT, AT,
                PullRequest.Origin.REVIEW_REQUESTED, ImmutableList.of(), ImmutableMap.of(), false, null, null, null, ImmutableList.of(), PullRequestDetail.CiStatus.NONE,
                1, 0, 0, "open", null, null, true, "clean", AT, "feature", null, null, AT, null);
    }

    private static Workspace workspace()
    {
        RepoRef repository = RepoRef.of("acme", "warehouse");
        return new Workspace("ws1", "Warehouse", 1, repository, Optional.empty(), Optional.empty(), 1, repository, Workspace.Topology.DIRECT, "main",
                "https://github.com/acme/warehouse.git", Optional.empty(), Optional.empty(), "/tmp/rule-work/ws1", Optional.of(AT), Optional.of(AT), 0, AT, AT,
                HostingServer.GITHUB_COM);
    }
}
