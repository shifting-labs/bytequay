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

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.spi.ReviewTool;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.rules.AgentRuleService;
import com.bytequay.app.service.rules.RuleDefinition;
import com.bytequay.app.service.rules.RuleText;
import com.bytequay.core.backport.BackportChain;
import com.bytequay.core.backport.BackportChainStore;
import com.bytequay.core.domain.AssistantItem;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.HandledAction;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.store.IssueDevelopmentStore;
import com.bytequay.core.store.JiraIssueTaskStore;
import com.bytequay.core.store.PrDiffStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.PullRequestTaskStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.bytequay.agent.spi.ToolSchemas.object;
import static com.bytequay.app.agenda.AgendaReader.clip;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.joining;

/**
 * The {@code assistant_rules} tool, which a conversation carries once the user asks it for rules with {@code @rules}. It reads the user's
 * own work of the last 30 days from what ByteQuay stored, with the rules they saved, and offers a rule file, checked as Check rule
 * checks it, as a card in the conversation. Only the user's click saves the rule, which then starts in Observe.
 */
@Component
class AssistantRules
{
    static final String NAME = "assistant_rules";
    static final int OFFERS = 3;
    /** The key of the receipt that answers an offered rule, followed by the offer's item id, so it is answered once. */
    static final String ANSWER = "rule-offer:";
    private static final Pattern COMMAND = Pattern.compile("^\\s*@rules(?=\\s|$)", Pattern.CASE_INSENSITIVE);
    private static final Duration WINDOW = Duration.ofDays(30);
    private static final int MAXIMUM_ARGUMENTS = 2 * RuleText.MAXIMUM_LENGTH;
    private static final int WHY = 200;
    private static final int TITLE = 80;
    private static final int WORDS = 160;
    private static final int REVIEWS = 40;
    // ponytail: a stored diff loads every patch, so only the newest reviews say where they change files.
    private static final int WITH_FILES = 30;
    private static final int FOLDERS = 5;
    private static final int COUNTS = 8;
    private static final int RECENT_TASKS = 200;
    private static final int TASKS = 30;
    private static final int MERGED = 20;
    private static final Map<String, Set<String>> FIELDS = ImmutableMap.of(
            "history", ImmutableSet.of("op"),
            "offer", ImmutableSet.of("op", "rule", "why"));
    // Merging and allowing permissions are powers a rule gets when the user activates it, not work to suggest from history.
    private static final Set<RuleDefinition.Action> POWERS = ImmutableSet.of(RuleDefinition.Action.MERGE, RuleDefinition.Action.ALLOW);

    /** What an {@code @rules} message asks of the assistant, with each event's choices read from the parser's own so they cannot drift. */
    static final String BRIEF = """
            The user typed @rules. Help them write rules from their own recent work with assistant_rules, following any words after @rules.
            1. Read assistant_rules op history: their last 30 days of reviews, tasks they started, merged pull requests with their backports,
               and the rules they saved.
            2. Find work they repeat that a rule could take over and no saved rule covers.
            3. Offer each, at most %d, with assistant_rules op offer: the rule file, and in "why" one line of the data behind it, such as
               "You reviewed 9 of the 11 pull requests that touched src/storage". When the offer says what is wrong, fix the file and offer it again.
            4. Then say in a few lines what you offered, or that nothing repeats enough yet, and name ideas that need an event ByteQuay does
               not watch.
            Offering saves nothing: the user saves a rule from its card, and a saved rule starts by only observing.
            A rule file is a header between two lines of three dashes, then the rule's instructions in plain words:
            ---
            name: Review pull requests that touch storage
            when: review-requested
            repositories: [owner/repository]
            paths: [src/storage/**]
            does: review
            agrees-when: reviewed
            ---
            Check the storage changes for bugs and missing tests.
            Other header lines are labels, authors, and within, the days to wait for the user's own action, such as 3d. Only pull request
            events take paths. review-requested and pull-request-updated are about other people's pull requests, and pull-request-behind and
            pull-request-merged about the user's own, so those need no authors line. Each "when" with the "does" and "agrees-when" it takes:
            %s""".formatted(OFFERS, choices());

    private final AgentRuleService rules;
    private final PullRequestStore pullRequests;
    private final PrDiffStore diffs;
    private final AgentTaskStore tasks;
    private final AgentTurnStore turns;
    private final IssueDevelopmentStore issueTasks;
    private final JiraIssueTaskStore jiraTasks;
    private final PullRequestTaskStore pullRequestTasks;
    private final BackportChainStore backports;
    private final CredentialService credentials;
    private final ObjectMapper mapper;
    private final Clock clock;

    AssistantRules(AgentRuleService rules, PullRequestStore pullRequests, PrDiffStore diffs, AgentTaskStore tasks, AgentTurnStore turns,
            IssueDevelopmentStore issueTasks, JiraIssueTaskStore jiraTasks, PullRequestTaskStore pullRequestTasks, BackportChainStore backports,
            CredentialService credentials, ObjectMapper mapper, Clock clock)
    {
        this.rules = requireNonNull(rules, "rules is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.diffs = requireNonNull(diffs, "diffs is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.issueTasks = requireNonNull(issueTasks, "issueTasks is null");
        this.jiraTasks = requireNonNull(jiraTasks, "jiraTasks is null");
        this.pullRequestTasks = requireNonNull(pullRequestTasks, "pullRequestTasks is null");
        this.backports = requireNonNull(backports, "backports is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /** Whether the user's message asks for rules: {@code @rules}, alone or with their own words after it. */
    static boolean command(String text)
    {
        return COMMAND.matcher(text).find();
    }

    /** Whether the user asked this conversation for rules, so its later messages may refine and offer them too. */
    static boolean asked(List<AssistantItem> items)
    {
        return items.stream().anyMatch(item -> item.kind() == AssistantItem.Kind.USER && command(item.text()));
    }

    /** An offered rule's name for its receipt, or a plain word when the parser no longer reads it. */
    static String name(String rule)
    {
        try {
            return RuleText.parse(rule).name();
        }
        catch (IllegalArgumentException unreadable) {
            return "the rule";
        }
    }

    /** The tool for one message, which adds the rules it offers through {@code items}, at most {@link #OFFERS}. */
    ReviewTool tool(AssistantItem message, Consumer<AssistantItem> items)
    {
        checkArgument(message.kind() == AssistantItem.Kind.USER, "only a user's message can offer rules");
        AtomicInteger offered = new AtomicInteger();
        Map<String, Object> schema = object(ImmutableMap.of(
                "op", ImmutableMap.of("type", "string", "enum", ImmutableList.copyOf(FIELDS.keySet())),
                "rule", ImmutableMap.of("type", "string", "description", "offer: the whole rule file, its header between two lines of three dashes, then its instructions"),
                "why", ImmutableMap.of("type", "string", "description", "offer: one line of the data behind the rule, such as how often the user did this work")), "op");
        return new ReviewTool(NAME, "Read the user's own work of the last 30 days and the rules they saved, or offer a rule file as a card the user saves with a "
                + "click; an offer saves nothing.", schema, arguments -> call(message, items, offered, arguments), false);
    }

    private String call(AssistantItem message, Consumer<AssistantItem> items, AtomicInteger offered, String arguments)
    {
        try {
            checkArgument(arguments.length() <= MAXIMUM_ARGUMENTS, "arguments are too long");
            JsonNode body = mapper.readTree(arguments.isBlank() ? "{}" : arguments);
            checkArgument(body.isObject(), "arguments must be an object");
            String op = body.path("op").asText("");
            checkArgument(FIELDS.containsKey(op), "op must be one of %s", String.join(", ", FIELDS.keySet()));
            body.fieldNames().forEachRemaining(field -> checkArgument(FIELDS.get(op).contains(field), "%s takes no %s", op, field));
            if (op.equals("offer")) {
                return offer(message, items, offered, body);
            }
        }
        catch (JsonProcessingException invalid) {
            return reply("error", "arguments are not JSON");
        }
        catch (IllegalArgumentException invalid) {
            return reply("error", invalid.getMessage());
        }
        try {
            return mapper.writeValueAsString(history());
        }
        catch (JsonProcessingException unwritable) {
            return reply("error", "the history could not be read");
        }
    }

    /** Shows a rule the parser reads and no saved rule already starts the same way; the user saves it from the card. */
    private String offer(AssistantItem message, Consumer<AssistantItem> items, AtomicInteger offered, JsonNode body)
    {
        String text = body.path("rule").asText("").strip();
        String why = body.path("why").asText("").strip();
        checkArgument(!text.isEmpty(), "offer needs the rule file");
        checkArgument(!why.isEmpty(), "offer needs why: one line of the data behind the rule");
        RuleDefinition rule = RuleText.parse(text);
        Optional<String> saved = rules.list().stream().filter(view -> view.rule().sameTrigger(rule)).map(view -> view.rule().name()).findFirst();
        if (saved.isPresent()) {
            return reply("refused", "The saved rule “" + saved.orElseThrow() + "” already does this");
        }
        if (offered.incrementAndGet() > OFFERS) {
            return reply("refused", "Offer at most " + OFFERS + " rules in one reply");
        }
        items.accept(AssistantItem.rule(message.conversationId(), clock.instant(), clip(why, WHY), text));
        return reply("done", "Showed “" + rule.name() + "”; nothing is saved until the user chooses Save");
    }

    /** The user's own work of the last 30 days, newest first, read from what ByteQuay stored; nothing here asks GitHub. */
    History history()
    {
        Instant since = clock.instant().minus(WINDOW);
        Optional<String> viewer = credentials.cachedLogin();
        List<PullRequest> candidates = pullRequests.findAttentionCandidates();
        List<PullRequest> asked = candidates.stream().filter(pr -> !pr.mine(viewer) && (requested(pr, since) || reviewed(pr, since).isPresent()))
                .sorted(Comparator.comparing((PullRequest pr) -> latest(pr, since)).reversed())
                .collect(toImmutableList());
        Map<Long, List<String>> files = asked.stream().limit(WITH_FILES)
                .flatMap(pr -> diffs.find(pr.id(), null, null).map(diff -> Map.entry(pr.id(), diff.files().stream().map(DiffFile::filename).collect(toImmutableList()))).stream())
                .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));
        List<Review> reviews = asked.stream().limit(REVIEWS)
                .map(pr -> new Review(pr.repo() + "#" + pr.number(), clip(pr.title(), TITLE), Optional.ofNullable(pr.author()), pr.labels(),
                        Optional.ofNullable(files.get(pr.id())).map(AssistantRules::folders), requested(pr, since), reviewed(pr, since),
                        reviewed(pr, since).flatMap(_ -> Optional.ofNullable(pr.handledAction())).map(action -> action.name().toLowerCase(Locale.ROOT))))
                .collect(toImmutableList());
        List<Count> counts = ImmutableList.<Count>builder()
                .addAll(count("repository", asked, pr -> Stream.of(pr.repo()), since))
                .addAll(count("author", asked, pr -> Stream.ofNullable(pr.author()), since))
                .addAll(count("label", asked, pr -> pr.labels().stream(), since))
                .addAll(count("folder", asked.stream().filter(pr -> files.containsKey(pr.id())).collect(toImmutableList()),
                        pr -> files.get(pr.id()).stream().map(AssistantRules::folder).flatMap(Optional::stream), since))
                .build();
        List<Started> started = tasks.listRecent(RECENT_TASKS).stream().filter(task -> task.interactive() && !task.createdAt().isBefore(since))
                .sorted(Comparator.comparing(AgentTask::createdAt).reversed())
                .filter(task -> !byRule(task))
                .limit(TASKS)
                .map(this::started)
                .collect(toImmutableList());
        List<BackportChain> chains = backports.list();
        List<Merged> merged = candidates.stream().filter(pr -> pr.mine(viewer) && pr.mergedAt() != null && !pr.mergedAt().isBefore(since))
                .sorted(Comparator.comparing(PullRequest::mergedAt).reversed())
                .limit(MERGED)
                .map(pr -> new Merged(pr.repo() + "#" + pr.number(), clip(pr.title(), TITLE), pr.labels(), pr.mergedAt(), backportedTo(pr, chains)))
                .collect(toImmutableList());
        List<Saved> saved = rules.list().stream()
                .map(view -> new Saved(view.rule().name(), view.rule().event().key(), view.rule().action().key(), view.rule().repositories(), view.rule().paths(),
                        view.rule().labels(), view.rule().authors(), view.mode().name().toLowerCase(Locale.ROOT), view.paused()))
                .collect(toImmutableList());
        return new History(since, counts, reviews, started, merged, saved);
    }

    // A task a rule started is the rule's work, not the user's.
    private boolean byRule(AgentTask task)
    {
        return turns.list(task.id()).stream().findFirst().filter(turn -> turn.idempotencyKey().startsWith("rule:")).isPresent();
    }

    private Started started(AgentTask task)
    {
        String id = task.id().toString();
        String repository = task.repository().owner() + "/" + task.repository().name();
        return issueTasks.findByTaskId(id)
                .map(link -> started(task, "issue", Optional.of(link.source().repo() + "#" + link.source().number()), repository, link.instructions()))
                .or(() -> jiraTasks.findByTaskId(id).map(link -> started(task, "jira", Optional.of(link.source().key()), repository, Optional.of(link.instructions()))))
                .or(() -> pullRequestTasks.findByTaskId(id)
                        .map(link -> started(task, "pull-request", Optional.of(link.repo() + "#" + link.number()), repository, Optional.of(link.initialContext()))))
                .orElseGet(() -> started(task, "text", Optional.empty(), repository, Optional.empty()));
    }

    private static Started started(AgentTask task, String from, Optional<String> item, String repository, Optional<String> words)
    {
        return new Started(from, item, repository, task.createdAt(), clip(words.map(String::strip).filter(value -> !value.isEmpty()).orElse(task.title()), WORDS));
    }

    private static List<String> backportedTo(PullRequest pr, List<BackportChain> chains)
    {
        return chains.stream()
                .filter(chain -> chain.sourcePullRequest().filter(source -> (source.owner() + "/" + source.repo()).equalsIgnoreCase(pr.repo()) && source.number() == pr.number())
                        .isPresent())
                .flatMap(chain -> chain.hops().stream())
                .map(hop -> hop.route().repository().owner() + "/" + hop.route().repository().repo() + " " + hop.route().branch())
                .distinct()
                .collect(toImmutableList());
    }

    /** The groups of pull requests that share a value, largest first: how many there are, how many asked for the user's review, how many they reviewed. */
    private static List<Count> count(String by, List<PullRequest> prs, Function<PullRequest, Stream<String>> values, Instant since)
    {
        Map<String, List<PullRequest>> groups = new LinkedHashMap<>();
        prs.forEach(pr -> values.apply(pr).distinct().forEach(value -> groups.computeIfAbsent(value, _ -> new ArrayList<>()).add(pr)));
        return groups.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<String, List<PullRequest>> group) -> group.getValue().size()).reversed())
                .limit(COUNTS)
                .map(group -> new Count(by, group.getKey(), group.getValue().size(), (int) group.getValue().stream().filter(pr -> requested(pr, since)).count(),
                        (int) group.getValue().stream().filter(pr -> reviewed(pr, since).isPresent()).count()))
                .collect(toImmutableList());
    }

    private static boolean requested(PullRequest pr, Instant since)
    {
        return pr.reviewRequestedAt() != null && !pr.reviewRequestedAt().isBefore(since);
    }

    // Merging in ByteQuay stamps the review time too, which is not a review.
    private static Optional<Instant> reviewed(PullRequest pr, Instant since)
    {
        return Optional.ofNullable(pr.reviewedAt()).filter(at -> !at.isBefore(since) && pr.handledAction() != HandledAction.MERGED);
    }

    private static Instant latest(PullRequest pr, Instant since)
    {
        return Stream.concat(Optional.ofNullable(pr.reviewRequestedAt()).filter(at -> !at.isBefore(since)).stream(), reviewed(pr, since).stream())
                .max(Comparator.naturalOrder()).orElse(since);
    }

    /** Where a pull request changes files, two folders deep, most changed first. */
    private static List<String> folders(List<String> files)
    {
        return files.stream().map(AssistantRules::folder).flatMap(Optional::stream)
                .collect(groupingBy(folder -> folder, LinkedHashMap::new, counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(FOLDERS)
                .map(Map.Entry::getKey)
                .collect(toImmutableList());
    }

    private static Optional<String> folder(String file)
    {
        List<String> parts = Splitter.on('/').splitToList(file);
        return parts.size() < 2 ? Optional.empty() : Optional.of(String.join("/", parts.subList(0, Math.min(2, parts.size() - 1))) + "/");
    }

    private static String choices()
    {
        return Stream.of(RuleDefinition.Event.values()).filter(event -> event.actions().stream().noneMatch(POWERS::contains))
                .map(event -> "- %s: does %s; agrees-when %s".formatted(event.key(), keys(event.actions(), RuleDefinition.Action::key),
                        keys(event.agreements(), RuleDefinition.Agreement::key)))
                .collect(joining("\n"));
    }

    private static <T> String keys(Set<T> values, Function<T, String> key)
    {
        return values.stream().map(key).collect(joining(" or "));
    }

    /** What the tool tells the assistant came of an offer. */
    private String reply(String outcome, String message)
    {
        try {
            return mapper.writeValueAsString(ImmutableMap.of("outcome", outcome, "message", message));
        }
        catch (JsonProcessingException impossible) {
            return "{\"outcome\":\"" + outcome + "\"}";
        }
    }

    /** The user's own work since {@code since}, newest first, and the rules they saved. */
    record History(Instant since, List<Count> counts, List<Review> reviews, List<Started> tasks, List<Merged> merged, List<Saved> rules) {}

    /**
     * A pull request whose review was asked of the user, or that they reviewed.
     *
     * @param folders where it changes files, two folders deep, most changed first; empty when ByteQuay has not stored its files
     * @param answer how they reviewed it, such as approved
     */
    record Review(String pullRequest, String title, Optional<String> author, List<String> labels, Optional<List<String>> folders, boolean asked,
            Optional<Instant> reviewed, Optional<String> answer) {}

    /** How many of those pull requests share a repository, author, label or folder, how many asked for the user's review, and how many they reviewed. */
    record Count(String by, String value, int pullRequests, int asked, int reviewed) {}

    /** A task the user started, what it started from, and the words it started with. */
    record Started(String from, Optional<String> item, String repository, Instant at, String words) {}

    /** One of the user's merged pull requests, and the repositories and branches it was backported to. */
    record Merged(String pullRequest, String title, List<String> labels, Instant at, List<String> backportedTo) {}

    /** A saved rule, so an offer does not repeat it. */
    record Saved(String name, String when, String does, List<String> repositories, List<String> paths, List<String> labels, List<String> authors, String mode,
            boolean paused) {}
}
