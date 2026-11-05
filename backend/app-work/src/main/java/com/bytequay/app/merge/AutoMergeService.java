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
package com.bytequay.app.merge;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.QueuedInputStore;
import com.bytequay.agent.spi.DevelopmentResultReport;
import com.bytequay.app.agent.runtime.AgentServingGate;
import com.bytequay.app.discussion.ReviewFixService;
import com.bytequay.app.service.pr.PullRequestRead;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.settings.AppSettingsService;
import com.bytequay.app.workspace.TaskPullRequestOwnership;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixStore;
import com.bytequay.core.domain.MergeResult;
import com.bytequay.core.domain.PrMergeQueueRemoval;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.merge.AutoMerge;
import com.bytequay.core.merge.AutoMergeStore;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.core.store.PullRequestReplacementStore;
import com.google.common.collect.ImmutableSet;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;

/**
 * Auto-merge: once a task's pull request can merge at one head, waits for the delay set in Settings, then rebase-merges
 * it or joins the base branch's merge queue. The provider's own merge state decides required checks, approvals, conflicts and
 * whether the branch is behind; the task's own CI fix and review fix must also have nothing left. The merge names the
 * head it checked, so nothing newer can be merged, and a merge that was started is never sent again.
 */
@Service
public class AutoMergeService
{
    private static final Logger log = LoggerFactory.getLogger(AutoMergeService.class);
    private static final Set<String> MERGEABLE = ImmutableSet.of("clean", "has_hooks");
    private static final Set<AutoMerge.Outcome> FINAL = ImmutableSet.of(AutoMerge.Outcome.MERGED, AutoMerge.Outcome.CANCELLED, AutoMerge.Outcome.UNKNOWN);
    private static final Set<String> QUEUE_EVENTS = ImmutableSet.of("added_to_merge_queue", "removed_from_merge_queue", "auto_merge_disabled");
    // GitHub stamps queue events with its own clock; the slack keeps a local clock running ahead from hiding them.
    private static final Duration CLOCK_SLACK = Duration.ofMinutes(1);
    private static final Duration DETAIL_FRESHNESS = Duration.ofMinutes(1);

    private final AgentTaskStore tasks;
    private final AgentTurnStore turns;
    private final QueuedInputStore queue;
    private final AgentServingGate gate;
    private final TaskPullRequestOwnership ownership;
    private final PullRequestService pullRequests;
    private final PrDetailStore details;
    private final CiAutofixStore ciRuns;
    private final ReviewFixService reviewFixes;
    private final AutoMergeStore store;
    private final PullRequestReplacementStore replacements;
    private final AppSettingsService settings;
    private final Clock clock;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("auto-merge-", 0).factory());

    public AutoMergeService(AgentTaskStore tasks, AgentTurnStore turns, QueuedInputStore queue, AgentServingGate gate, TaskPullRequestOwnership ownership,
            PullRequestService pullRequests, PrDetailStore details, CiAutofixStore ciRuns, ReviewFixService reviewFixes, AutoMergeStore store,
            PullRequestReplacementStore replacements, AppSettingsService settings, Clock clock)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.queue = requireNonNull(queue, "queue is null");
        this.gate = requireNonNull(gate, "gate is null");
        this.ownership = requireNonNull(ownership, "ownership is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.details = requireNonNull(details, "details is null");
        this.ciRuns = requireNonNull(ciRuns, "ciRuns is null");
        this.reviewFixes = requireNonNull(reviewFixes, "reviewFixes is null");
        this.store = requireNonNull(store, "store is null");
        this.replacements = requireNonNull(replacements, "replacements is null");
        this.settings = requireNonNull(settings, "settings is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /** The scheduler thread also times out approvals, so the provider calls run on this service's own thread. */
    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    public void monitor()
    {
        if (!gate.isReady()) {
            return;
        }
        executor.execute(() -> tasks.listLive().stream()
                .filter(task -> task.interactive() && task.state() == TaskState.OPEN)
                .filter(task -> task.defaults().developmentAutomation().filter(DevelopmentAutomation::autoMerge).isPresent())
                .forEach(task -> {
                    try {
                        advance(task);
                    }
                    catch (RuntimeException deferred) {
                        log.info("Auto-merge on task {} was deferred: {}", task.id(), deferred.toString());
                    }
                }));
    }

    synchronized void advance(AgentTask task)
    {
        if (replacements.find(task.id().toString()).filter(operation -> !operation.completed()).isPresent()) {
            return;
        }
        Optional<PullRequest> linked = ownership.linkedPullRequest(task).filter(TaskPullRequestOwnership::isOpen);
        if (linked.isEmpty()) {
            return;
        }
        PullRequest pr = linked.orElseThrow();
        String taskId = task.id().toString();
        Optional<AutoMerge> saved = store.find(taskId);
        if (saved.flatMap(AutoMerge::outcome).filter(FINAL::contains).isPresent()) {
            return;
        }
        if (saved.filter(merge -> merge.startedAt().isPresent() && merge.outcome().isEmpty()).isPresent()) {
            store.save(saved.orElseThrow().ended(AutoMerge.Outcome.UNKNOWN, "The merge's outcome is unknown; check the pull request before merging it"));
            return;
        }
        pullRequests.warmDetail(pr.repo(), pr.number(), DETAIL_FRESHNESS);
        Optional<StoredPrDetail> detail = details.find(pr.id());
        if (detail.isEmpty() || detail.orElseThrow().raw().headSha() == null) {
            return;
        }
        PrRawDetail raw = detail.orElseThrow().raw();
        String head = raw.headSha();
        // Joining the queue is not merging: until GitHub merges or closes the pull request, the queue can still drop it.
        Optional<AutoMerge> queued = saved.filter(merge -> merge.outcome().filter(AutoMerge.Outcome.QUEUED::equals).isPresent() && merge.headSha().equals(head));
        if (queued.isPresent()) {
            leftQueue(detail.orElseThrow().timeline(), queued.orElseThrow().startedAt().orElseThrow())
                    .ifPresent(reason -> store.save(queued.orElseThrow().ended(AutoMerge.Outcome.FAILED, reason)));
            return;
        }
        // A failed merge waits for a new head rather than trying the same one again.
        if (saved.flatMap(AutoMerge::outcome).isPresent() && saved.orElseThrow().headSha().equals(head)) {
            return;
        }
        AutoMerge current = saved.filter(merge -> merge.headSha().equals(head) && merge.outcome().isEmpty())
                .orElseGet(() -> AutoMerge.waiting(taskId, head, "Checking whether the pull request can merge"));
        Optional<String> waitingFor = waitingFor(task, pr, detail.orElseThrow());
        if (waitingFor.isPresent()) {
            AutoMerge waiting = AutoMerge.waiting(taskId, head, waitingFor.orElseThrow());
            if (!waiting.equals(current) || saved.isEmpty()) {
                store.save(waiting);
            }
            return;
        }
        Instant now = clock.instant();
        if (current.readySince().isEmpty()) {
            current = current.ready(now);
            store.save(current);
        }
        if (now.isBefore(current.readySince().orElseThrow().plus(delay()))) {
            return;
        }
        merge(current, pr, raw);
    }

    /** Stops a waiting merge for this task; it stays stopped, like a stopped automation. */
    public synchronized void cancel(String taskId)
    {
        AutoMerge current = store.find(taskId).orElseThrow(() -> new IllegalStateException("This task has no merge waiting"));
        checkState(current.outcome().isEmpty() && current.startedAt().isEmpty(), "This task's merge has already started or ended");
        store.save(current.ended(AutoMerge.Outcome.CANCELLED, "Auto-merge was stopped"));
    }

    /** Called while the replacement's user turn owns the task, before any remote mutation. */
    public synchronized void preparePullRequestReplacement(String taskId)
    {
        store.find(taskId).ifPresent(current -> {
            checkState(current.startedAt().isEmpty(), "The original pull request's merge has started; reconcile its outcome before replacing it");
            if (current.outcome().filter(AutoMerge.Outcome.CANCELLED::equals).isEmpty()) {
                store.save(AutoMerge.waiting(taskId, current.headSha(), "Waiting for pull request replacement to finish"));
            }
        });
    }

    /** A replacement at the same commit still needs its own checks and full merge delay; an explicit stop stays stopped. */
    public synchronized void pullRequestReplaced(String taskId, String headSha)
    {
        store.find(taskId).ifPresent(current -> {
            checkState(current.startedAt().isEmpty(), "The original pull request's merge has started; its outcome must not be discarded");
            if (current.outcome().filter(AutoMerge.Outcome.CANCELLED::equals).isEmpty()) {
                store.save(AutoMerge.waiting(taskId, headSha, "Checking the replacement pull request before starting a new merge delay"));
            }
        });
    }

    /**
     * Where the task's merge stands, for its automation status; empty before the pull request is first checked. A queued merge
     * ends with its linked pull request, which is the merged one or none once it is merged or closed, and a task whose
     * pull request ended is no longer checked.
     */
    public Optional<AutoMergeSummary> summary(String taskId, Optional<PullRequest> linked)
    {
        String queued = linked.map(pr -> TaskPullRequestOwnership.isOpen(pr) ? AutoMerge.Outcome.QUEUED : AutoMerge.Outcome.MERGED).orElse(AutoMerge.Outcome.CANCELLED).name();
        return store.find(taskId).map(merge -> {
            String state = merge.outcome().map(outcome -> outcome == AutoMerge.Outcome.QUEUED ? queued : outcome.name())
                    .orElse(merge.startedAt().isPresent() ? "MERGING" : merge.readySince().isPresent() ? "READY" : "WAITING");
            return new AutoMergeSummary(state, merge.readySince().filter(ignored -> merge.outcome().isEmpty()).map(since -> since.plus(delay())), merge.message());
        });
    }

    /**
     * Why the pull request is no longer on its way into the merge queue it was sent to: its latest queue event since then is
     * a removal, or auto-merge was turned off before it joined.
     */
    private static Optional<String> leftQueue(List<PrTimelineEvent> timeline, Instant sentAt)
    {
        Instant since = sentAt.minus(CLOCK_SLACK);
        List<PrTimelineEvent> events = timeline.stream()
                .filter(event -> QUEUE_EVENTS.contains(event.event()) && event.timestamp() != null && !event.timestamp().isBefore(since))
                .sorted(comparing(PrTimelineEvent::timestamp))
                .toList();
        Optional<PrTimelineEvent> latest = events.stream().filter(event -> !event.event().equals("auto_merge_disabled")).reduce((previous, next) -> next);
        if (latest.filter(event -> event.event().equals("removed_from_merge_queue")).isPresent()) {
            String reason = Optional.ofNullable(latest.orElseThrow().payload().mergeQueueRemoval()).flatMap(PrMergeQueueRemoval::reason).map(text -> " (" + text + ")").orElse("");
            return Optional.of("The merge queue removed the pull request" + reason + "; a new push starts the wait again");
        }
        if (latest.isEmpty() && events.stream().anyMatch(event -> event.event().equals("auto_merge_disabled"))) {
            return Optional.of("Auto-merge was turned off before the pull request joined the merge queue; a new push starts the wait again");
        }
        return Optional.empty();
    }

    private Optional<String> waitingFor(AgentTask task, PullRequest pr, StoredPrDetail detail)
    {
        // The merge ends delivering the task's own work: not while it is still working, and not before its latest request
        // delivered, so opening a task on an existing pull request merges nothing by itself.
        if (task.developmentContinuationUnfinished() || task.worktreeMutationClaim().isPresent()
                || turns.findActive(task.id()).isPresent() || queue.listQueued(task.id()).stream().anyMatch(input -> input.state().live())) {
            return Optional.of("Waiting for the task to finish its current work");
        }
        Optional<AgentTurn> latest = turns.list(task.id()).stream().filter(turn -> turn.developmentOrigin().isEmpty()).reduce((previous, next) -> next);
        if (latest.filter(turn -> turn.state() == TurnState.COMPLETED && turn.cancellationRequestedAt().isEmpty()
                && turn.developmentResult().filter(DevelopmentResultReport::readyForDelivery).isPresent()).isEmpty()) {
            return Optional.of("Waiting for the task to deliver its latest request");
        }
        if (detail.unavailableSections().contains(StoredPrDetail.REVIEW_THREADS)) {
            return Optional.of("Waiting for review threads to refresh");
        }
        PrRawDetail raw = detail.raw();
        if (raw.draft()) {
            return Optional.of("Waiting for the pull request to leave draft");
        }
        String state = Optional.ofNullable(raw.mergeableState()).orElse("unknown").toLowerCase(Locale.ROOT);
        if (!MERGEABLE.contains(state)) {
            return Optional.of("Waiting until the pull request can merge (" + state + ")");
        }
        Optional<CiAutofixRun> ci = ciRuns.listByParentTaskId(task.id().toString()).stream().max(comparing(CiAutofixRun::createdAt));
        if (ci.isPresent() && !(ci.get().status() == CiAutofixRun.Status.FINISHED && ci.get().finishReason().filter(reason -> reason == CiAutofixRun.FinishReason.GREEN).isPresent()
                && ci.get().expectedRemoteHead().equals(raw.headSha()))) {
            return Optional.of("Waiting for CI fix to pass at the pull request's head");
        }
        if (!reviewFixes.settled(task, pr)) {
            return Optional.of("Waiting for open review threads");
        }
        return Optional.empty();
    }

    private void merge(AutoMerge ready, PullRequest pr, PrRawDetail raw)
    {
        merge(ready, pr, raw, Optional.empty());
    }

    /** A rule grants the same checked merge, immediately, for an owned PR and one explicitly approved head. */
    public synchronized AutoMerge mergeAuthorized(PullRequestRead route, PullRequest pr, String expectedHead, String actionKey,
            Predicate<PullRequestDetail> matches, BooleanSupplier authorized)
    {
        String key = "pr:" + pr.id();
        AutoMerge pending = AutoMerge.waiting(key, expectedHead, "Checking the rule's merge conditions").authorizedBy(actionKey);
        Optional<AutoMerge> prior = store.find(key).filter(value -> value.startedAt().isPresent());
        if (prior.isEmpty() && !authorized.getAsBoolean()) {
            return pending.ended(AutoMerge.Outcome.CANCELLED, "The rule changed or was paused");
        }
        PullRequestDetail fresh = pullRequests.refreshForAction(route);
        Optional<StoredPrDetail> detail = details.find(pr.id());
        checkState(fresh.id() == pr.id() && fresh.number() == pr.number() && fresh.repo().equalsIgnoreCase(pr.repo()), "The refreshed pull request changed identity");
        if (prior.isPresent() && !prior.orElseThrow().actionKey().filter(actionKey::equals).isPresent()
                && (prior.orElseThrow().headSha().equals(expectedHead) || prior.orElseThrow().outcome().isEmpty()
                        || prior.orElseThrow().outcome().filter(AutoMerge.Outcome.UNKNOWN::equals).isPresent())) {
            return pending.ended(AutoMerge.Outcome.CANCELLED, "Another authorized action already owns this merge; inspect its result");
        }
        if (fresh.mergedAt() != null) {
            if (prior.isEmpty()) {
                return pending.ended(AutoMerge.Outcome.CANCELLED, "The pull request was already merged; this rule did not send a merge");
            }
            AutoMerge merged = prior.orElse(pending).ended(AutoMerge.Outcome.MERGED, "Merged");
            store.save(merged);
            return merged;
        }
        if (!"open".equalsIgnoreCase(fresh.state())) {
            return pending.ended(AutoMerge.Outcome.CANCELLED, "The pull request was closed without merging");
        }
        if (prior.filter(value -> value.outcome().isEmpty() || value.outcome().filter(AutoMerge.Outcome.UNKNOWN::equals).isPresent()).isPresent()) {
            AutoMerge unknown = prior.orElseThrow().ended(AutoMerge.Outcome.UNKNOWN, "The merge outcome is unknown; check the pull request before merging it");
            store.save(unknown);
            return unknown;
        }
        if (!expectedHead.equals(fresh.headSha())) {
            return pending.ended(AutoMerge.Outcome.CANCELLED, "The pull request has a new head; this action is no longer current");
        }
        if (detail.isEmpty() || !expectedHead.equals(detail.orElseThrow().raw().headSha())) {
            return pending;
        }
        if (prior.filter(value -> value.headSha().equals(expectedHead) && value.outcome().filter(AutoMerge.Outcome.QUEUED::equals).isPresent()).isPresent()) {
            AutoMerge queued = prior.orElseThrow();
            AutoMerge observed = leftQueue(detail.orElseThrow().timeline(), queued.startedAt().orElseThrow())
                    .map(reason -> queued.ended(AutoMerge.Outcome.FAILED, reason)).orElse(queued);
            store.save(observed);
            return observed;
        }
        if (!fresh.author().equalsIgnoreCase(route.login()) || !"open".equalsIgnoreCase(fresh.state()) || fresh.draft()
                || fresh.ciStatus() != PullRequestDetail.CiStatus.PASSING || !fresh.viewerCanWrite() || !approvedHead(detail.orElseThrow(), expectedHead)) {
            return pending;
        }
        if (!matches.test(fresh)) {
            return pending.ended(AutoMerge.Outcome.CANCELLED, "The pull request no longer matches the rule's scope");
        }
        if (!Boolean.TRUE.equals(fresh.mergeable()) || !MERGEABLE.contains(Optional.ofNullable(fresh.mergeableState()).orElse("").toLowerCase(Locale.ROOT))) {
            return pending;
        }
        for (AgentTask task : tasks.listLive()) {
            if (!task.interactive() || ownership.linkedPullRequest(task).filter(linked -> linked.id() == pr.id()).isEmpty()) {
                continue;
            }
            Optional<AutoMerge> taskMerge = store.find(task.id().toString());
            if (taskMerge.flatMap(AutoMerge::outcome).filter(AutoMerge.Outcome.CANCELLED::equals).isPresent()) {
                return pending.ended(AutoMerge.Outcome.CANCELLED, "Auto-merge was stopped for this work");
            }
            if (replacements.find(task.id().toString()).filter(operation -> !operation.completed()).isPresent()
                    || waitingFor(task, pr, detail.orElseThrow()).isPresent()) {
                return pending;
            }
        }
        if (!authorized.getAsBoolean()) {
            return pending.ended(AutoMerge.Outcome.CANCELLED, "The rule changed or was paused");
        }
        return merge(pending.ready(clock.instant()), pr, detail.orElseThrow().raw(), Optional.of(route));
    }

    static boolean approvedHead(StoredPrDetail detail, String head)
    {
        if (!detail.reviewsComplete() || !detail.checkRunsComplete() || !detail.unavailableSections().isEmpty()) {
            return false;
        }
        var verdicts = PrReviewState.latestVerdicts(detail.reviews());
        return verdicts.stream().noneMatch(review -> "CHANGES_REQUESTED".equals(review.state()))
                && verdicts.stream().anyMatch(review -> "APPROVED".equals(review.state()) && head.equals(review.commitId()));
    }

    private AutoMerge merge(AutoMerge ready, PullRequest pr, PrRawDetail raw, Optional<PullRequestRead> route)
    {
        String operation = "pr:" + pr.id();
        Optional<AutoMerge> earlier = store.find(operation).filter(value -> value.startedAt().isPresent()
                && (value.headSha().equals(ready.headSha()) || value.outcome().isEmpty() || value.outcome().filter(AutoMerge.Outcome.UNKNOWN::equals).isPresent()));
        if (earlier.isPresent()) {
            AutoMerge observed = earlier.orElseThrow();
            AutoMerge result = observed.outcome().isPresent() ? observed
                    : observed.ended(AutoMerge.Outcome.UNKNOWN, "The merge outcome is unknown; refresh the pull request before retrying");
            store.save(new AutoMerge(ready.taskId(), result.headSha(), result.readySince(), result.startedAt(), result.outcome(), result.message(), result.actionKey()));
            return result;
        }
        AutoMerge started = ready.started(clock.instant());
        // Both task automation and rules reserve the PR operation before sending it, so overlapping rules cannot merge twice.
        store.save(new AutoMerge(operation, started.headSha(), started.readySince(), started.startedAt(), started.outcome(), started.message(), started.actionKey()));
        store.save(started);
        AutoMerge finished;
        try {
            boolean queue = raw.baseRef() != null && route.map(read -> pullRequests.hasMergeQueue(read, raw.baseRef()))
                    .orElseGet(() -> pullRequests.hasMergeQueue(pr.repo(), raw.baseRef()));
            MergeResult result = route.map(read -> pullRequests.mergePullRequest(read, pr.id(), queue ? "queue" : "rebase", started.headSha()))
                    .orElseGet(() -> pullRequests.mergePullRequest(pr.repo(), pr.number(), pr.id(), queue ? "queue" : "rebase", started.headSha()));
            if (result.merged()) {
                finished = started.ended(AutoMerge.Outcome.MERGED, "Merged");
            }
            // A branch that gained a queue since it was checked turns the rebase into a queue entry too.
            else if (result.queued()) {
                finished = started.ended(AutoMerge.Outcome.QUEUED, Optional.ofNullable(result.message()).orElse("In the merge queue"));
            }
            else {
                finished = started.ended(AutoMerge.Outcome.FAILED, Optional.ofNullable(result.message()).orElse("The pull request was not merged"));
            }
        }
        catch (RuntimeException failure) {
            log.warn("Auto-merge of {}#{} failed", pr.repo(), pr.number(), failure);
            finished = started.ended(AutoMerge.Outcome.UNKNOWN, "The merge outcome is unknown; check the pull request before merging it: "
                    + Optional.ofNullable(failure.getMessage()).orElse(failure.getClass().getSimpleName()));
        }
        store.save(finished);
        store.save(new AutoMerge(operation, finished.headSha(), finished.readySince(), finished.startedAt(), finished.outcome(), finished.message(), finished.actionKey()));
        return finished;
    }

    private Duration delay()
    {
        return Duration.ofMinutes(settings.preferences().autoMergeDelayMinutes());
    }

    @PreDestroy
    public void close()
    {
        executor.shutdownNow();
    }

    /**
     * @param state WAITING, READY, MERGING, MERGED, QUEUED, FAILED, UNKNOWN or CANCELLED
     * @param mergeAt when a ready pull request merges
     */
    public record AutoMergeSummary(String state, Optional<Instant> mergeAt, Optional<String> message) {}
}
