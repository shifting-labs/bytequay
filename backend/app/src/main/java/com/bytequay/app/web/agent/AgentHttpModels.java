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
package com.bytequay.app.web.agent;

import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventSource;
import com.bytequay.agent.core.AgentHistoryPage;
import com.bytequay.agent.core.AgentRuntimeStopImpact;
import com.bytequay.agent.core.ApprovalDecision;
import com.bytequay.agent.core.ApprovalRequest;
import com.bytequay.agent.core.StoredAgentAttachment;
import com.bytequay.agent.core.TaskCompletion;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.spi.AgentCapabilities;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.ProviderAgentEvent.MessageKind;
import com.bytequay.agent.spi.ProviderAgentEvent.MessagePhase;
import com.bytequay.agent.spi.ProviderAgentEventSource;
import com.bytequay.agent.spi.ProviderApprovalRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;

import static com.google.common.collect.ImmutableList.toImmutableList;

/**
 * HTTP shapes for agent activity, shared with the review pane's own routes.
 *
 * <p>Public only so the sibling {@code web.review} package can render the same transcript,
 * approval, and history shapes; this type stays internal to the application module.
 */
public final class AgentHttpModels
{
    private AgentHttpModels() {}

    public static EventResponse event(AgentEventEnvelope envelope)
    {
        return new EventResponse(
                envelope.schemaVersion(),
                envelope.eventId().toString(),
                envelope.sequence(),
                envelope.taskId().toString(),
                envelope.turnId().map(Object::toString),
                envelope.source(),
                envelope.occurredAt(),
                envelope.type().name().toLowerCase(Locale.ROOT),
                envelope.payload());
    }

    public static ApprovalResponse approval(ApprovalDecision decision)
    {
        return new ApprovalResponse(
                decision.approvalId().toString(),
                decision.decision().name(),
                decision.scopedPermission(),
                decision.forTask(),
                decision.resolvedAt(),
                decision.timedOut());
    }

    public static HistoryResponse history(AgentHistoryPage page)
    {
        List<AgentEventEnvelope> events = page.events();
        return new HistoryResponse(
                withoutReplacedPieces(events).stream().map(AgentHttpModels::event).collect(toImmutableList()),
                events.isEmpty() ? OptionalLong.empty() : OptionalLong.of(events.getFirst().sequence()),
                events.isEmpty() ? OptionalLong.empty() : OptionalLong.of(events.getLast().sequence()),
                page.beforeCursor().isPresent() ? Optional.of(Long.toString(page.beforeCursor().getAsLong())) : Optional.empty(),
                page.afterCursor().isPresent() ? Optional.of(Long.toString(page.afterCursor().getAsLong())) : Optional.empty(),
                page.latestSequence());
    }

    /**
     * Leaves out streamed message pieces whose completed message follows them directly in the same page. They are often most of a
     * page, and the transcript shows the completed text in their place; a run whose completion is not in the page stays.
     */
    private static List<AgentEventEnvelope> withoutReplacedPieces(List<AgentEventEnvelope> events)
    {
        List<AgentEventEnvelope> kept = new ArrayList<>(events.size());
        Optional<MessageItem> completed = Optional.empty();
        for (AgentEventEnvelope event : events.reversed()) {
            Optional<MessageItem> item = messageItem(event);
            // The transcript treats a message with a provider event id as complete, whatever its phase.
            boolean finished = event.source().providerEventId().isPresent();
            boolean piece = !finished && event.payload() instanceof AgentEventPayload.AssistantMessage message && message.phase() == MessagePhase.PARTIAL;
            if (piece && item.isPresent() && item.equals(completed)) {
                continue;
            }
            completed = finished ? item : Optional.empty();
            kept.add(event);
        }
        return kept.reversed();
    }

    private static Optional<MessageItem> messageItem(AgentEventEnvelope event)
    {
        if (!(event.payload() instanceof AgentEventPayload.AssistantMessage message) || event.source().providerItemId().isEmpty()) {
            return Optional.empty();
        }
        AgentEventSource source = event.source();
        return Optional.of(new MessageItem(event.turnId(), source.providerThreadId(), source.providerTurnId(), source.providerItemId().orElseThrow(), message.messageKind()));
    }

    private record MessageItem(Optional<TurnId> turnId, Optional<String> threadId, Optional<String> providerTurnId, String itemId, MessageKind kind) {}

    public static PendingApprovalResponse pendingApproval(ApprovalRequest request)
    {
        ProviderApprovalRequest provider = request.providerRequest();
        ProviderAgentEventSource source = provider.source();
        return new PendingApprovalResponse(
                request.id().toString(), request.taskId().toString(), request.turnId().toString(),
                new AgentEventSource(source.providerThreadId(), source.parentProviderThreadId(), source.logicalAgentPath(),
                        source.providerTurnId(), source.providerItemId(), Optional.empty()),
                request.requestedAt(), request.expiresAt(), provider.title(), provider.detail(),
                switch (provider.kind()) {
                    case COMMAND, FILE_CHANGE -> provider.kind().name();
                    case TOOL, OTHER -> "OTHER";
                },
                provider.requestedPermissions(), provider.sandboxed(), provider.availableDecisions().stream().map(Enum::name).collect(toImmutableList()), true);
    }

    public static ProviderStatusResponse providerStatus(AgentProviderStatus status)
    {
        return new ProviderStatusResponse(status.laneId().toString(), status.readiness(), status.version(), status.diagnostic(), status.capabilities(), status.signInCommand());
    }

    public static CompletionResponse completion(TaskCompletion completion)
    {
        return new CompletionResponse(
                completion.taskId().toString(),
                InteractiveAgentHttpContract.summary(completion.summary()),
                completion.completedAt(),
                completion.deleteAfter());
    }

    public static RuntimeStopImpactResponse stopImpact(AgentRuntimeStopImpact impact)
    {
        return new RuntimeStopImpactResponse(impact.taskId().toString(), impact.generation(), impact.activeTurnId().map(Object::toString), impact.liveProcess());
    }

    /** A stored attachment's facts; the blob itself is read through its own endpoint. */
    public record AttachmentResponse(String id, String name, String mediaType, long byteSize, int width, int height) {}

    public static AttachmentResponse attachment(StoredAgentAttachment stored)
    {
        return new AttachmentResponse(stored.id(), stored.name(), stored.mediaType(), stored.byteSize(), stored.width(), stored.height());
    }

    public record EventResponse(
            int schemaVersion,
            String eventId,
            long sequence,
            String taskId,
            Optional<String> turnId,
            AgentEventSource source,
            Instant occurredAt,
            String type,
            AgentEventPayload payload) {}

    public record ApprovalResponse(
            String approvalId,
            String decision,
            Optional<String> scopedPermission,
            boolean forTask,
            Instant resolvedAt,
            boolean timedOut) {}

    /** The events skip streamed pieces their completed message replaces; the sequence bounds are the page's own, before that. */
    public record HistoryResponse(
            List<EventResponse> events, OptionalLong firstSequence, OptionalLong lastSequence, Optional<String> beforeCursor, Optional<String> afterCursor, long latestSequence) {}

    public record PendingApprovalResponse(
            String approvalId, String taskId, String turnId, AgentEventSource source, Instant occurredAt, Instant expiresAt,
            String title, String detail, String approvalKind, List<String> scope, boolean sandboxed, List<String> availableDecisions, boolean actionable) {}

    public record ProviderStatusResponse(
            String laneId,
            AgentProviderStatus.Readiness readiness,
            Optional<String> version,
            String diagnostic,
            AgentCapabilities capabilities,
            Optional<String> signInCommand) {}

    public record CompletionResponse(
            String taskId,
            InteractiveAgentHttpContract.TaskSummaryResponse summary,
            Instant completedAt,
            Instant deleteAfter) {}

    public record RuntimeStopImpactResponse(
            String taskId,
            String generation,
            Optional<String> activeTurnId,
            boolean liveProcess) {}

    /** The creation step an in-flight create request is in, or empty once that request has returned. */
    public record StartupProgressResponse(Optional<String> phase) {}
}
