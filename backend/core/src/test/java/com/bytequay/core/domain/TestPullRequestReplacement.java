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

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestPullRequestReplacement
{
    @Test
    void cannotCompleteWithoutTheVerifiedReplacementOrChangeItsIdentity()
    {
        PullRequestReplacement replacement = new PullRequestReplacement("task", "workspace", 1, 2, RepoRef.of("contributor", "repo"), RepoRef.of("owner", "repo"),
                11, "old-branch", "main", "a".repeat(40), "new-branch", "Fix ordering", "", true, false, Optional.empty(), false);
        assertThatThrownBy(replacement::complete).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("requires a replacement");
        assertThatThrownBy(() -> replacement.created(11)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("different pull request");
        assertThatThrownBy(() -> replacement.created(12).created(13)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot change");
    }
}
