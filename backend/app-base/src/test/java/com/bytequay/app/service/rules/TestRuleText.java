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
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestRuleText
{
    static final String STORAGE_REVIEW = """
            ---
            name: Review PRs that touch storage
            when: pull-request-updated
            repositories: [acme/warehouse]
            paths: [src/storage/**, docs/storage.md]
            does: review
            agrees-when: reviewed
            ---
            Review the change for bugs and the nits a maintainer of this code would flag.
            Bring each finding as a draft comment on its line; post nothing.
            """;

    @Test
    void readsTheHeaderAndKeepsTheInstructionsAsWritten()
    {
        RuleDefinition rule = RuleText.parse(STORAGE_REVIEW);

        assertThat(rule.name()).isEqualTo("Review PRs that touch storage");
        assertThat(rule.event()).isEqualTo(Event.PULL_REQUEST_UPDATED);
        assertThat(rule.repositories()).containsExactly("acme/warehouse");
        assertThat(rule.paths()).containsExactly("src/storage/**", "docs/storage.md");
        assertThat(rule.labels()).isEmpty();
        assertThat(rule.authors()).isEmpty();
        assertThat(rule.action()).isEqualTo(Action.REVIEW);
        assertThat(rule.agreement()).isEqualTo(Agreement.REVIEWED);
        assertThat(rule.withinDays()).isEqualTo(3);
        assertThat(rule.body()).isEqualTo("""
                Review the change for bugs and the nits a maintainer of this code would flag.
                Bring each finding as a draft comment on its line; post nothing.""");
    }

    @Test
    void aBackportWaitsLongerUnlessTheRuleSaysHowLong()
    {
        String backport = """
                ---
                name: Draft backports
                when: pull-request-merged
                labels: backport
                does: backport
                agrees-when: opened-backport
                ---
                Prepare the backport to each release branch the labels name.
                """;
        assertThat(RuleText.parse(backport).withinDays()).isEqualTo(7);
        assertThat(RuleText.parse(backport).labels()).containsExactly("backport");
        assertThat(RuleText.parse(backport.replace("labels: backport", "within: 12d")).withinDays()).isEqualTo(12);
    }

    @Test
    void refusesWhatItCannotRunWithASentenceThatSaysWhatToChange()
    {
        assertRefused("name: No dashes", "starts with a line of three dashes");
        assertRefused(STORAGE_REVIEW.replace("agrees-when: reviewed\n---", "agrees-when: reviewed"), "second line of three dashes");
        assertRefused(STORAGE_REVIEW.replace("does: review\n", ""), "Add a \"does\" line");
        assertRefused(STORAGE_REVIEW.replace("does: review", "does: review\nmode: auto"), "Remove the mode line");
        assertRefused(STORAGE_REVIEW.replace("does: review", "does: review\npriority: high"), "Unknown header line \"priority\"");
        assertRefused(STORAGE_REVIEW.replace("does: review", "does: review\ndoes: task"), "two \"does\" lines");
        assertRefused(STORAGE_REVIEW.replace("pull-request-updated", "pull-request-opened"), "\"when: pull-request-opened\" is not one ByteQuay knows");
        assertRefused(STORAGE_REVIEW.replace("does: review", "does: rebase"), "can do review, task, not rebase");
        assertRefused(STORAGE_REVIEW.replace("agrees-when: reviewed", "agrees-when: started-task"), "agrees when reviewed, not started-task");
        assertRefused(STORAGE_REVIEW.replace("acme/warehouse", "warehouse"), "owner/repo, not \"warehouse\"");
        assertRefused(STORAGE_REVIEW.replace("src/storage/**", "src/[storage"), "\"src/[storage\" is not a path pattern");
        assertRefused(STORAGE_REVIEW.replace("does: review", "does: review\nwithin: 2w"), "in days, such as 3d");
        assertRefused(STORAGE_REVIEW.replace("does: review", "does: review\nwithin: 45d"), "1 to 30 days");
        assertRefused(STORAGE_REVIEW.substring(0, STORAGE_REVIEW.lastIndexOf("---") + 3), "write what the rule should do");
        assertRefused("""
                ---
                name: Start work on assigned issues
                when: issue-assigned
                paths: [src/**]
                does: task
                agrees-when: started-task
                ---
                Draft a plan.
                """, "\"paths\" only fits pull request events");
    }

    private static void assertRefused(String text, String reason)
    {
        assertThatThrownBy(() -> RuleText.parse(text)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(reason);
    }
}
