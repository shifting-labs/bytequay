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
package com.bytequay.app.service.rules;

import com.bytequay.app.service.rules.RuleDefinition.Action;
import com.bytequay.app.service.rules.RuleDefinition.Agreement;
import com.bytequay.app.service.rules.RuleDefinition.Event;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.nio.file.FileSystems;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

/**
 * Reads a rule file: a header between two lines of three dashes, checked here, then the instructions in plain words. Every problem is
 * one sentence the user reads under Check rule, so the messages say what to change.
 */
public final class RuleText
{
    public static final int MAXIMUM_LENGTH = 20_000;

    private static final String DASHES = "---";
    private static final Set<String> KEYS = ImmutableSet.of("name", "when", "repositories", "paths", "labels", "authors", "does", "agrees-when", "within");
    private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
    private static final Pattern WITHIN = Pattern.compile("(\\d{1,2})d");
    private static final int MAXIMUM_DAYS = 30;

    private RuleText() {}

    public static RuleDefinition parse(String text)
    {
        requireNonNull(text, "text is null");
        checkArgument(text.length() <= MAXIMUM_LENGTH, "A rule is at most %s characters", MAXIMUM_LENGTH);
        List<String> lines = text.strip().lines().collect(toImmutableList());
        checkArgument(!lines.isEmpty() && lines.getFirst().strip().equals(DASHES), "A rule starts with a line of three dashes, then its header");
        int end = 1;
        while (end < lines.size() && !lines.get(end).strip().equals(DASHES)) {
            end++;
        }
        checkArgument(end < lines.size(), "End the header with a second line of three dashes");

        Map<String, String> header = new LinkedHashMap<>();
        for (String line : lines.subList(1, end)) {
            if (line.isBlank()) {
                continue;
            }
            int colon = line.indexOf(':');
            checkArgument(colon > 0, "Header lines read \"key: value\", not \"%s\"", line.strip());
            String key = line.substring(0, colon).strip();
            checkArgument(!key.equals("mode"), "Remove the mode line: a rule's record sets its mode, and every new rule starts in Observe");
            checkArgument(KEYS.contains(key), "Unknown header line \"%s\"; a rule may have %s", key, String.join(", ", KEYS));
            checkArgument(header.put(key, line.substring(colon + 1).strip()) == null, "The header has two \"%s\" lines", key);
        }
        String body = lines.subList(end + 1, lines.size()).stream().collect(joining("\n")).strip();
        checkArgument(!body.isEmpty(), "Under the header, write what the rule should do");

        String name = required(header, "name");
        checkArgument(name.length() <= 100, "A rule's name is at most 100 characters");
        Event event = choice(required(header, "when"), Event.values(), Event::key, "when");
        Action action = choice(required(header, "does"), Action.values(), Action::key, "does");
        Agreement agreement = choice(required(header, "agrees-when"), Agreement.values(), Agreement::key, "agrees-when");
        checkArgument(event.actions().contains(action), "A \"%s\" rule can do %s, not %s", event.key(), keys(event.actions(), Action::key), action.key());
        checkArgument(event.agreements().contains(agreement), "A \"%s\" rule agrees when %s, not %s",
                event.key(), keys(event.agreements(), Agreement::key), agreement.key());

        List<String> repositories = values(header, "repositories");
        repositories.forEach(repository -> checkArgument(REPOSITORY.matcher(repository).matches(), "Write repositories as owner/repo, not \"%s\"", repository));
        List<String> paths = values(header, "paths");
        checkArgument(paths.isEmpty() || event.pullRequest(), "\"paths\" only fits pull request events, not \"%s\"", event.key());
        paths.forEach(path -> checkArgument(glob(path), "\"%s\" is not a path pattern such as src/storage/**", path));
        int withinDays = Optional.ofNullable(header.get("within")).map(RuleText::days).orElse(action == Action.BACKPORT ? 7 : 3);
        return new RuleDefinition(name, event, repositories, paths, values(header, "labels"), values(header, "authors"), action, agreement, withinDays, body);
    }

    private static boolean glob(String path)
    {
        try {
            FileSystems.getDefault().getPathMatcher("glob:" + path);
            return true;
        }
        catch (PatternSyntaxException failure) {
            return false;
        }
    }

    private static String required(Map<String, String> header, String key)
    {
        String value = Optional.ofNullable(header.get(key)).orElse("");
        checkArgument(!value.isEmpty(), "Add a \"%s\" line to the header", key);
        return value;
    }

    private static <T> T choice(String value, T[] choices, Function<T, String> key, String line)
    {
        return Arrays.stream(choices).filter(choice -> key.apply(choice).equals(value)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("\"%s: %s\" is not one ByteQuay knows; use one of %s".formatted(line, value,
                        keys(ImmutableList.copyOf(choices), key))));
    }

    private static <T> String keys(Iterable<T> choices, Function<T, String> key)
    {
        return ImmutableList.copyOf(choices).stream().map(key).collect(joining(", "));
    }

    /** A list reads "[a, b]"; a single value without brackets is a list of one. */
    private static List<String> values(Map<String, String> header, String key)
    {
        String value = Optional.ofNullable(header.get(key)).orElse("");
        if (value.startsWith("[")) {
            checkArgument(value.endsWith("]"), "Close the \"%s\" list with ]", key);
            value = value.substring(1, value.length() - 1);
        }
        return Splitter.on(',').trimResults().omitEmptyStrings().splitToStream(value).collect(toImmutableList());
    }

    private static int days(String value)
    {
        Matcher matcher = WITHIN.matcher(value);
        checkArgument(matcher.matches(), "Write \"within\" in days, such as 3d, not \"%s\"", value);
        int days = Integer.parseInt(matcher.group(1));
        checkArgument(days >= 1 && days <= MAXIMUM_DAYS, "\"within\" is 1 to %s days", MAXIMUM_DAYS);
        return days;
    }
}
