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
package com.bytequay.app.service.epic;

import com.bytequay.agent.spi.AgentCapabilities;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.app.service.ai.TextOnlyHelper;
import com.bytequay.app.service.epic.EpicDrafts.Answer;
import com.bytequay.app.service.epic.EpicDrafts.Draft;
import com.bytequay.app.service.epic.EpicDrafts.Message;
import com.bytequay.app.service.epic.EpicDrafts.Reply;
import com.bytequay.app.service.epic.EpicDrafts.Role;
import com.bytequay.app.service.settings.AgentModelSetting;
import com.bytequay.app.service.settings.AgentModelType;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.RepositoryIssuePage;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.hosting.HostingServer;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestEpicDrafts
{
    private static final AgentLaneId LANE = new AgentLaneId("codex");
    private static final Message ASK = new Message(Role.USER, "Cache Iceberg catalog metadata", Optional.empty());
    private static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");
    private static final RepoRef REPOSITORY = RepoRef.of("owner", "repository");
    private static final Workspace WORKSPACE = new Workspace("workspace", "repository", 1, REPOSITORY, Optional.empty(), Optional.empty(), 1, REPOSITORY,
            Workspace.Topology.DIRECT, "main", "https://github.com/owner/repository.git", Optional.empty(), Optional.empty(), "/managed/repository", Optional.empty(),
            Optional.empty(), 0, NOW, NOW, HostingServer.GITHUB_COM);

    private final ModelSettingsService models = mock(ModelSettingsService.class);
    private final AgentDriverRegistry drivers = mock(AgentDriverRegistry.class);
    private final AgentDriver driver = mock(AgentDriver.class);
    private final TextOnlyHelper helper = mock(TextOnlyHelper.class);
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final RepositoryIssueStore issues = mock(RepositoryIssueStore.class);
    private final EpicDrafts drafts = new EpicDrafts(models, drivers, helper, workspaces, issues);

    @BeforeEach
    void setUp()
    {
        when(models.agentModel(AgentModelType.DEVELOPMENT, Optional.of("workspace")))
                .thenReturn(new AgentModelSetting(AgentModelType.DEVELOPMENT, true, LANE.value(), "Codex", "model", "Model", Optional.empty()));
        when(drivers.require(LANE)).thenReturn(driver);
        when(driver.capabilities()).thenReturn(AgentCapabilities.none());
        when(driver.probe()).thenReturn(new AgentProviderStatus(LANE, AgentProviderStatus.Readiness.READY, Optional.empty(), "Ready", AgentCapabilities.none()));
        when(workspaces.findById("workspace")).thenReturn(Optional.of(WORKSPACE));
    }

    @Test
    void theModelReadsTheWholeConversationWithTheDraftItShowed()
    {
        Draft shown = new Draft("Cache catalog metadata", "Cache table metadata per catalog.", "");
        when(helper.answer(any(), anyString(), eq(""), anyString(), any(Duration.class), startsWith("epic-draft:"))).thenReturn("""
                ```json
                {"message": " Creating it now. ", "draft": {"title": " Cache catalog metadata ", "description": "Cache table metadata per catalog.", "context": null},
                 "create": true}
                ```""");
        Reply reply = drafts.answer("workspace", ImmutableList.of(ASK, new Message(Role.AGENT, "Here is a draft. Create it?", Optional.of(shown)),
                new Message(Role.USER, "yes, create it", Optional.empty())));
        assertThat(reply).isEqualTo(new Reply("Creating it now.", Optional.of(shown), true));
        verify(helper).answer(any(), argThat(prompt -> prompt.contains("\"repository\":\"owner/repository\"") && prompt.contains("<conversation>") && prompt.contains("\"role\":\"AGENT\"")
                && prompt.contains("\"title\":\"Cache catalog metadata\"") && prompt.contains("yes, create it")), eq(""), anyString(), any(Duration.class), anyString());
    }

    @Test
    void theModelSearchesTheStoredOpenIssuesBeforeItReplies()
    {
        when(issues.findPage(any(WorkspaceStore.class), any(Workspace.class), anyInt(), anyInt())).thenReturn(new RepositoryIssuePage(1, REPOSITORY, ImmutableList.of(
                issue(13, "Reduce page source allocations", "Alice suggested a memory context here.", "bob"),
                issue(12, "Track page source memory through MemoryContext", "", "alice"),
                issue(14, "Fix a typo in the memory docs", "", "alice")), 0, 500, 3, false, Optional.empty()));
        when(helper.answer(any(), anyString(), eq(""), anyString(), any(Duration.class), startsWith("epic-draft:")))
                .thenReturn("{\"search\": \" alice memory context \"}", "{\"message\": \"Is #12 the issue?\", \"draft\": null, \"create\": false}");

        Reply reply = drafts.answer("workspace", ImmutableList.of(new Message(Role.USER, "Track alice's issue about memory contexts", Optional.empty())));

        assertThat(reply).isEqualTo(new Reply("Is #12 the issue?", Optional.empty(), false));
        List<String> prompts = prompts(2);
        assertThat(prompts.get(0)).doesNotContain("\n<search>\n");
        String searched = prompts.get(1);
        assertThat(searched).contains("\"words\":\"alice memory context\"", "\"found\":2", "\"author\":\"alice\"", "https://github.com/owner/repository/issues/12")
                .doesNotContain("\"number\":14");
        assertThat(searched.indexOf("\"number\":12")).isLessThan(searched.indexOf("\"number\":13"));
    }

    @Test
    void aModelThatKeepsSearchingIsUnreadable()
    {
        when(issues.findPage(any(WorkspaceStore.class), any(Workspace.class), anyInt(), anyInt()))
                .thenReturn(new RepositoryIssuePage(1, REPOSITORY, ImmutableList.of(), 0, 500, 0, false, Optional.empty()));
        when(helper.answer(any(), anyString(), eq(""), anyString(), any(Duration.class), startsWith("epic-draft:"))).thenReturn("{\"search\": \"cache\"}");

        assertThatThrownBy(() -> drafts.answer("workspace", ImmutableList.of(ASK))).isInstanceOf(AgentUnavailableException.class).hasMessageContaining("kept searching");
        assertThat(prompts(EpicDrafts.MAX_SEARCHES + 1).getLast()).contains("reply to the developer now");
    }

    @Test
    void theConversationEndsWithTheUsersMessage()
    {
        assertThatThrownBy(() -> drafts.answer("workspace", ImmutableList.of(ASK, new Message(Role.AGENT, "What is out of scope?", Optional.empty()))))
                .hasMessageContaining("must end with your message");
        assertThatThrownBy(() -> drafts.answer("workspace", ImmutableList.of())).hasMessageContaining("1 to 60 messages");
        verifyNoInteractions(helper);
    }

    @Test
    void proseIsAReplyWithoutADraftAndAnythingElseIsUnreadable()
    {
        assertThat(EpicDrafts.parse(" What should stay out of scope? ")).isEqualTo(new Answer(Optional.empty(), Optional.of("What should stay out of scope?"), Optional.empty(), Optional.empty()));
        assertThat(EpicDrafts.parse("{\"message\": \"Which catalogs?\", \"draft\": null, \"create\": false}"))
                .isEqualTo(new Answer(Optional.empty(), Optional.of("Which catalogs?"), Optional.empty(), Optional.of(false)));
        assertThat(EpicDrafts.parse("{\"search\": \"catalog cache\"}")).isEqualTo(new Answer(Optional.of("catalog cache"), Optional.empty(), Optional.empty(), Optional.empty()));
        assertThatThrownBy(() -> EpicDrafts.parse("{\"draft\": null}")).isInstanceOf(AgentUnavailableException.class);
        assertThatThrownBy(() -> EpicDrafts.parse("{\"search\": \" \"}")).isInstanceOf(AgentUnavailableException.class);
        assertThatThrownBy(() -> EpicDrafts.parse("{\"message\": \"Draft\", \"draft\": {\"title\": \" \"}}")).isInstanceOf(AgentUnavailableException.class);
        assertThatThrownBy(() -> EpicDrafts.parse(" ")).isInstanceOf(AgentUnavailableException.class);
    }

    private List<String> prompts(int count)
    {
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(helper, times(count)).answer(any(), prompts.capture(), eq(""), anyString(), any(Duration.class), anyString());
        return prompts.getAllValues();
    }

    private static RepositoryIssue issue(int number, String title, String body, String author)
    {
        return new RepositoryIssue(number, number, title, body, author, "", "https://github.com/owner/repository/issues/" + number, ImmutableList.of(), ImmutableList.of(), 0, NOW, NOW);
    }
}
