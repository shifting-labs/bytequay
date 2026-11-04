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
package com.bytequay.persistence.sqlite.store;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.core.domain.PullRequestReplacement;
import com.bytequay.core.domain.PullRequestTask;
import com.bytequay.core.domain.RepoRef;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static com.bytequay.agent.core.AgentTaskFixtures.created;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSqlitePullRequestReplacementStore
        extends AbstractTestTransactionalSqliteStore
{
    @Autowired
    private SqlitePullRequestReplacementStore replacements;

    @Autowired
    private SqlitePullRequestTaskStore assignments;

    @Autowired
    private SqliteAgentTaskStore tasks;

    @Autowired
    private EntityManager entities;

    @Test
    void retainsTheAcceptedTargetAndAnUncertainCreateAcrossReloads()
    {
        PullRequestReplacement accepted = replacement("task");
        replacements.save(accepted);
        entities.clear();
        assertThat(replacements.find("task")).contains(accepted);

        PullRequestReplacement started = accepted.started();
        replacements.save(started);
        entities.clear();
        assertThat(replacements.find("task")).contains(started);
        assertThat(replacements.find("task").orElseThrow().replacementNumber()).isEmpty();

        PullRequestReplacement created = started.created(12);
        replacements.save(created);
        entities.clear();
        assertThat(replacements.find("task")).contains(created);
        assertThat(replacements.find("task").orElseThrow().completed()).isFalse();

        replacements.save(created.complete());
        entities.clear();
        assertThat(replacements.find("task")).contains(created.complete());
        assertThat(replacements.find("another-task")).isEmpty();
    }

    @Test
    void rebindsOnlyTheExpectedAssignmentAndPreservesItsContext()
    {
        PullRequestTask original = new PullRequestTask("task", "workspace", "owner/repo", 11, "Original request context");
        PullRequestTask replacement = new PullRequestTask("task", "workspace", "owner/repo", 12, original.initialContext());
        assertThat(assignments.reassign(original, Optional.empty())).isTrue();
        entities.clear();
        assertThat(assignments.reassign(replacement, Optional.empty())).isFalse();
        assertThat(assignments.reassign(replacement, Optional.of(original))).isTrue();
        entities.clear();
        assertThat(assignments.findByTaskId("task")).contains(replacement);
        assertThat(assignments.reassign(original, Optional.of(original))).isFalse();
        assertThat(assignments.findByTaskId("task")).contains(replacement);
    }

    @Test
    void refusesToReassignAnotherTasksExpectedRow()
    {
        PullRequestTask original = new PullRequestTask("task", "workspace", "owner/repo", 11, "Original request context");
        assignments.insert(original);
        PullRequestTask replacement = new PullRequestTask("other-task", "workspace", "owner/repo", 12, original.initialContext());
        assertThatThrownBy(() -> assignments.reassign(replacement, Optional.of(original))).isInstanceOf(IllegalArgumentException.class);
        assertThat(assignments.findByTaskId("task")).contains(original);
        assertThat(assignments.findByTaskId("other-task")).isEmpty();
    }

    @Test
    void deletesTheReceiptWithItsTask()
    {
        AgentTask task = created(TaskId.random(), Optional.of("workspace"), true, "Replace PR", AgentLaneId.CODEX_CLI,
                new RepositoryIdentity("owner", "repo"), Path.of("/tmp/bytequay-replacement-worktree"), AgentPermissionPolicy.AUTO, Instant.EPOCH);
        tasks.insert(task);
        replacements.save(replacement(task.id().toString()).started());
        entities.clear();
        tasks.delete(task.id());
        entities.clear();
        assertThat(replacements.find(task.id().toString())).isEmpty();
    }

    private static PullRequestReplacement replacement(String taskId)
    {
        return new PullRequestReplacement(taskId, "workspace", 1, 2, RepoRef.of("contributor", "repo"), RepoRef.of("owner", "repo"),
                11, "old-branch", "main", "a".repeat(40), "new-branch", "Fix ordering", "Keep the original description", true, false, Optional.empty(), false);
    }
}
