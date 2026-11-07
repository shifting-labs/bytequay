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
package com.bytequay.app.assistant;

import com.bytequay.app.agenda.AgendaFixtures;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.core.domain.AgendaTarget;
import com.bytequay.core.domain.PrDetailFreshness;
import com.bytequay.core.domain.PrTimelineComment;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PrTimelinePayload;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.hosting.HostingPullRequest;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.WorkspaceStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestAssistantDiscussion
{
    private static final Instant NOW = Instant.parse("2026-11-07T09:00:00Z");
    private static final String REF = "pr:example/harbor#12";

    private final ObjectMapper mapper = AgendaFixtures.mapper();
    private final PullRequestStore pullRequests = mock(PullRequestStore.class);
    private final PrDetailStore details = mock(PrDetailStore.class);
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final PullRequestService service = mock(PullRequestService.class);
    private final ExecutorService executor = mock(ExecutorService.class);
    private final List<Runnable> queued = new ArrayList<>();
    private final AssistantDiscussion discussions = new AssistantDiscussion(pullRequests, details, workspaces, service, executor);
    private final PullRequest pr = mock(PullRequest.class, withSettings().mockMaker(MockMakers.INLINE));

    @BeforeEach
    void setUp()
    {
        when(pr.id()).thenReturn(12L);
        when(pr.repo()).thenReturn("example/harbor");
        when(pr.number()).thenReturn(12);
        when(pr.activityAt()).thenReturn(NOW);
        when(pullRequests.findIdByRepoAndNumber("example/harbor", 12)).thenReturn(Optional.of(12L));
        when(pullRequests.findById(12L)).thenReturn(Optional.of(pr));
        when(pullRequests.findHostingIdentity(12L)).thenReturn(Optional.of(new HostingPullRequest(12, 20, 12)));
        Workspace workspace = mock(Workspace.class, withSettings().mockMaker(MockMakers.INLINE));
        when(workspace.id()).thenReturn("ws1");
        when(workspaces.findByHostingRepositoryId(20)).thenReturn(Optional.of(workspace));
        doAnswer(call -> queued.add(call.getArgument(0))).when(executor).execute(any());
    }

    @Test
    void readsReviewsThreadsAndCommentsNewestFirstFromTheStoredCopy()
            throws Exception
    {
        stored(NOW, ImmutableList.of(
                event(1, "reviewed", "sam-r", NOW.minusSeconds(3 * 3600), "Please add a test for eviction.", PrTimelinePayload.builder().state("CHANGES_REQUESTED")),
                // A review that only carries inline comments says nothing of its own.
                event(2, "reviewed", "sam-r", NOW.minusSeconds(3 * 3600), "", PrTimelinePayload.builder().state("COMMENTED")),
                event(3, "reviewed", "lee-k", NOW.minusSeconds(3600), null, PrTimelinePayload.builder().state("APPROVED")),
                event(4, "review_thread", "sam-r", NOW.minusSeconds(2 * 3600), null, PrTimelinePayload.builder().path("src/cache.ts").line(40).comments(ImmutableList.of(
                        comment(10, "sam-r", NOW.minusSeconds(2 * 3600), "This drops   pinned\nkeys."), comment(11, "jo-p", NOW.minusSeconds(1800), "Fixed in the next commit.")))),
                event(5, "commented", "ci-bot", NOW.minusSeconds(600), "Coverage is 81%.", PrTimelinePayload.builder().actorBot(true)),
                event(6, "labeled", "sam-r", NOW, null, PrTimelinePayload.builder().label("bug"))));

        AssistantReader.Page page = discussions.read(REF, 0);

        JsonNode read = mapper.valueToTree(page.body());
        assertThat(read.path("ref").asText()).isEqualTo(REF);
        assertThat(read.has("note")).isFalse();
        JsonNode said = read.path("entries");
        assertThat(said.findValuesAsText("type")).containsExactly("comment", "thread", "review", "review");
        assertThat(said.path(0).path("by").asText()).isEqualTo("ci-bot");
        assertThat(said.path(0).path("bot").asBoolean()).isTrue();
        assertThat(said.path(1).path("where").asText()).isEqualTo("src/cache.ts:40");
        assertThat(said.path(1).path("state").asText()).isEqualTo("open");
        assertThat(said.path(1).path("comments").findValuesAsText("text")).containsExactly("This drops pinned keys.", "Fixed in the next commit.");
        assertThat(said.path(2).path("verdict").asText()).isEqualTo("approved");
        assertThat(said.path(2).has("text")).isFalse();
        assertThat(said.path(3).path("verdict").asText()).isEqualTo("changes_requested");
        assertThat(said.path(3).has("bot")).isFalse();
        assertThat(page.refs()).containsEntry(REF, Optional.of(AgendaTarget.pull(12, "example/harbor", 12, Optional.of("ws1"))));
        // A copy as fresh as the pull request's activity asks the host for nothing.
        assertThat(queued).isEmpty();
    }

    @Test
    void saysWhenTheStoredCopyIsOlderThanTheActivityAndRefreshesItInTheBackgroundOnce()
            throws Exception
    {
        stored(NOW.minusSeconds(3600), ImmutableList.of(event(1, "commented", "sam-r", NOW.minusSeconds(4000), "Looks close.", PrTimelinePayload.builder())));

        JsonNode read = mapper.valueToTree(discussions.read(REF, 0).body());
        discussions.read(REF, 0);

        assertThat(read.path("note").asText()).isEqualTo("Newer activity is not in this copy yet; ByteQuay is reading it again");
        assertThat(read.path("entries")).hasSize(1);
        // The answer never waits for the host: the refresh is queued, once while it runs.
        verify(service, never()).warmDetail(anyString(), anyInt(), any());
        assertThat(queued).hasSize(1);
        doThrow(new IllegalStateException("rate limited")).when(service).warmDetail("example/harbor", 12, Duration.ofMinutes(1));
        queued.getFirst().run();
        verify(service).warmDetail("example/harbor", 12, Duration.ofMinutes(1));
        discussions.read(REF, 0);
        assertThat(queued).hasSize(2);
    }

    @Test
    void readsNothingYetWhenNoCopyIsStored()
            throws Exception
    {
        when(details.findFreshness(12L)).thenReturn(PrDetailFreshness.unknown());

        JsonNode read = mapper.valueToTree(discussions.read(REF, 0).body());

        assertThat(read.path("note").asText()).isEqualTo("ByteQuay has not read this pull request's discussion yet; it is reading it now");
        assertThat(read.has("entries")).isFalse();
        assertThat(queued).hasSize(1);
    }

    @Test
    void pagesTwentyAtATime()
            throws Exception
    {
        stored(NOW, IntStream.range(0, 25).mapToObj(index -> event(index, "commented", "sam-r", NOW.minusSeconds(index), "Comment " + index, PrTimelinePayload.builder()))
                .collect(ImmutableList.toImmutableList()));

        JsonNode first = mapper.valueToTree(discussions.read(REF, 0).body());
        JsonNode second = mapper.valueToTree(discussions.read(REF, 1).body());

        assertThat(first.path("entries")).hasSize(20);
        assertThat(first.path("nextPage").asInt()).isEqualTo(1);
        assertThat(second.path("entries")).hasSize(5);
        assertThat(second.path("entries").path(4).path("text").asText()).isEqualTo("Comment 24");
        assertThat(second.has("nextPage")).isFalse();
    }

    @Test
    void readsOnlyAPullRequestByItsRef()
    {
        assertThatThrownBy(() -> discussions.read("task:t1", 0)).isInstanceOf(IllegalArgumentException.class).hasMessage("discussion reads a pr: ref");
        assertThat(discussions.read("pr:example/harbor#13", 0).refs()).isEmpty();
        verify(executor, times(0)).execute(any());
    }

    private void stored(Instant discussionAt, List<PrTimelineEvent> timeline)
    {
        StoredPrDetail detail = mock(StoredPrDetail.class, withSettings().mockMaker(MockMakers.INLINE));
        when(detail.timeline()).thenReturn(timeline);
        when(details.find(12L)).thenReturn(Optional.of(detail));
        when(details.findFreshness(12L)).thenReturn(new PrDetailFreshness(Optional.of(discussionAt), Optional.of(discussionAt), Optional.of(discussionAt), Optional.empty()));
    }

    private static PrTimelineEvent event(long id, String kind, String actor, Instant at, String body, PrTimelinePayload.Builder payload)
    {
        return new PrTimelineEvent(id, kind, actor, at, body, payload.build(), Optional.empty());
    }

    private static PrTimelineComment comment(long id, String actor, Instant at, String body)
    {
        return new PrTimelineComment(id, actor, null, false, at, body, "https://example.com/example/harbor/pull/12#c" + id, 1L, null, ImmutableList.of());
    }
}
