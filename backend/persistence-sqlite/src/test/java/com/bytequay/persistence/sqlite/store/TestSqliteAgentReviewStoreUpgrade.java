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

import com.bytequay.core.review.ReviewCommit;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.review.ReviewStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestSqliteAgentReviewStoreUpgrade
        extends AbstractTestSqliteStore
{
    private static final String DATABASE_URL = newDatabaseUrl("bytequay-agent-review-upgrade-");
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
    private static final String HEAD_SHA = "3".repeat(40);

    @Autowired
    private SqliteAgentReviewStore store;

    @Autowired
    private EntityManager entities;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry)
    {
        createRunTableBeforeChosenCommits();
        registerSqliteProperties(registry, DATABASE_URL, "update");
    }

    @Test
    void persistsChosenCommitsScopeAfterSchemaUpdate()
    {
        ReviewRun run = new ReviewRun("run-commits", "review-1", 1, ReviewRun.Scope.COMMITS, "main", "0".repeat(40), "1".repeat(40), HEAD_SHA, Optional.empty(),
                Optional.empty(), ImmutableList.of(new ReviewCommit(HEAD_SHA, "Add the chosen commit", "octocat")), ImmutableList.of(), ImmutableList.of("turn-1"),
                ReviewRun.State.RUNNING, Optional.empty(), Optional.empty(), Optional.empty(), ImmutableList.of(ReviewStep.initial()), false, ImmutableMap.of(),
                ImmutableMap.of(), Optional.empty(), 0, NOW, NOW, Optional.empty());

        store.insertRun(run);
        entities.flush();
        entities.clear();

        assertThat(store.findRun(run.id())).contains(run);
    }

    // The agent_review_run table as Hibernate created it before reviews could cover chosen commits.
    private static void createRunTableBeforeChosenCommits()
    {
        try (var connection = DriverManager.getConnection(DATABASE_URL);
                var statement = connection.createStatement()) {
            statement.execute("""
                    create table agent_review_run (id varchar(255) not null, base_ref varchar(255) not null, base_sha varchar(255) not null,
                        commits_json TEXT not null, completion_repair_used boolean not null, coverage_json TEXT, created_at varchar(255) not null,
                        diff_json TEXT not null, ended_at varchar(255), evidence_json TEXT not null, head_sha varchar(255) not null, investigation_json TEXT,
                        merge_base_sha varchar(255) not null, operations_json TEXT not null, ordinal integer not null, outcome TEXT, prior_head_sha varchar(255),
                        review_id varchar(255) not null, scope varchar(255) not null check (scope in ('FULL','INCREMENTAL')),
                        state varchar(255) not null check (state in ('RUNNING','ACCEPTED','INCOMPLETE','STOPPED','FAILED')), steps_json TEXT,
                        summary_json TEXT, turn_ids_json TEXT not null, updated_at varchar(255) not null, version bigint not null,
                        worktree_tree_sha varchar(255), primary key (id))
                    """);
        }
        catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
