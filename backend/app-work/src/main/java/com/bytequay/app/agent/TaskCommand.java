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

import com.google.common.collect.ImmutableMap;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Locale.ENGLISH;
import static java.util.regex.Pattern.CASE_INSENSITIVE;
import static java.util.stream.Collectors.joining;

/**
 * The transcript keeps the user's short command; only the provider's input carries the full brief.
 */
final class TaskCommand
{
    private static final Pattern COMMAND = Pattern.compile("^\\s*@(contribute|fix|review)(?:\\s+|$)", CASE_INSENSITIVE);
    /** A leading focus word narrows {@code @contribute} to one kind of change; any other words stay the user's own instructions. */
    static final Map<String, String> FOCUSES = ImmutableMap.of(
            "bug", "A bug fix: a concrete problem users or operators hit under realistic conditions, shown by a reproduction, "
                    + "with a regression test that fails before the fix and passes after.",
            "performance", "A performance improvement: less CPU, memory, I/O, or allocation on a path that runs often or on large inputs, "
                    + "with before/after measurements from the repository's benchmarks or a focused measurement. "
                    + "Skip micro-optimizations the measurement cannot show.",
            "cleanup", "A small cleanup: dead or duplicated code, a convoluted expression with a simpler equivalent, a misleading name or stale comment, "
                    + "or hand-written code an existing helper already covers. Behavior stays the same and the existing tests still pass. "
                    + "Keep it to one coherent change a reviewer can read in a minute; skip formatting-only churn, broad refactors, and matters of taste.",
            "tests", "A test improvement: a test for important behavior that has none, or a flaky test fixed at its cause. "
                    + "A new test must fail when the behavior it covers breaks; do not fix flakiness with retries or longer timeouts.",
            "docs", "A documentation fix: documentation, comments, or error messages that contradict the code, or a missing step users need. "
                    + "Check each claim against the code; skip rewording that does not change meaning.");
    private static final String CONTRIBUTE = """
            Find one small, worthwhile contribution to this repository.

            %s

            Look for candidates in both places:
            - The code: read the source and tests yourself, starting with code that runs often or changed recently, and find problems there \
            directly rather than only through the issue tracker.
            - The tracker: open issues that are well scoped and unclaimed.

            Before choosing:
            1. Read the contribution guidelines and the pull-request template.
            2. Search open and recently closed issues and pull requests for the candidate. Skip anything already fixed, claimed, or in review.
            3. Confirm the problem in the code and gather the evidence its kind needs.

            If nothing qualifies, say so and stop instead of manufacturing a change.

            Implement the smallest justified change:
            - Match the surrounding code: naming, formatting, comment density, and test layout. Run the repository's formatter and linter \
            when it has them.
            - Follow the repository's commit-message style from its history, one logical change per commit.

            Write the pull-request title and description as any contributor would:
            - Use the repository's template. Keep its headings and checkboxes and fill in only what applies.
            - Add only what the title leaves out, in a few short sentences: the problem and why it matters, then how the change solves it. \
            When the title already explains the change, leave the description empty.
            - Leave tests and local verification out: no description of added tests, commands, test runs or their results, or "tested locally".
            - Add no AI attribution, disclosure, co-author, or generated-with text, and leave any AI-use question in the template unanswered.
            """;

    private TaskCommand() {}

    /** Replace only a leading command, keeping the selected reference and the user's additional instructions. */
    static String expand(String message)
    {
        Matcher command = COMMAND.matcher(message);
        if (!command.find()) {
            return message;
        }
        String rest = message.substring(command.end()).strip();
        String brief = switch (command.group(1).toLowerCase(ENGLISH)) {
            case "contribute" -> {
                String[] words = rest.split("\\s+", 2);
                Optional<String> focus = Optional.ofNullable(FOCUSES.get(words[0].toLowerCase(ENGLISH)));
                if (focus.isPresent()) {
                    rest = words.length > 1 ? words[1] : "";
                }
                yield contribute(focus);
            }
            case "fix" -> """
                    Investigate the referenced open issue and fix its root cause in this repository. Read the issue and relevant code first, \
                    implement the smallest justified fix, and verify it with focused tests. If the issue is missing or ambiguous, ask which \
                    issue to fix before making changes. Follow this task's selected automation settings for delivery.
                    """;
            case "review" -> """
                    Review the referenced pull request's current changes against its base. Read its description, diff, and relevant code, \
                    then report actionable findings with file and line references and explain their impact. If no issues are found, say so \
                    and mention any verification gaps. If the pull request is missing or ambiguous, ask which one to review. This request \
                    is for review and suggestions only: do not modify files, commit, push, or publish comments, reviews, or approvals.
                    """;
            default -> throw new IllegalStateException("Unknown task command");
        };
        return rest.isEmpty() ? brief.strip() : brief + "\n" + rest;
    }

    static String contribute(Optional<String> focus)
    {
        String kinds = focus.map(kind -> "Look only for this kind of change:\n- " + kind)
                .orElseGet(() -> FOCUSES.values().stream().map(kind -> "- " + kind)
                        .collect(joining("\n", "Any of these kinds qualifies; when several do, choose the one with the clearest benefit to users:\n", "")));
        return CONTRIBUTE.formatted(kinds);
    }
}
