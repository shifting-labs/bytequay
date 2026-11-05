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

import com.bytequay.app.jira.JiraService;
import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.rules.AgentRuleService;
import com.bytequay.app.service.rules.InMemoryRuleRunStore;
import com.bytequay.app.workspace.WorkspaceHostingService;
import com.bytequay.core.backport.BackportChainStore;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.HandledAction;
import com.bytequay.core.domain.HostedIssueSource;
import com.bytequay.core.domain.IssueTask;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.RepositoryIssuePage;
import com.bytequay.core.domain.RuleRun;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.domain.StoredPrDiff;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.domain.Workspace.Topology;
import com.bytequay.core.hosting.HostingServer;
import com.bytequay.core.store.IssueDevelopmentStore;
import com.bytequay.core.store.JiraIssueTaskStore;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.core.store.PrDiffStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.WorkspaceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockMakers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static com.bytequay.core.domain.RuleRun.Verdict.AGREED;
import static com.bytequay.core.domain.RuleRun.Verdict.DISAGREED;
import static com.bytequay.core.domain.RuleRun.Verdict.EDITED;
import static com.bytequay.core.domain.RuleRun.Verdict.PENDING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestRuleObserver
{
    private static final Instant SAVED = Instant.parse("2026-11-03T08:00:00Z");
    private static final Workspace WAREHOUSE = workspace();

    private final MutableClock clock = new MutableClock(SAVED);
    private final InMemoryRuleRunStore runs = new InMemoryRuleRunStore();
    private final AgentRuleService rules = new AgentRuleService(new InMemoryAppSettingsStore(), runs, new ObjectMapper().findAndRegisterModules(), clock);
    private final PullRequestStore pullRequests = mock(PullRequestStore.class);
    private final PrDiffStore diffs = mock(PrDiffStore.class);
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final RepositoryIssueStore issues = mock(RepositoryIssueStore.class);
    private final IssueDevelopmentStore issueTasks = mock(IssueDevelopmentStore.class);
    private final JiraService jira = mock(JiraService.class);
    private final CredentialService credentials = mock(CredentialService.class);
    private final RuleWork work = mock(RuleWork.class);
    private final WorkspaceHostingService hosting = mock(WorkspaceHostingService.class);
    private final PrDetailStore details = mock(PrDetailStore.class);
    private final RuleObserver observer = new RuleObserver(rules, runs, work, pullRequests, diffs, workspaces, issues, issueTasks, jira, mock(JiraIssueTaskStore.class),
            mock(BackportChainStore.class), credentials, clock, details, mock(PullRequestService.class), mock(RuleLineOwnership.class), hosting);

    @BeforeEach
    void setUp()
    {
        when(workspaces.findAll()).thenReturn(ImmutableList.of(WAREHOUSE));
        when(credentials.cachedLogin()).thenReturn(Optional.of("viewer"));
        when(hosting.viewerLogin(WAREHOUSE.id())).thenReturn(Optional.of("viewer"));
        when(jira.attention()).thenReturn(ImmutableList.of());
    }

    @Test
    void activatingAnObservedRuleAuthorizesANewRunOnceForThatRevision()
    {
        String id = rule("""
                when: review-requested
                does: review
                agrees-when: reviewed
                """);
        PullRequest asked = pullRequest(7, "octocat", SAVED.plusSeconds(60), null, null, "clean");
        candidates(asked);
        tick(Duration.ofMinutes(2));
        assertThat(rules.record(id)).hasSize(1);

        rules.activate(id, rules.list().getFirst().version());
        when(work.starting(any())).thenReturn(true);
        tick(Duration.ofMinutes(1));
        tick(Duration.ofMinutes(1));

        assertThat(rules.record(id)).hasSize(2).filteredOn(run -> run.authorization().isPresent()).singleElement()
                .satisfies(run -> assertThat(run.authorization().orElseThrow().ruleVersion()).isEqualTo(rules.list().getFirst().version()));
        verify(work, times(1)).start(any(), any());
    }

    @Test
    void pausingARuleStillObservesAMergeAlreadyAccepted()
    {
        String id = rule("""
                when: pull-request-ready
                does: merge
                agrees-when: merged
                """);
        rules.activate(id, rules.list().getFirst().version());
        var authorization = rules.authorization(id, rules.list().getFirst().version()).orElseThrow();
        RuleRun queued = RuleRun.automatic(authorization, "pr:7:rule:1:ready:head", "acme/warehouse#7", "Change", SAVED)
                .started("pr:7").finished("QUEUED");
        runs.save(queued);
        rules.pause(id, true);

        tick(Duration.ofMinutes(1));

        verify(work).reconcileMerge(queued);
        verify(work, never()).start(any(), eq(queued));
    }

    @Test
    void aMergeInterruptedBeforeItsRunReceiptIsReconciledEvenAfterDeletingTheRule()
    {
        String id = rule("""
                when: pull-request-ready
                does: merge
                agrees-when: merged
                """);
        rules.activate(id, rules.list().getFirst().version());
        RuleRun starting = RuleRun.automatic(rules.authorization(id, rules.list().getFirst().version()).orElseThrow(),
                "pr:7:rule:1:ready:head", "acme/warehouse#7", "Change", SAVED);
        runs.save(starting);
        rules.remove(id);

        tick(Duration.ofMinutes(1));

        verify(work).reconcileMerge(starting);
        verify(work, never()).start(any(), any());
    }

    @Test
    void mergeCandidatesAndAtMeUseTheWatchedWorkspacesAccount()
    {
        String id = rule("""
                when: pull-request-ready
                authors: [@me]
                does: merge
                agrees-when: merged
                """);
        rules.activate(id, rules.list().getFirst().version());
        when(hosting.viewerLogin(WAREHOUSE.id())).thenReturn(Optional.of("workspace-viewer"));
        PullRequest own = pullRequest(7, "workspace-viewer", null, null, SAVED, "clean", PullRequestDetail.CiStatus.PASSING);
        PullRequest otherAccount = pullRequest(8, "viewer", null, null, SAVED, "clean", PullRequestDetail.CiStatus.PASSING);
        candidates(own, otherAccount);
        when(pullRequests.findById(7)).thenReturn(Optional.of(own));
        when(pullRequests.findById(8)).thenReturn(Optional.of(otherAccount));
        PrRawDetail raw = mock(PrRawDetail.class, withSettings().mockMaker(MockMakers.INLINE));
        when(raw.headSha()).thenReturn("head-1");
        StoredPrDetail detail = new StoredPrDetail(raw, ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), true);
        when(details.find(anyLong())).thenReturn(Optional.of(detail));

        tick(Duration.ofMinutes(1));

        assertThat(subjects(id)).containsExactly("acme/warehouse#7");
        verify(work, times(1)).start(any(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletingOrEditingARuleStillReconcilesItsAcceptedMerge(boolean deleted)
    {
        String id = rule("""
                when: pull-request-ready
                does: merge
                agrees-when: merged
                """);
        rules.activate(id, rules.list().getFirst().version());
        var authorization = rules.authorization(id, rules.list().getFirst().version()).orElseThrow();
        RuleRun queued = RuleRun.automatic(authorization, "pr:7:rule:1:ready:head", "acme/warehouse#7", "Change", SAVED)
                .started("pr:7").finished("QUEUED");
        runs.save(queued);
        if (deleted) {
            rules.remove(id);
        }
        else {
            rules.update(id, rules.list().getFirst().text().replace("pull-request-ready", "review-requested")
                    .replace("does: merge", "does: review").replace("agrees-when: merged", "agrees-when: reviewed"));
        }
        doAnswer(invocation -> {
            runs.save(queued.finished("MERGED"));
            return null;
        }).when(work).reconcileMerge(queued);

        tick(Duration.ofMinutes(1));

        verify(work).reconcileMerge(queued);
        verify(work, never()).start(any(), eq(queued));
        assertThat(runs.findByRule(id)).containsExactly(queued.finished("MERGED"));
    }

    @Test
    void aReviewRequestThatAwaitsYouRunsOncePerRoundAndAgreesWhenYouReview()
    {
        String id = rule("""
                when: review-requested
                does: review
                agrees-when: reviewed
                """);
        PullRequest asked = pullRequest(7, "octocat", SAVED.plusSeconds(60), null, null, "clean");
        PullRequest undated = new PullRequest(10, "acme/warehouse", 10, "Change 10", "octocat", "https://github.com/acme/warehouse/pull/10", SAVED, SAVED,
                PullRequest.Origin.REVIEW_REQUESTED, ImmutableList.of(), ImmutableMap.of(), false, null, null, null, ImmutableList.of(),
                PullRequestDetail.CiStatus.NONE, 1, 0, 0, "open", null, null, true, "clean", null, "feature", null, null, null, null);
        candidates(asked, pullRequest(8, "octocat", SAVED.minusSeconds(3600), null, null, "clean"), undated);
        when(pullRequests.findById(7)).thenReturn(Optional.of(asked));

        tick(Duration.ofMinutes(2));
        assertThat(rules.record(id)).containsExactly(RuleRun.observed(id, "pr:7:requested:0", "acme/warehouse#7", "Change 7", SAVED.plusSeconds(60)));

        // A later sync that finds the same request stamps it again.
        candidates(pullRequest(7, "octocat", SAVED.plusSeconds(300), null, null, "clean"));
        tick(Duration.ofMinutes(5));
        assertThat(rules.record(id)).hasSize(1);

        PullRequest reviewed = pullRequest(7, "octocat", SAVED.plusSeconds(300), SAVED.plusSeconds(600), null, "clean");
        candidates(reviewed);
        when(pullRequests.findById(7)).thenReturn(Optional.of(reviewed));
        tick(Duration.ofMinutes(5));
        assertThat(rules.record(id)).singleElement().extracting(RuleRun::verdict).isEqualTo(AGREED);

        candidates(pullRequest(7, "octocat", SAVED.plusSeconds(900), SAVED.plusSeconds(600), null, "clean"));
        tick(Duration.ofMinutes(5));
        assertThat(rules.record(id)).extracting(RuleRun::key)
                .containsExactly("pr:7:requested:" + SAVED.plusSeconds(600).getEpochSecond(), "pr:7:requested:0");
    }

    @Test
    void aPathRuleWaitsForStoredFilesReadsAMissOnceAndDisagreesWhenItsTimePasses()
    {
        String id = rule("""
                when: pull-request-updated
                paths: [src/storage/**]
                does: review
                agrees-when: reviewed
                """);
        Instant pushed = SAVED.plusSeconds(60);
        candidates(pullRequest(3, "octocat", null, null, pushed, "clean"), pullRequest(4, "octocat", null, null, pushed, "clean"),
                pullRequest(5, "octocat", null, null, pushed, "clean"), pullRequest(6, "viewer", null, null, pushed, "clean"));
        files(3, "src/storage/Blocks.java");
        files(4, "docs/readme.md");
        files(6, "src/storage/Blocks.java");
        when(pullRequests.findById(anyLong())).thenAnswer(call -> Optional.of(pullRequest(call.getArgument(0), "octocat", null, null, pushed, "clean")));

        tick(Duration.ofMinutes(2));
        tick(Duration.ofMinutes(1));
        assertThat(subjects(id)).containsExactly("acme/warehouse#3");
        verify(diffs, times(1)).find(4, null, null);

        files(5, "src/storage/Pages.java");
        tick(Duration.ofMinutes(1));
        assertThat(subjects(id)).containsExactlyInAnyOrder("acme/warehouse#3", "acme/warehouse#5");

        tick(Duration.ofDays(3));
        assertThat(rules.record(id)).extracting(RuleRun::verdict).containsOnly(DISAGREED);

        rules.pause(id, true);
        candidates(pullRequest(9, "octocat", null, null, clock.instant(), "clean"));
        files(9, "src/storage/Blocks.java");
        tick(Duration.ofMinutes(1));
        assertThat(subjects(id)).doesNotContain("acme/warehouse#9");
    }

    @Test
    void anIssueAssignedToYouWhileByteQuayWatchesAgreesWhenATaskStarts()
    {
        String id = rule("""
                when: issue-assigned
                does: task
                agrees-when: started-task
                """);
        when(issueTasks.findTask(eq("github.com"), anyLong())).thenReturn(Optional.empty());
        storedIssues(issue(900, 12, "octocat"), issue(902, 14, "Viewer"));
        tick(Duration.ofMinutes(1));
        storedIssues(issue(900, 12, "Viewer"), issue(902, 14, "Viewer"));
        tick(Duration.ofMinutes(1));
        assertThat(rules.record(id)).singleElement().satisfies(run -> {
            assertThat(run.subject()).isEqualTo("acme/warehouse#12");
            assertThat(run.verdict()).isEqualTo(PENDING);
        });

        when(issueTasks.findTask("github.com", 900)).thenReturn(Optional.of(new IssueTask("task-1", WAREHOUSE.id(),
                new HostedIssueSource("github.com", 900, "acme/warehouse", 12, "https://github.com/acme/warehouse/issues/12", "Issue 12", "", SAVED),
                Optional.empty(), clock.instant())));
        tick(Duration.ofMinutes(1));
        assertThat(rules.record(id)).singleElement().extracting(RuleRun::verdict).isEqualTo(AGREED);
    }

    @Test
    void yourPullRequestFallingBehindWhileByteQuayWatchesAgreesWhenTheBranchMoves()
    {
        String id = rule("""
                when: pull-request-behind
                does: rebase
                agrees-when: updated-branch
                """);
        Instant head = SAVED.minus(Duration.ofDays(1));
        candidates(pullRequest(11, "viewer", null, null, head, "clean"), pullRequest(13, "viewer", null, null, head, "dirty"),
                pullRequest(12, "octocat", null, null, head, "behind"));
        tick(Duration.ofMinutes(1));
        candidates(pullRequest(11, "viewer", null, null, head, "behind"), pullRequest(13, "viewer", null, null, head, "dirty"),
                pullRequest(12, "octocat", null, null, head, "behind"));
        when(pullRequests.findById(11)).thenReturn(Optional.of(pullRequest(11, "viewer", null, null, head, "behind")));

        tick(Duration.ofMinutes(1));
        assertThat(rules.record(id)).singleElement().satisfies(run -> {
            assertThat(run.subject()).isEqualTo("acme/warehouse#11");
            assertThat(run.occurredAt()).isEqualTo(SAVED.plus(Duration.ofMinutes(2)));
        });

        when(pullRequests.findById(11)).thenReturn(Optional.of(pullRequest(11, "viewer", null, null, clock.instant().plusSeconds(60), "clean")));
        tick(Duration.ofMinutes(2));
        assertThat(rules.record(id)).singleElement().extracting(RuleRun::verdict).isEqualTo(AGREED);
    }

    @Test
    void aRuleInSuggestStartsItsWorkUpToItsDailyLimit()
    {
        String id = suggesting("""
                when: review-requested
                does: review
                agrees-when: reviewed
                """);
        when(work.starting(any(RuleRun.class))).thenReturn(true);
        // A pull request outside the watched repositories cannot be reviewed, and so must not take one of the day's suggestions.
        when(work.cannotStart(any(), any())).thenAnswer(call -> call.<RuleRun>getArgument(1).key().startsWith("pr:20:")
                ? Optional.of("Not in a watched repository") : Optional.empty());
        candidates(pullRequest(20, "octocat", SAVED.plusSeconds(59), null, null, "clean"), pullRequest(21, "octocat", SAVED.plusSeconds(60), null, null, "clean"),
                pullRequest(22, "octocat", SAVED.plusSeconds(61), null, null, "clean"), pullRequest(23, "octocat", SAVED.plusSeconds(62), null, null, "clean"),
                pullRequest(24, "octocat", SAVED.plusSeconds(63), null, null, "clean"));

        tick(Duration.ofMinutes(2));
        List<RuleRun> suggestions = rules.record(id).stream().filter(RuleRun::suggestion).toList();
        assertThat(suggestions).hasSize(5);
        assertThat(suggestions).filteredOn(run -> run.skipped().isPresent()).extracting(run -> run.subject() + " " + run.skipped().orElseThrow())
                .containsExactlyInAnyOrder("acme/warehouse#20 Not in a watched repository", "acme/warehouse#24 Daily limit");
        verify(work, times(3)).start(any(), any());
        tick(Duration.ofMinutes(1));
        verify(work, times(3)).start(any(), any());
    }

    @Test
    void aSuggestionStillStartingWhenByteQuayStoppedIsSetAsideAndOthersTakeTheirWorksVerdict()
    {
        String id = suggesting("""
                when: review-requested
                does: review
                agrees-when: reviewed
                """);
        RuleRun stranded = RuleRun.suggested(id, "pr:31:requested:0", "acme/warehouse#31", "Change 31", SAVED.plusSeconds(60));
        RuleRun edited = RuleRun.suggested(id, "pr:32:requested:0", "acme/warehouse#32", "Change 32", SAVED.plusSeconds(60)).started("review:review-32");
        RuleRun ignored = RuleRun.suggested(id, "pr:33:requested:0", "acme/warehouse#33", "Change 33", SAVED.plusSeconds(60)).started("review:review-33");
        runs.save(stranded);
        runs.save(edited);
        runs.save(ignored);
        when(work.judge(any(RuleRun.class))).thenAnswer(call -> call.getArgument(0));
        when(work.judge(edited)).thenReturn(edited.decide(EDITED));
        candidates();

        tick(Duration.ofMinutes(2));
        assertThat(rules.record(id)).filteredOn(RuleRun::suggestion).extracting(RuleRun::key, RuleRun::verdict, run -> run.skipped().orElse(""))
                .containsExactlyInAnyOrder(tuple("pr:31:requested:0", PENDING, "ByteQuay stopped before the work started"), tuple("pr:32:requested:0", EDITED, ""),
                        tuple("pr:33:requested:0", PENDING, ""));
        tick(Duration.ofDays(3));
        assertThat(rules.record(id)).filteredOn(run -> run.key().equals("pr:33:requested:0")).singleElement().extracting(RuleRun::verdict).isEqualTo(DISAGREED);
        verify(work, never()).start(any(), any());
    }

    /** A rule moved up to Suggest the way the user does it: ten agreeing runs, then Move to Suggest. */
    private String suggesting(String header)
    {
        String id = rule(header);
        for (int i = 1; i <= 10; i++) {
            runs.save(RuleRun.observed(id, "pr:" + (900 + i) + ":requested:0", "acme/warehouse#" + (900 + i), "Change", SAVED.plusSeconds(i)).decide(AGREED));
        }
        rules.moveUp(id);
        return id;
    }

    private String rule(String header)
    {
        return rules.add("---\nname: Test rule\n" + header + "---\nDo the work.\n").getFirst().id();
    }

    private void tick(Duration later)
    {
        clock.advance(later);
        observer.observe();
    }

    private List<String> subjects(String id)
    {
        return rules.record(id).stream().map(RuleRun::subject).toList();
    }

    private void candidates(PullRequest... rows)
    {
        when(pullRequests.findAttentionCandidates()).thenReturn(ImmutableList.copyOf(rows));
    }

    private void storedIssues(RepositoryIssue... rows)
    {
        when(issues.findPage(any(WorkspaceStore.class), eq(WAREHOUSE), eq(0), anyInt())).thenReturn(new RepositoryIssuePage(1, WAREHOUSE.monitoredRepository(),
                ImmutableList.copyOf(rows), 0, Integer.MAX_VALUE, rows.length, false, Optional.empty()));
    }

    private void files(long prId, String file)
    {
        when(diffs.find(prId, null, null)).thenReturn(Optional.of(new StoredPrDiff(ImmutableList.of(new DiffFile(file, "modified", 1, 0, "+")), ImmutableList.of())));
    }

    private static PullRequest pullRequest(long id, String author, Instant requested, Instant reviewed, Instant head, String mergeState)
    {
        return pullRequest(id, author, requested, reviewed, head, mergeState, PullRequestDetail.CiStatus.NONE);
    }

    private static PullRequest pullRequest(long id, String author, Instant requested, Instant reviewed, Instant head, String mergeState, PullRequestDetail.CiStatus ci)
    {
        return new PullRequest(id, "acme/warehouse", (int) id, "Change " + id, author, "https://github.com/acme/warehouse/pull/" + id, SAVED, SAVED,
                PullRequest.Origin.OTHER, ImmutableList.of(), ImmutableMap.of(), false, null, reviewed, reviewed == null ? null : HandledAction.COMMENTED,
                requested == null ? ImmutableList.of() : ImmutableList.of("viewer"), ci,
                1, 0, 0, "open", null, null, true, mergeState, head, "feature", null, null, requested, null);
    }

    private static RepositoryIssue issue(long id, int number, String assignee)
    {
        return new RepositoryIssue(id, number, "Issue " + number, "", "octocat", "", "https://github.com/acme/warehouse/issues/" + number, ImmutableList.of(),
                ImmutableList.of(assignee), 0, SAVED, SAVED.plusSeconds(60));
    }

    private static Workspace workspace()
    {
        RepoRef repository = RepoRef.of("acme", "warehouse");
        return new Workspace("ws1", "Warehouse", 1, repository, Optional.empty(), Optional.empty(), 1, repository, Topology.DIRECT, "main",
                "https://github.com/acme/warehouse.git", Optional.empty(), Optional.empty(), "/tmp/bytequay-rules/ws1", Optional.of(SAVED), Optional.of(SAVED), 0,
                SAVED, SAVED, HostingServer.GITHUB_COM);
    }

    private static final class MutableClock
            extends Clock
    {
        private Instant now;

        private MutableClock(Instant now)
        {
            this.now = now;
        }

        void advance(Duration amount)
        {
            now = now.plus(amount);
        }

        @Override
        public ZoneId getZone()
        {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone)
        {
            return this;
        }

        @Override
        public Instant instant()
        {
            return now;
        }
    }
}
