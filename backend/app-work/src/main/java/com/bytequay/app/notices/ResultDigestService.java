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
import com.bytequay.core.review.ReviewStep;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;

/** Reads what a result card decides from, from local stores only: it never contacts a provider or dates the viewer's last look. */
@Service
public class ResultDigestService
{
    /** The review tools' severities, most severe first. */
    private static final List<String> SEVERITIES = ImmutableList.of("critical", "high", "medium", "low");
    // The review tools record a command's output under this source, followed by the command line, and its exit code in the provenance.
    private static final String COMMAND_SOURCE = "command output: ";
    private static final Pattern EXIT_CODE = Pattern.compile("exitCode=(-?\\d+)");
    /** As many findings as a review result shows; the review itself holds every one. */
    private static final int FINDINGS = 20;

    private final WorkspaceStore workspaces;
    private final AgentReviewStore reviews;
    private final PullRequestStore pullRequests;
    private final PullRequestService details;
    private final CredentialService credentials;
    private final AgentTurnStore turns;

    public ResultDigestService(WorkspaceStore workspaces, AgentReviewStore reviews, PullRequestStore pullRequests, PullRequestService details, CredentialService credentials,
            AgentTurnStore turns)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.details = requireNonNull(details, "details is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.turns = requireNonNull(turns, "turns is null");
    }

    /**
     * The notice facts a card already holds: its pull request as {@code owner/repo#number}, its review, and the turn it reports.
     */
    public record Request(String noticeId, String workspaceId, Optional<String> reviewId, Optional<String> pullRequest, Optional<String> turnId)
    {
        public Request
        {
            requireNonNull(noticeId, "noticeId is null");
            requireNonNull(workspaceId, "workspaceId is null");
            requireNonNull(reviewId, "reviewId is null");
            requireNonNull(pullRequest, "pullRequest is null");
            requireNonNull(turnId, "turnId is null");
        }
    }

    public ResultDigest digest(Request request)
    {
        Optional<Workspace> workspace = workspaces.findById(request.workspaceId());
        String host = workspace.map(Workspace::provider).filter(HostingProvider.GITLAB::equals).map(_ -> "GitLab").orElse("GitHub");
        Optional<AgentReview> review = request.reviewId().flatMap(reviews::findReview).filter(found -> found.workspaceId().equals(request.workspaceId()));
        // A review's turn reports nothing of its own; its findings say what it found.
        Optional<String> report = review.isPresent() ? Optional.empty() : request.turnId().map(TurnId::valueOf).flatMap(turns::find)
                .flatMap(AgentTurn::developmentResult).flatMap(DevelopmentResultReport::message).filter(text -> !text.isBlank());
        return new ResultDigest(request.noticeId(), host, request.pullRequest().flatMap(this::pull), review.flatMap(this::review), report);
    }

    /** A review's digest as its result card reads it, with its pull request when it has one; empty when the review is gone. */
    public Optional<ResultDigest> review(String reviewId)
    {
        return reviews.findReview(reviewId).map(review -> digest(new Request("review:" + reviewId, review.workspaceId(), Optional.of(reviewId),
                review.number().stream().mapToObj(number -> review.repo() + "#" + number).findFirst(), Optional.empty())));
    }

    private Optional<ResultDigest.Pull> pull(String ref)
    {
        int at = ref.lastIndexOf('#');
        checkArgument(at > 0 && at < ref.length() - 1, "pullRequest is not owner/repo#number: %s", ref);
        String repo = ref.substring(0, at);
        int number = Integer.parseInt(ref.substring(at + 1));
        Optional<PullRequest> row = pullRequests.findIdByRepoAndNumber(repo, number).flatMap(pullRequests::findById);
        if (row.isEmpty()) {
            return Optional.empty();
        }
        PullRequest stored = row.orElseThrow();
        Optional<PullRequestDetail> detail = details.findPullRequestDetail(repo, number);
        Optional<String> viewer = credentials.cachedLogin();
        List<String> approvers = detail.map(found -> found.reviewers().stream().filter(reviewer -> "APPROVED".equals(reviewer.state()))
                .map(PullRequestDetail.Reviewer::login).collect(toImmutableList())).orElse(ImmutableList.of());
        List<ResultDigest.Check> checks = detail.map(found -> found.checkRuns().stream().filter(check -> check.name() != null)
                .map(check -> new ResultDigest.Check(check.name(), Optional.ofNullable(check.category()).orElse("pending"), Optional.ofNullable(check.detailsUrl())))
                .collect(toImmutableList())).orElse(ImmutableList.of());
        // GitLab keeps a merge request "opened" or "locked"; the card reads open, closed or merged.
        String stated = detail.filter(found -> found.mergedAt() != null).map(_ -> "merged")
                .or(() -> Optional.ofNullable(stored.state())).or(() -> detail.map(PullRequestDetail::state)).orElse("open").toLowerCase(Locale.ROOT);
        String state = stated.equals("merged") ? "merged" : stated.equals("closed") || stated.equals("locked") ? "closed" : "open";
        boolean draft = detail.map(PullRequestDetail::draft).orElse(stored.draft());
        return Optional.of(new ResultDigest.Pull(number, stored.title(), stored.htmlUrl(), state, draft, stored.mine(viewer), stored.author(),
                detail.map(PullRequestDetail::headSha), detail.map(PullRequestDetail::baseRef), detail.map(PullRequestDetail::additions).orElse(stored.additions()),
                detail.map(PullRequestDetail::deletions).orElse(stored.deletions()), detail.map(PullRequestDetail::changedFiles).orElse(0), checks, approvers,
                viewer.filter(login -> approvers.stream().anyMatch(login::equalsIgnoreCase)).isPresent(), stored.mergeBlocked(),
                detail.map(PullRequestDetail::mergeable).or(() -> Optional.ofNullable(stored.mergeable()))));
    }

    private Optional<ResultDigest.Review> review(AgentReview review)
    {
        Optional<ReviewRun> latest = reviews.listRuns(review.id()).stream().filter(run -> !run.explanationOnly()).reduce((first, last) -> last)
                .filter(run -> run.state() == ReviewRun.State.ACCEPTED);
        if (latest.isEmpty()) {
            return Optional.empty();
        }
        ReviewRun run = latest.orElseThrow();
        List<ReviewFinding> all = reviews.listFindings(review.id());
        List<ResultDigest.Finding> open = all.stream()
                .filter(finding -> finding.state() == ReviewFinding.State.OPEN && finding.validatedHeadSha().equals(run.sourceRevision()))
                .sorted(comparing(ResultDigestService::rank).thenComparingInt(ReviewFinding::ordinal))
                .limit(FINDINGS)
                .map(ResultDigestService::finding)
                .collect(toImmutableList());
        int handled = (int) all.stream().filter(finding -> finding.state() == ReviewFinding.State.RESOLVED && finding.validatedHeadSha().equals(run.sourceRevision())).count();
        Set<String> changed = run.diff().stream().map(DiffFile::filename).collect(toImmutableSet());
        // The working investigation holds the latest reading; a run that sealed its only one keeps it in that step.
        List<String> read = run.investigation().or(() -> run.latestSealedInvestigation().flatMap(ReviewStep::investigation))
                .map(ReviewInvestigation::reviewedFiles).orElse(ImmutableList.of());
        int filesRead = (int) read.stream().distinct().filter(changed::contains).count();
        Set<String> cited = all.stream().flatMap(finding -> finding.evidence().stream()).map(ReviewEvidence::ref).collect(toImmutableSet());
        return Optional.of(new ResultDigest.Review(run.headSha(), review.observedHeadSha(), filesRead, changed.size(),
                run.coverage().map(ReviewCoverage::complete).orElse(true),
                run.coverage().map(ReviewCoverage::omissions).orElse(ImmutableList.of()),
                run.coverage().map(ReviewCoverage::warnings).orElse(ImmutableList.of()),
                commands(run, cited), open, handled, review.sourceTaskId()));
    }

    /** The commands the reviewer ran in this run that no finding cites, each once, with the exit code of its latest run. */
    private static List<ResultDigest.Command> commands(ReviewRun run, Set<String> cited)
    {
        Map<String, ResultDigest.Command> commands = new LinkedHashMap<>();
        for (ReviewEvidence evidence : run.evidence().values()) {
            if (!evidence.source().startsWith(COMMAND_SOURCE) || cited.contains(evidence.ref())) {
                continue;
            }
            String command = evidence.source().substring(COMMAND_SOURCE.length());
            commands.remove(command);
            commands.put(command, new ResultDigest.Command(command, exitCode(evidence)));
        }
        return ImmutableList.copyOf(commands.values());
    }

    private static ResultDigest.Finding finding(ReviewFinding finding)
    {
        Optional<String> ran = finding.evidence().stream().filter(evidence -> evidence.source().startsWith(COMMAND_SOURCE)).findFirst()
                .map(evidence -> evidence.source().substring(COMMAND_SOURCE.length()) + exitCode(evidence).map(code -> " · exit " + code).orElse(""));
        return new ResultDigest.Finding(finding.id(), finding.label(), finding.title(), finding.body(), finding.severity().map(value -> value.toLowerCase(Locale.ROOT)),
                finding.anchor().map(ReviewAnchor::path), finding.anchor().map(ReviewAnchor::startLine), finding.outdated(), finding.version(), finding.revision(), ran);
    }

    private static Optional<Integer> exitCode(ReviewEvidence evidence)
    {
        Matcher matcher = EXIT_CODE.matcher(evidence.provenance());
        return matcher.find() ? Optional.of(Integer.parseInt(matcher.group(1))) : Optional.empty();
    }

    private static int rank(ReviewFinding finding)
    {
        return finding.severity().map(severity -> SEVERITIES.indexOf(severity.toLowerCase(Locale.ROOT))).filter(index -> index >= 0).orElse(SEVERITIES.size());
    }
}
