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
package com.bytequay.persistence.sqlite.entity;

import com.bytequay.core.domain.RuleRun;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "rule_run", indexes = {
        @Index(name = "idx_rule_run_rule_time", columnList = "rule_id,occurred_at"),
        @Index(name = "idx_rule_run_pending_time", columnList = "needs_reconciliation,occurred_at")})
public class RuleRunEntity
{
    // The rule and the event key, so a rule's second run for one event replaces the first.
    @Id
    private String id;

    @Column(name = "rule_id", nullable = false)
    private String ruleId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "needs_reconciliation")
    private Boolean needsReconciliation;

    @Column(name = "run", nullable = false, columnDefinition = "TEXT")
    @JdbcTypeCode(SqlTypes.JSON)
    private RuleRun run;

    public RuleRunEntity() {}

    public static RuleRunEntity of(RuleRun run)
    {
        RuleRunEntity entity = new RuleRunEntity();
        entity.id = run.ruleId() + ":" + run.key();
        entity.ruleId = run.ruleId();
        entity.occurredAt = run.occurredAt();
        entity.needsReconciliation = run.needsReconciliation();
        entity.run = run;
        return entity;
    }

    public RuleRun toDomain()
    {
        return run;
    }
}
