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
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.rules.AgentRuleService;
import com.bytequay.app.service.rules.AgentRuleView;
import com.bytequay.app.service.rules.RuleDefinition;
import com.bytequay.core.backport.BackportChainStore;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.HandledAction;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.RuleRun;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.IssueDevelopmentStore;
import com.bytequay.core.store.JiraIssueTaskStore;
import com.bytequay.core.store.PrDiffStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.RuleRunStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.bytequay.core.domain.RuleRun.Verdict.AGREED;
import static com.bytequay.core.domain.RuleRun.Verdict.DISAGREED;
import static com.bytequay.core.domain.RuleRun.Verdict.PENDING;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

/**
 * Keeps the records of rules in Observe, with no model and nothing written anywhere but the record: every minute it reads the events
 * rules start from out of what ByteQuay already synced, adds a run for each event a rule matches, and marks the run agreed once the user
 * does what the rule agrees with, or disagreed when the rule's time passes first.
 */
@Component
public class RuleObserver
{
    private static final Logger log = LoggerFactory.getLogger(RuleObserver.class);
    static final Duration KEPT = Duration.ofDays(30);
    // GitHub's merge states for a branch behind its base and for one with conflicts; GitLab only reports the second.
    private static final Set<String> BEHIND = ImmutableSet.of("behind", "dirty");
    // ponytail: a path rule reads a pull request's stored files once per head, so the misses are kept in memory and dropped all at once
    // at this size; each is then read once more.
    private static final int MISSES = 10_000;

    private final AgentRuleService rules;
    private final RuleRunStore runs;
    private final PullRequestStore pullRequests;
    private final PrDiffStore diffs;
    private final WorkspaceStore workspaces;
    private final RepositoryIssueStore issues;
    private final IssueDevelopmentStore issueTasks;
    private final JiraService jira;
    private final JiraIssueTaskStore jiraTasks;
    private final BackportChainStore backports;
    private final CredentialService credentials;
    private final Clock clock;
    private final Set<String> misses = ConcurrentHashMap.newKeySet();
    // ponytail: nothing stores when a pull request fell behind or an issue was assigned, so those count only when seen starting: each
    // check keeps what was so, and the first check after a start only learns it. One that starts while ByteQuay is closed is missed.
    private final Map<RuleDefinition.Event, Set<String>> before = new EnumMap<>(RuleDefinition.Event.class);

    public RuleObserver(AgentRuleService rules, RuleRunStore runs, PullRequestStore pullRequests, PrDiffStore diffs, WorkspaceStore workspaces,
            RepositoryIssueStore issues, IssueDevelopmentStore issueTasks, JiraService jira, JiraIssueTaskStore jiraTasks, BackportChainStore backports,
            CredentialService credentials, Clock clock)
    {
        this.rules = requireNonNull(rules, "rules is null");
        this.runs = requireNonNull(runs, "runs is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.diffs = requireNonNull(diffs, "diffs is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.issues = requireNonNull(issues, "issues is null");
        this.issueTasks = requireNonNull(issueTasks, "issueTasks is null");
        this.jira = requireNonNull(jira, "jira is null");
        this.jiraTasks = requireNonNull(jiraTasks, "jiraTasks is null");
        this.backports = requireNonNull(backports, "backports is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /**
     * Something a rule can start from. The key begins with what it is about, "pr:" and the pull request's row, "issue:" and the issue's id
     * and host, or "jira:" and the ticket's site and id, so a run's agreement can find it again.
     */
    private record Happened(String key, String subject, String title, Instant at, Optional<String> repository, List<String> labels,
            Optional<String> author, OptionalLong pullRequestId) {}

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M", scheduler = "workflowScheduler")
    public synchronized void check()
    {
        try {
            observe();
        }
        catch (RuntimeException failure) {
            log.warn("Rules could not be checked", failure);
        }
    }

    void observe()
    {
        Instant now = clock.instant();
        runs.deleteBefore(now.minus(KEPT));
        Supplier<List<PullRequest>> candidates = Suppliers.memoize(pullRequests::findAttentionCandidates);
        Map<RuleDefinition.Event, List<Happened>> happened = new EnumMap<>(RuleDefinition.Event.class);
        for (AgentRuleView rule : rules.list()) {
            // Read for a paused rule too, so what a state was at the last check stays current while the rule waits.
            List<Happened> events = happened.computeIfAbsent(rule.rule().event(), type -> happened(type, candidates, now));
            if (rule.paused()) {
                continue;
            }
            Duration within = Duration.ofDays(rule.rule().withinDays());
            List<RuleRun> record = rules.record(rule.id());
            Set<String> known = record.stream().map(RuleRun::key).collect(toImmutableSet());
            for (Happened event : events) {
                // An event first read after its time passed, as after a week away, would judge the rule by what nobody saw in time.
                if (!known.contains(event.key()) && !event.at().isBefore(rule.recordSince()) && event.at().isAfter(now.minus(within)) && matches(rule, event)) {
                    runs.save(new RuleRun(rule.id(), event.key(), event.subject(), event.title(), event.at(), PENDING));
                }
            }
            for (RuleRun run : record) {
                if (run.verdict() == PENDING && agreed(rule.rule().agreement(), run)) {
                    runs.save(run.decide(AGREED));
                }
                else if (run.verdict() == PENDING && now.isAfter(run.occurredAt().plus(within))) {
                    runs.save(run.decide(DISAGREED));
                }
            }
        }
    }

    private List<Happened> happened(RuleDefinition.Event event, Supplier<List<PullRequest>> candidates, Instant now)
    {
        Optional<String> viewer = credentials.cachedLogin();
        Supplier<Stream<PullRequest>> open = () -> candidates.get().stream().filter(RuleObserver::open);
        return switch (event) {
            // Once per round of review, known by your last review: a request is stamped again whenever a sync finds it again.
            case REVIEW_REQUESTED -> open.get().filter(pr -> pr.reviewRequestedAt() != null && awaitsReview(pr, viewer))
                    .map(pr -> pullRequest(pr, "requested:" + Optional.ofNullable(pr.reviewedAt()).map(Instant::getEpochSecond).orElse(0L),
                            pr.reviewRequestedAt()))
                    .collect(toImmutableList());
            case PULL_REQUEST_UPDATED -> {
                Set<String> watched = workspaces.findAll().stream().map(workspace -> lower(workspace.monitoredRepository().fullName())).collect(toImmutableSet());
                yield open.get().filter(pr -> pr.headPushedAt() != null && !pr.draft() && !pr.mine(viewer) && watched.contains(lower(pr.repo())))
                        .map(pr -> pullRequest(pr, "head:" + pr.headPushedAt().getEpochSecond(), pr.headPushedAt())).collect(toImmutableList());
            }
            // Known by the head it fell behind at, so falling behind again after an update is another event.
            case PULL_REQUEST_BEHIND -> started(event, viewer, () -> open.get()
                    .filter(pr -> pr.mine(viewer) && pr.mergeableState() != null && BEHIND.contains(lower(pr.mergeableState())))
                    .map(pr -> pullRequest(pr, "behind:" + Optional.ofNullable(pr.headPushedAt()).map(Instant::getEpochSecond).orElse(0L), now)));
            case PULL_REQUEST_MERGED -> candidates.get().stream().filter(pr -> pr.mergedAt() != null && pr.mine(viewer))
                    .map(pr -> pullRequest(pr, "merged", pr.mergedAt())).collect(toImmutableList());
            case ISSUE_ASSIGNED -> started(event, viewer, () -> Stream.concat(assignedIssues(viewer.orElseThrow(), now), assignedTickets(now)));
        };
    }

    /** What is so now and was not at the last check; nothing until the viewer is known, which would make everything look new. */
    private List<Happened> started(RuleDefinition.Event event, Optional<String> viewer, Supplier<Stream<Happened>> current)
    {
        if (viewer.isEmpty()) {
            return ImmutableList.of();
        }
        List<Happened> now = current.get().collect(toImmutableList());
        Set<String> was = before.put(event, now.stream().map(Happened::key).collect(toImmutableSet()));
        return was == null ? ImmutableList.of() : now.stream().filter(happened -> !was.contains(happened.key())).collect(toImmutableList());
    }

    /** As Home reads it: your review is asked for, by the search for it or by name, and you have not answered since. */
    private static boolean awaitsReview(PullRequest pr, Optional<String> viewer)
    {
        boolean requested = pr.origin() == PullRequest.Origin.REVIEW_REQUESTED
                || viewer.filter(login -> pr.requestedReviewers().stream().anyMatch(login::equalsIgnoreCase)).isPresent();
        return requested && !pr.draft() && !pr.mine(viewer) && pr.handledAction() != HandledAction.APPROVED
                && (pr.reviewedAt() == null || pr.handledAction() == null || pr.reviewRequestedAt() != null && pr.reviewRequestedAt().isAfter(pr.reviewedAt()));
    }

    private static Happened pullRequest(PullRequest pr, String event, Instant at)
    {
        return new Happened("pr:" + pr.id() + ":" + event, pr.repo() + "#" + pr.number(), pr.title(), at, Optional.of(pr.repo()), pr.labels(),
                Optional.ofNullable(pr.author()), OptionalLong.of(pr.id()));
    }

    // A rule that starts work has nothing to do for an issue a task already works on. ponytail: each minute this reads every stored
    // issue of each watched repository; query by assignee if that shows in a profile.
    private Stream<Happened> assignedIssues(String viewer, Instant now)
    {
        return workspaces.findAll().stream().flatMap(workspace -> issues.findPage(workspaces, workspace, 0, Integer.MAX_VALUE).issues().stream()
                .filter(issue -> issue.assignees().stream().anyMatch(viewer::equalsIgnoreCase))
                .filter(issue -> issueTasks.findTask(workspace.issueHost(), issue.id()).isEmpty())
                .map(issue -> new Happened("issue:" + issue.id() + ":" + workspace.issueHost(), repository(workspace) + "#" + issue.number(), issue.title(),
                        now, Optional.of(repository(workspace)), issue.labels(), Optional.ofNullable(issue.author()), OptionalLong.empty())));
    }

    private Stream<Happened> assignedTickets(Instant now)
    {
        return jira.attention().stream().filter(ticket -> ticket.task().isEmpty())
                .map(ticket -> new Happened("jira:" + ticket.cloudId() + ":" + ticket.id(), ticket.key(), ticket.title(), now, Optional.empty(),
                        ticket.labels(), Optional.empty(), OptionalLong.empty()));
    }

    private boolean matches(AgentRuleView rule, Happened event)
    {
        RuleDefinition definition = rule.rule();
        return anyOf(definition.repositories(), event.repository())
                && (definition.labels().isEmpty() || event.labels().stream().anyMatch(label -> anyOf(definition.labels(), Optional.of(label))))
                && anyOf(definition.authors(), event.author())
                && (definition.paths().isEmpty() || touches(rule, event));
    }

    private static boolean anyOf(List<String> wanted, Optional<String> value)
    {
        return wanted.isEmpty() || value.filter(found -> wanted.stream().anyMatch(found::equalsIgnoreCase)).isPresent();
    }

    // ponytail: the files ByteQuay stored when the pull request was last opened or warmed, which can be a head behind; one whose files
    // were never read waits for them.
    private boolean touches(AgentRuleView rule, Happened event)
    {
        String miss = rule.id() + " " + rule.recordSince() + " " + event.key();
        if (misses.contains(miss)) {
            return false;
        }
        Optional<List<String>> files = diffs.find(event.pullRequestId().orElseThrow(), null, null)
                .map(diff -> diff.files().stream().map(DiffFile::filename).collect(toImmutableList()));
        if (files.isEmpty()) {
            return false;
        }
        List<PathMatcher> globs = rule.rule().paths().stream().map(glob -> FileSystems.getDefault().getPathMatcher("glob:" + glob)).collect(toImmutableList());
        boolean touched = files.get().stream().anyMatch(file -> globs.stream().anyMatch(glob -> glob.matches(Path.of(file))));
        if (!touched) {
            if (misses.size() >= MISSES) {
                misses.clear();
            }
            misses.add(miss);
        }
        return touched;
    }

    private boolean agreed(RuleDefinition.Agreement agreement, RuleRun run)
    {
        String[] key = run.key().split(":", 3);
        return switch (agreement) {
            case REVIEWED -> pullRequest(key).filter(pr -> after(pr.reviewedAt(), run)).isPresent();
            case UPDATED_BRANCH -> pullRequest(key).filter(pr -> after(pr.headPushedAt(), run)).isPresent();
            case OPENED_BACKPORT -> pullRequest(key).filter(pr -> backports.list().stream().flatMap(chain -> chain.sourcePullRequest().stream())
                    .anyMatch(source -> lower(source.owner() + "/" + source.repo()).equals(lower(pr.repo())) && source.number() == pr.number())).isPresent();
            case STARTED_TASK -> key[0].equals("jira")
                    ? jiraTasks.findBySource(key[1], key[2]).isPresent()
                    : issueTasks.findTask(key[2], Long.parseLong(key[1])).isPresent();
        };
    }

    private Optional<PullRequest> pullRequest(String[] key)
    {
        return pullRequests.findById(Long.parseLong(key[1]));
    }

    private static boolean after(Instant action, RuleRun run)
    {
        return action != null && action.isAfter(run.occurredAt());
    }

    private static boolean open(PullRequest pr)
    {
        return pr.mergedAt() == null && pr.closedAt() == null && (pr.state() == null || pr.state().equalsIgnoreCase("open"));
    }

    private static String repository(Workspace workspace)
    {
        return workspace.monitoredRepository().fullName();
    }

    private static String lower(String value)
    {
        return value.toLowerCase(Locale.ROOT);
    }
}
