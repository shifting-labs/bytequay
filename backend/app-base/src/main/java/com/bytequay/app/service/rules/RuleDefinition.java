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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.util.List;
import java.util.Set;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * What a rule file says, as {@link RuleText} reads it: the event it starts from and its filters, the job it does, the user's action
 * that counts as agreeing with it, how long to wait for that action, and the instructions in plain words.
 */
public record RuleDefinition(
        String name,
        Event event,
        List<String> repositories,
        List<String> paths,
        List<String> labels,
        List<String> authors,
        Action action,
        Agreement agreement,
        int withinDays,
        String body)
{
    public RuleDefinition
    {
        checkText(name, "name", 100);
        requireNonNull(event, "event is null");
        repositories = ImmutableList.copyOf(repositories);
        paths = ImmutableList.copyOf(paths);
        labels = ImmutableList.copyOf(labels);
        authors = ImmutableList.copyOf(authors);
        requireNonNull(action, "action is null");
        requireNonNull(agreement, "agreement is null");
        checkArgument(withinDays > 0, "withinDays must be positive");
        checkText(body, "body");
    }

    /** Whether a record kept for {@code other} still judges this rule: only the name and the instructions may differ. */
    public boolean sameTrigger(RuleDefinition other)
    {
        return event == other.event && repositories.equals(other.repositories) && paths.equals(other.paths) && labels.equals(other.labels)
                && authors.equals(other.authors) && action == other.action && agreement == other.agreement && withinDays == other.withinDays;
    }

    /** An event ByteQuay matches in code, with the jobs and the agreeing actions that fit it. */
    public enum Event
    {
        REVIEW_REQUESTED("review-requested", true, ImmutableSet.of(Action.REVIEW, Action.TASK), ImmutableSet.of(Agreement.REVIEWED)),
        PULL_REQUEST_UPDATED("pull-request-updated", true, ImmutableSet.of(Action.REVIEW, Action.TASK), ImmutableSet.of(Agreement.REVIEWED)),
        ISSUE_ASSIGNED("issue-assigned", false, ImmutableSet.of(Action.TASK), ImmutableSet.of(Agreement.STARTED_TASK)),
        PULL_REQUEST_BEHIND("pull-request-behind", true, ImmutableSet.of(Action.REBASE, Action.TASK), ImmutableSet.of(Agreement.UPDATED_BRANCH)),
        PULL_REQUEST_MERGED("pull-request-merged", true, ImmutableSet.of(Action.BACKPORT, Action.TASK), ImmutableSet.of(Agreement.OPENED_BACKPORT));

        private final String key;
        private final boolean pullRequest;
        private final Set<Action> actions;
        private final Set<Agreement> agreements;

        Event(String key, boolean pullRequest, Set<Action> actions, Set<Agreement> agreements)
        {
            this.key = key;
            this.pullRequest = pullRequest;
            this.actions = actions;
            this.agreements = agreements;
        }

        public String key()
        {
            return key;
        }

        /** Whether the event is about a pull request, so that its changed files can be matched against {@code paths}. */
        public boolean pullRequest()
        {
            return pullRequest;
        }

        public Set<Action> actions()
        {
            return actions;
        }

        public Set<Agreement> agreements()
        {
            return agreements;
        }
    }

    /** The job a rule does, each through a flow ByteQuay already has. */
    public enum Action
    {
        REVIEW("review"),
        TASK("task"),
        REBASE("rebase"),
        BACKPORT("backport");

        private final String key;

        Action(String key)
        {
            this.key = key;
        }

        public String key()
        {
            return key;
        }
    }

    /** The user's own action that shows a run agreed with them. */
    public enum Agreement
    {
        REVIEWED("reviewed"),
        STARTED_TASK("started-task"),
        UPDATED_BRANCH("updated-branch"),
        OPENED_BACKPORT("opened-backport");

        private final String key;

        Agreement(String key)
        {
            this.key = key;
        }

        public String key()
        {
            return key;
        }
    }
}
