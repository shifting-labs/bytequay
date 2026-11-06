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

import com.bytequay.core.discussion.DiscussionSession;
import com.bytequay.core.domain.AgendaTarget;
import com.bytequay.core.domain.AssistantAction;
import com.bytequay.core.domain.AssistantAssessment;
import com.bytequay.core.domain.AssistantConversation;
import com.bytequay.core.domain.AssistantDraft;
import com.bytequay.core.domain.AssistantFollow;
import com.bytequay.core.domain.AssistantItem;
import com.bytequay.core.domain.AssistantList;
import com.bytequay.core.domain.AssistantReceipt;
import com.bytequay.core.domain.AssistantReport;
import com.bytequay.core.domain.AssistantRuleAction;
import com.bytequay.core.domain.RuleAuthorization;
import com.bytequay.core.domain.RuleRun;
import com.bytequay.core.domain.ServedRef;
import com.bytequay.persistence.sqlite.converter.JsonColumns;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSqliteAssistantStore
        extends AbstractTestTransactionalSqliteStore
{
    private static final String CONVERSATION = "0c6a3f0e-5b1d-4c39-9d43-7f1f3a2b9e10";
    private static final String OTHER = "5f0c1d9a-2e7b-4a61-8c3f-9b4d2e6a1c07";
    private static final String THIRD = "9a8b7c6d-5e4f-4a3b-9c2d-1e0f9a8b7c6d";
    private static final Instant AT = Instant.parse("2026-10-17T01:02:00Z");

    @Autowired
    private SqliteAssistantStore store;

    @Autowired
    private SqliteAssistantFollowStore follows;

    @Autowired
    private EntityManager entities;

    @ParameterizedTest
    @ValueSource(strings = {"READY", "DECISION", "FAILED"})
    void retainedReportsRemainReadableAfterTheCardContractChanges(String oldState)
            throws Exception
    {
        AssistantItem before = AssistantItem.user(CONVERSATION, AT, "Keep this history", ImmutableList.of(), Optional.empty(), "earlier-message");
        AssistantItem report = AssistantItem.report(CONVERSATION, AT, "The original recorded result",
                new AssistantReport(AssistantReport.State.RESULT_READY, "finished:turn-1", AgendaTarget.of(AgendaTarget.Kind.TASK, "workspace", "task-1")));
        store.add(before);
        store.add(report);
        entities.flush();
        ObjectNode value = (ObjectNode) JsonColumns.mapper().valueToTree(report);
        ObjectNode card = (ObjectNode) value.get("report");
        card.put("state", oldState);
        card.remove(ImmutableList.of("sourceTurnId", "assessment", "evidence", "ruleActions", "outcome"));
        entities.createNativeQuery("update assistant_conversation_item set value = ? where id = ?")
                .setParameter(1, value.toString()).setParameter(2, report.id()).executeUpdate();
        entities.clear();

        assertThat(store.items(CONVERSATION)).hasSize(2).first().isEqualTo(before);
        AssistantItem restored = store.items(CONVERSATION).getLast();
        assertThat(restored.text()).isEqualTo(report.text());
        AssistantReport restoredCard = restored.report().orElseThrow();
        assertThat(restoredCard.state()).isEqualTo(oldState.equals("READY") ? AssistantReport.State.RESULT_READY : AssistantReport.State.ATTENTION);
        assertThat(restoredCard.evidence()).isEmpty();
        assertThat(restoredCard.ruleActions()).isEmpty();
        assertThat(restoredCard.assessment()).isEmpty();
    }

    @Test
    void keepsAConversationWithItsSessionAndServedRefs()
    {
        store.save(AssistantConversation.start(CONVERSATION, "what's waiting on me?", AT));
        AssistantConversation replied = new AssistantConversation(CONVERSATION, "what's waiting on me?", Optional.of("claude"), Optional.of("Sonnet"),
                Optional.of(new DiscussionSession(1, "session-1")), ImmutableMap.of(
                        "pr:owner/repository#31402", new ServedRef("mine", Optional.of(AgendaTarget.pull(1, "owner/repository", 31402, Optional.of("ws1")))),
                        "ws:ws1", new ServedRef("start_options", Optional.empty())), AT, AT.plusSeconds(20), 3);
        store.save(replied);
        entities.flush();
        entities.clear();

        assertThat(store.find(CONVERSATION)).contains(replied);
        assertThat(store.find(OTHER)).isEmpty();
    }

    @Test
    void listsTheMostRecentlyUpdatedConversationsFirst()
    {
        store.save(AssistantConversation.start(CONVERSATION, "first", AT));
        store.save(AssistantConversation.start(OTHER, "second", AT.plusSeconds(60)));
        store.save(AssistantConversation.start(THIRD, "third", AT.plusSeconds(30)));
        entities.flush();
        entities.clear();

        assertThat(store.conversations(2)).extracting(AssistantConversation::title).containsExactly("second", "third");
    }

    @Test
    void reloadKeepsQueuedAndUncertainDeliveriesAndCompletionIsIdempotent()
    {
        store.save(AssistantConversation.start(CONVERSATION, "first", AT));
        store.save(AssistantConversation.start(OTHER, "other", AT));
        AssistantItem first = AssistantItem.user(CONVERSATION, AT, "start the task", ImmutableList.of("work:w1"), Optional.of("ws:workspace"), "first-key");
        AssistantItem waiting = AssistantItem.user(CONVERSATION, AT, "then read its status", ImmutableList.of("task:t1"), Optional.empty(), "waiting-key");
        AssistantItem other = AssistantItem.user(OTHER, AT, "another question", ImmutableList.of(), Optional.empty(), "other-key");
        store.enqueue(first);
        store.enqueue(first);
        store.enqueue(waiting);
        store.enqueue(other);
        assertThat(store.begin(first.id())).isTrue();
        assertThat(store.begin(first.id())).isFalse();
        entities.flush();
        entities.clear();

        assertThat(store.interrupted()).containsExactly(first);
        assertThat(store.queued()).containsExactly(waiting, other);
        store.deleteBefore(AT.plus(Duration.ofDays(40)), LocalDate.ofInstant(AT, ZoneId.of("UTC")));
        assertThat(store.find(CONVERSATION)).isPresent();
        assertThat(store.find(OTHER)).isPresent();
        AssistantItem outcome = AssistantItem.failure(CONVERSATION, AT, "Check the existing action before asking again", false);
        store.complete(first.id(), outcome);
        store.complete(first.id(), AssistantItem.failure(CONVERSATION, AT, "Duplicate outcome", false));
        store.cancelQueued(CONVERSATION);
        entities.flush();
        entities.clear();

        assertThat(store.items(CONVERSATION)).containsExactly(first, waiting, outcome);
        assertThat(store.interrupted()).isEmpty();
        assertThat(store.queued()).containsExactly(other);
        assertThat(store.begin(waiting.id())).isFalse();
        store.deleteBefore(AT.plus(Duration.ofDays(40)), LocalDate.ofInstant(AT, ZoneId.of("UTC")));
        assertThat(store.find(CONVERSATION)).isEmpty();
        assertThat(store.find(OTHER)).isPresent();
    }

    @Test
    void listsItemsInTheOrderTheyWereAddedEvenWithinOneMillisecond()
    {
        AssistantItem message = AssistantItem.user(CONVERSATION, AT, "what's waiting on me?", ImmutableList.of("approval:a1"), Optional.of("pr:owner/repository#31402"), "key-1");
        AssistantItem failure = AssistantItem.failure(CONVERSATION, AT, "Claude is signed out", true);
        AssistantItem reply = AssistantItem.reply(CONVERSATION, AT, "One card waits on task:t1.", ImmutableMap.of("task:t1", AgendaTarget.of(AgendaTarget.Kind.TASK, "ws1", "t1")));
        AssistantItem receipt = AssistantItem.receipt(CONVERSATION, AT, "Started “Expose snapshot id”", new AssistantReceipt(AssistantReceipt.Operation.START_TASK,
                AssistantReceipt.Outcome.DONE, Optional.of("owner/repository · Claude Opus 5.5"), Optional.of(AgendaTarget.of(AgendaTarget.Kind.TASK, "ws1", "t2")),
                Optional.empty(), Optional.of("turn-1"), Optional.empty(), Optional.of("start the snapshot id one")));
        AssistantItem opened = AssistantItem.receipt(CONVERSATION, AT, "Opened Settings · Models", new AssistantReceipt(AssistantReceipt.Operation.OPEN,
                AssistantReceipt.Outcome.DONE, Optional.empty(), Optional.empty(), Optional.of("settings/models"), Optional.empty(), Optional.empty(),
                Optional.of("take me to model settings")));
        for (AssistantItem item : ImmutableList.of(message, failure, reply, receipt, opened)) {
            store.add(item);
        }
        store.add(AssistantItem.user(OTHER, AT, "another topic", ImmutableList.of(), Optional.empty(), "key-0"));
        entities.flush();
        entities.clear();

        assertThat(store.items(CONVERSATION)).containsExactly(message, failure, reply, receipt, opened);
        assertThat(store.queued()).isEmpty();
        assertThat(store.interrupted()).isEmpty();
    }

    @Test
    void keepsAListsStyleTitleAndRefsButNoRowText()
    {
        AssistantItem list = AssistantItem.list(CONVERSATION, AT, "Waiting on you", new AssistantList(AssistantList.Style.TIMELINE,
                ImmutableList.of("pr:owner/repository#31402", "task:t1", "epic:LOCAL:w1")));
        store.add(AssistantItem.user(CONVERSATION, AT, "what is waiting on me?", ImmutableList.of(), Optional.empty(), "key-1"));
        store.add(list);
        entities.flush();
        entities.clear();

        assertThat(store.items(CONVERSATION).getLast()).isEqualTo(list);
        AssistantItem draft = AssistantItem.draft(CONVERSATION, AT, "Iceberg snapshot metadata", new AssistantDraft("ws1", "owner/repository", "Track the snapshot work.", "w9"));
        store.add(draft);
        entities.flush();
        entities.clear();
        assertThat(store.items(CONVERSATION).getLast()).isEqualTo(draft);
        Object stored = entities.createNativeQuery("select value from assistant_conversation_item where id = ?").setParameter(1, list.id()).getSingleResult();
        // The refs alone: each row's text is read from the stores when the conversation is drawn.
        assertThat(stored.toString()).contains("\"text\":\"Waiting on you\"")
                .contains("\"list\":{\"style\":\"TIMELINE\",\"refs\":[\"pr:owner/repository#31402\",\"task:t1\",\"epic:LOCAL:w1\"]}");
    }

    @Test
    void keepsEachMessagesActionsInOrderAndDropsThemWithTheirDay()
    {
        AssistantItem message = AssistantItem.user(CONVERSATION, AT, "allow it, then start the snapshot one", ImmutableList.of("approval:a1"), Optional.empty(), "key-1");
        AssistantAction allowed = AssistantAction.of(message, AssistantReceipt.Operation.ANSWER_APPROVAL, "approval:a1", "allow it", AssistantReceipt.Outcome.DONE,
                Optional.empty(), Optional.of("ALLOW"), AT.plusSeconds(2));
        AssistantAction refused = AssistantAction.of(message, AssistantReceipt.Operation.START_TASK, "work:w22", "start the snapshot one", AssistantReceipt.Outcome.REFUSED,
                Optional.of("Starting tasks is off"), Optional.empty(), AT.plusSeconds(1));
        AssistantItem yesterday = AssistantItem.user(OTHER, AT.minus(Duration.ofDays(1)), "allow it", ImmutableList.of(), Optional.empty(), "key-0");
        store.addAction(allowed);
        store.addAction(refused);
        store.addAction(AssistantAction.of(yesterday, AssistantReceipt.Operation.ANSWER_APPROVAL, "approval:a0", "allow it", AssistantReceipt.Outcome.DONE,
                Optional.empty(), Optional.of("ALLOW"), AT.minus(Duration.ofDays(1))));
        entities.flush();
        entities.clear();

        assertThat(store.actions(message.id())).containsExactly(refused, allowed);
        // An action's day is the user's wall-clock day.
        store.deleteBefore(AT.minus(Duration.ofDays(30)), LocalDate.ofInstant(AT, ZoneId.systemDefault()));
        entities.flush();
        entities.clear();
        assertThat(store.actions(yesterday.id())).isEmpty();
        assertThat(store.actions(message.id())).hasSize(2);
    }

    @Test
    void dropsConversationsLastUpdatedBeforeTheCutoffWithTheirItems()
    {
        ImmutableMap<String, Instant> updated = ImmutableMap.of(CONVERSATION, AT.minus(Duration.ofDays(2)), OTHER, AT.minus(Duration.ofDays(1)), THIRD, AT);
        updated.forEach((id, at) -> {
            store.save(AssistantConversation.start(id, "hello", at));
            store.add(AssistantItem.user(id, at, "hello", ImmutableList.of(), Optional.empty(), "key-" + id));
        });
        entities.flush();
        entities.clear();

        store.deleteBefore(AT.minus(Duration.ofHours(36)), LocalDate.parse("2026-01-01"));
        entities.flush();
        entities.clear();

        assertThat(store.find(CONVERSATION)).isEmpty();
        assertThat(store.items(CONVERSATION)).isEmpty();
        assertThat(store.find(OTHER)).isPresent();
        assertThat(store.items(THIRD)).hasSize(1);
    }

    @Test
    void aTaskReceiptAtomicallyKeepsItsFollowAndProtectsItsConversationFromRetention()
    {
        store.save(AssistantConversation.start(CONVERSATION, "Follow this work", AT));
        AssistantItem receipt = AssistantItem.receipt(CONVERSATION, AT, "Started work",
                AssistantReceipt.done(AssistantReceipt.Operation.START_TASK, Optional.of(AgendaTarget.of(AgendaTarget.Kind.TASK, "workspace", "task-1"))));
        store.add(receipt);
        store.add(receipt);
        entities.flush();
        entities.clear();

        assertThat(store.items(CONVERSATION)).containsExactly(receipt);
        assertThat(follows.follow(CONVERSATION, "task-1")).contains(new AssistantFollow(CONVERSATION, "task-1", AT, 0, "Started work"));
        store.deleteBefore(AT.plus(Duration.ofDays(40)), LocalDate.ofInstant(AT, ZoneId.of("UTC")).plusDays(40));
        assertThat(store.find(CONVERSATION)).isPresent();
        assertThat(store.items(CONVERSATION)).containsExactly(receipt);
    }

    @Test
    void keepsQueuedAssessmentAndEventCursorWhenThePersistenceContextReloads()
    {
        AssistantFollow follow = new AssistantFollow(CONVERSATION, "task-1", AT, 321, "Explain the retained result");
        follows.save(follow);
        AssistantItem item = AssistantItem.report(CONVERSATION, AT, "Result ready",
                new AssistantReport(AssistantReport.State.RESULT_READY, "finished:turn-1", AgendaTarget.of(AgendaTarget.Kind.TASK, "workspace", "task-1")));
        AssistantAssessment job = new AssistantAssessment(item.id(), CONVERSATION, "task-1", "source-version", item, "Explain the result", true, false,
                AssistantAssessment.State.QUEUED);
        follows.save(job);
        entities.flush();
        entities.clear();

        assertThat(follows.follows()).containsExactly(follow);
        assertThat(follows.followsTask("task-1")).isTrue();
        assertThat(follows.followsTask("other-task")).isFalse();
        assertThat(follows.result("task-1", "finished:turn-1", "Result ready")).contains(job);
        assertThat(follows.result("task-1", "finished:turn-1", "Changed source")).isEmpty();
        assertThat(follows.queued()).containsExactly(job);
        follows.save(job.ended(AssistantAssessment.State.PUBLISHED));
        entities.flush();
        entities.clear();
        assertThat(follows.queued()).isEmpty();
        assertThat(follows.assessment(job.id())).contains(job.ended(AssistantAssessment.State.PUBLISHED));
    }

    @Test
    void readsRetainedChatFollowsAndJobsWithoutLosingTheirPublicationIdentity()
    {
        AssistantFollow follow = new AssistantFollow(CONVERSATION, "task-1", AT, 321, "Follow this work");
        AssistantItem item = AssistantItem.report(CONVERSATION, AT, "Result ready",
                new AssistantReport(AssistantReport.State.RESULT_READY, "finished:turn-1", AgendaTarget.of(AgendaTarget.Kind.TASK, "workspace", "task-1")));
        AssistantAssessment job = new AssistantAssessment(item.id(), CONVERSATION, "task-1", "source-version", item, "Follow this work", true, false,
                AssistantAssessment.State.QUEUED);
        follows.save(follow);
        follows.save(job);
        entities.flush();
        ObjectNode oldFollow = JsonColumns.mapper().valueToTree(follow);
        oldFollow.remove("ruleRun");
        ObjectNode oldJob = JsonColumns.mapper().valueToTree(job);
        oldJob.remove(ImmutableList.of("at", "sourceLine", "report"));
        oldJob.set("item", JsonColumns.mapper().valueToTree(item));
        entities.createNativeQuery("update assistant_follow set value = ? where id = ?").setParameter(1, oldFollow.toString()).setParameter(2, follow.id()).executeUpdate();
        entities.createNativeQuery("update assistant_assessment set value = ? where id = ?").setParameter(1, oldJob.toString()).setParameter(2, job.id()).executeUpdate();
        entities.clear();

        assertThat(follows.follow(CONVERSATION, "task-1")).contains(follow);
        assertThat(follows.queued()).containsExactly(job);
        assertThat(follows.assessment(job.id()).orElseThrow().item()).isEqualTo(item);
        AssistantAssessment assessed = job.assessed(job.report().assessed("The result needs your review."), AssistantAssessment.State.PUBLISHED);
        follows.save(assessed);
        entities.flush();
        entities.clear();

        assertThat(follows.result("task-1", "finished:turn-1", "Result ready")).contains(assessed);
        assertThat(entities.createNativeQuery("select value from assistant_assessment where id = ?").setParameter(1, job.id()).getSingleResult().toString())
                .contains("\"sourceLine\":\"Result ready\"").doesNotContain("\"item\":");
    }

    @Test
    void ruleResultsSurviveReloadAndChatRetentionWithoutCreatingAConversation()
    {
        AssistantFollow follow = AssistantFollow.ruleReview("task-1", AT, "Tell me about meaningful review findings", reviewRun("head-1", AT)).readThrough(321);
        follows.save(follow);
        AssistantReport report = new AssistantReport(AssistantReport.State.RESULT_READY, "review-result:run-1",
                AgendaTarget.of(AgendaTarget.Kind.REVIEW, "workspace", "review-1"));
        AssistantAssessment job = new AssistantAssessment("result-1", Optional.empty(), "task-1", "run-1", AT, "Review ready", report, follow.context(), true, false,
                AssistantAssessment.State.QUEUED);
        follows.save(job);
        store.save(AssistantConversation.start(CONVERSATION, "Old chat", AT));
        entities.flush();
        entities.clear();

        assertThat(follows.follow("task-1")).contains(follow);
        assertThat(follows.followsTask("task-1")).isTrue();
        assertThat(follows.queued()).containsExactly(job);
        assertThatThrownBy(job::item).isInstanceOf(NoSuchElementException.class);

        AssistantAssessment assessed = job.assessed(report.assessed("One new risk needs your review."), AssistantAssessment.State.PUBLISHED);
        follows.save(assessed);
        follows.remove(follow);
        store.deleteBefore(AT.plus(Duration.ofDays(40)), LocalDate.ofInstant(AT, ZoneId.of("UTC")).plusDays(40));
        entities.flush();
        entities.clear();

        assertThat(store.conversations(10)).isEmpty();
        assertThat(follows.follow("task-1")).isEmpty();
        assertThat(follows.queued()).isEmpty();
        assertThat(follows.result("task-1", "review-result:run-1", "Review ready")).contains(assessed);
        assertThat(follows.assessment(job.id())).contains(assessed);
    }

    @Test
    void aNewRuleInvocationUpdatesItsScopeWithoutResettingTheCursor()
    {
        AssistantFollow first = AssistantFollow.ruleReview("task-1", AT, "First request", reviewRun("head-1", AT)).readThrough(100);
        AssistantFollow chat = new AssistantFollow(CONVERSATION, "task-1", AT, 5, "User's own request");
        follows.save(first);
        follows.save(chat);
        AssistantFollow newer = AssistantFollow.ruleReview("task-1", AT.plusSeconds(60), "New request", reviewRun("head-2", AT.plusSeconds(60)));
        follows.save(newer);
        follows.save(first.readThrough(120));
        entities.flush();
        entities.clear();

        assertThat(follows.follow("task-1")).contains(new AssistantFollow(Optional.empty(), "task-1", AT, 120, "New request", newer.ruleRun()));
        assertThat(follows.follow(CONVERSATION, "task-1")).contains(chat);
        follows.remove(chat);
        assertThat(follows.followsTask("task-1")).isTrue();
        assertThat(follows.follow(CONVERSATION, "task-1")).isEmpty();
    }

    @Test
    void advancingAChatCursorPreservesTheLatestUserRequest()
    {
        AssistantFollow old = new AssistantFollow(CONVERSATION, "task-1", AT, 80, "First request");
        follows.save(old);
        store.add(AssistantItem.receipt(CONVERSATION, AT.plusSeconds(1), "Sent your reply",
                AssistantReceipt.done(AssistantReceipt.Operation.REPLY_TO_TASK, Optional.of(AgendaTarget.of(AgendaTarget.Kind.TASK, "workspace", "task-1")))
                        .withWords("Check the revised implementation")));
        follows.save(old.readThrough(100));
        entities.flush();
        entities.clear();

        assertThat(follows.follow(CONVERSATION, "task-1")).contains(new AssistantFollow(CONVERSATION, "task-1", AT, 100, "Check the revised implementation"));
    }

    @Test
    void aReportsOfferSurvivesReload()
    {
        AssistantReport report = new AssistantReport(AssistantReport.State.RESULT_READY, "turn:1", AgendaTarget.of(AgendaTarget.Kind.TASK, "workspace", "task-1"))
                .offering(AssistantReport.Offer.PUSH);
        store.save(AssistantConversation.start(CONVERSATION, "Push the fix", AT));
        follows.save(new AssistantAssessment("result-2", Optional.of(CONVERSATION), "task-1", "turn-1", AT, "Result ready", report, "", true, false,
                AssistantAssessment.State.PUBLISHED));
        entities.flush();
        entities.clear();

        assertThat(follows.assessment("result-2").orElseThrow().report().offer()).contains(AssistantReport.Offer.PUSH);
    }

    @Test
    void inboxPermissionReceiptsAreQueriedWithoutAChatIdentity()
    {
        AssistantReport report = new AssistantReport(AssistantReport.State.PROGRESS, "approval:1", AgendaTarget.of(AgendaTarget.Kind.TASK, "workspace", "task-1"),
                Optional.empty(), Optional.empty(), ImmutableList.of(), ImmutableList.of(new AssistantRuleAction("permission-rule", "Read GitHub", 2, "ALLOW", "Read PR")),
                Optional.empty());
        AssistantAssessment inbox = new AssistantAssessment("receipt-1", Optional.empty(), "task-1", "event:5", AT, "Approved by rule", report, "", false, false,
                AssistantAssessment.State.QUIET);
        AssistantAssessment chat = new AssistantAssessment("receipt-2", Optional.of(CONVERSATION), "task-1", "event:5", AT, "Approved by rule", report, "", false, false,
                AssistantAssessment.State.QUIET);
        follows.save(inbox);
        follows.save(chat);
        entities.flush();
        entities.clear();

        assertThat(follows.ruleReceipts(Optional.empty(), "task-1")).containsExactly(inbox);
        assertThat(follows.ruleReceipts(Optional.of(CONVERSATION), "task-1")).containsExactly(chat);
    }

    private static RuleRun reviewRun(String head, Instant at)
    {
        return RuleRun.automatic(new RuleAuthorization("review-rule", "Review my lines", 1), "pr:1:updated:" + head, "owner/repository#42", "Update query planning", at)
                .started("review:review-1").finished("STARTED");
    }
}
