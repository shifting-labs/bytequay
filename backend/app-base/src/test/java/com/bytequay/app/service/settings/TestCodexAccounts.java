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
package com.bytequay.app.service.settings;

import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestCodexAccounts
{
    @Test
    void keepsBrowserLoginsSeparateAndScopedWithoutChangingGatewayAccounts()
    {
        InMemoryAppSettingsStore store = new InMemoryAppSettingsStore();
        CodexAccounts accounts = new CodexAccounts(store, new ObjectMapper());
        CodexAccount work = accounts.add("Work", " work@example.com ", Optional.of("repository"));
        CodexAccount personal = accounts.add("Personal", "personal@example.com", Optional.empty());
        assertThat(work.email()).contains("work@example.com");
        assertThat(work.gatewayUrl()).isEmpty();
        assertThat(new CodexAccounts(store, new ObjectMapper()).list()).containsExactly(work, personal);
        assertThat(accounts.availableIn(work.laneId().value(), Optional.of("other"))).isFalse();
        assertThat(accounts.availableIn(personal.laneId().value(), Optional.of("other"))).isTrue();
        accounts.restrictToWorkspace(personal.laneId().value(), "repository");
        assertThat(accounts.find(personal.laneId().value()).orElseThrow().email()).isEqualTo(personal.email());
        assertThatThrownBy(() -> accounts.add("Invalid", "not-an-email", Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CodexAccount(work.id(), "Both", work.email(), Optional.of("https://llm.example.com/v1"), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keepsGatewayIdentityAndRepositoryScopeAcrossRestartAndRemoval()
    {
        InMemoryAppSettingsStore store = new InMemoryAppSettingsStore();
        CodexAccounts accounts = new CodexAccounts(store, new ObjectMapper());
        CodexAccount account = accounts.addGateway(" Work ", " https://llm.example.com/v1 ", Optional.empty());
        assertThat(account.label()).isEqualTo("Work");
        assertThat(account.gatewayUrl()).contains("https://llm.example.com/v1");
        assertThat(accounts.availableIn(account.laneId().value(), Optional.of("other"))).isTrue();
        accounts.restrictToWorkspace(account.laneId().value(), "repository");
        CodexAccounts restarted = new CodexAccounts(store, new ObjectMapper());
        assertThat(restarted.find(account.laneId().value()).orElseThrow().id()).isEqualTo(account.id());
        assertThat(restarted.availableIn(account.laneId().value(), Optional.of("repository"))).isTrue();
        assertThat(restarted.availableIn(account.laneId().value(), Optional.of("other"))).isFalse();
        assertThat(restarted.availableIn(account.laneId().value(), Optional.empty())).isFalse();
        assertThatThrownBy(() -> restarted.restrictToWorkspace(account.laneId().value(), "other")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> restarted.addGateway("work", account.gatewayUrl().orElseThrow(), Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        restarted.remove(account.id());
        assertThat(restarted.availableIn(account.laneId().value(), Optional.of("repository"))).isFalse();
        assertThat(restarted.addGateway("Work", account.gatewayUrl().orElseThrow(), Optional.empty()).id()).isNotEqualTo(account.id());
    }

    @Test
    void rejectsInvalidUrlsAndPreservesUnreadableSettings()
    {
        InMemoryAppSettingsStore store = new InMemoryAppSettingsStore();
        CodexAccounts accounts = new CodexAccounts(store, new ObjectMapper());
        for (String url : new String[] {"", "llm.example.com", "ftp://llm.example.com", "https://user:secret@llm.example.com",
                "https://", "https://llm.example.com?key=secret", "https://llm.example.com/#fragment"}) {
            assertThatThrownBy(() -> accounts.addGateway("Work", url, Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(accounts.list()).isEmpty();
        store.put("model.codexAccounts", "broken");
        assertThatThrownBy(() -> accounts.addGateway("Work", "https://llm.example.com/v1", Optional.empty())).isInstanceOf(IllegalStateException.class);
        assertThat(store.get("model.codexAccounts")).contains("broken");
    }
}
