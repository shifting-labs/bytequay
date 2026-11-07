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

import com.bytequay.app.agenda.AgendaPages;
import com.bytequay.app.agenda.AgendaReader;
import com.bytequay.app.agenda.AgendaRefs;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.core.domain.AgendaTarget;
import com.bytequay.core.domain.PrTimelineComment;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PrTimelinePayload;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.WorkspaceStore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

import static com.bytequay.app.config.AsyncConfig.IO_EXECUTOR;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * What people said on a pull request, for the assistant's {@code discussion} read: reviews with their verdict and text, inline comments
 * with where they are and their replies, and conversation comments, newest first, from the detail ByteQuay stored. It never waits on the
 * host: a stored copy older than the pull request's latest activity says so, and is refreshed in the background for a later read.
 */
@Component
class AssistantDiscussion
{
    private static final Logger log = LoggerFactory.getLogger(AssistantDiscussion.class);
    private static final int TEXT = 800;
    // As fresh as the background refresh asks for: activity newer than the stored copy is worth a read a minute after the last one.
    private static final Duration REFRESH = Duration.ofMinutes(1);

    private final PullRequestStore pullRequests;
    private final PrDetailStore details;
    private final WorkspaceStore workspaces;
    private final PullRequestService service;
    private final ExecutorService executor;
    private final Set<Long> refreshing = ConcurrentHashMap.newKeySet();

    AssistantDiscussion(PullRequestStore pullRequests, PrDetailStore details, WorkspaceStore workspaces, PullRequestService service,
            @Qualifier(IO_EXECUTOR) ExecutorService executor)
    {
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.details = requireNonNull(details, "details is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.service = requireNonNull(service, "service is null");
        this.executor = requireNonNull(executor, "executor is null");
    }

    /** One page of what was said, newest first; an empty page for a pull request ByteQuay does not hold. */
    AssistantReader.Page read(String ref, int page)
    {
        checkArgument(AgendaRefs.kind(ref).equals("pr"), "discussion reads a pr: ref");
        Optional<PullRequest> found = AgendaRefs.numbered(ref).flatMap(numbered -> pullRequests.findIdByRepoAndNumber(numbered.repo(), numbered.number()))
                .flatMap(pullRequests::findById);
        if (found.isEmpty()) {
            return new AssistantReader.Page(new Discussion("discussion", ref, Optional.empty(), Optional.empty(), ImmutableList.of(), Optional.empty()), ImmutableMap.of());
        }
        PullRequest pr = found.orElseThrow();
        String canonical = AgendaRefs.pull(pr.repo(), pr.number());
        Optional<StoredPrDetail> detail = details.find(pr.id());
        Optional<Instant> asOf = details.findFreshness(pr.id()).discussionAt();
        Optional<Instant> activity = Optional.ofNullable(pr.activityAt()).or(() -> Optional.ofNullable(pr.updatedAt()));
        Optional<String> note = Optional.empty();
        if (detail.isEmpty() || asOf.isEmpty() || activity.filter(at -> at.isAfter(asOf.orElseThrow())).isPresent()) {
            refresh(pr);
            note = Optional.of(detail.isEmpty() ? "ByteQuay has not read this pull request's discussion yet; it is reading it now"
                    : "Newer activity is not in this copy yet; ByteQuay is reading it again");
        }
        List<Entry> said = detail.map(AssistantDiscussion::entries).orElse(ImmutableList.of());
        int from = Math.min(said.size(), page * AgendaPages.PAGE);
        int to = Math.min(said.size(), from + AgendaPages.PAGE);
        Optional<String> workspace = pullRequests.findHostingIdentity(pr.id()).flatMap(identity -> workspaces.findByHostingRepositoryId(identity.repositoryId()))
                .map(Workspace::id);
        return new AssistantReader.Page(new Discussion("discussion", canonical, asOf, note, said.subList(from, to), to < said.size() ? Optional.of(page + 1) : Optional.empty()),
                ImmutableMap.of(canonical, Optional.of(AgendaTarget.pull(pr.id(), pr.repo(), pr.number(), workspace))));
    }

    /** Reads the pull request from its host on the IO pool, once at a time; the turn that asked has already answered from the stored copy. */
    private void refresh(PullRequest pr)
    {
        if (!refreshing.add(pr.id())) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    service.warmDetail(pr.repo(), pr.number(), REFRESH);
                }
                catch (RuntimeException failure) {
                    log.info("Could not refresh the discussion of {}#{}: {}", pr.repo(), pr.number(), failure.toString());
                }
                finally {
                    refreshing.remove(pr.id());
                }
            });
        }
        catch (RuntimeException rejected) {
            refreshing.remove(pr.id());
            log.info("Could not queue a refresh of {}#{}: {}", pr.repo(), pr.number(), rejected.toString());
        }
    }

    /** Reviews that say something, inline threads and conversation comments, newest first by what was said last. */
    static List<Entry> entries(StoredPrDetail detail)
    {
        ImmutableList.Builder<Entry> entries = ImmutableList.builder();
        for (PrTimelineEvent event : detail.timeline()) {
            PrTimelinePayload payload = event.payload();
            Optional<String> text = Optional.ofNullable(event.body()).filter(body -> !body.isBlank()).map(body -> AgendaReader.clip(body, TEXT));
            switch (Optional.ofNullable(event.event()).orElse("")) {
                // A review that only carries inline comments says nothing of its own; those comments are in their threads.
                case "reviewed" -> {
                    String verdict = Optional.ofNullable(payload.state()).orElse("commented").toLowerCase(Locale.ROOT);
                    if (text.isPresent() || !verdict.equals("commented")) {
                        entries.add(new Entry("review", by(event.actor()), bot(payload.actorBot()), Optional.ofNullable(event.timestamp()), Optional.of(verdict),
                                Optional.empty(), Optional.empty(), text, ImmutableList.of()));
                    }
                }
                case "commented" -> text.ifPresent(body -> entries.add(new Entry("comment", by(event.actor()), bot(payload.actorBot()), Optional.ofNullable(event.timestamp()),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(body), ImmutableList.of())));
                case "review_thread" -> {
                    if (!payload.comments().isEmpty()) {
                        Optional<Integer> line = Optional.ofNullable(payload.line()).or(() -> Optional.ofNullable(payload.originalLine()));
                        Optional<String> where = Optional.ofNullable(payload.path()).map(path -> path + line.map(number -> ":" + number).orElse(""));
                        String state = payload.resolved() ? "resolved" : payload.outdated() ? "outdated" : "open";
                        List<Comment> comments = payload.comments().stream().map(AssistantDiscussion::comment).collect(toImmutableList());
                        entries.add(new Entry("thread", Optional.empty(), Optional.empty(), comments.getLast().at(), Optional.empty(), where, Optional.of(state), Optional.empty(),
                                comments));
                    }
                }
                default -> {}
            }
        }
        return entries.build().stream().sorted(Comparator.comparing((Entry entry) -> entry.at().orElse(Instant.MIN)).reversed()).collect(toImmutableList());
    }

    private static Comment comment(PrTimelineComment comment)
    {
        return new Comment(Optional.ofNullable(comment.actor()).orElse("someone"), bot(comment.actorBot()), Optional.ofNullable(comment.timestamp()),
                AgendaReader.clip(Optional.ofNullable(comment.body()).orElse(""), TEXT));
    }

    private static Optional<String> by(String actor)
    {
        return Optional.of(Optional.ofNullable(actor).orElse("someone"));
    }

    private static Optional<Boolean> bot(boolean bot)
    {
        return bot ? Optional.of(true) : Optional.empty();
    }

    /**
     * A pull request's discussion as the assistant reads it.
     *
     * @param asOf when ByteQuay last read the discussion from the host; empty before it ever did
     * @param note why the copy may miss what was said lately, when it may
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record Discussion(String kind, String ref, Optional<Instant> asOf, Optional<String> note, List<Entry> entries, Optional<Integer> nextPage) {}

    /**
     * One thing said: a review with its verdict, a conversation comment, or an inline thread with where it is and each comment in it, oldest
     * first; the newest thread comment dates the thread.
     *
     * @param state an inline thread's state: open, resolved or outdated
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record Entry(String type, Optional<String> by, Optional<Boolean> bot, Optional<Instant> at, Optional<String> verdict, Optional<String> where, Optional<String> state,
            Optional<String> text, List<Comment> comments) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    record Comment(String by, Optional<Boolean> bot, Optional<Instant> at, String text) {}
}
