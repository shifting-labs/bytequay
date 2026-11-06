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

import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventSource;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.TaskLogStore;
import com.bytequay.app.agent.runtime.ReviewFixTurns;
import com.bytequay.core.domain.UserWords;
import com.bytequay.core.store.IssueDevelopmentStore;
import com.bytequay.core.store.JiraIssueTaskStore;
import com.bytequay.core.store.PullRequestTaskStore;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/**
 * Whether words an agent quotes are the user's own request in a development task's current turn, so the action they ask for needs no
 * card: they name the action, are part of a message the user sent in that turn, and are not something it says not to do. A turn
 * ByteQuay started itself, or one whose message wraps an issue's, a ticket's or a pull request's own text, never authorizes.
 */
@Component
public class TaskTurnWords
{
    private static final int SEARCHED_EVENTS = 10_000;

    private final AgentTurnStore turns;
    private final TaskLogStore logs;
    private final IssueDevelopmentStore issueTasks;
    private final JiraIssueTaskStore jiraTasks;
    private final PullRequestTaskStore pullRequestTasks;

    public TaskTurnWords(AgentTurnStore turns, TaskLogStore logs, IssueDevelopmentStore issueTasks, JiraIssueTaskStore jiraTasks, PullRequestTaskStore pullRequestTasks)
    {
        this.turns = requireNonNull(turns, "turns is null");
        this.logs = requireNonNull(logs, "logs is null");
        this.issueTasks = requireNonNull(issueTasks, "issueTasks is null");
        this.jiraTasks = requireNonNull(jiraTasks, "jiraTasks is null");
        this.pullRequestTasks = requireNonNull(pullRequestTasks, "pullRequestTasks is null");
    }

    /**
     * The words, when they are the user's own request for the action in the task's active turn; empty when the user must allow it instead.
     *
     * @param action what the words must name as a whole word, such as "push" or a branch name
     */
    public Optional<String> authorize(TaskId taskId, TurnId turnId, Optional<String> words, String action)
    {
        if (words.filter(said -> names(said, action)).isEmpty()) {
            return Optional.empty();
        }
        Optional<AgentTurn> turn = turns.findActive(taskId).filter(active -> active.id().equals(turnId) && active.cancellationRequestedAt().isEmpty());
        if (turn.isEmpty() || turn.orElseThrow().developmentOrigin().isPresent() || ReviewFixTurns.isFixTurn(turn.orElseThrow())
                || turn.orElseThrow().idempotencyKey().startsWith(AgentTurn.ASSISTANT_CONTINUATION_PREFIX) || wrapsItem(taskId, turnId)) {
            return Optional.empty();
        }
        String said = words.orElseThrow();
        return logs.findLatest(taskId, event -> event.turnId().filter(turnId::equals).isPresent() && event.source().equals(AgentEventSource.root())
                        && event.payload() instanceof AgentEventPayload.UserMessage message && UserWords.unsaid(message.text(), said).isEmpty(), SEARCHED_EVENTS)
                .map(_ -> said);
    }

    // "push" in "push it", but not in "pushed", "push-back" or "don't push": any two words of the message would otherwise authorize.
    private static boolean names(String words, String action)
    {
        String said = UserWords.normal(words);
        String named = UserWords.normal(action);
        return Pattern.compile("(?<![\\w-])" + Pattern.quote(named) + "(?![\\w-])").matcher(said).find() && !UserWords.saysNot(said, named);
    }

    // The first message of a task started from an issue, a ticket or a pull request carries their titles and bodies beside any words of the user's.
    private boolean wrapsItem(TaskId taskId, TurnId turnId)
    {
        String id = taskId.toString();
        boolean first = turns.list(taskId).stream().findFirst().filter(earliest -> earliest.id().equals(turnId)).isPresent();
        return first && (issueTasks.findByTaskId(id).isPresent() || jiraTasks.findByTaskId(id).isPresent() || pullRequestTasks.findByTaskId(id).isPresent());
    }
}
