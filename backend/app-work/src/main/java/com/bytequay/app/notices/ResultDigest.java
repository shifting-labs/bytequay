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
package com.bytequay.app.notices;

import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * What a result card decides from, read from what ByteQuay stores: the pull request where it stands, the review's latest accepted run,
 * and the agent's own report of a development turn.
 *
 * @param host the hosting provider's name, GitHub or GitLab, for the receipt's link
 */
public record ResultDigest(String noticeId, String host, Optional<Pull> pull, Optional<Review> review, Optional<String> report)
{
    public ResultDigest
    {
        requireNonNull(noticeId, "noticeId is null");
        requireNonNull(host, "host is null");
        requireNonNull(pull, "pull is null");
        requireNonNull(review, "review is null");
        requireNonNull(report, "report is null");
    }

    /**
     * The pull request as its stored detail last read it.
     *
     * @param state open, closed or merged
     * @param checks the checks of the current head
     * @param approvers who approves it, by each reviewer's latest verdict
     */
    public record Pull(int number, String title, String url, String state, boolean draft, boolean mine, String author, Optional<String> head, Optional<String> base,
            int additions, int deletions, int files, List<Check> checks, List<String> approvers, boolean youApproved, boolean mergeBlocked, Optional<Boolean> mergeable)
    {
        public Pull
        {
            requireNonNull(title, "title is null");
            requireNonNull(url, "url is null");
            requireNonNull(state, "state is null");
            requireNonNull(author, "author is null");
            requireNonNull(head, "head is null");
            requireNonNull(base, "base is null");
            checks = ImmutableList.copyOf(checks);
            approvers = ImmutableList.copyOf(approvers);
            requireNonNull(mergeable, "mergeable is null");
        }
    }

    /**
     * One check of the current head.
     *
     * @param state failing, pending, successful, neutral, skipped or cancelled
     */
    public record Check(String name, String state, Optional<String> url)
    {
        public Check
        {
            requireNonNull(name, "name is null");
            requireNonNull(state, "state is null");
            requireNonNull(url, "url is null");
        }
    }

    /**
     * The review's latest accepted run.
     *
     * @param currentHead the head ByteQuay last saw on the pull request, which moved on when it differs from the reviewed one
     * @param filesRead the changed files the reviewer read
     * @param commands the commands the reviewer ran that no finding cites
     * @param findings the open findings at the reviewed revision, the most severe first
     * @param fixTask the development task that wrote the pull request, which takes the findings to fix
     */
    public record Review(String reviewedHead, Optional<String> currentHead, int filesRead, int filesChanged, boolean complete, List<String> omissions, List<String> warnings,
            List<Command> commands, List<Finding> findings, Optional<String> fixTask)
    {
        public Review
        {
            requireNonNull(reviewedHead, "reviewedHead is null");
            requireNonNull(currentHead, "currentHead is null");
            omissions = ImmutableList.copyOf(omissions);
            warnings = ImmutableList.copyOf(warnings);
            commands = ImmutableList.copyOf(commands);
            findings = ImmutableList.copyOf(findings);
            requireNonNull(fixTask, "fixTask is null");
        }
    }

    public record Command(String command, Optional<Integer> exitCode)
    {
        public Command
        {
            requireNonNull(command, "command is null");
            requireNonNull(exitCode, "exitCode is null");
        }
    }

    /**
     * @param line the first line of the finding's anchor
     * @param ran the command the finding cites as proof, with its exit code, when the reviewer ran one
     */
    public record Finding(String id, String label, String title, String body, Optional<String> severity, Optional<String> path, Optional<Integer> line, boolean outdated,
            long version, int revision, Optional<String> ran)
    {
        public Finding
        {
            requireNonNull(id, "id is null");
            requireNonNull(label, "label is null");
            requireNonNull(title, "title is null");
            requireNonNull(body, "body is null");
            requireNonNull(severity, "severity is null");
            requireNonNull(path, "path is null");
            requireNonNull(line, "line is null");
            requireNonNull(ran, "ran is null");
        }
    }
}
