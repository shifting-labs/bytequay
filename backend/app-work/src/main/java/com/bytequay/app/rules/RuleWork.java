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
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.app.agenda.ItemTaskStarter;
import com.bytequay.app.review.AgentReviewService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.rules.AgentRuleView;
import com.bytequay.app.service.rules.RuleDefinition;
import com.bytequay.app.utils.ContextText;
import com.bytequay.app.workspace.TaskPullRequestOwnership;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.RuleRun;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.RuleRunStore;
import com.bytequay.core.store.WorkspaceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

import static com.bytequay.app.config.AsyncConfig.IO_EXECUTOR;
import static com.bytequay.core.domain.RuleRun.Verdict.AGREED;
import static com.bytequay.core.domain.RuleRun.Verdict.DISAGREED;
import static com.bytequay.core.domain.RuleRun.Verdict.EDITED;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/**
 * Does a rule's work in Suggest and reads back what the user did with it. A review only drafts findings, and a task only commits on this
 * Mac, so nothing reaches GitHub until the user publishes or pushes it themselves; what they do then is the run's verdict.
 */
@Component
public class RuleWork
{
    private static final Logger log = LoggerFactory.getLogger(RuleWork.class);
    static final String REVIEW = "review:";
    static final String TASK = "task:";

    private final AgentReviewService reviewer;
    private final AgentReviewStore reviews;
    private final PullRequestService reads;
    private final PullRequestStore pullRequests;
    private final WorkspaceStore workspaces;
    private final ItemTaskStarter starter;
    private final AgentTaskStore tasks;
    private final AgentTurnStore turns;
    private final TaskPullRequestOwnership ownership;
    private final RuleRunStore runs;
    private final ExecutorService executor;
    // ponytail: in memory, so a run still starting when ByteQuay stops is set aside at the next check rather than resumed.
    private final Set<String> starting = ConcurrentHashMap.newKeySet();

    public RuleWork(AgentReviewService reviewer, AgentReviewStore reviews, PullRequestService reads, PullRequestStore pullRequests, WorkspaceStore workspaces,
            ItemTaskStarter starter, AgentTaskStore tasks, AgentTurnStore turns, TaskPullRequestOwnership ownership, RuleRunStore runs,
            @Qualifier(IO_EXECUTOR) ExecutorService executor)
    {
        this.reviewer = requireNonNull(reviewer, "reviewer is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.reads = requireNonNull(reads, "reads is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.starter = requireNonNull(starter, "starter is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.ownership = requireNonNull(ownership, "ownership is null");
        this.runs = requireNonNull(runs, "runs is null");
        this.executor = requireNonNull(executor, "executor is null");
    }

    /** Starts the run's work away from the scheduler, since it fetches and checks out; the run then names it, or says why it has none. */
    public void start(AgentRuleView rule, RuleRun run)
    {
        String id = run.ruleId() + ":" + run.key();
        starting.add(id);
        executor.execute(() -> {
            RuleRun done;
            try {
                done = rule.rule().action() == RuleDefinition.Action.REVIEW ? review(rule, run) : task(rule, run);
            }
            catch (RuntimeException failure) {
                log.warn("Rule {} could not start its work for {}", rule.id(), run.subject(), failure);
                done = run.skip(clip("Could not start: " + Optional.ofNullable(failure.getMessage()).orElse(failure.getClass().getSimpleName())));
            }
            try {
                runs.save(done);
            }
            finally {
                starting.remove(id);
            }
        });
    }

    /**
     * Why the run's work cannot start, as far as stored data tells before trying, so a run that cannot start never takes one of the day's
     * suggestions: a review needs a pull request in a watched repository that has no review open yet.
     */
    public Optional<String> cannotStart(AgentRuleView rule, RuleRun run)
    {
        if (rule.rule().action() != RuleDefinition.Action.REVIEW) {
            return Optional.empty();
        }
        Optional<PullRequest> pr = pullRequests.findById(pullRequestId(run));
        if (pr.isEmpty()) {
            return Optional.of("The pull request is gone");
        }
        Optional<Workspace> workspace = watched(pr.orElseThrow());
        if (workspace.isEmpty()) {
            return Optional.of("Not in a watched repository");
        }
        return reviewer.find(workspace.orElseThrow().id(), pr.orElseThrow().repo(), pr.orElseThrow().number()).isPresent()
                ? Optional.of("Already has a review") : Optional.empty();
    }

    /** Whether the run's work is still being started. */
    public boolean starting(RuleRun run)
    {
        return starting.contains(run.ruleId() + ":" + run.key());
    }

    /** The task whose finished turn is the work's result, for the card that asks the user about it. */
    public Optional<String> resultTask(RuleRun run)
    {
        return run.work().flatMap(work -> work.startsWith(REVIEW) ? reviews.findReview(work.substring(REVIEW.length())).map(AgentReview::taskId)
                : Optional.of(work.substring(TASK.length())));
    }

    /** The run as the user's handling of its work leaves it: decided, set aside with a reason, or unchanged while it waits. */
    public RuleRun judge(RuleRun run)
    {
        String work = run.work().orElseThrow();
        return work.startsWith(REVIEW) ? judgeReview(run, work.substring(REVIEW.length())) : judgeTask(run, TaskId.valueOf(work.substring(TASK.length())));
    }

    private RuleRun review(AgentRuleView rule, RuleRun run)
    {
        // Checked again here, since the review was asked for a moment ago and the user may have started one since.
        Optional<String> cannot = cannotStart(rule, run);
        if (cannot.isPresent()) {
            return run.skip(cannot.orElseThrow());
        }
        PullRequest pr = pullRequests.findById(pullRequestId(run)).orElseThrow();
        String workspaceId = watched(pr).orElseThrow().id();
        reads.prepareRead(workspaceId, pr.repo(), pr.number());
        return run.started(REVIEW + reviewer.start(workspaceId, pr.repo(), pr.number(), rule.rule().body()).review().id());
    }

    private Optional<Workspace> watched(PullRequest pr)
    {
        return workspaces.findAll().stream().filter(each -> each.monitoredRepository().fullName().equalsIgnoreCase(pr.repo())).findFirst();
    }

    private static long pullRequestId(RuleRun run)
    {
        return Long.parseLong(run.key().split(":", 3)[1]);
    }

    private RuleRun task(AgentRuleView rule, RuleRun run)
    {
        // An issue's run is known by "issue:<id>:<host>" and is about "owner/repo#number"; a ticket's run key is its own ref.
        String ref = run.key().startsWith("jira:") ? run.key() : "issue:" + run.subject();
        String key = "rule:" + rule.id() + ":" + run.key();
        TaskId taskId = new TaskId(UUID.nameUUIDFromBytes(key.getBytes(UTF_8)));
        return starter.start(starter.item(ref).followedBy("\n\n" + rule.rule().body()), Optional.empty(), taskId, key, DevelopmentAutomation.DEFAULT)
                .map(started -> started.fresh() ? run.started(TASK + started.task().id()) : run.skip("Already has a task"))
                .orElseGet(() -> run.skip("The agent is not ready"));
    }

    /**
     * What a review shows of the user's handling of it.
     *
     * @param posted whether one of its findings was posted as written
     * @param findings the findings it has, withdrawn ones left out
     * @param finished when its first run ended
     * @param accepted whether that run's report was accepted
     * @param reviewed when the user last submitted a review of the pull request themselves
     */
    record ReviewSeen(boolean open, boolean posted, int findings, Optional<Instant> finished, boolean accepted, Optional<Instant> reviewed) {}

    /**
     * Agreed once one of its findings is posted as written; edited when the user reviews the pull request themselves after it, posting
     * none of them as written; disagreed when the review ends with neither. A review that found nothing, or did not finish, has nothing
     * to judge.
     */
    static RuleRun judge(RuleRun run, ReviewSeen review)
    {
        if (review.posted()) {
            return run.decide(AGREED);
        }
        if (review.finished().isPresent() && review.findings() == 0) {
            return run.skip(review.accepted() ? "Found nothing" : "The review did not finish");
        }
        if (review.finished().isPresent() && review.reviewed().filter(at -> at.isAfter(review.finished().orElseThrow())).isPresent()) {
            return run.decide(EDITED);
        }
        return review.open() ? run : run.decide(DISAGREED);
    }

    /**
     * What a task shows of the user's handling of it.
     *
     * @param dropped deleted, archived or failed
     * @param steered sent anything more than the rule's own first message
     * @param kept completed, or its pull request opened
     */
    record TaskSeen(boolean dropped, boolean steered, boolean kept) {}

    /** Agreed when the user keeps the work as it is; edited once they steer it; disagreed when they drop it. */
    static RuleRun judge(RuleRun run, TaskSeen task)
    {
        if (task.dropped()) {
            return run.decide(DISAGREED);
        }
        if (task.steered()) {
            return run.decide(EDITED);
        }
        return task.kept() ? run.decide(AGREED) : run;
    }

    private RuleRun judgeReview(RuleRun run, String reviewId)
    {
        Optional<AgentReview> review = reviews.findReview(reviewId);
        if (review.isEmpty()) {
            return run.decide(DISAGREED);
        }
        List<ReviewFinding> findings = reviews.listFindings(reviewId).stream().filter(finding -> finding.state() != ReviewFinding.State.WITHDRAWN).toList();
        Optional<ReviewRun> first = reviews.listRuns(reviewId).stream().filter(each -> each.endedAt().isPresent()).findFirst();
        Optional<Instant> reviewed = pullRequests.findById(pullRequestId(run)).map(PullRequest::reviewedAt);
        return judge(run, new ReviewSeen(review.orElseThrow().state() == AgentReview.State.OPEN,
                findings.stream().anyMatch(finding -> finding.stateReason().filter(ReviewFinding.POSTED::equals).isPresent()), findings.size(),
                first.flatMap(ReviewRun::endedAt), first.filter(each -> each.state() == ReviewRun.State.ACCEPTED).isPresent(), reviewed));
    }

    private RuleRun judgeTask(RuleRun run, TaskId taskId)
    {
        Optional<AgentTask> task = tasks.find(taskId);
        if (task.isEmpty() || task.orElseThrow().archivedAt().isPresent() || task.orElseThrow().state() == TaskState.FAILED) {
            return judge(run, new TaskSeen(true, false, false));
        }
        String first = "rule:" + run.ruleId() + ":" + run.key();
        boolean steered = turns.list(taskId).stream().anyMatch(turn -> turn.developmentOrigin().isEmpty() && !turn.idempotencyKey().equals(first));
        return judge(run, new TaskSeen(false, steered, task.orElseThrow().state() == TaskState.COMPLETED || ownership.linkedPullRequest(task.orElseThrow()).isPresent()));
    }

    private static String clip(String text)
    {
        return ContextText.excerpt(text.strip().replaceAll("\\s+", " "), 200);
    }
}
