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

import com.bytequay.app.agenda.AgendaRefs;
import com.bytequay.app.review.ReviewPublicationService;
import com.bytequay.app.service.hosting.HostingIntegrations;
import com.bytequay.app.service.pr.PullRequestRead;
import com.bytequay.app.service.pr.PullRequestRoute;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.core.domain.AgendaTarget;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.PullRequestIdentity;
import com.bytequay.core.domain.RepoUser;
import com.bytequay.core.domain.UserWords;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.hosting.HostingIntegration;
import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.core.hosting.HostingTarget;
import com.bytequay.core.review.ReviewDraft;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.PullRequestTaskStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.bytequay.app.agenda.AgendaReader.clip;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

/**
 * What the assistant's pull request operations do once its checks pass. Each reads the pull request from its host again first, then
 * writes through the service the pull request page's own controls use, under the account of the repository's workspace. Merging is not
 * here: it stays the user's click on the pull request's card, which reads it again with the head it showed.
 */
@Component
class AssistantPullRequests
{
    private static final int TITLE = 80;
    private static final int TEXT = 160;

    // Branch names a host and Git both take as they are: letters, digits, dots, dashes and underscores, in slash-separated parts.
    private static final Pattern BRANCH = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*(?:/[A-Za-z0-9][A-Za-z0-9._-]*)*");

    private final PullRequestService service;
    private final ReviewPublicationService reviews;
    private final PullRequestStore pullRequests;
    private final PrDetailStore details;
    private final WorkspaceStore workspaces;
    private final PullRequestTaskStore tasks;
    private final HostingIntegrations integrations;

    AssistantPullRequests(PullRequestService service, ReviewPublicationService reviews, PullRequestStore pullRequests, PrDetailStore details, WorkspaceStore workspaces,
            PullRequestTaskStore tasks, HostingIntegrations integrations)
    {
        this.service = requireNonNull(service, "service is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.details = requireNonNull(details, "details is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.integrations = requireNonNull(integrations, "integrations is null");
    }

    /** Closes it, as its page's Close pull request does. */
    AssistantWork.Done close(String ref)
    {
        Read pr = open(resolve(ref));
        service.closePullRequest(pr.found().route());
        return pr.done("Closed", Optional.empty());
    }

    /** Reopens a closed one; the host refuses when its branch is gone. */
    AssistantWork.Done reopen(String ref)
    {
        Read pr = read(resolve(ref));
        checkState(pr.detail().mergedAt() == null, "It was merged, so it can't be reopened");
        checkState(!"open".equals(pr.detail().state()), "It's already open");
        service.reopenPullRequest(pr.found().route());
        return pr.done("Reopened", Optional.empty());
    }

    /** Takes a draft out of draft, as the merge sheet's Ready for review does. */
    AssistantWork.Done markReady(String ref)
    {
        Read pr = open(resolve(ref));
        checkState(pr.detail().draft(), "It's already ready for review");
        service.markReadyForReview(pr.found().route());
        return pr.done("Marked ready for review:", Optional.empty());
    }

    /** Turns an open one back into a draft, so it is not asked to be reviewed or merged yet. */
    AssistantWork.Done markDraft(String ref)
    {
        Read pr = open(resolve(ref));
        checkState(!pr.detail().draft(), "It's already a draft");
        service.markDraft(pr.found().route());
        return pr.done("Turned back into a draft:", Optional.empty());
    }

    /** Reruns the failed checks of its head, as its checks' Rerun failed does; the host says when nothing can run again. */
    AssistantWork.Done rerunChecks(String ref)
    {
        Read pr = open(resolve(ref));
        service.rerunFailedChecks(pr.found().route());
        return pr.done("Reran the failed checks of", Optional.empty());
    }

    /** Asks people or teams of the repository to review it, or takes the request back. */
    AssistantWork.Done requestReview(String ref, List<String> add, List<String> remove)
    {
        Read pr = open(resolve(ref));
        PullRequestRoute routing = PullRequestRoute.of(pr.found().workspace().id(), null);
        Set<String> reviewers = Stream.concat(service.listRepositoryUsers(pr.found().repo(), routing).stream().map(RepoUser::login),
                service.listRepositoryTeams(pr.found().repo(), routing).stream()).map(AssistantPullRequests::lower).collect(toImmutableSet());
        Stream.concat(add.stream(), remove.stream()).forEach(login -> checkArgument(reviewers.contains(lower(login)), "%s can't review %s", login, pr.found().repo()));
        add.forEach(login -> service.updateReviewerRequest(pr.found().route(), login, true));
        remove.forEach(login -> service.updateReviewerRequest(pr.found().route(), login, false));
        String asked = add.isEmpty() ? "" : "Asked " + String.join(", ", add) + " to review";
        String taken = remove.isEmpty() ? "" : (add.isEmpty() ? "Took back the review request of " : "; took back ") + String.join(", ", remove) + " on";
        return pr.done(asked + taken, Optional.empty());
    }

    /** Adds labels the repository has and removes ones the pull request has, keeping the rest. */
    AssistantWork.Done setLabels(String ref, List<String> add, List<String> remove)
    {
        Read pr = open(resolve(ref));
        Map<String, String> known = new LinkedHashMap<>();
        service.listRepositoryLabels(pr.found().repo(), PullRequestRoute.of(pr.found().workspace().id(), null))
                .forEach(label -> known.putIfAbsent(lower(label.name()), label.name()));
        List<String> adding = add.stream().map(name -> Optional.ofNullable(known.get(lower(name)))
                .orElseThrow(() -> new IllegalArgumentException(pr.found().repo() + " has no label " + name))).collect(toImmutableList());
        Set<String> removing = remove.stream().map(AssistantPullRequests::lower).collect(toImmutableSet());
        List<String> current = pr.detail().labels().stream().map(PullRequestDetail.Label::name).collect(toImmutableList());
        Set<String> had = current.stream().map(AssistantPullRequests::lower).collect(toImmutableSet());
        // ponytail: the host replaces the whole set, so a label someone else adds between this read and the write is lost; read-modify-write
        // with the host's compare-and-swap if that shows up.
        Map<String, String> next = new LinkedHashMap<>();
        Stream.concat(current.stream(), adding.stream()).filter(name -> !removing.contains(lower(name))).forEach(name -> next.putIfAbsent(lower(name), name));
        service.updateMetadata(pr.found().route(), Optional.empty(), Optional.of(ImmutableList.copyOf(next.values())));
        String added = adding.stream().filter(name -> !had.contains(lower(name))).map(name -> "+" + name).collect(joining(" "));
        String removed = current.stream().filter(name -> removing.contains(lower(name))).map(name -> "−" + name).collect(joining(" "));
        return pr.done("Labeled", Optional.of(Stream.of(added, removed).filter(change -> !change.isEmpty()).collect(joining(" "))).filter(change -> !change.isEmpty()));
    }

    /** Comments with the user's own words, as its page's comment box does. */
    AssistantWork.Done comment(String ref, String body)
    {
        Read pr = read(resolve(ref));
        service.commentOnPullRequest(pr.found().route(), body);
        return pr.done("Commented on", Optional.of("“" + clip(body, TEXT) + "”"));
    }

    /**
     * Approves it as its card's Approve does: through an empty review draft, bound to the head commit ByteQuay held when the user asked, so
     * commits that arrived since are never approved unseen.
     */
    AssistantWork.Done approve(String ref)
    {
        Found found = resolve(ref);
        Read pr = openAsSeen(found);
        checkState(Optional.ofNullable(pr.detail().author()).filter(found.route().login()::equalsIgnoreCase).isEmpty(), "You can't approve your own pull request");
        String head = pr.detail().headSha();
        ReviewDraft draft = reviews.draft(found.route());
        checkState(draft.reviewPublication().isEmpty(), "A review you sent has no answer yet; open the pull request to settle it first");
        checkState(draft.body().isBlank() && draft.items().isEmpty(), "Your review draft has comments; send it from the pull request");
        ReviewDraft saved = reviews.saveReview(found.route(), draft.version(), draft.body(), "APPROVE");
        reviews.submit(found.route(), saved.version(), saved.body().strip(), "APPROVE", head);
        return pr.done("Approved", Optional.empty());
    }

    /**
     * Brings its branch up to date with its base, from the head commit the user last saw: a rebase when their words say rebase, else a merge
     * of the base. GitLab only rebases, which rewrites the branch's commits, so there it waits for the word. The host finishes it later.
     */
    AssistantWork.Done updateBranch(String ref, String words)
    {
        Found found = resolve(ref);
        boolean rebase = UserWords.names(words, "rebase");
        checkState(rebase || found.route().session().access().server().provider() != HostingProvider.GITLAB,
                "GitLab can only rebase it, which rewrites its commits; ask to rebase it if you want that");
        Read pr = openAsSeen(found);
        service.updateBranch(found.route(), pr.detail().headSha(), rebase);
        String base = pr.detail().baseRef();
        return pr.done(rebase ? "Rebasing onto " + base + ":" : "Merging " + base + " into", Optional.of("the host finishes it shortly"));
    }

    /**
     * Moves the user's own open pull request to a new branch at the head commit they last saw. GitHub closes a pull request whose head branch
     * is renamed, so a new one from the new branch takes its title, description and draft state, and then it closes, keeping its branch;
     * reviews, comments and labels stay with it. A retry reuses the branch and pull request an earlier attempt made. One a task follows is
     * the task's to move, so the task follows it.
     */
    AssistantWork.Done renameBranch(String ref, String branch)
    {
        checkArgument(branch.length() <= 200 && BRANCH.matcher(branch).matches() && !branch.contains("..") && !branch.endsWith(".lock"),
                "“%s” isn't a branch name ByteQuay can use", branch);
        Found found = resolve(ref);
        checkState(tasks.find(found.workspace().id(), found.repo(), found.number()).isEmpty(),
                "A task follows this pull request; ask the task to move it, so the task follows the new one");
        Read pr = openAsSeen(found);
        checkState(Optional.ofNullable(pr.detail().author()).filter(found.route().login()::equalsIgnoreCase).isPresent(), "Only your own pull request can move to a new branch");
        HostingTarget target = found.route().target();
        HostingIntegration hosting = integrations.of(target);
        PullRequestIdentity current = hosting.contents().identity(target, found.number());
        checkState(current.state() == PullRequestIdentity.State.OPEN && current.headSha().equals(pr.detail().headSha()), "It changed just now; look at it again");
        checkArgument(!current.headBranch().equals(branch), "It's already on %s", branch);
        int number = hosting.contents().identitiesByHead(target, current.headRepository(), branch).stream()
                .filter(earlier -> earlier.state() == PullRequestIdentity.State.OPEN && earlier.baseBranch().equals(current.baseBranch())
                        && earlier.headSha().equals(current.headSha()))
                .mapToInt(earlier -> earlier.pullRequest().number())
                .findFirst()
                .orElseGet(() -> openOnBranch(hosting, target, current, branch, pr.detail().title(), found.number()));
        try {
            hosting.writer().close(target, found.number());
        }
        catch (RuntimeException failure) {
            throw new IllegalStateException("Opened #" + number + " from " + branch + ", but closing #" + found.number() + " failed: " + failure.getMessage()
                    + ". Ask again to finish", failure);
        }
        service.refreshForAction(found.route());
        service.refreshForAction(service.prepareRead(found.workspace().id(), found.repo(), number));
        AgendaTarget opens = pullRequests.findIdByRepoAndNumber(found.repo(), number)
                .map(id -> AgendaTarget.pull(id, found.repo(), number, Optional.of(found.workspace().id()))).orElse(found.target());
        // The line is all the assistant reads back, so it names the new pull request: a move is a new one, never the old one on a new branch.
        return new AssistantWork.Done("Moved “" + clip(pr.detail().title(), TITLE) + "” to #" + number + " on " + branch + "; closed #" + found.number(),
                Optional.of(found.repo() + "#" + number + " · #" + found.number() + "'s branch kept"), Optional.of(opens), found.repo() + "#" + number);
    }

    private static int openOnBranch(HostingIntegration hosting, HostingTarget target, PullRequestIdentity current, String branch, String title, int previous)
    {
        hosting.writer().createBranch(target, current.headRepository(), branch, current.headSha());
        try {
            return hosting.writer().create(target, current.headRepository(), branch, current.baseBranch(), title, hosting.contents().description(target, previous),
                    current.draft()).number();
        }
        catch (RuntimeException failure) {
            throw new IllegalStateException("Made branch " + branch + ", but opening its pull request failed: " + failure.getMessage() + ". Ask again to retry",
                    failure);
        }
    }

    /** Sets its title or description to the user's own words, as its page's editors do. */
    AssistantWork.Done edit(String ref, Optional<String> title, Optional<String> body)
    {
        checkArgument(title.isPresent() || body.isPresent(), "edit_pull_request needs a title or a body");
        Read pr = read(resolve(ref));
        title.ifPresent(value -> service.updateTitle(pr.found().route(), value));
        body.ifPresent(value -> service.updateDescription(pr.found().route(), value));
        if (title.isPresent()) {
            return new AssistantWork.Done("Renamed “" + clip(pr.detail().title(), TITLE) + "” to “" + clip(title.orElseThrow(), TITLE) + "”",
                    Optional.of(pr.found().key() + body.map(_ -> " · description updated").orElse("")), Optional.of(pr.found().target()), pr.found().key());
        }
        return pr.done("Updated the description of", Optional.empty());
    }

    /** The pull request a ref names, in a watched repository, with the route its workspace's account reads and writes it through. */
    private Found resolve(String ref)
    {
        AgendaRefs.Numbered numbered = AgendaRefs.numbered(ref).orElseThrow(() -> new IllegalArgumentException("That pull request ref is not valid"));
        long id = pullRequests.findIdByRepoAndNumber(numbered.repo(), numbered.number())
                .orElseThrow(() -> new IllegalArgumentException("ByteQuay no longer holds that pull request"));
        Workspace workspace = pullRequests.findHostingIdentity(id).flatMap(identity -> workspaces.findByHostingRepositoryId(identity.repositoryId()))
                .filter(found -> workspaces.isWatched(found.id())).orElseThrow(() -> new IllegalArgumentException("That pull request's repository is not watched"));
        return new Found(id, numbered.repo(), numbered.number(), workspace, service.prepareRead(workspace.id(), numbered.repo(), numbered.number()));
    }

    /** Its state now, from its host, so a write never rests on a stale row. */
    private Read read(Found found)
    {
        return new Read(found, service.refreshForAction(found.route()));
    }

    private Read open(Found found)
    {
        Read pr = read(found);
        checkState(pr.detail().mergedAt() == null && "open".equals(pr.detail().state()), "That pull request is not open");
        return pr;
    }

    /** It open, at the head commit ByteQuay held before reading it again, so commits that arrived since are never acted on unseen. */
    private Read openAsSeen(Found found)
    {
        Optional<String> seen = details.find(found.id()).map(stored -> stored.raw().headSha()).filter(head -> head != null && !head.isBlank());
        Read pr = open(found);
        String head = pr.detail().headSha();
        checkState(head != null && seen.map(head::equals).orElse(true), "New commits arrived since you last saw it; open the pull request to check them");
        return pr;
    }

    private static String lower(String value)
    {
        return value.toLowerCase(Locale.ROOT);
    }

    private record Found(long id, String repo, int number, Workspace workspace, PullRequestRead route)
    {
        String key()
        {
            return repo + "#" + number;
        }

        AgendaTarget target()
        {
            return AgendaTarget.pull(id, repo, number, Optional.of(workspace.id()));
        }
    }

    private record Read(Found found, PullRequestDetail detail)
    {
        /** The receipt: what was done to the pull request by its title, where it lives, and its page to open. */
        AssistantWork.Done done(String did, Optional<String> more)
        {
            String where = found.key() + more.map(text -> " · " + text).orElse("");
            return new AssistantWork.Done(did + " “" + clip(detail().title(), TITLE) + "”", Optional.of(where), Optional.of(found.target()), found.key());
        }
    }
}
