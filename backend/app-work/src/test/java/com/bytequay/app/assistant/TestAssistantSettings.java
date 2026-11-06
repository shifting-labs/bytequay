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

import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestAssistantSettings
{
    private final InMemoryAppSettingsStore store = new InMemoryAppSettingsStore();
    private final AssistantSettings settings = new AssistantSettings(store, new ObjectMapper());

    @Test
    void aSwitchTheUserNeverSavedReadsAsItsDefault()
    {
        assertThat(settings.permissions()).isEqualTo(new AssistantPermissions(false, false, false, false, true));

        // Saved before pull request work existed: the user's choices stay, and the new switch starts on.
        store.put("assistant.permissions", "{\"startTasks\":true,\"answerTasks\":false,\"changeWork\":true,\"markAttention\":false}");
        assertThat(settings.permissions()).isEqualTo(new AssistantPermissions(true, false, true, false, true));

        settings.save(new AssistantPermissions(true, true, false, false, false));
        assertThat(settings.permissions()).isEqualTo(new AssistantPermissions(true, true, false, false, false));
    }

    @Test
    void unreadableSwitchesReadAsAllOff()
    {
        store.put("assistant.permissions", "not json");
        assertThat(settings.permissions()).isEqualTo(new AssistantPermissions(false, false, false, false, false));
        store.put("assistant.permissions", "[true]");
        assertThat(settings.permissions()).isEqualTo(new AssistantPermissions(false, false, false, false, false));
    }
}
