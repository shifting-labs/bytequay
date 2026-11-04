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
package com.bytequay.core.domain;

import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkSha;
import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** The accepted replacement and its remote receipt; an attempted create must be reconciled before any retry. */
public record PullRequestReplacement(
        String taskId,
        String workspaceId,
        long accountId,
        long repositoryId,
        RepoRef headRepository,
        RepoRef baseRepository,
        int previousNumber,
        String previousBranch,
        String baseBranch,
        String headSha,
        String branch,
        String title,
        String body,
        boolean draft,
        boolean createStarted,
        Optional<Integer> replacementNumber,
        boolean completed)
{
    public PullRequestReplacement
    {
        checkText(taskId, "taskId");
        checkText(workspaceId, "workspaceId");
        checkArgument(accountId > 0 && repositoryId > 0, "hosting identities must be positive");
        requireNonNull(headRepository, "headRepository is null");
        requireNonNull(baseRepository, "baseRepository is null");
        checkText(headRepository.owner(), "headRepository.owner");
        checkText(headRepository.repo(), "headRepository.repo");
        checkText(baseRepository.owner(), "baseRepository.owner");
        checkText(baseRepository.repo(), "baseRepository.repo");
        checkArgument(previousNumber > 0, "previousNumber must be positive");
        checkText(previousBranch, "previousBranch");
        checkText(baseBranch, "baseBranch");
        checkSha(headSha, "headSha");
        checkText(branch, "branch");
        checkArgument(!branch.equals(previousBranch), "replacement branch must differ from the previous branch");
        checkText(title, "title");
        requireNonNull(body, "body is null");
        requireNonNull(replacementNumber, "replacementNumber is null");
        replacementNumber.ifPresent(number -> checkArgument(number > 0 && number != previousNumber, "replacement must be a different pull request"));
        checkArgument(replacementNumber.isEmpty() || createStarted, "replacement requires a create attempt");
        checkArgument(!completed || replacementNumber.isPresent(), "completion requires a replacement pull request");
    }

    public PullRequestReplacement started()
    {
        return new PullRequestReplacement(taskId, workspaceId, accountId, repositoryId, headRepository, baseRepository, previousNumber, previousBranch,
                baseBranch, headSha, branch, title, body, draft, true, replacementNumber, completed);
    }

    public PullRequestReplacement created(int number)
    {
        checkArgument(replacementNumber.isEmpty() || replacementNumber.orElseThrow() == number, "replacement pull request cannot change");
        return new PullRequestReplacement(taskId, workspaceId, accountId, repositoryId, headRepository, baseRepository, previousNumber, previousBranch,
                baseBranch, headSha, branch, title, body, draft, true, Optional.of(number), completed);
    }

    public PullRequestReplacement complete()
    {
        return new PullRequestReplacement(taskId, workspaceId, accountId, repositoryId, headRepository, baseRepository, previousNumber, previousBranch,
                baseBranch, headSha, branch, title, body, draft, createStarted, replacementNumber, true);
    }
}
