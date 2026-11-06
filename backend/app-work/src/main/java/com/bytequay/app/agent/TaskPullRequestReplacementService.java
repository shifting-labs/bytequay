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
package com.bytequay.app.agent;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.impl.AgentRuntimeRegistry;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.AgentUnitOfWork;
import com.bytequay.agent.core.store.QueuedInputStore;
import com.bytequay.agent.spi.TaskPullRequestReplacementBridge.Prepared;
import com.bytequay.app.ci.CiAutofixService;
import com.bytequay.app.ci.StartCiAutofixCommand;
import com.bytequay.app.discussion.ReviewFixService;
import com.bytequay.app.merge.AutoMergeService;
import com.bytequay.app.service.hosting.HostingIntegrations;
import com.bytequay.app.service.pr.PullRequestRefreshService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.workspace.TaskPullRequestOwnership;
import com.bytequay.app.workspace.WorkspaceHostingRead;
import com.bytequay.app.workspace.WorkspaceHostingService;
import com.bytequay.app.workspace.WorkspaceOperationLocks;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.PullRequestIdentity;
import com.bytequay.core.domain.PullRequestReplacement;
import com.bytequay.core.domain.PullRequestTask;
import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.store.PullRequestReplacementStore;
import com.bytequay.core.store.PullRequestTaskStore;
import com.google.common.util.concurrent.Striped;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.Lock;

import static com.bytequay.app.utils.Locks.withLock;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

/** Replaces one task's PR after a concrete one-shot approval, retaining partial remote results for reconciliation. */
@Service
public class TaskPullRequestReplacementService
{
    private final AgentTaskStore tasks;
    private final AgentTurnStore turns;
    private final QueuedInputStore queue;
    private final AgentReviewStore reviews;
    private final TaskPullRequestOwnership ownership;
    private final PullRequestTaskStore assignments;
    private final PullRequestReplacementStore replacements;
    private final DevelopmentGitInspector git;
    private final WorkspaceHostingService hosting;
    private final HostingIntegrations integrations;
    private final PullRequestService pullRequests;
    private final PullRequestRefreshService refresh;
    private final CiAutofixService ci;
    private final ReviewFixService reviewFixes;
    private final AutoMergeService autoMerge;
    private final ObjectProvider<DevelopmentFollowupCoordinator> followups;
    private final AgentRuntimeRegistry runtime;
    private final AgentUnitOfWork unitOfWork;
    private final TaskTurnWords words;
    private final Clock clock;
    private final Striped<Lock> operations = Striped.lock(64);

    public TaskPullRequestReplacementService(AgentTaskStore tasks, AgentTurnStore turns, QueuedInputStore queue, AgentReviewStore reviews, TaskPullRequestOwnership ownership,
            PullRequestTaskStore assignments, PullRequestReplacementStore replacements, DevelopmentGitInspector git, WorkspaceHostingService hosting,
            HostingIntegrations integrations, PullRequestService pullRequests, PullRequestRefreshService refresh, CiAutofixService ci,
            ReviewFixService reviewFixes, AutoMergeService autoMerge, ObjectProvider<DevelopmentFollowupCoordinator> followups,
            AgentRuntimeRegistry runtime, AgentUnitOfWork unitOfWork, TaskTurnWords words, Clock clock)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.queue = requireNonNull(queue, "queue is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.ownership = requireNonNull(ownership, "ownership is null");
        this.assignments = requireNonNull(assignments, "assignments is null");
        this.replacements = requireNonNull(replacements, "replacements is null");
        this.git = requireNonNull(git, "git is null");
        this.hosting = requireNonNull(hosting, "hosting is null");
        this.integrations = requireNonNull(integrations, "integrations is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.refresh = requireNonNull(refresh, "refresh is null");
        this.ci = requireNonNull(ci, "ci is null");
        this.reviewFixes = requireNonNull(reviewFixes, "reviewFixes is null");
        this.autoMerge = requireNonNull(autoMerge, "autoMerge is null");
        this.followups = requireNonNull(followups, "followups is null");
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.unitOfWork = requireNonNull(unitOfWork, "unitOfWork is null");
        this.words = requireNonNull(words, "words is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public Optional<AgentTask> eligible(TaskId id)
    {
        return tasks.find(id).filter(task -> task.interactive() && task.ownsWorktree() && task.state() == TaskState.OPEN)
                .filter(task -> !task.permissionPolicy().unattended() && reviews.findReviewByTask(id.toString()).isEmpty());
    }

    /** The replacement onto {@code branch}, which runs at once when {@code said} is the user's own request in this turn. */
    public Prepared prepare(TaskId id, TurnId turnId, String branch, Optional<String> said)
    {
        AgentTask task = requireTask(id, turnId);
        Optional<PullRequestReplacement> stored = replacements.find(id.toString());
        checkState(stored.filter(value -> !value.completed() && !value.branch().equals(branch)).isEmpty(),
                "Finish the pending PR replacement on branch %s before choosing another branch", stored.map(PullRequestReplacement::branch).orElse(""));
        DevelopmentBaseline baseline = git.capture(task);
        WorkspaceHostingRead selected = hosting.captureRead(baseline.worktree().workspaceId());
        checkArgument(selected.session().access().server().provider() == HostingProvider.GITHUB, "Task PR replacement currently supports GitHub");
        PullRequestReplacement plan;
        if (stored.filter(value -> value.branch().equals(branch)).isPresent()) {
            plan = stored.orElseThrow();
            requireRoute(plan, baseline, selected);
        }
        else {
            int number = ownership.linkedPullRequest(task).orElseThrow(() -> new IllegalStateException("This task has no linked pull request to replace")).number();
            PullRequestIdentity original = integrations.of(selected.target()).contents().identity(selected.target(), number);
            checkState(original.state() == PullRequestIdentity.State.OPEN, "Only an open pull request can be replaced");
            checkState(original.author().equalsIgnoreCase(selected.login()), "Only your own task pull request can be replaced");
            checkState(original.headRepository().equals(baseline.route().headRepository()) && original.baseRepository().equals(baseline.route().baseRepository())
                    && original.headBranch().equals(baseline.route().headBranch()) && original.baseBranch().equals(baseline.route().baseBranch()),
                    "The linked pull request no longer matches the task's publication route");
            checkArgument(!branch.equals(original.headBranch()) && !branch.equals(original.baseBranch()), "Choose a different, non-target branch for the replacement");
            requireRevision(baseline, original.headSha());
            DevelopmentPublicationFacts target = git.inspectReplacement(baseline.worktree(), baseline.route(), branch);
            checkState(target.matchingPullRequests().isEmpty(), "The replacement branch already has a pull request; no existing PR will be adopted");
            checkState(target.remoteHeadSha().isEmpty() || target.remoteHeadSha().filter(original.headSha()::equals).isPresent(),
                    "The replacement branch contains a different commit; it will not be overwritten");
            String title = hosting.readPullRequest(selected, number).pullRequest().title();
            String body = integrations.of(selected.target()).contents().description(selected.target(), number);
            plan = new PullRequestReplacement(id.toString(), baseline.worktree().workspaceId(), selected.session().accountId(), selected.repository().id(),
                    original.headRepository(), original.baseRepository(), number, original.headBranch(), original.baseBranch(), original.headSha(), branch,
                    title, body, original.draft(), false, Optional.empty(), false);
        }
        String preview = plan.completed() ? "PR #" + plan.replacementNumber().orElseThrow() + " already replaced PR #" + plan.previousNumber()
                + ". Reconnect this task's enabled CI tracking without creating another PR or closing anything again."
                : "Replace " + plan.baseRepository().fullName() + "#" + plan.previousNumber() + " using branch " + plan.branch()
                + "\nCommit: " + plan.headSha() + "\nTitle: " + plan.title()
                + "\nCopy the existing description and " + (plan.draft() ? "draft" : "ready-for-review") + " status."
                + "\nCreate and verify the replacement, keep this conversation and its enabled CI/review tracking connected, then close the previous PR."
                + "\nExisting branches are kept. Existing GitHub reviews and checks remain on the previous PR; the replacement gets fresh checks."
                + (plan.createStarted() && !plan.completed() ? "\nResume the saved operation; an uncertain create will be checked, never blindly repeated." : "");
        return new Prepared(preview, () -> withLock(operations.get(id), () -> execute(id, turnId, plan, stored)),
                words.authorize(id, turnId, said, plan.branch()).map(quoted -> "the user asked: “" + quoted + "”"));
    }

    private String execute(TaskId id, TurnId turnId, PullRequestReplacement approved, Optional<PullRequestReplacement> expected)
    {
        AgentTask task = requireTask(id, turnId);
        checkState(replacements.find(id.toString()).equals(expected), "The replacement changed while awaiting approval; request a fresh preview");
        DevelopmentBaseline baseline = git.capture(task);
        WorkspaceHostingRead selected = hosting.captureRead(approved.workspaceId());
        requireRoute(approved, baseline, selected);
        if (approved.completed()) {
            PullRequestIdentity current = integrations.of(selected.target()).contents().identity(selected.target(), approved.replacementNumber().orElseThrow());
            checkState(current.headRepository().equals(approved.headRepository()) && current.headBranch().equals(approved.branch())
                    && current.baseRepository().equals(approved.baseRepository()) && current.baseBranch().equals(approved.baseBranch()),
                    "The completed replacement's destination changed");
            if (current.state() == PullRequestIdentity.State.OPEN) {
                resumeCi(task, turnId, current);
            }
            return "Replacement already completed: PR #" + approved.replacementNumber().orElseThrow() + " remains connected to this task.";
        }
        if (approved.replacementNumber().isEmpty()) {
            requireRevision(baseline, approved.headSha());
        }
        requireOriginal(approved, selected);
        autoMerge.preparePullRequestReplacement(id.toString());
        if (assignments.findByTaskId(id.toString()).filter(value -> approved.replacementNumber().filter(number -> number == value.number()).isPresent()).isEmpty()) {
            ci.stopDevelopment(id);
        }
        requireTask(id, turnId);
        replacements.save(approved);
        PullRequestReplacement operation = approved;
        try {
            operation = create(id, turnId, operation, baseline, selected);
            PullRequestIdentity replacement = requireReplacement(operation, selected);
            PullRequestDetail detail = refresh.refreshAllDetail(
                    pullRequests.prepareRead(operation.workspaceId(), operation.baseRepository().fullName(), replacement.pullRequest().number()));
            checkState(detail.number() == replacement.pullRequest().number(), "The replacement PR cache could not be verified");
            PullRequestReplacement verified = operation;
            reviewFixes.pullRequestReplaced(id, operation.baseRepository().fullName(), operation.previousNumber(), () -> {
                synchronized (runtime.taskLock(id)) {
                    requireTask(id, turnId);
                    WorkspaceOperationLocks.withLock(verified.workspaceId(), () -> unitOfWork.execute(() -> {
                        checkState(assignments.find(verified.workspaceId(), verified.baseRepository().fullName(), replacement.pullRequest().number())
                                .filter(value -> !value.taskId().equals(id.toString())).isEmpty(), "The replacement PR was assigned to another task");
                        Optional<PullRequestTask> previous = assignments.findByTaskId(id.toString());
                        checkState(previous.isEmpty() || previous.filter(value -> value.number() == verified.previousNumber()
                                || value.number() == replacement.pullRequest().number()).isPresent(), "The task's linked PR changed during replacement");
                        PullRequestTask next = new PullRequestTask(id.toString(), verified.workspaceId(), verified.baseRepository().fullName(),
                                replacement.pullRequest().number(), PullRequestTaskService.initialContext(detail, verified.headSha(), baseline.route().baseSha()));
                        checkState(assignments.reassign(next, previous), "The task's PR assignment changed during replacement");
                        replacements.save(verified);
                        return null;
                    }));
                }
            });
            autoMerge.pullRequestReplaced(id.toString(), operation.headSha());
            followups.getObject().pullRequestReplaced(task, turnId);
            requireTask(id, turnId);
            selected = hosting.captureRead(operation.workspaceId());
            requireRoute(operation, git.capture(task), selected);
            PullRequestIdentity original = requireOriginal(operation, selected);
            requireReplacement(operation, selected);
            if (original.state() == PullRequestIdentity.State.OPEN) {
                requireTask(id, turnId);
                integrations.of(selected.target()).writer().close(selected.target(), operation.previousNumber());
            }
            checkState(requireOriginal(operation, selected).state() == PullRequestIdentity.State.CLOSED,
                    "The replacement is tracked, but closing the previous PR could not be verified");
            refresh.refreshDetail(pullRequests.prepareRead(operation.workspaceId(), operation.baseRepository().fullName(), operation.previousNumber()));
            replacements.save(operation.complete());
            resumeCi(task, turnId, replacement);
            return "Created " + replacement.url() + "; closed PR #" + operation.previousNumber()
                    + ". This task, conversation, and enabled CI/review tracking now follow the replacement. Both branches were kept.";
        }
        catch (RuntimeException failure) {
            PullRequestReplacement saved = replacements.find(id.toString()).orElse(operation);
            String progress = saved.completed() ? "The PR replacement completed; reconnecting CI needs attention. "
                    : saved.replacementNumber().map(number -> "Replacement PR #" + number + " was recorded. ").orElse("The previous PR was kept. ");
            throw new IllegalStateException(progress + "Replacement needs attention: " + failure.getMessage()
                    + ". Call replace_task_pull_request again with branch " + approved.branch() + " to check and finish the saved operation in ByteQuay.", failure);
        }
    }

    private PullRequestReplacement create(TaskId id, TurnId turnId, PullRequestReplacement operation, DevelopmentBaseline baseline, WorkspaceHostingRead selected)
    {
        if (operation.replacementNumber().isPresent()) {
            return operation;
        }
        DevelopmentPublicationFacts facts = git.inspectReplacement(baseline.worktree(), baseline.route(), operation.branch());
        if (operation.createStarted()) {
            List<PullRequestIdentity> matches = facts.matchingPullRequests();
            checkState(matches.size() == 1, "The previous create has an uncertain outcome; no second PR will be created. Retry after GitHub exposes the result");
            PullRequestReplacement found = operation.created(matches.getFirst().pullRequest().number());
            requireReplacement(found, selected);
            replacements.save(found);
            return found;
        }
        checkState(facts.matchingPullRequests().isEmpty(), "A PR appeared on the replacement branch after approval; request a fresh preview");
        requireOriginal(operation, selected);
        DevelopmentBaseline current = git.capture(requireTask(id, turnId));
        requireRoute(operation, current, hosting.captureRead(operation.workspaceId()));
        checkState(current.route().equals(baseline.route()), "The publication route changed after approval");
        requireRevision(current, operation.headSha());
        if (facts.remoteHeadSha().isEmpty()) {
            git.pushReplacementHead(baseline.worktree(), baseline.route(), operation.branch(), operation.headSha());
        }
        facts = git.inspectReplacement(baseline.worktree(), baseline.route(), operation.branch());
        checkState(facts.remoteHeadSha().filter(operation.headSha()::equals).isPresent(), "The replacement branch no longer contains the approved commit");
        checkState(facts.matchingPullRequests().isEmpty(), "A PR appeared on the replacement branch; publication was held");
        requireTask(id, turnId);
        requireRoute(operation, git.capture(requireTask(id, turnId)), hosting.captureRead(operation.workspaceId()));
        requireOriginal(operation, selected);
        requireTask(id, turnId);
        PullRequestReplacement started = operation.started();
        replacements.save(started);
        var created = integrations.of(selected.target()).writer().create(selected.target(), operation.headRepository(), operation.branch(), operation.baseBranch(),
                operation.title(), operation.body(), operation.draft());
        PullRequestReplacement result = started.created(created.number());
        replacements.save(result);
        return result;
    }

    private PullRequestIdentity requireOriginal(PullRequestReplacement operation, WorkspaceHostingRead selected)
    {
        PullRequestIdentity original = integrations.of(selected.target()).contents().identity(selected.target(), operation.previousNumber());
        checkState(original.pullRequest().number() == operation.previousNumber()
                        && original.pullRequest().repoFullName().equalsIgnoreCase(operation.baseRepository().fullName())
                        && original.state() != PullRequestIdentity.State.MERGED && original.author().equalsIgnoreCase(selected.login())
                        && original.headSha().equals(operation.headSha()) && original.headRepository().equals(operation.headRepository())
                        && original.headBranch().equals(operation.previousBranch()) && original.baseRepository().equals(operation.baseRepository())
                        && original.baseBranch().equals(operation.baseBranch()),
                "The previous PR changed or merged; replacement is held");
        checkState(original.state() == PullRequestIdentity.State.OPEN || operation.replacementNumber().isPresent(), "The previous PR closed before a replacement was recorded");
        return original;
    }

    private PullRequestIdentity requireReplacement(PullRequestReplacement operation, WorkspaceHostingRead selected)
    {
        PullRequestIdentity replacement = integrations.of(selected.target()).contents().identity(selected.target(), operation.replacementNumber().orElseThrow());
        checkState(replacement.pullRequest().number() == operation.replacementNumber().orElseThrow()
                        && replacement.pullRequest().repoFullName().equalsIgnoreCase(operation.baseRepository().fullName())
                        && replacement.state() == PullRequestIdentity.State.OPEN && replacement.author().equalsIgnoreCase(selected.login())
                        && replacement.headSha().equals(operation.headSha()) && replacement.headRepository().equals(operation.headRepository())
                        && replacement.headBranch().equals(operation.branch()) && replacement.baseRepository().equals(operation.baseRepository())
                        && replacement.baseBranch().equals(operation.baseBranch()) && replacement.draft() == operation.draft(),
                "The replacement PR does not match the approved branch, revision, destination, or status");
        return replacement;
    }

    private void requireRoute(PullRequestReplacement operation, DevelopmentBaseline baseline, WorkspaceHostingRead selected)
    {
        checkState(operation.workspaceId().equals(baseline.worktree().workspaceId()) && operation.accountId() == selected.session().accountId()
                        && operation.repositoryId() == selected.repository().id() && selected.repositoryRef().equals(operation.baseRepository())
                        && baseline.route().headRepository().equals(operation.headRepository()) && baseline.route().baseRepository().equals(operation.baseRepository())
                        && baseline.route().baseBranch().equals(operation.baseBranch())
                        && baseline.route().assignedPullRequest().filter(pr -> pr.number() != operation.previousNumber()
                        && operation.replacementNumber().filter(number -> number == pr.number()).isEmpty()).isEmpty()
                        && (baseline.route().headBranch().equals(operation.previousBranch()) || baseline.route().headBranch().equals(operation.branch())),
                "The approved hosting account, repository, or task route changed; replacement is held");
    }

    private void requireRevision(DevelopmentBaseline baseline, String head)
    {
        DevelopmentLocalFacts local = git.inspectLocal(baseline.worktree(), baseline.route());
        checkState(local.headSha().equals(head) && local.uncommittedFileCount() == 0 && !local.gitOperationInProgress(),
                "PR replacement requires a clean checkout at the PR's current commit; finish or publish the local work first");
    }

    private AgentTask requireTask(TaskId id, TurnId turnId)
    {
        AgentTask task = eligible(id).orElseThrow(() -> new IllegalStateException("PR replacement is available only to the task's open development conversation"));
        checkState(turns.findActive(id).filter(turn -> turn.id().equals(turnId) && turn.cancellationRequestedAt().isEmpty() && turn.developmentOrigin().isEmpty()).isPresent(),
                "The user turn ended or was stopped; request PR replacement in the current conversation");
        checkState(queue.listQueued(id).stream().noneMatch(input -> input.state().live() && input.turnId().filter(turnId::equals).isEmpty()),
                "Finish or remove queued messages before replacing this task's PR");
        return task;
    }

    private void resumeCi(AgentTask task, TurnId turnId, PullRequestIdentity replacement)
    {
        AgentTurn turn = turns.findActive(task.id()).filter(value -> value.id().equals(turnId)).orElseThrow();
        DevelopmentAutomation automation = turn.settings().orElseThrow().developmentAutomation().orElse(DevelopmentAutomation.LOCAL_ONLY);
        if (automation.autoCiFix()) {
            requireTask(task.id(), turnId);
            ci.startDevelopment(new StartCiAutofixCommand(task.workspaceId().orElseThrow(), replacement.pullRequest().number(),
                    automation.ciLaneId().orElse(task.laneId()), "development-ci:" + task.id() + ":" + replacement.pullRequest().number() + ":" + replacement.headSha()),
                    task.id(), replacement, clock.instant());
        }
    }
}
