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
package com.bytequay.core.domain;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether words an agent quotes are the user's own to act on: part of the user's message, ignoring case and runs of whitespace, at
 * least two characters long, and not something the message says not to do.
 */
public final class UserWords
{
    // Words after one of these, in the same clause, are what the user said not to do: "push to main" in "don't push to main".
    private static final Pattern NEGATION = Pattern.compile("\\b(?:not|never|don't|dont|doesn't|didn't|can't|cannot|won't|wouldn't|shouldn't|mustn't|isn't|aren't|wasn't"
            + "|stop|avoid|without|neither|nor|except|instead of|rather than)\\b");
    // ponytail: a comma or a contrast ends the clause, so "don't push, open a PR" allows the PR, but a list after a comma ("don't push, merge
    // or tag") escapes; asking the user to confirm the words would close that.
    private static final Pattern CLAUSE_END = Pattern.compile("[,.!?;:]|\\b(?:but|then|otherwise)\\b");

    private UserWords() {}

    /** Why the words are not the user's to act on, or empty when they are. */
    public static Optional<String> unsaid(String message, String words)
    {
        if (!inMessage(message, words)) {
            return Optional.of("Those words aren't in your message");
        }
        return saysNot(message, words) ? Optional.of("Your message says not to do that") : Optional.empty();
    }

    /** Whether the words are part of the message, ignoring case and runs of whitespace, and at least two characters long. */
    public static boolean inMessage(String message, String words)
    {
        String said = normal(words);
        return said.length() >= 2 && normal(message).contains(said);
    }

    /** Whether the message says not to do what the words say, wherever they appear in it. */
    public static boolean saysNot(String message, String words)
    {
        return negated(normal(message), normal(words));
    }

    /** Lower case, plain apostrophes and single spaces, as the checks compare text. */
    public static String normal(String text)
    {
        return text.toLowerCase(Locale.ROOT).replace('’', '\'').replaceAll("\\s+", " ").strip();
    }

    /**
     * Whether the message says not to do what the words say: each place they appear follows a negation earlier in its clause. Both are
     * normalized. Words that carry their own negation, such as "don't push", are not negated by it.
     */
    static boolean negated(String message, String words)
    {
        boolean found = false;
        for (int at = message.indexOf(words); at >= 0; at = message.indexOf(words, at + 1)) {
            found = true;
            String before = message.substring(0, at);
            Matcher end = CLAUSE_END.matcher(before);
            int clause = 0;
            while (end.find()) {
                clause = end.end();
            }
            if (!NEGATION.matcher(before.substring(clause)).find()) {
                return false;
            }
        }
        return found;
    }
}
