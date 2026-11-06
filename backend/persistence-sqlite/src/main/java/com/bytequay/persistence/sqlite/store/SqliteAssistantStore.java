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

import com.bytequay.core.domain.AgendaTarget;
import com.bytequay.core.domain.AssistantAction;
import com.bytequay.core.domain.AssistantConversation;
import com.bytequay.core.domain.AssistantFollow;
import com.bytequay.core.domain.AssistantItem;
import com.bytequay.core.domain.AssistantReceipt;
import com.bytequay.core.store.AssistantStore;
import com.bytequay.persistence.sqlite.entity.AssistantActionEntity;
import com.bytequay.persistence.sqlite.entity.AssistantConversationEntity;
import com.bytequay.persistence.sqlite.entity.AssistantFollowEntity;
import com.bytequay.persistence.sqlite.entity.AssistantItemEntity;
import com.bytequay.persistence.sqlite.repository.AssistantActionJpaRepository;
import com.bytequay.persistence.sqlite.repository.AssistantAssessmentJpaRepository;
import com.bytequay.persistence.sqlite.repository.AssistantConversationJpaRepository;
import com.bytequay.persistence.sqlite.repository.AssistantFollowJpaRepository;
import com.bytequay.persistence.sqlite.repository.AssistantItemJpaRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static com.bytequay.persistence.sqlite.entity.AssistantItemEntity.Delivery.QUEUED;
import static com.bytequay.persistence.sqlite.entity.AssistantItemEntity.Delivery.RUNNING;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteAssistantStore
        implements AssistantStore
{
    private final AssistantConversationJpaRepository conversations;
    private final AssistantItemJpaRepository items;
    private final AssistantActionJpaRepository actions;
    private final AssistantFollowJpaRepository follows;
    private final AssistantAssessmentJpaRepository assessments;

    public SqliteAssistantStore(AssistantConversationJpaRepository conversations, AssistantItemJpaRepository items, AssistantActionJpaRepository actions,
            AssistantFollowJpaRepository follows, AssistantAssessmentJpaRepository assessments)
    {
        this.conversations = requireNonNull(conversations, "conversations is null");
        this.items = requireNonNull(items, "items is null");
        this.actions = requireNonNull(actions, "actions is null");
        this.follows = requireNonNull(follows, "follows is null");
        this.assessments = requireNonNull(assessments, "assessments is null");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AssistantConversation> find(String conversationId)
    {
        return conversations.findById(conversationId).map(AssistantConversationEntity::toDomain);
    }

    @Override
    @Transactional
    public void save(AssistantConversation conversation)
    {
        AssistantConversationEntity entity = conversations.findById(conversation.id()).orElseGet(() -> new AssistantConversationEntity(conversation));
        entity.update(conversation);
        conversations.save(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AssistantConversation> conversations(int limit)
    {
        return conversations.findAllByOrderByUpdatedAtDesc(PageRequest.of(0, limit)).stream().map(AssistantConversationEntity::toDomain).collect(toImmutableList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<AssistantItem> items(String conversationId)
    {
        return items.findByConversationIdOrderByOrdinalAsc(conversationId).stream().map(AssistantItemEntity::toDomain).collect(toImmutableList());
    }

    @Override
    @Transactional
    public void add(AssistantItem item)
    {
        // Append and the follow relationship commit together. Publication retries keep the original ordinal.
        if (items.existsById(item.id())) {
            return;
        }
        items.save(new AssistantItemEntity(item, items.countByConversationId(item.conversationId())));
        item.receipt().filter(receipt -> receipt.outcome() == AssistantReceipt.Outcome.DONE
                        && (receipt.operation() == AssistantReceipt.Operation.START_TASK || receipt.operation() == AssistantReceipt.Operation.REPLY_TO_TASK))
                .flatMap(AssistantReceipt::target).filter(target -> target.kind() == AgendaTarget.Kind.TASK).flatMap(AgendaTarget::id)
                .ifPresent(task -> {
                    AssistantFollow follow = new AssistantFollow(item.conversationId(), task, item.at(), 0, item.receipt().flatMap(AssistantReceipt::words).orElse(item.text()));
                    AssistantFollowEntity saved = follows.findById(follow.id()).orElseGet(() -> new AssistantFollowEntity(follow));
                    AssistantFollow previous = saved.toDomain();
                    saved.update(new AssistantFollow(previous.conversationId(), previous.taskId(), previous.since(), previous.afterSequence(),
                            follow.context(), previous.ruleRun()));
                    follows.save(saved);
                });
    }

    @Override
    @Transactional
    public void addCommand(AssistantItem message, AssistantItem receipt)
    {
        checkArgument(message.kind() == AssistantItem.Kind.USER && receipt.kind() == AssistantItem.Kind.RECEIPT, "a command needs its user message and receipt");
        checkArgument(message.conversationId().equals(receipt.conversationId()), "receipt belongs to another conversation");
        checkArgument(!message.id().equals(receipt.id()), "message and receipt must be distinct");
        if (!items.existsById(message.id())) {
            add(message);
            add(receipt);
        }
    }

    @Override
    @Transactional
    public void addAction(AssistantAction action)
    {
        actions.save(new AssistantActionEntity(action));
    }

    @Override
    @Transactional
    public void enqueue(AssistantItem message)
    {
        checkArgument(message.kind() == AssistantItem.Kind.USER, "only a user's message can be queued");
        AssistantItemEntity entity = items.findById(message.id()).orElseGet(() -> new AssistantItemEntity(message, items.countByConversationId(message.conversationId())));
        if (entity.delivery().isEmpty()) {
            entity.delivery(Optional.of(QUEUED));
        }
        items.save(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AssistantItem> queued()
    {
        return items.findByDeliveryOrderByConversationIdAscOrdinalAsc(QUEUED).stream().map(AssistantItemEntity::toDomain).collect(toImmutableList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<AssistantItem> interrupted()
    {
        return items.findByDeliveryOrderByConversationIdAscOrdinalAsc(RUNNING).stream().map(AssistantItemEntity::toDomain).collect(toImmutableList());
    }

    @Override
    @Transactional
    public boolean begin(String itemId)
    {
        Optional<AssistantItemEntity> entity = items.findById(itemId).filter(value -> value.delivery().filter(QUEUED::equals).isPresent());
        entity.ifPresent(value -> value.delivery(Optional.of(RUNNING)));
        return entity.isPresent();
    }

    @Override
    @Transactional
    public void complete(String itemId, AssistantItem outcome)
    {
        items.findById(itemId).filter(value -> value.delivery().filter(RUNNING::equals).isPresent()).ifPresent(value -> {
            checkArgument(value.toDomain().conversationId().equals(outcome.conversationId()), "outcome belongs to another conversation");
            add(outcome);
            value.delivery(Optional.empty());
        });
    }

    @Override
    @Transactional
    public void cancelQueued(String conversationId)
    {
        items.findByConversationIdAndDelivery(conversationId, QUEUED).forEach(value -> value.delivery(Optional.empty()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AssistantAction> actions(String itemId)
    {
        return actions.findByItemId(itemId).stream().map(AssistantActionEntity::toDomain).sorted(comparing(AssistantAction::at)).collect(toImmutableList());
    }

    @Override
    @Transactional
    public void deleteBefore(Instant updatedBefore, LocalDate actionsBefore)
    {
        List<String> retained = Stream.concat(follows.findAll().stream().flatMap(entity -> entity.toDomain().conversationId().stream()),
                Stream.concat(queued().stream(), interrupted().stream()).map(AssistantItem::conversationId)).collect(toImmutableList());
        List<AssistantConversationEntity> old = conversations.findByUpdatedAtBefore(updatedBefore).stream()
                .filter(conversation -> !retained.contains(conversation.getId())).collect(toImmutableList());
        items.deleteByConversationIdIn(old.stream().map(AssistantConversationEntity::getId).collect(toImmutableList()));
        assessments.deleteByConversationIdIn(old.stream().map(AssistantConversationEntity::getId).collect(toImmutableList()));
        conversations.deleteAll(old);
        actions.deleteByDayLessThan(actionsBefore.toString());
    }
}
