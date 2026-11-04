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
package com.bytequay.app.workspace;

import com.bytequay.app.service.hosting.HostingIntegrations;
import com.bytequay.core.hosting.HostingIntegration;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.UncheckedExecutionException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Throwables.throwIfUnchecked;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Pages of items for a composer command, newest first, each one provider search, so a long history is never read in full. */
@Service
public class ComposerSuggestionService
{
    /** The picker asks for another page while the last one was full. */
    public static final int PAGE_SIZE = 20;

    private final WorkspaceHostingService hosting;
    private final HostingIntegrations integrations;
    private final WorkspaceRelationships relationships;
    private final Cache<CacheKey, List<ComposerSuggestion>> pages = CacheBuilder.newBuilder()
            .maximumSize(500).expireAfterWrite(Duration.ofMinutes(1)).build();

    public ComposerSuggestionService(WorkspaceHostingService hosting, HostingIntegrations integrations, WorkspaceRelationships relationships)
    {
        this.hosting = requireNonNull(hosting, "hosting is null");
        this.integrations = requireNonNull(integrations, "integrations is null");
        this.relationships = requireNonNull(relationships, "relationships is null");
    }

    public List<ComposerSuggestion> list(String workspaceId, Command command, String search, int page)
    {
        requireNonNull(command, "command is null");
        requireNonNull(search, "search is null");
        checkArgument(search.length() <= 500, "search must be at most 500 characters");
        // GitHub returns at most the first 1,000 results of a search.
        checkArgument(page > 0 && page <= 1000 / PAGE_SIZE, "page must be between 1 and %s", 1000 / PAGE_SIZE);
        String query = search.strip().toLowerCase(Locale.ROOT);
        // GitHub caps a search at 256 characters, and text without a letter or digit matches no title word or number.
        if (!query.isEmpty() && (query.length() > 100 || query.chars().noneMatch(Character::isLetterOrDigit))) {
            return ImmutableList.of();
        }
        WorkspaceHostingRead route = hosting.storedRead(command == Command.BACKPORT ? backportSource(workspaceId) : workspaceId);
        CacheKey key = new CacheKey(route.session().cacheScope(), route.repository().id(), route.login(), command, query, page);
        try {
            // Coalesce loads across keystrokes; failed loads are never cached.
            return pages.get(key, () -> load(route, command, query, page));
        }
        catch (ExecutionException | UncheckedExecutionException failure) {
            throwIfUnchecked(failure.getCause());
            throw new UncheckedExecutionException(failure.getCause());
        }
    }

    /** Backports come down the repo line, so a workspace with an upstream searches that upstream's merged PRs; the top of a line searches its own. */
    private String backportSource(String workspaceId)
    {
        return relationships.line(workspaceId).stream()
                .filter(node -> node.workspaceId().equals(workspaceId))
                .findFirst()
                .flatMap(WorkspaceRelationships.LineNode::parentWorkspaceId)
                .orElse(workspaceId);
    }

    private List<ComposerSuggestion> load(WorkspaceHostingRead route, Command command, String query, int page)
    {
        HostingIntegration integration = integrations.of(route.target());
        Stream<ComposerSuggestion> found = command == Command.FIX
                ? integration.issues().search(route.target(), query, page, PAGE_SIZE).stream()
                        .map(issue -> new ComposerSuggestion(issue.number(), issue.title(), issue.htmlUrl(), issue.author(), issue.updatedAt()))
                : integration.contents().search(route.target(), command == Command.BACKPORT, command == Command.REVIEW ? Optional.of(route.login()) : Optional.empty(),
                                query, page, PAGE_SIZE).stream()
                        .map(pr -> new ComposerSuggestion(pr.number(), pr.title(), pr.htmlUrl(), pr.author(), pr.updatedAt()));
        // A number also finds items that mention it; the item itself leads.
        String number = query.startsWith("#") ? query.substring(1) : query;
        return found.sorted(Comparator.comparing(item -> !Integer.toString(item.number()).equals(number))).collect(toImmutableList());
    }

    public enum Command
    {
        BACKPORT,
        FIX,
        REVIEW
    }

    private record CacheKey(String accountScope, long repositoryId, String login, Command command, String query, int page) {}
}
