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

import com.bytequay.core.domain.AssistantAssessment;
import com.bytequay.core.domain.AssistantFollow;
import com.bytequay.core.store.AssistantFollowStore;
import com.bytequay.persistence.sqlite.entity.AssistantAssessmentEntity;
import com.bytequay.persistence.sqlite.entity.AssistantFollowEntity;
import com.bytequay.persistence.sqlite.repository.AssistantAssessmentJpaRepository;
import com.bytequay.persistence.sqlite.repository.AssistantFollowJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteAssistantFollowStore
        implements AssistantFollowStore
{
    private final AssistantFollowJpaRepository follows;
    private final AssistantAssessmentJpaRepository assessments;

    public SqliteAssistantFollowStore(AssistantFollowJpaRepository follows, AssistantAssessmentJpaRepository assessments)
    {
        this.follows = requireNonNull(follows, "follows is null");
        this.assessments = requireNonNull(assessments, "assessments is null");
    }

    @Override
    @Transactional(readOnly = true)
    public List<AssistantFollow> follows()
    {
        return follows.findAll().stream().map(AssistantFollowEntity::toDomain).collect(toImmutableList());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AssistantFollow> follow(String conversationId, String taskId)
    {
        return follows.findById(conversationId + ":" + taskId).map(AssistantFollowEntity::toDomain);
    }

    @Override
    @Transactional
    public void save(AssistantFollow follow)
    {
        AssistantFollowEntity entity = follows.findById(follow.id()).orElseGet(() -> new AssistantFollowEntity(follow));
        AssistantFollow previous = entity.toDomain();
        // A cursor read can finish after a newer user receipt refreshed the follow's request context.
        entity.update(new AssistantFollow(follow.conversationId(), follow.taskId(), previous.since(), Math.max(previous.afterSequence(), follow.afterSequence()),
                previous.context()));
        follows.save(entity);
    }

    @Override
    @Transactional
    public void remove(String conversationId, String taskId)
    {
        follows.deleteById(conversationId + ":" + taskId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AssistantAssessment> assessment(String id)
    {
        return assessments.findById(id).map(AssistantAssessmentEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AssistantAssessment> queued()
    {
        return assessments.findByStateOrderByIdAsc(AssistantAssessment.State.QUEUED.name()).stream().map(AssistantAssessmentEntity::toDomain).collect(toImmutableList());
    }

    @Override
    @Transactional
    public void save(AssistantAssessment assessment)
    {
        AssistantAssessmentEntity entity = assessments.findById(assessment.id()).orElseGet(() -> new AssistantAssessmentEntity(assessment));
        entity.update(assessment);
        assessments.save(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean followsTask(String taskId)
    {
        return follows.existsByTaskId(taskId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AssistantAssessment> result(String taskId, String noticeId, String sourceLine)
    {
        return assessments.findByTaskIdAndNoticeId(taskId, noticeId).stream().map(AssistantAssessmentEntity::toDomain)
                .filter(job -> !job.requested() && job.item().text().equals(sourceLine) && job.state() != AssistantAssessment.State.SUPERSEDED)
                .max(comparing((AssistantAssessment job) -> job.state() == AssistantAssessment.State.PUBLISHED).thenComparing(job -> job.item().at()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AssistantAssessment> ruleReceipts(String conversationId, String taskId)
    {
        return assessments.findByConversationIdAndTaskIdAndRuleReceiptTrue(conversationId, taskId).stream().map(AssistantAssessmentEntity::toDomain)
                .collect(toImmutableList());
    }
}
