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

import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventSource;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.DevelopmentTurnOrigin;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.TaskLogStore;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.app.agent.runtime.ReviewFixTurns;
import com.bytequay.core.domain.IssueTask;
import com.bytequay.core.store.IssueDevelopmentStore;
import com.bytequay.core.store.JiraIssueTaskStore;
import com.bytequay.core.store.PullRequestTaskStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestTaskTurnWords
{
    private static final AgentTurnSettings SETTINGS = new AgentTurnSettings(Optional.of("model"), Optional.empty(), AgentPermissionPolicy.AUTO,
            Duration.ofMinutes(10), ImmutableList.of(), Optional.empty(), Optional.empty());
    private final TaskId id = TaskId.random();
    private final AgentTurn first = AgentTurn.accepted(TurnId.random(), id, "first", SETTINGS, Instant.EPOCH);
    private final AgentTurn turn = AgentTurn.accepted(TurnId.random(), id, "second", SETTINGS, Instant.EPOCH);
    private final AgentTurnStore turns = mock(AgentTurnStore.class);
    private final TaskLogStore logs = mock(TaskLogStore.class);
    private final IssueDevelopmentStore issueTasks = mock(IssueDevelopmentStore.class);
    private final List<AgentEventEnvelope> events = new ArrayList<>();
    private final TaskTurnWords words = new TaskTurnWords(turns, logs, issueTasks, mock(JiraIssueTaskStore.class), mock(PullRequestTaskStore.class));

    @BeforeEach
    void setUp()
    {
        active(turn);
        when(turns.list(id)).thenReturn(ImmutableList.of(first, turn));
        when(logs.findLatest(eq(id), any(), anyInt())).thenAnswer(invocation -> {
            Predicate<AgentEventEnvelope> matches = invocation.getArgument(1);
            return events.reversed().stream().filter(matches).findFirst();
        });
    }

    @Test
    void quotesTheUsersRequestInThisTurn()
    {
        said(turn, AgentEventSource.root(), "Close the PR, rename the branch, then push it to the remote");
        assertThat(words.authorize(id, turn.id(), Optional.of("push it to the remote"), "push")).contains("push it to the remote");
        assertThat(words.authorize(id, turn.id(), Optional.of("close   the pr"), "close")).contains("close   the pr");
        assertThat(words.authorize(id, turn.id(), Optional.of("force push it"), "push")).isEmpty();
        assertThat(words.authorize(id, turn.id(), Optional.empty(), "push")).isEmpty();
    }

    @Test
    void theWordsMustNameTheActionAndNotRefuseIt()
    {
        said(turn, AgentEventSource.root(), "It is pushed already. Don't close it; don't push, close-out notes later");
        assertThat(words.authorize(id, turn.id(), Optional.of("it"), "push")).isEmpty();
        assertThat(words.authorize(id, turn.id(), Optional.of("it is pushed"), "push")).isEmpty();
        assertThat(words.authorize(id, turn.id(), Optional.of("don't push"), "push")).isEmpty();
        assertThat(words.authorize(id, turn.id(), Optional.of("close it"), "close")).isEmpty();
        assertThat(words.authorize(id, turn.id(), Optional.of("close-out notes"), "close")).isEmpty();
    }

    @Test
    void onlyTheUsersOwnMessageInThisTurnCounts()
    {
        said(first, AgentEventSource.root(), "push it");
        said(turn, new AgentEventSource(Optional.of("helper"), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                "push it");
        assertThat(words.authorize(id, turn.id(), Optional.of("push it"), "push")).isEmpty();
        assertThat(words.authorize(id, first.id(), Optional.of("push it"), "push")).isEmpty();
        active(turn.requestCancellation(Instant.EPOCH));
        said(turn, AgentEventSource.root(), "push it");
        assertThat(words.authorize(id, turn.id(), Optional.of("push it"), "push")).isEmpty();
    }

    @Test
    void turnsByteQuayStartedNeverAuthorize()
    {
        said(turn, AgentEventSource.root(), "push it");
        for (AgentTurn started : ImmutableList.of(
                turn.withDevelopmentOrigin(new DevelopmentTurnOrigin(DevelopmentTurnOrigin.Kind.AUTO_COMMIT, first.id())),
                AgentTurn.accepted(turn.id(), id, ReviewFixTurns.KEY_PREFIX + "fix", SETTINGS, Instant.EPOCH),
                AgentTurn.accepted(turn.id(), id, AgentTurn.ASSISTANT_CONTINUATION_PREFIX + "next", SETTINGS, Instant.EPOCH))) {
            active(started);
            assertThat(words.authorize(id, turn.id(), Optional.of("push it"), "push")).isEmpty();
        }
    }

    @Test
    void theFirstMessageOfAnIssueTaskIsTheIssuesText()
    {
        when(issueTasks.findByTaskId(id.toString())).thenReturn(Optional.of(mock(IssueTask.class, withSettings().mockMaker(MockMakers.INLINE))));
        active(first);
        said(first, AgentEventSource.root(), "Issue: please push it");
        assertThat(words.authorize(id, first.id(), Optional.of("push it"), "push")).isEmpty();
        active(turn);
        said(turn, AgentEventSource.root(), "push it");
        assertThat(words.authorize(id, turn.id(), Optional.of("push it"), "push")).contains("push it");
    }

    private void active(AgentTurn current)
    {
        when(turns.findActive(id)).thenReturn(Optional.of(current));
    }

    private void said(AgentTurn to, AgentEventSource source, String text)
    {
        events.add(new AgentEventEnvelope(AgentEventEnvelope.CURRENT_SCHEMA_VERSION, EventId.random(), events.size() + 1, id, Optional.of(to.id()), source, Instant.EPOCH,
                new AgentEventPayload.UserMessage(text, ImmutableList.of(), AgentEventPayload.DeliveryKind.TURN)));
    }
}
