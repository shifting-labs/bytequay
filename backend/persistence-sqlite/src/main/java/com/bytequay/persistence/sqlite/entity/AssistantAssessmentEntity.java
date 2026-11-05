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

import com.bytequay.core.domain.AssistantAssessment;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "assistant_assessment", indexes = {
        @Index(name = "ix_assistant_assessment_state", columnList = "state"),
        @Index(name = "ix_assistant_assessment_source", columnList = "task_id,notice_id")})
public class AssistantAssessmentEntity
        extends AssignedIdEntity<String>
{
    @Id
    @Getter(onMethod_ = @Override)
    private String id;

    @Column(nullable = false)
    private String state;

    @Column(name = "conversation_id", nullable = false)
    private String conversationId;

    @Column(name = "task_id", nullable = false)
    private String taskId;

    @Column(name = "notice_id", nullable = false)
    private String noticeId;

    @Column(name = "rule_receipt", nullable = false)
    private boolean ruleReceipt;

    @Column(nullable = false, columnDefinition = "TEXT")
    @JdbcTypeCode(SqlTypes.JSON)
    private AssistantAssessment value;

    protected AssistantAssessmentEntity() {}

    public AssistantAssessmentEntity(AssistantAssessment record)
    {
        id = record.id();
        update(record);
    }

    public void update(AssistantAssessment record)
    {
        state = record.state().name();
        conversationId = record.conversationId();
        taskId = record.taskId();
        noticeId = record.item().report().orElseThrow().noticeId();
        ruleReceipt = record.ruleReceipt();
        value = record;
    }

    public AssistantAssessment toDomain()
    {
        return value;
    }
}
