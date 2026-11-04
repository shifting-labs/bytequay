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

import com.bytequay.core.domain.PullRequestReplacement;
import com.bytequay.core.store.PullRequestReplacementStore;
import com.bytequay.persistence.sqlite.entity.PullRequestReplacementEntity;
import com.bytequay.persistence.sqlite.repository.PullRequestReplacementJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Repository
@Transactional(readOnly = true)
public class SqlitePullRequestReplacementStore
        implements PullRequestReplacementStore
{
    private final PullRequestReplacementJpaRepository replacements;

    public SqlitePullRequestReplacementStore(PullRequestReplacementJpaRepository replacements)
    {
        this.replacements = requireNonNull(replacements, "replacements is null");
    }

    @Override
    public Optional<PullRequestReplacement> find(String taskId)
    {
        return replacements.findById(taskId).map(PullRequestReplacementEntity::toDomain);
    }

    @Override
    @Transactional
    public void save(PullRequestReplacement replacement)
    {
        PullRequestReplacementEntity entity = replacements.findById(replacement.taskId()).orElseGet(() -> new PullRequestReplacementEntity(replacement));
        entity.update(replacement);
        replacements.saveAndFlush(entity);
    }
}
