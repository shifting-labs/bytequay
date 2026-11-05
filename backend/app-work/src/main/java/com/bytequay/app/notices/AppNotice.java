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

import com.bytequay.app.utils.ContextText;
import com.bytequay.core.domain.AssistantReport;
import com.bytequay.core.domain.RuleRun;

import java.time.Instant;
import java.util.Optional;

/**
 * A short projection of task facts. The source owns resolution; notices own no durable lifecycle.
 *
 * @param branch the reviewed pull request's head, or the development task's own branch
 * @param workItemId the Epic work a {@link Kind#NEXT_WORK} notice suggests starting next
 * @param expiresAt when the agent stops waiting for a pending approval or question card
 */
public record AppNotice(
        String id,
        Kind kind,
        String taskId,
        String workspaceId,
        Optional<String> turnId,
        Optional<String> reviewId,
        String title,
        Optional<String> pullRequest,
        Optional<String> pullRequestTitle,
        Optional<String> branch,
        Optional<Changes> changes,
        String message,
        String repository,
        String laneId,
        Optional<String> workItemId,
        Instant occurredAt,
        Optional<Instant> expiresAt,
        Optional<RuleRun> ruleRun,
        AssistantReport.Delivery delivery,
        String deliveryReason,
        Optional<AssistantReport> report)
{
    public AppNotice(String id, Kind kind, String taskId, String workspaceId, Optional<String> turnId, Optional<String> reviewId, String title,
            Optional<String> pullRequest, Optional<String> pullRequestTitle, Optional<String> branch, Optional<Changes> changes, String message,
            String repository, String laneId, Optional<String> workItemId, Instant occurredAt, Optional<Instant> expiresAt, Optional<RuleRun> ruleRun)
    {
        this(id, kind, taskId, workspaceId, turnId, reviewId, title, pullRequest, pullRequestTitle, branch, changes, message, repository, laneId, workItemId,
                occurredAt, expiresAt, ruleRun, AssistantReport.deliveryFor(state(kind)), AssistantReport.reasonFor(state(kind)), Optional.empty());
    }

    public AppNotice(String id, Kind kind, String taskId, String workspaceId, Optional<String> turnId, Optional<String> reviewId, String title,
            Optional<String> pullRequest, Optional<String> pullRequestTitle, Optional<String> branch, Optional<Changes> changes, String message,
            String repository, String laneId, Optional<String> workItemId, Instant occurredAt, Optional<Instant> expiresAt)
    {
        this(id, kind, taskId, workspaceId, turnId, reviewId, title, pullRequest, pullRequestTitle, branch, changes, message, repository, laneId, workItemId,
                occurredAt, expiresAt, Optional.empty());
    }

    public String summaryLine()
    {
        return "“" + ContextText.excerpt(title.strip().replaceAll("\\s+", " "), 80) + "”: " + message;
    }

    public AppNotice withRuleRun(RuleRun run)
    {
        return new AppNotice(id, kind, taskId, workspaceId, turnId, reviewId, title, pullRequest, pullRequestTitle, branch, changes, message, repository, laneId,
                workItemId, occurredAt, expiresAt, Optional.of(run), delivery, deliveryReason, report);
    }

    public AppNotice withMessage(String summary)
    {
        return new AppNotice(id, kind, taskId, workspaceId, turnId, reviewId, title, pullRequest, pullRequestTitle, branch, changes, summary, repository, laneId,
                workItemId, occurredAt, expiresAt, ruleRun, delivery, deliveryReason, report);
    }

    public AppNotice withReport(AssistantReport value)
    {
        return new AppNotice(id, kind, taskId, workspaceId, turnId, reviewId, title, pullRequest, pullRequestTitle, branch, changes, message, repository, laneId,
                workItemId, occurredAt, expiresAt, ruleRun, value.delivery(), value.deliveryReason(), Optional.of(value));
    }

    public AppNotice delivered(AssistantReport.Delivery value, String reason)
    {
        return new AppNotice(id, kind, taskId, workspaceId, turnId, reviewId, title, pullRequest, pullRequestTitle, branch, changes, message, repository, laneId,
                workItemId, occurredAt, expiresAt, ruleRun, value, reason, report.map(item -> item.delivered(value, reason)));
    }

    public static AssistantReport.State state(Kind kind)
    {
        return switch (kind) {
            case TURN_FINISHED -> AssistantReport.State.RESULT_READY;
            case QUESTION -> AssistantReport.State.ANSWER_REQUIRED;
            case APPROVAL -> AssistantReport.State.APPROVAL_REQUIRED;
            case NEXT_WORK -> AssistantReport.State.PROPOSAL;
            case FAILURE, RECOVERY, STUCK, QUEUE_PAUSED, CI_ATTENTION -> AssistantReport.State.ATTENTION;
        };
    }

    public enum Kind
    {
        TURN_FINISHED,
        QUESTION,
        APPROVAL,
        FAILURE,
        RECOVERY,
        /** A running turn that looks stuck, as the watchdog tells it. */
        STUCK,
        QUEUE_PAUSED,
        CI_ATTENTION,
        NEXT_WORK
    }

    /** Counts for the latest review run; {@code update} marks a run that covers only changes since the prior review. */
    public record Changes(boolean update, int commits, int files, long additions, long deletions) {}
}
