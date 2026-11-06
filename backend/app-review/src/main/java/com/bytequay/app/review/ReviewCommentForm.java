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
package com.bytequay.app.review;

import com.bytequay.core.review.ReviewInvalidRequestException;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The form a finding must have before it is saved as a review comment: short, with the request up front and stated
 * plainly. Fenced code is never measured, and inline code counts only as words, so quoted code cannot trip a rule.
 *
 * <p>Whether a sentence makes a request is judged from its wording alone: a question, a clause that opens with one
 * of the listed verbs, or a statement of what should change. A request worded any other way is refused, and the
 * refusal says which wordings pass.
 */
final class ReviewCommentForm
{
    private static final int MAX_WORDS = 120;
    private static final int MAX_NIT_WORDS = 35;
    private static final int MAX_SENTENCES = 5;
    private static final int MAX_NIT_SENTENCES = 2;
    private static final int REQUEST_BY = 2;
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+(?:['’-][\\p{L}\\p{N}]+)*");
    private static final Pattern INLINE_CODE = Pattern.compile("`[^`\\n]*`");
    // A full stop after these does not end a sentence.
    private static final Pattern ABBREVIATION = Pattern.compile("\\b(?:e\\.g|i\\.e|vs|etc|cf|approx)\\.", Pattern.CASE_INSENSITIVE);
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.?!])[\"')\\]]*\\s+|\\n\\s*\\n");
    private static final Pattern CLAUSE_BREAK = Pattern.compile(":\\s+");
    private static final Pattern LEAD_IN = Pattern.compile("^[\\s\"'(\\[*_>#-]*(?:(?:please|then|also|instead|otherwise|here|so|and|or),?\\s+)*", Pattern.CASE_INSENSITIVE);
    private static final Pattern STATED_CHANGE = Pattern.compile("\\b(?:should|must|needs? to|has to|have to|ought to)\\b", Pattern.CASE_INSENSITIVE);
    // Verbs that open a requested change. Words that as often open a statement, such as note, state, name and default, are left out.
    private static final Pattern VERB = Pattern.compile("(?:"
            + "add|align|allow|apply|assert|avoid|batch|bound|bump|call|cancel|cap|catch|change|check|cite|clamp|clarify|clear|close|collapse|combine|compare|compute"
            + "|convert|copy|cover|create|declare|deduplicate|defer|define|delete|deprecate|derive|describe|disable|document|drop|emit|enable|ensure|escape|exclude"
            + "|explain|expose|extend|extract|fail|fall|fix|flush|fold|follow|give|guard|handle|hide|hoist|include|initialize|inline|introduce|invert|join|keep|leave"
            + "|let|lower|make|mark|measure|mention|merge|move|narrow|normalize|pass|pin|prefer|preserve|propagate|pull|put|quote|raise|read|reduce|register|reject"
            + "|release|remove|rename|reorder|replace|require|reset|restore|restrict|rethrow|retry|return|reuse|revert|reword|rewrite|run|say|set|shorten|simplify"
            + "|skip|sort|split|squash|synchronize|take|test|throw|tick|track|treat|trim|truncate|turn|unwrap|update|use|validate|verify|wait|widen|wrap|write"
            + ")\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEDGE = Pattern.compile("\\b(?:may|might|could) (?:want|wish|like) to\\b|(?:^|[.?!:]\\s+)(?:please\\s+)?consider\\b"
            + "|\\b(?:could|might|may|would) consider\\b|\\bworth considering\\b|\\bperhaps\\b|\\bmaybe\\b|\\bfeel free to\\b|\\bnot a blocker\\b", Pattern.CASE_INSENSITIVE);

    private ReviewCommentForm() {}

    static void check(String title, String body)
    {
        boolean nit = title.regionMatches(true, 0, "Nit:", 0, 4);
        String prose = withoutFencedCode(body);
        long words = WORD.matcher(prose).results().count();
        int wordLimit = nit ? MAX_NIT_WORDS : MAX_WORDS;
        if (words > wordLimit) {
            throw new ReviewInvalidRequestException("body: " + words + " prose words exceeds " + wordLimit
                    + "; polish before saving. Preserve the trigger, consequence and material conditions; move the step-by-step example into the walkthrough");
        }
        String plain = INLINE_CODE.matcher(prose).replaceAll("code").strip();
        List<String> sentences = sentences(plain);
        int sentenceLimit = nit ? MAX_NIT_SENTENCES : MAX_SENTENCES;
        if (sentences.size() > sentenceLimit) {
            throw new ReviewInvalidRequestException("body: " + sentences.size() + " sentences exceeds " + sentenceLimit
                    + "; keep the request and the reason it needs, and move the rest into the walkthrough");
        }
        Matcher hedge = HEDGE.matcher(plain);
        if (hedge.find()) {
            throw new ReviewInvalidRequestException("body: \"" + hedge.group().replaceFirst("^[.?!:]\\s+", "").strip()
                    + "\" hedges the request; say which change or answer you want");
        }
        if (sentences.stream().limit(REQUEST_BY).noneMatch(ReviewCommentForm::requests)) {
            throw new ReviewInvalidRequestException("body: lead with the request. One of the first " + REQUEST_BY
                    + " sentences must ask a question, open with the action such as Remove or Use, or say what should change; the reason follows it");
        }
    }

    /**
     * Every finding about lines except a nit owes the user a walkthrough to read before posting: an example followed
     * through the code. Its wording is not measured, but it must show code.
     */
    static void checkWalkthrough(String title, boolean lines, Optional<String> walkthrough)
    {
        if (!lines || title.regionMatches(true, 0, "Nit:", 0, 4)) {
            return;
        }
        String text = walkthrough.orElseThrow(() -> new ReviewInvalidRequestException("walkthrough: required for a finding about lines, apart from a Nit:. "
                + "Follow one concrete example input through the code: each step as path:line with the code and the value it holds, "
                + "where the result differs from the expected one and whether that ran or was traced, then the fixed code"));
        withoutFencedCode(text, "walkthrough");
        if (text.lines().map(String::stripLeading).noneMatch(line -> line.startsWith("```") || line.startsWith("~~~"))) {
            throw new ReviewInvalidRequestException("walkthrough: show the code each step runs in fenced code blocks");
        }
    }

    private static String withoutFencedCode(String body)
    {
        return withoutFencedCode(body, "body");
    }

    private static String withoutFencedCode(String body, String field)
    {
        StringBuilder prose = new StringBuilder();
        String fence = "";
        for (String line : body.lines().toList()) {
            String stripped = line.stripLeading();
            if (fence.isEmpty() && (stripped.startsWith("```") || stripped.startsWith("~~~"))) {
                fence = stripped.substring(0, 3);
            }
            else if (!fence.isEmpty() && stripped.startsWith(fence)) {
                fence = "";
            }
            else if (fence.isEmpty()) {
                prose.append(line).append('\n');
            }
        }
        if (!fence.isEmpty()) {
            throw new ReviewInvalidRequestException(field + ": close the code fence before saving the comment");
        }
        return prose.toString();
    }

    private static List<String> sentences(String prose)
    {
        String guarded = ABBREVIATION.matcher(prose).replaceAll(match -> match.group().replace('.', '\u0000'));
        return SENTENCE_END.splitAsStream(guarded).map(sentence -> sentence.replace('\u0000', '.').strip()).filter(sentence -> !sentence.isEmpty()).toList();
    }

    private static boolean requests(String sentence)
    {
        if (sentence.replaceFirst("[\"')\\]\\s]+$", "").endsWith("?") || STATED_CHANGE.matcher(sentence).find()) {
            return true;
        }
        // "Commit message of abc123: say what changes for the caller" names its subject before the request.
        return CLAUSE_BREAK.splitAsStream(sentence).anyMatch(clause -> VERB.matcher(LEAD_IN.matcher(clause).replaceFirst("")).lookingAt());
    }
}
