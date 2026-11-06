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
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.QueuedInputStore;
import com.bytequay.agent.spi.TaskPublicationBridge.Prepared;
import com.bytequay.app.service.hosting.HostingIntegrations;
import com.bytequay.app.service.pr.PullRequestRefreshService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.workspace.TaskPullRequestOwnership;
import com.bytequay.app.workspace.WorkspaceHostingRead;
import com.bytequay.app.workspace.WorkspaceHostingService;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestIdentity;
import com.google.common.util.concurrent.Striped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.locks.Lock;

import static com.bytequay.app.utils.Locks.withLock;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

/**
 * Pushes a development task's branch and closes its own pull request with ByteQuay's credentials, so the task's agent never asks the user
 * to run git or gh. Each action is prepared with what it will do, runs at once when the user's own words in the turn ask for it and
 * otherwise after an Allow once, and rechecks the task, the branch and the remote right before it writes.
 */
@Service
public class TaskPublicationService
{
    private static final Logger log = LoggerFactory.getLogger(TaskPublicationService.class);
    private static final int TITLE = 80;

    private final TaskPullRequestReplacementService replacements;
    private final AgentTurnStore turns;
    private final QueuedInputStore queue;
    private final DevelopmentGitInspector git;
    private final WorkspaceHostingService hosting;
    private final HostingIntegrations integrations;
    private final TaskPullRequestOwnership ownership;
    private final PullRequestService pullRequests;
    private final PullRequestRefreshService refresh;
    private final TaskTurnWords words;
    private final Striped<Lock> operations = Striped.lock(64);

    public TaskPublicationService(TaskPullRequestReplacementService replacements, AgentTurnStore turns, QueuedInputStore queue, DevelopmentGitInspector git,
            WorkspaceHostingService hosting, HostingIntegrations integrations, TaskPullRequestOwnership ownership, PullRequestService pullRequests,
            PullRequestRefreshService refresh, TaskTurnWords words)
    {
        this.replacements = requireNonNull(replacements, "replacements is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.queue = requireNonNull(queue, "queue is null");
        this.git = requireNonNull(git, "git is null");
        this.hosting = requireNonNull(hosting, "hosting is null");
        this.integrations = requireNonNull(integrations, "integrations is null");
        this.ownership = requireNonNull(ownership, "ownership is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.refresh = requireNonNull(refresh, "refresh is null");
        this.words = requireNonNull(words, "words is null");
    }

    /** The open, attended development tasks these actions serve, as pull request replacement does. */
    public Optional<AgentTask> eligible(TaskId id)
    {
        return replacements.eligible(id);
    }

    /**
     * Pushes the task branch's committed head. The lease names the remote head read now, and it may replace only commits the task once had,
     * as delivery's own push does; commits it never had are someone else's work.
     */
    public Prepared push(TaskId id, TurnId turnId, Optional<String> said)
    {
        AgentTask task = requireTask(id, turnId);
        DevelopmentBaseline baseline = git.capture(task);
        checkState(baseline.local().uncommittedFileCount() == 0 && !baseline.local().gitOperationInProgress(), "Commit the work first: only committed work is pushed");
        String head = baseline.local().headSha();
        Optional<String> remote = git.inspectPublication(baseline.worktree(), baseline.route()).remoteHeadSha();
        String where = baseline.route().headRepository().fullName() + ":" + baseline.route().headBranch();
        if (remote.filter(head::equals).isPresent()) {
            return new Prepared("Nothing to push", () -> where + " is already at " + abbreviate(head) + "; nothing was pushed.", Optional.of("nothing to push"));
        }
        checkState(remote.isEmpty() || git.branchHeld(baseline.worktree(), remote.orElseThrow()), "The branch has commits this task never had; rebase onto them first");
        String preview = "Push " + abbreviate(head) + " to " + where + remote.map(previous -> ", moving it from " + abbreviate(previous)).orElse(" as a new branch")
                + baseline.route().assignedPullRequest().map(pr -> "\nUpdates PR #" + pr.number()).orElse("");
        return new Prepared(preview, () -> withLock(operations.get(id), () -> pushNow(id, turnId, baseline, head, remote, where)), authorization(id, turnId, said, "push"));
    }

    /** Closes the task's own open pull request, keeping its branch. */
    public Prepared close(TaskId id, TurnId turnId, Optional<String> said)
    {
        AgentTask task = requireTask(id, turnId);
        PullRequest linked = ownership.linkedPullRequest(task).orElseThrow(() -> new IllegalStateException("This task has no linked pull request to close"));
        String workspaceId = task.workspaceId().orElseThrow(() -> new IllegalStateException("This task has no workspace"));
        requireOwnOpen(hosting.captureRead(workspaceId), linked.number());
        String preview = "Close " + linked.repo() + "#" + linked.number() + " “" + clip(linked.title()) + "”. Its branch is kept.";
        return new Prepared(preview, () -> withLock(operations.get(id), () -> closeNow(id, turnId, workspaceId, linked)), authorization(id, turnId, said, "close"));
    }

    private String pushNow(TaskId id, TurnId turnId, DevelopmentBaseline prepared, String head, Optional<String> remote, String where)
    {
        DevelopmentBaseline current = git.capture(requireTask(id, turnId));
        checkState(current.route().equals(prepared.route()) && current.local().headSha().equals(head) && current.local().uncommittedFileCount() == 0,
                "The task's branch or commit changed after the push was prepared; ask again");
        checkState(git.inspectPublication(current.worktree(), current.route()).remoteHeadSha().equals(remote), "The remote branch moved after the push was prepared; ask again");
        requireTask(id, turnId);
        git.pushDevelopmentHead(current.worktree(), current.route(), head, remote);
        checkState(git.inspectPublication(current.worktree(), current.route()).remoteHeadSha().filter(head::equals).isPresent(),
                "Remote result unknown: the pushed commit was not verified");
        return "Pushed " + abbreviate(head) + " to " + where + ".";
    }

    private String closeNow(TaskId id, TurnId turnId, String workspaceId, PullRequest linked)
    {
        requireTask(id, turnId);
        WorkspaceHostingRead selected = hosting.captureRead(workspaceId);
        if (identity(selected, linked.number()).state() == PullRequestIdentity.State.CLOSED) {
            return "PR #" + linked.number() + " is already closed.";
        }
        requireOwnOpen(selected, linked.number());
        requireTask(id, turnId);
        integrations.of(selected.target()).writer().close(selected.target(), linked.number());
        checkState(identity(selected, linked.number()).state() == PullRequestIdentity.State.CLOSED, "Remote result unknown: closing the pull request was not verified");
        // The close is verified; a failed refresh only leaves the list behind until its next poll, and must not read as a failed close.
        try {
            refresh.refreshDetail(pullRequests.prepareRead(workspaceId, linked.repo(), linked.number()));
        }
        catch (RuntimeException failure) {
            log.warn("Closed PR #{} but could not refresh it", linked.number(), failure);
        }
        return "Closed PR #" + linked.number() + ". Its branch was kept.";
    }

    private void requireOwnOpen(WorkspaceHostingRead selected, int number)
    {
        PullRequestIdentity current = identity(selected, number);
        checkState(current.state() == PullRequestIdentity.State.OPEN, "The task's pull request is not open");
        checkState(current.author().equalsIgnoreCase(selected.login()), "Only your own task pull request can be closed");
    }

    private PullRequestIdentity identity(WorkspaceHostingRead selected, int number)
    {
        return integrations.of(selected.target()).contents().identity(selected.target(), number);
    }

    private Optional<String> authorization(TaskId id, TurnId turnId, Optional<String> said, String action)
    {
        return words.authorize(id, turnId, said, action).map(quoted -> "the user asked: “" + quoted + "”");
    }

    /** The task's open conversation, in the turn that asked, with nothing else waiting to change it. */
    private AgentTask requireTask(TaskId id, TurnId turnId)
    {
        AgentTask task = eligible(id).orElseThrow(() -> new IllegalStateException("Pushing and closing are available only to the task's open development conversation"));
        checkState(turns.findActive(id).filter(turn -> turn.id().equals(turnId) && turn.cancellationRequestedAt().isEmpty() && turn.developmentOrigin().isEmpty()).isPresent(),
                "The user turn ended or was stopped; ask again in the current conversation");
        checkState(queue.listQueued(id).stream().noneMatch(input -> input.state().live() && input.turnId().filter(turnId::equals).isEmpty()),
                "Finish or remove queued messages first");
        return task;
    }

    private static String abbreviate(String sha)
    {
        return sha.substring(0, Math.min(12, sha.length()));
    }

    private static String clip(String text)
    {
        return text.length() <= TITLE ? text : text.substring(0, TITLE - 1) + "…";
    }
}
