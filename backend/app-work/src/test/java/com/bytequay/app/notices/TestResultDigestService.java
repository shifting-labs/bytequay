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
package com.bytequay.app.notices;

import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.spi.DevelopmentResultReport;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewAnchor;
import com.bytequay.core.review.ReviewCoverage;
import com.bytequay.core.review.ReviewEvidence;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.review.ReviewInvestigation;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static com.bytequay.app.review.ReviewTestData.openReview;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.MockMakers.INLINE;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestResultDigestService
{
    private static final Instant NOW = Instant.parse("2026-10-20T08:00:00Z");

    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final AgentReviewStore reviews = mock(AgentReviewStore.class);
    private final PullRequestStore pullRequests = mock(PullRequestStore.class);
    private final PullRequestService details = mock(PullRequestService.class);
    private final CredentialService credentials = mock(CredentialService.class);
    private final AgentTurnStore turns = mock(AgentTurnStore.class);
    private final ResultDigestService service = new ResultDigestService(workspaces, reviews, pullRequests, details, credentials, turns);

    @Test
    void aReviewSaysWhatItReadRanAndFoundAtTheRevisionItReviewed()
    {
        AgentReview review = openReview("review", "workspace", "owner/repo", OptionalInt.of(61), "review-task", Optional.of("dev-task"), NOW).withObservedHead("moved", NOW);
        when(reviews.findReview("review")).thenReturn(Optional.of(review));
        ReviewEvidence test = command("E-command-1", "pnpm test --filter api", 1);
        ReviewEvidence retried = command("E-command-2", "pnpm test --filter api", 0);
        ReviewEvidence reproduction = command("E-command-3", "pnpm vitest run refresh.race.spec.ts", 1);
        ReviewEvidence source = new ReviewEvidence("E-source-1", "source", "src/auth/refresh.ts", "if (!this.refreshing)", "");
        ReviewInvestigation investigation = new ReviewInvestigation("map", ImmutableList.of("src/auth/refresh.ts", "src/http/client.ts", "src/auth/refresh.ts", "README.md"),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), Optional.empty(), ImmutableList.of(), Optional.empty());
        ReviewRun run = run(ReviewRun.State.ACCEPTED, ImmutableList.of("src/auth/refresh.ts", "src/http/client.ts", "src/session.ts"), Optional.of(investigation),
                Optional.of(new ReviewCoverage(false, ImmutableList.of("Skipped schema.generated.ts over the line limit"), ImmutableList.of(), NOW)),
                ImmutableMap.of(test.ref(), test, retried.ref(), retried, reproduction.ref(), reproduction, source.ref(), source));
        when(reviews.listRuns("review")).thenReturn(ImmutableList.of(run));
        ReviewFinding medium = finding("medium", 2, "Retries on 429 have no upper limit", "Medium", ImmutableList.of(source));
        ReviewFinding high = finding("high", 3, "Token refresh can run twice", "HIGH", ImmutableList.of(reproduction));
        ReviewFinding resolved = finding("resolved", 1, "Missing null check", "critical", ImmutableList.of())
                .withState(ReviewFinding.State.RESOLVED, Optional.empty(), NOW);
        when(reviews.listFindings("review")).thenReturn(ImmutableList.of(medium, high, resolved));

        ResultDigest.Review digest = service.digest(request(Optional.of("review"), Optional.empty(), Optional.of(TurnId.random().toString()))).review().orElseThrow();

        assertThat(digest.reviewedHead()).isEqualTo("head");
        assertThat(digest.currentHead()).contains("moved");
        // Only changed files count, each once.
        assertThat(digest.filesRead()).isEqualTo(2);
        assertThat(digest.filesChanged()).isEqualTo(3);
        assertThat(digest.complete()).isFalse();
        assertThat(digest.omissions()).containsExactly("Skipped schema.generated.ts over the line limit");
        // A command a finding cites is that finding's proof, and a command run again keeps its latest exit code.
        assertThat(digest.commands()).containsExactly(new ResultDigest.Command("pnpm test --filter api", Optional.of(0)));
        assertThat(digest.findings()).extracting(ResultDigest.Finding::id).containsExactly("high", "medium");
        assertThat(digest.findings().getFirst()).satisfies(finding -> {
            assertThat(finding.label()).isEqualTo("F-3");
            assertThat(finding.severity()).contains("high");
            assertThat(finding.path()).contains("src/Finding.java");
            assertThat(finding.line()).contains(40);
            assertThat(finding.ran()).contains("pnpm vitest run refresh.race.spec.ts · exit 1");
        });
        assertThat(digest.findings().getLast().ran()).isEmpty();
        assertThat(digest.fixTask()).contains("dev-task");
    }

    @Test
    void aReviewWithoutAnAcceptedRunOrFromAnotherRepositoryHasNoFindings()
    {
        AgentReview review = openReview("review", "workspace", "owner/repo", OptionalInt.of(61), "review-task", Optional.empty(), NOW);
        when(reviews.findReview("review")).thenReturn(Optional.of(review));
        when(reviews.listRuns("review")).thenReturn(ImmutableList.of(run(ReviewRun.State.FAILED, ImmutableList.of(), Optional.empty(), Optional.empty(), ImmutableMap.of())));
        assertThat(service.digest(request(Optional.of("review"), Optional.empty(), Optional.empty())).review()).isEmpty();

        when(reviews.findReview("elsewhere")).thenReturn(Optional.of(openReview("elsewhere", "other", "owner/repo", OptionalInt.of(61), "task", Optional.empty(), NOW)));
        assertThat(service.digest(request(Optional.of("elsewhere"), Optional.empty(), Optional.empty())).review()).isEmpty();
    }

    @Test
    void aDevelopmentResultCarriesItsPullRequestAsStoredAndTheAgentsWholeReport()
    {
        Workspace workspace = mock(Workspace.class, withSettings().mockMaker(INLINE));
        when(workspace.provider()).thenReturn(HostingProvider.GITLAB);
        when(workspaces.findById("workspace")).thenReturn(Optional.of(workspace));
        when(pullRequests.findIdByRepoAndNumber("owner/repo", 62)).thenReturn(Optional.of(7L));
        when(pullRequests.findById(7)).thenReturn(Optional.of(row("opened")));
        when(details.findPullRequestDetail("owner/repo", 62)).thenReturn(Optional.of(detail(null)));
        when(credentials.cachedLogin()).thenReturn(Optional.of("Lee"));
        TurnId turnId = TurnId.random();
        AgentTurn turn = mock(AgentTurn.class, withSettings().mockMaker(INLINE));
        String report = "Moved session storage to Redis. ".repeat(20).strip();
        when(turn.developmentResult()).thenReturn(Optional.of(new DevelopmentResultReport(true, Optional.of(report))));
        when(turns.find(turnId)).thenReturn(Optional.of(turn));

        ResultDigest digest = service.digest(request(Optional.empty(), Optional.of("owner/repo#62"), Optional.of(turnId.toString())));

        assertThat(digest.noticeId()).isEqualTo("finished:turn");
        assertThat(digest.host()).isEqualTo("GitLab");
        assertThat(digest.report()).contains(report);
        ResultDigest.Pull pull = digest.pull().orElseThrow();
        assertThat(pull.number()).isEqualTo(62);
        assertThat(pull.state()).isEqualTo("open");
        assertThat(pull.draft()).isTrue();
        assertThat(pull.head()).contains("abc123");
        assertThat(pull.base()).contains("main");
        assertThat(pull.files()).isEqualTo(11);
        assertThat(pull.checks()).containsExactly(new ResultDigest.Check("test (node 20)", "failing", Optional.of("https://ci.example.com/1")),
                new ResultDigest.Check("lint", "successful", Optional.empty()));
        assertThat(pull.approvers()).containsExactly("lee");
        assertThat(pull.youApproved()).isTrue();
        assertThat(pull.mine()).isFalse();
        assertThat(pull.mergeable()).contains(true);

        when(details.findPullRequestDetail("owner/repo", 62)).thenReturn(Optional.of(detail(NOW)));
        assertThat(service.digest(request(Optional.empty(), Optional.of("owner/repo#62"), Optional.empty())).pull().orElseThrow().state()).isEqualTo("merged");
        when(details.findPullRequestDetail("owner/repo", 62)).thenReturn(Optional.of(detail(null)));
        when(pullRequests.findById(7)).thenReturn(Optional.of(row("locked")));
        assertThat(service.digest(request(Optional.empty(), Optional.of("owner/repo#62"), Optional.empty())).pull().orElseThrow().state()).isEqualTo("closed");
        // A pull request ByteQuay never stored has nothing to show, and the host defaults to GitHub without a workspace.
        ResultDigest unknown = service.digest(new ResultDigestService.Request("finished:turn", "gone", Optional.empty(), Optional.of("owner/repo#63"), Optional.empty()));
        assertThat(unknown.pull()).isEmpty();
        assertThat(unknown.host()).isEqualTo("GitHub");
    }

    private static ResultDigestService.Request request(Optional<String> reviewId, Optional<String> pullRequest, Optional<String> turnId)
    {
        return new ResultDigestService.Request("finished:turn", "workspace", reviewId, pullRequest, turnId);
    }

    private static ReviewEvidence command(String ref, String command, int exitCode)
    {
        String provenance = "command run by the agent in the review worktree; eventId=1; exitCode=" + exitCode + "; excerptTruncated=false";
        return new ReviewEvidence(ref, "command output: " + command, provenance, "$ " + command + "\nExit code: " + exitCode + "\n", "");
    }

    private static ReviewRun run(ReviewRun.State state, List<String> files, Optional<ReviewInvestigation> investigation, Optional<ReviewCoverage> coverage,
            Map<String, ReviewEvidence> evidence)
    {
        return new ReviewRun("run", "review", 1, ReviewRun.Scope.FULL, "main", "base", "base", "head", Optional.empty(), Optional.empty(),
                ImmutableList.of(), files.stream().map(file -> new DiffFile(file, "modified", 1, 1, "")).collect(ImmutableList.toImmutableList()), ImmutableList.of(),
                state, Optional.empty(), coverage, investigation, ImmutableList.of(), false, ImmutableMap.of(), evidence, Optional.empty(), 0, NOW, NOW, Optional.of(NOW));
    }

    private static ReviewFinding finding(String id, int ordinal, String title, String severity, List<ReviewEvidence> evidence)
    {
        return new ReviewFinding(id, "review", "run", ordinal, title, "Why it matters.", Optional.of("Walk it through."), Optional.of(severity),
                Optional.of(new ReviewAnchor("src/Finding.java", "RIGHT", 40, 42, ImmutableList.of())), evidence, 1, ReviewFinding.State.OPEN, Optional.empty(), "head",
                false, 3, NOW, NOW, ImmutableList.of(), ReviewFinding.Origin.AUTOMATIC, Optional.empty());
    }

    private static PullRequest row(String state)
    {
        return new PullRequest(7, "owner/repo", 62, "Move sessions to Redis", "dev-sam", "https://gitlab.example.com/owner/repo/-/merge_requests/62", NOW, NOW,
                PullRequest.Origin.OTHER, ImmutableList.of(), ImmutableMap.of(), true, null, null, null, ImmutableList.of(), PullRequestDetail.CiStatus.FAILING, 412, 198, 0,
                state, null, null, true, "clean", null, "rotate-keys", null, null, null, null);
    }

    private static PullRequestDetail detail(Instant mergedAt)
    {
        return new PullRequestDetail(7, "owner/repo", 62, "Move sessions to Redis", "https://gitlab.example.com/owner/repo/-/merge_requests/62", "open", true, true, "clean",
                "dev-sam", "", NOW, NOW, mergedAt, "main", "rotate-keys", "abc123", "", ImmutableList.of(), ImmutableList.of(), null,
                ImmutableList.of(new PullRequestDetail.Reviewer("lee", "", "APPROVED"), new PullRequestDetail.Reviewer("ana", "", "CHANGES_REQUESTED")),
                412, 198, 11, 1, PullRequestDetail.CiStatus.FAILING, ImmutableList.of(),
                ImmutableList.of(new PullRequestDetail.CheckRun(1L, "test (node 20)", "completed", "failure", "https://ci.example.com/1", "actions", "failing", NOW, NOW),
                        new PullRequestDetail.CheckRun(2L, "lint", "completed", "success", null, "actions", "successful", NOW, NOW),
                        new PullRequestDetail.CheckRun(3L, null, "completed", "success", null, "actions", "successful", NOW, NOW)),
                ImmutableList.of(), true);
    }
}
