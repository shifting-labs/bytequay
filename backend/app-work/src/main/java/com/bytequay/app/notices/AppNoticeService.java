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

import com.bytequay.agent.core.AgentApproval;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.ApprovalRequest;
import com.bytequay.agent.core.QueuedAgentInput;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.store.AgentApprovalStore;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.QueuedInputStore;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.app.rules.RuleWork;
import com.bytequay.app.service.epic.EpicNextWork;
import com.bytequay.app.service.rules.AgentRule;
import com.bytequay.app.service.rules.AgentRuleService;
import com.bytequay.app.service.rules.AgentRuleView;
import com.bytequay.app.utils.ContextText;
import com.bytequay.app.workspace.TaskPullRequestOwnership;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixStore;
import com.bytequay.core.domain.Artifact;
import com.bytequay.core.domain.AssistantAssessment;
import com.bytequay.core.domain.AssistantFollow;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.RuleRun;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.review.ReviewRunOverview;
import com.bytequay.core.store.ArtifactStore;
import com.bytequay.core.store.AssistantFollowStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.WorkspaceStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.bytequay.agent.spi.ProviderApprovalRequest.QUESTION_TOOL;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

/** Reads local facts without opening transcripts, contacting providers, or changing execution. */
@Service
public class AppNoticeService
{
    // Marks a notice that opens an Artifacts conversation, whose id the notice's task id holds; the app reads the same prefix.
    private static final String ARTIFACT_NOTICE = "artifact:";
    // A rule whose record lets it move up, and a rule's finished work asking what the user makes of it; the app reads the same prefixes.
    // The move-up card belongs to no task or repository, so it names the rules in their place.
    private static final String RULE_NOTICE = "rule:";
    private static final String RULE_RESULT_NOTICE = "rule-result:";
    private static final String RULES = "rules";

    private final AgentTaskStore tasks;
    private final AgentTurnStore turns;
    private final AgentApprovalStore approvals;
    private final QueuedInputStore queues;
    private final AgentReviewStore reviews;
    private final PullRequestStore pullRequests;
    private final TaskPullRequestOwnership ownership;
    private final CiAutofixStore ciRuns;
    private final EpicNextWork next;
    private final ArtifactStore artifacts;
    private final WorkspaceStore workspaces;
    private final TaskWatchdog watchdog;
    private final AgentRuleService rules;
    private final RuleWork ruleWork;
    private final ObjectMapper mapper;
    private final AssistantFollowStore follows;

    public AppNoticeService(
            AgentTaskStore tasks,
            AgentTurnStore turns,
            AgentApprovalStore approvals,
            QueuedInputStore queues,
            AgentReviewStore reviews,
            PullRequestStore pullRequests,
            TaskPullRequestOwnership ownership,
            CiAutofixStore ciRuns,
            EpicNextWork next,
            ArtifactStore artifacts,
            WorkspaceStore workspaces,
            TaskWatchdog watchdog,
            AgentRuleService rules,
            RuleWork ruleWork,
            ObjectMapper mapper,
            AssistantFollowStore follows)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.approvals = requireNonNull(approvals, "approvals is null");
        this.queues = requireNonNull(queues, "queues is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.ownership = requireNonNull(ownership, "ownership is null");
        this.ciRuns = requireNonNull(ciRuns, "ciRuns is null");
        this.next = requireNonNull(next, "next is null");
        this.artifacts = requireNonNull(artifacts, "artifacts is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.watchdog = requireNonNull(watchdog, "watchdog is null");
        this.rules = requireNonNull(rules, "rules is null");
        this.ruleWork = requireNonNull(ruleWork, "ruleWork is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.follows = requireNonNull(follows, "follows is null");
    }

    /**
     * What the app alerts on: the task notices, each Artifacts conversation whose question timed out unanswered, and the rules that wait on
     * the user: a rule whose record lets it move up, and a rule's finished work, asked about in place of its task's own result.
     */
    public List<AppNotice> alerts()
    {
        Map<String, Suggestion> suggestions = suggestions();
        Map<String, RuleRun> automatic = automaticTasks();
        ImmutableList.Builder<AppNotice> alerts = ImmutableList.builder();
        for (AppNotice notice : current()) {
            Optional<Suggestion> suggestion = Optional.ofNullable(suggestions.get(notice.taskId())).filter(any -> notice.kind() == AppNotice.Kind.TURN_FINISHED);
            AppNotice result = suggestion.map(found -> suggested(notice, found)).orElse(notice);
            resultNotice(automatic.containsKey(notice.taskId()) ? automaticResult(result, automatic.get(notice.taskId())) : result).ifPresent(alerts::add);
        }
        automaticResults().forEach(alerts::add);
        for (AgentRuleView rule : rules.movable()) {
            alerts.add(new AppNotice(RULE_NOTICE + rule.id() + ":" + rule.keptObserving().orElse(rule.recordSince()).toEpochMilli(), AppNotice.Kind.QUESTION,
                    RULE_NOTICE + rule.id(), RULES, Optional.empty(), Optional.empty(), clip(rule.rule().name(), 160), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), "Agreed " + rule.agreed() + " of " + rule.answered() + " times. Move it to Suggest?", "Rules", RULES, Optional.empty(),
                    rules.record(rule.id()).stream().findFirst().map(RuleRun::occurredAt).orElse(rule.recordSince()), Optional.empty()));
        }
        for (Artifact artifact : artifacts.missed()) {
            Artifact.MissedQuestion missed = artifact.missed().orElseThrow();
            workspaces.findById(artifact.workspaceId()).ifPresent(workspace -> alerts.add(new AppNotice(ARTIFACT_NOTICE + missed.approvalId(), AppNotice.Kind.QUESTION,
                    artifact.id(), artifact.workspaceId(), Optional.empty(), Optional.empty(), clip(artifact.title(), 160), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), clip("Timed out: " + question(missed.detail()), 280), workspace.monitoredRepository().fullName(),
                    missed.laneId(), Optional.empty(), missed.at(), Optional.empty())));
        }
        return alerts.build();
    }

    private Optional<AppNotice> resultNotice(AppNotice notice)
    {
        if (notice.kind() != AppNotice.Kind.TURN_FINISHED) {
            return Optional.of(notice);
        }
        String sourceLine = notice.summaryLine();
        Optional<AssistantAssessment> result = follows.result(notice.taskId(), notice.id(), sourceLine);
        if (result.isPresent()) {
            return result.filter(job -> job.state() == AssistantAssessment.State.PUBLISHED)
                    .map(job -> notice.withMessage(ContextText.excerpt(job.report().assessment().orElse(notice.message()), 1000)));
        }
        boolean automaticReview = notice.reviewId().isPresent() && notice.ruleRun().flatMap(RuleRun::authorization).isPresent();
        return follows.followsTask(notice.taskId()) || automaticReview ? Optional.empty() : Optional.of(notice);
    }

    private static AppNotice automaticResult(AppNotice notice, RuleRun run)
    {
        if (notice.kind() == AppNotice.Kind.TURN_FINISHED && run.outcome().filter(outcome -> outcome.equals("UNKNOWN") || outcome.equals("STARTING")).isPresent()) {
            return new AppNotice("rule-action:" + run.ruleId() + ":" + run.key() + ":" + run.outcome().orElseThrow(), AppNotice.Kind.RECOVERY,
                    notice.taskId(), notice.workspaceId(), notice.turnId(), notice.reviewId(), notice.title(), notice.pullRequest(), notice.pullRequestTitle(), notice.branch(),
                    notice.changes(), run.skipped().orElse("The automatic action has not been confirmed; inspect existing work before retrying"), notice.repository(),
                    notice.laneId(), notice.workItemId(), run.occurredAt(), Optional.empty(), Optional.of(run));
        }
        RuleRun projected = switch (notice.kind()) {
            case TURN_FINISHED -> run.finished("RESULT_READY");
            case FAILURE, RECOVERY -> run.finished("FAILED");
            default -> run;
        };
        return notice.withRuleRun(projected);
    }

    private Map<String, RuleRun> automaticTasks()
    {
        Map<String, RuleRun> result = new HashMap<>();
        follows.follows().forEach(follow -> follow.ruleRun().ifPresent(run -> result.put(follow.taskId(), run)));
        automaticRuns().stream().filter(run -> run.authorization().isPresent() && run.work().isPresent())
                .forEach(run -> ruleWork.resultTask(run).ifPresent(taskId -> result.merge(taskId, run,
                        (retained, incoming) -> incoming.occurredAt().isAfter(retained.occurredAt()) ? incoming : retained)));
        return result;
    }

    /** Retained accepted starts can enroll reviews created before inbox following was available. New starts enroll durably at admission. */
    public Map<String, RuleRun> ruleReviews()
    {
        return automaticTasks().entrySet().stream().filter(entry -> entry.getValue().work().filter(work -> work.startsWith("review:")).isPresent()
                && entry.getValue().outcome().filter("STARTED"::equals).isPresent()).collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private List<RuleRun> automaticRuns()
    {
        Map<String, RuleRun> result = new LinkedHashMap<>();
        rules.pendingRuns().forEach(run -> result.put(run.ruleId() + ":" + run.key(), run));
        rules.recentRuns().forEach(run -> result.put(run.ruleId() + ":" + run.key(), run));
        return ImmutableList.copyOf(result.values());
    }

    private List<AppNotice> automaticResults()
    {
        ImmutableList.Builder<AppNotice> result = ImmutableList.builder();
        for (RuleRun run : automaticRuns()) {
            if (run.authorization().isEmpty() || run.outcome().isEmpty() || !run.key().startsWith("pr:")) {
                continue;
            }
            String outcome = run.outcome().orElseThrow();
            boolean failed = outcome.equals("FAILED") || outcome.equals("UNKNOWN");
            if (!(failed || outcome.equals("MERGED") || outcome.equals("QUEUED")) || failed && ruleWork.resultTask(run).isPresent()) {
                continue;
            }
            long prId = Long.parseLong(run.key().split(":", 3)[1]);
            pullRequests.findById(prId).ifPresent(pr -> workspaces.findAll().stream()
                    .filter(workspace -> RuleWork.watchedWorkspace(pr, workspace)).findFirst().ifPresent(workspace -> {
                        result.add(new AppNotice("rule-action:" + run.ruleId() + ":" + run.key() + ":" + outcome,
                                failed ? AppNotice.Kind.FAILURE : AppNotice.Kind.TURN_FINISHED, "rule:" + run.ruleId(), workspace.id(), Optional.empty(), Optional.empty(),
                                pr.title(), Optional.of(run.subject()), Optional.of(pr.title()), Optional.empty(), Optional.empty(),
                                switch (outcome) {
                                    case "MERGED" -> "Pull request merged";
                                    case "QUEUED" -> "Waiting in the merge queue";
                                    case "UNKNOWN" -> run.skipped().orElse("Action outcome unknown; inspect the pull request before retrying");
                                    default -> run.skipped().orElse("The automatic action could not finish");
                                }, pr.repo(), "Rules", Optional.empty(), run.occurredAt(), Optional.empty(), Optional.of(run)));
                    }));
        }
        return result.build();
    }

    /** A rule's suggestion whose work waits on the user. */
    private record Suggestion(AgentRuleView rule, RuleRun run) {}

    /** The suggestions waiting on the user, by the task whose finished turn is their result. */
    private Map<String, Suggestion> suggestions()
    {
        Map<String, Suggestion> waiting = new HashMap<>();
        for (AgentRuleView rule : rules.list()) {
            if (rule.mode() != AgentRule.Mode.SUGGEST) {
                continue;
            }
            rules.record(rule.id()).stream().filter(run -> run.authorization().isEmpty() && run.verdict() == RuleRun.Verdict.PENDING && run.work().isPresent())
                    .forEach(run -> ruleWork.resultTask(run).ifPresent(taskId -> waiting.put(taskId, new Suggestion(rule, run))));
        }
        return waiting;
    }

    private static AppNotice suggested(AppNotice result, Suggestion suggestion)
    {
        String done = result.reviewId().isPresent() ? "drafted its findings; nothing is posted until you publish them"
                : "committed on this Mac; nothing is pushed until you do";
        return new AppNotice(RULE_RESULT_NOTICE + suggestion.rule().id() + ":" + suggestion.run().key(), AppNotice.Kind.QUESTION, result.taskId(), result.workspaceId(),
                result.turnId(), result.reviewId(), result.title(), result.pullRequest(), result.pullRequestTitle(), result.branch(), result.changes(),
                clip(suggestion.rule().rule().name() + " · " + done, 280), result.repository(), result.laneId(), Optional.empty(), result.occurredAt(), Optional.empty());
    }

    /** The task notices, which the Assistant and the agenda read as tasks. */
    public List<AppNotice> current()
    {
        Map<TaskId, AgentTask> candidates = new LinkedHashMap<>();
        // Unresolved requests must survive even when newer completed tasks fill the recent page.
        tasks.listRecent(200).forEach(task -> candidates.put(task.id(), task));
        tasks.listLive().forEach(task -> candidates.put(task.id(), task));
        follows.follows().stream().filter(follow -> follow.ruleRun().isPresent()).map(AssistantFollow::taskId)
                .forEach(id -> tasks.find(TaskId.valueOf(id)).ifPresent(task -> candidates.putIfAbsent(task.id(), task)));
        return notices(candidates);
    }

    /** The notices of these tasks only, for following a few tasks without reading all of them. */
    public List<AppNotice> current(Collection<TaskId> taskIds)
    {
        Map<TaskId, AgentTask> candidates = new LinkedHashMap<>();
        taskIds.forEach(id -> tasks.find(id).ifPresent(task -> candidates.put(id, task)));
        Map<String, RuleRun> automatic = automaticTasks();
        return notices(candidates).stream().map(notice -> automatic.containsKey(notice.taskId()) ? automaticResult(notice, automatic.get(notice.taskId())) : notice)
                .collect(ImmutableList.toImmutableList());
    }

    private List<AppNotice> notices(Map<TaskId, AgentTask> candidates)
    {
        Map<String, AgentReview> reviewTasks = reviews.listOpenReviews().stream().collect(toImmutableMap(AgentReview::taskId, review -> review));
        Map<TaskId, AgentReview> scopedReviews = new LinkedHashMap<>();
        candidates.values().forEach(task -> Optional.ofNullable(reviewTasks.get(task.id().toString()))
                .filter(review -> task.workspaceId().filter(review.workspaceId()::equals).isPresent())
                .ifPresent(review -> scopedReviews.put(task.id(), review)));
        candidates.values().removeIf(task -> task.archivedAt().isPresent() || (!task.interactive() && !scopedReviews.containsKey(task.id())));
        List<ApprovalRequest> requests = approvals.listPending().stream().map(AgentApproval::request).collect(ImmutableList.toImmutableList());
        // A CI repair works in its development task's checkout, whose page answers the repair's cards, so they are that task's notices.
        Map<TaskId, TaskId> repairs = requests.stream().map(ApprovalRequest::taskId).filter(id -> !candidates.containsKey(id)).distinct()
                .flatMap(id -> tasks.find(id).stream())
                .filter(task -> task.permissionPolicy() == AgentPermissionPolicy.CI_AUTOFIX && task.taskWorktree().isPresent() && !task.ownsWorktree())
                .collect(toImmutableMap(AgentTask::id, task -> task.taskWorktree().orElseThrow().taskId()));
        Map<TaskId, AgentTurn> latestTurns = turns.findLatest(Sets.union(candidates.keySet(), repairs.keySet()));
        Map<TaskId, AgentTurn> reports = turns.findLatestDevelopmentResults(candidates.keySet());
        Map<String, ReviewRunOverview> runs = reviews.summarizeRuns(candidates.keySet().stream()
                .filter(scopedReviews::containsKey).map(id -> scopedReviews.get(id).id()).collect(toImmutableSet()));
        // A card counts only while the turn that asked is still its own task's latest, running turn.
        var pending = Multimaps.index(requests.stream()
                .filter(request -> Optional.ofNullable(latestTurns.get(request.taskId())).filter(turn -> turn.state().active() && turn.id().equals(request.turnId())).isPresent())
                .iterator(), request -> repairs.getOrDefault(request.taskId(), request.taskId()));
        ImmutableList.Builder<AppNotice> notices = ImmutableList.builder();
        for (AgentTask task : candidates.values()) {
            Optional<AgentReview> review = Optional.ofNullable(scopedReviews.get(task.id()));
            Optional<AgentTurn> latest = Optional.ofNullable(latestTurns.get(task.id()));
            Optional<ReviewRunOverview> overview = review.map(value -> runs.get(value.id()));
            Optional<ApprovalRequest> approval = pending.get(task.id()).stream().min(comparing(ApprovalRequest::requestedAt));
            select(task, latest, Optional.ofNullable(reports.get(task.id())), approval, review, overview).ifPresent(notices::add);
        }
        return notices.build();
    }

    private Optional<AppNotice> select(AgentTask task, Optional<AgentTurn> latest, Optional<AgentTurn> report, Optional<ApprovalRequest> approval,
            Optional<AgentReview> review, Optional<ReviewRunOverview> overview)
    {
        if (task.state() == TaskState.FAILED) {
            return Optional.of(notice(task, review, overview, latest, "failed:" + task.id(), AppNotice.Kind.FAILURE,
                    task.terminalReason().orElse("Task failed"), task.terminalAt().orElseThrow()));
        }
        if (task.state() == TaskState.OPEN && approval.isPresent()) {
            ApprovalRequest request = approval.orElseThrow();
            boolean question = request.providerRequest().requestedPermissions().contains(QUESTION_TOOL);
            String message = question ? question(request.providerRequest().detail()) : request.providerRequest().title();
            // The development task's page shows the repair's card under the same name.
            return Optional.of(notice(task, review, overview, latest, "approval:" + request.id(), question ? AppNotice.Kind.QUESTION : AppNotice.Kind.APPROVAL,
                    request.taskId().equals(task.id()) ? message : "CI Autofix · " + message, request.requestedAt(),
                    Optional.of(request.expiresAt()), Optional.empty()));
        }
        Optional<WorktreeMutationClaim> recovery = task.worktreeMutationClaim().filter(claim -> claim.kind() == WorktreeMutationClaim.Kind.RECOVERY
                || claim.kind() == WorktreeMutationClaim.Kind.TURN && latest.filter(turn -> turn.state().active()).isEmpty());
        if (recovery.isPresent()) {
            WorktreeMutationClaim claim = recovery.orElseThrow();
            return Optional.of(notice(task, review, overview, latest, "recovery:" + task.id() + ":" + claim.ownerId(), AppNotice.Kind.RECOVERY,
                    "ByteQuay could not verify that the agent stopped. New work is blocked; your files are kept.",
                    latest.flatMap(AgentTurn::endedAt).orElse(claim.acquiredAt())));
        }
        Optional<AgentTurn> active = latest.filter(turn -> turn.state().active());
        Optional<TaskWatchdog.Stuck> stuck = active.flatMap(turn -> watchdog.stuck(turn.id()));
        if (stuck.isPresent()) {
            TaskWatchdog.Stuck why = stuck.orElseThrow();
            // Each time a turn gets stuck is a notice of its own, so each one alerts once.
            return Optional.of(notice(task, review, overview, latest, "stuck:" + active.orElseThrow().id() + ":" + why.since().toEpochMilli(), AppNotice.Kind.STUCK,
                    why.reason(), why.since()));
        }
        if (task.worktreeMutationClaim().isPresent() || task.developmentContinuationUnfinished() || latest.filter(turn -> turn.state().active()).isPresent()) {
            return Optional.empty();
        }
        Optional<EpicNextWork.Pick> pick = next.find(task.id().toString());
        if (pick.isPresent()) {
            EpicNextWork.Pick work = pick.orElseThrow();
            return Optional.of(new AppNotice("next:" + task.id() + ":" + work.itemId(), AppNotice.Kind.NEXT_WORK, task.id().toString(), task.workspaceId().orElseThrow(),
                    Optional.empty(), Optional.empty(), clip(work.title(), 160), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), clip(work.message(), 280),
                    task.repository().toString(), task.laneId().value(), Optional.of(work.itemId()), work.at(), Optional.empty()));
        }
        if (task.state() == TaskState.OPEN && latest.filter(turn -> turn.state() == TurnState.COMPLETED).isPresent()
                && report.filter(turn -> turn.state() == TurnState.COMPLETED).flatMap(AgentTurn::developmentResult).filter(result -> !result.readyForDelivery()).isPresent()) {
            AgentTurn asked = report.orElseThrow();
            return Optional.of(notice(task, review, overview, Optional.of(asked), "question:" + asked.id(), AppNotice.Kind.QUESTION,
                    asked.developmentResult().orElseThrow().message().orElse("The agent is waiting for your answer."), asked.endedAt().orElse(asked.createdAt())));
        }
        List<QueuedAgentInput> queue = task.state() == TaskState.OPEN ? queues.listQueued(task.id()).stream()
                .filter(input -> !input.state().historical()).collect(ImmutableList.toImmutableList()) : ImmutableList.of();
        Optional<QueuedAgentInput> paused = queue.stream().findFirst()
                .filter(input -> input.state() == QueuedAgentInput.State.PAUSED || input.state() == QueuedAgentInput.State.UNCERTAIN);
        if (latest.filter(turn -> turn.state() == TurnState.FAILED || turn.state() == TurnState.INTERRUPTED).isPresent()) {
            AgentTurn failed = latest.orElseThrow();
            String message = failed.terminalReason().orElse("The turn stopped before it finished.");
            if (paused.isPresent()) {
                message += " Queued messages are paused.";
            }
            return Optional.of(notice(task, review, overview, latest, "failed:" + failed.id(), AppNotice.Kind.FAILURE, message, failed.endedAt().orElseThrow()));
        }
        if (paused.isPresent()) {
            QueuedAgentInput input = paused.orElseThrow();
            String message = input.state() == QueuedAgentInput.State.UNCERTAIN ? "Delivery is uncertain. Review the queue before sending anything again."
                    : input.pauseReason().orElse("Queued messages are waiting for you to resume them.");
            return Optional.of(notice(task, review, overview, latest, "queue:" + input.id() + ":" + input.updatedAt(), AppNotice.Kind.QUEUE_PAUSED, message, input.updatedAt()));
        }
        Optional<AppNotice> ci = task.state() == TaskState.OPEN ? ciNotice(task, review, overview, latest) : Optional.empty();
        if (ci.isPresent()) {
            return ci;
        }
        // A completed turn can still hand off to a queued message or another review step.
        if (!queue.isEmpty() || overview.filter(ReviewRunOverview::active).isPresent()) {
            return Optional.empty();
        }
        Optional<AssistantFollow> ruleFollow = follows.follow(task.id().toString());
        if (review.isPresent() && ruleFollow.isPresent()) {
            return ruleReviewResult(task, latest, review.orElseThrow(), overview, ruleFollow.orElseThrow().ruleRun().orElseThrow());
        }
        return latest.filter(turn -> turn.state() == TurnState.COMPLETED).map(turn -> finished(task, review, overview, turn));
    }

    private Optional<AppNotice> ruleReviewResult(AgentTask task, Optional<AgentTurn> latest, AgentReview review, Optional<ReviewRunOverview> overview, RuleRun origin)
    {
        Optional<ReviewRun> current = reviews.listRuns(review.id()).stream().filter(run -> !run.explanationOnly()).reduce((first, last) -> last);
        if (current.isEmpty() || current.orElseThrow().active()) {
            return Optional.empty();
        }
        ReviewRun run = current.orElseThrow();
        if (run.state() != ReviewRun.State.ACCEPTED) {
            return Optional.of(notice(task, Optional.of(review), overview, latest, "review-stopped:" + run.id(), AppNotice.Kind.FAILURE,
                    run.outcome().orElse("The review did not finish; its saved findings remain provisional."), run.endedAt().orElseThrow()));
        }
        if (review.observedHeadSha().filter(head -> !head.equals(run.headSha())).isPresent()) {
            return Optional.empty();
        }
        // Stable per accepted report, including carried reviews that created no provider turn. Later discussions are not new review results.
        String findings = reviews.listFindings(review.id()).stream()
                .map(finding -> finding.id() + ":" + finding.version() + ":" + finding.state() + ":" + finding.outdated()).sorted().collect(joining(";"));
        String source = findings + ":" + origin.authorization().orElseThrow() + ":" + origin.key();
        String id = "review-result:" + run.id() + ":" + run.version() + ":" + UUID.nameUUIDFromBytes(source.getBytes(UTF_8));
        return Optional.of(notice(task, Optional.of(review), overview, latest, id, AppNotice.Kind.TURN_FINISHED,
                "The private review is ready. Read the full review for its findings and verification.", run.endedAt().orElseThrow()));
    }

    /**
     * A development turn whose task has a pull request names it and where it stands rather than calling it ready, since a draft or
     * failing checks are not done; the Finished card then opens the pull request.
     */
    private AppNotice finished(AgentTask task, Optional<AgentReview> review, Optional<ReviewRunOverview> overview, AgentTurn turn)
    {
        Optional<PullRequest> delivered = review.isPresent() ? Optional.empty() : ownership.linkedPullRequest(task);
        return notice(task, review, overview, Optional.of(turn), "finished:" + turn.id(), AppNotice.Kind.TURN_FINISHED,
                delivered.map(AppNoticeService::standing).orElse("The latest response is ready to read."), turn.endedAt().orElseThrow(), Optional.empty(), delivered);
    }

    private static String standing(PullRequest pullRequest)
    {
        String name = "PR #" + pullRequest.number();
        if ("merged".equals(pullRequest.state()) || "closed".equals(pullRequest.state())) {
            return name + " " + pullRequest.state();
        }
        // A row the sync has not enriched yet has no checks to report.
        String checks = switch (Optional.ofNullable(pullRequest.ciStatus()).orElse(PullRequestDetail.CiStatus.NONE)) {
            case PASSING -> " · checks passing";
            case FAILING -> " · checks failing";
            case PENDING -> " · checks running";
            case NONE -> "";
        };
        return (pullRequest.draft() ? "Draft " + name : name) + checks;
    }

    /** The latest CI run watching a development task, when it needs the user: a pause, failures the base also has, or no repair left. */
    private Optional<AppNotice> ciNotice(AgentTask task, Optional<AgentReview> review, Optional<ReviewRunOverview> overview, Optional<AgentTurn> latest)
    {
        return ciRuns.listByParentTaskId(task.id().toString()).stream().max(comparing(CiAutofixRun::createdAt)).flatMap(run -> {
            String key = "ci:" + run.id() + ":";
            if (run.status() == CiAutofixRun.Status.PAUSED) {
                return Optional.of(notice(task, review, overview, latest, key + "paused:" + run.version(), AppNotice.Kind.CI_ATTENTION,
                        "CI Autofix paused: " + run.summary().orElseThrow(), run.updatedAt()));
            }
            if (run.finishReason().filter(CiAutofixRun.FinishReason.MAX_ATTEMPTS::equals).isPresent()) {
                return Optional.of(notice(task, review, overview, latest, key + "attempts", AppNotice.Kind.CI_ATTENTION,
                        run.summary().orElseThrow(), run.terminalAt().orElseThrow()));
            }
            return run.baseFailureWarning().filter(warning -> run.status() == CiAutofixRun.Status.ACTIVE)
                    .map(warning -> notice(task, review, overview, latest, key + warning.code(), AppNotice.Kind.CI_ATTENTION, warning.message(), warning.occurredAt()));
        });
    }

    private String question(String detail)
    {
        try {
            String text = Optional.ofNullable(mapper.readTree(detail)).map(node -> node.path("questions").path(0).path("question").asText()).orElse("");
            if (!text.isBlank()) {
                return text;
            }
        }
        catch (JsonProcessingException ignored) {
            // Provider input can be incomplete; answering still takes place in the full task view.
        }
        return "The agent has a question for you.";
    }

    private AppNotice notice(AgentTask task, Optional<AgentReview> review, Optional<ReviewRunOverview> overview, Optional<AgentTurn> turn,
            String id, AppNotice.Kind kind, String message, Instant occurredAt)
    {
        return notice(task, review, overview, turn, id, kind, message, occurredAt, Optional.empty(), Optional.empty());
    }

    private AppNotice notice(AgentTask task, Optional<AgentReview> review, Optional<ReviewRunOverview> overview, Optional<AgentTurn> turn,
            String id, AppNotice.Kind kind, String message, Instant occurredAt, Optional<Instant> expiresAt, Optional<PullRequest> delivered)
    {
        Optional<AgentReview> numbered = review.filter(value -> value.number().isPresent());
        Optional<String> pullRequest = numbered.map(value -> value.repo() + "#" + value.number().getAsInt())
                .or(() -> delivered.map(value -> value.repo() + "#" + value.number()));
        Optional<PullRequest> stored = numbered
                .flatMap(value -> pullRequests.findIdByRepoAndNumber(value.repo(), value.number().getAsInt()))
                .flatMap(pullRequests::findById);
        Optional<String> pullRequestTitle = stored.map(PullRequest::title).filter(title -> !title.isBlank()).map(title -> clip(title, 240));
        Optional<String> branch = (review.isPresent() ? stored.map(PullRequest::headRef) : task.taskWorktree().map(TaskWorktree::generatedBranch))
                .filter(value -> !value.isBlank());
        Optional<AppNotice.Changes> changes = overview.map(run -> new AppNotice.Changes(
                run.scope() == ReviewRun.Scope.INCREMENTAL, run.commits(), run.files(), run.additions(), run.deletions()));
        return new AppNotice(id, kind, task.id().toString(), task.workspaceId().orElseThrow(), turn.map(value -> value.id().toString()), review.map(AgentReview::id),
                clip(task.title(), 160), pullRequest, pullRequestTitle, branch, changes, clip(message, 280), task.repository().toString(), task.laneId().value(),
                Optional.empty(), occurredAt, expiresAt);
    }

    private static String clip(String text, int limit)
    {
        return ContextText.excerpt(text.strip().replaceAll("\\s+", " "), limit);
    }
}
