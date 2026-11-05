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

import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.core.store.AppSettingsStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Stores account names, login emails, gateway URLs, and stable identities in SQLite, never credentials. */
@Service
public class CodexAccounts
{
    /** The {@code ModelApiKeyStore} provider of a gateway account's token, saved under the account's ID. */
    public static final String GATEWAY_TOKEN_PROVIDER = "Codex gateway";
    private static final String KEY = "model.codexAccounts";

    private final AppSettingsStore settings;
    private final ObjectMapper mapper;

    public CodexAccounts(AppSettingsStore settings, ObjectMapper mapper)
    {
        this.settings = requireNonNull(settings, "settings is null");
        this.mapper = requireNonNull(mapper, "mapper is null").copy().registerModule(new Jdk8Module());
    }

    public List<CodexAccount> list()
    {
        return settings.get(KEY).map(json -> {
            try {
                return ImmutableList.copyOf(mapper.readValue(json, CodexAccount[].class));
            }
            catch (JsonProcessingException failure) {
                // Losing an account ID would strand its resumable tasks; never overwrite a broken list.
                throw new IllegalStateException("Codex account settings could not be read", failure);
            }
        }).orElse(ImmutableList.of());
    }

    public CodexAccount add(String label, String email, Optional<String> workspaceId)
    {
        return add(new CodexAccount(UUID.randomUUID().toString(), label, Optional.of(email), Optional.empty(), workspaceId));
    }

    public CodexAccount addGateway(String label, String gatewayUrl, Optional<String> workspaceId)
    {
        return add(new CodexAccount(UUID.randomUUID().toString(), label, Optional.empty(), Optional.of(gatewayUrl), workspaceId));
    }

    private synchronized CodexAccount add(CodexAccount account)
    {
        List<CodexAccount> existing = list();
        checkArgument(existing.stream().noneMatch(value -> value.label().equalsIgnoreCase(account.label())), "A Codex account with that name already exists");
        save(ImmutableList.<CodexAccount>builder().addAll(existing).add(account).build());
        return account;
    }

    public Optional<CodexAccount> find(String laneId)
    {
        return list().stream().filter(account -> account.laneId().value().equals(laneId)).findFirst();
    }

    public boolean availableIn(String laneId, Optional<String> workspaceId)
    {
        return !laneId.startsWith(AgentLaneId.CODEX_ACCOUNT_PREFIX)
                || find(laneId).filter(account -> account.availableIn(workspaceId)).isPresent();
    }

    public void checkWorkspaceAccess(String laneId, Optional<String> workspaceId)
    {
        if (!availableIn(laneId, workspaceId)) {
            throw new AgentUnavailableException("Codex account unavailable in this repository");
        }
    }

    /** Restrict an existing global profile without changing its login identity. */
    public synchronized void restrictToWorkspace(String laneId, String workspaceId)
    {
        CodexAccount account = find(laneId).orElseThrow(() -> new IllegalArgumentException("Unknown Codex account"));
        checkArgument(account.workspaceId().isEmpty(), "This account already belongs to a repository");
        CodexAccount scoped = new CodexAccount(account.id(), account.label(), account.email(), account.gatewayUrl(), Optional.of(workspaceId));
        save(list().stream().map(value -> value.id().equals(account.id()) ? scoped : value).collect(toImmutableList()));
    }

    /** Removes the saved choice, retaining CLI-owned login files and sessions already in flight. */
    public synchronized void remove(String id)
    {
        checkArgument(UUID.fromString(requireNonNull(id, "id is null")).toString().equals(id), "invalid Codex account ID");
        save(list().stream().filter(account -> !account.id().equals(id)).collect(toImmutableList()));
    }

    private void save(List<CodexAccount> accounts)
    {
        try {
            settings.put(KEY, mapper.writeValueAsString(accounts));
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("Codex account settings could not be saved", failure);
        }
    }
}
