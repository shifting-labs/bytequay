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

import static org.assertj.core.api.Assertions.assertThat;

class TestUserWords
{
    @Test
    void readsANegationOnlyWithinItsClause()
    {
        assertThat(UserWords.negated("don't push to main", "push to main")).isTrue();
        assertThat(UserWords.negated("please do not merge yet", "merge yet")).isTrue();
        assertThat(UserWords.negated("don't merge it or tag it", "tag it")).isTrue();
        assertThat(UserWords.negated("open a pr instead of pushing to main", "pushing to main")).isTrue();
        assertThat(UserWords.negated("don't push to main, open a pr instead", "open a pr instead")).isFalse();
        assertThat(UserWords.negated("don't push to main but push the branch", "push the branch")).isFalse();
        assertThat(UserWords.negated("merge it. don't tag it", "merge it")).isFalse();
        assertThat(UserWords.negated("nothing else, just merge it", "merge it")).isFalse();
        assertThat(UserWords.negated("don't push to main", "don't push to main")).isFalse();
        assertThat(UserWords.negated("ship it", "push to main")).isFalse();
    }
}
