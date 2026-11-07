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
package com.bytequay.app.web.agent;

import com.bytequay.app.notices.ResultDigest;
import com.bytequay.app.notices.ResultDigestService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@RestController
public class ResultDigestController
{
    /** More than any Results view shows at once. */
    private static final int MAXIMUM_NOTICES = 100;

    private final ResultDigestService digests;

    public ResultDigestController(ResultDigestService digests)
    {
        this.digests = requireNonNull(digests, "digests is null");
    }

    /** What each result card decides from; read only, so the Assistant may ask as often as its cards change. */
    @PostMapping("/agent/notices/digests")
    public List<ResultDigest> digests(@RequestBody List<ResultDigestService.Request> notices)
    {
        checkArgument(notices != null && notices.size() <= MAXIMUM_NOTICES, "Ask for at most %s notices", MAXIMUM_NOTICES);
        return notices.stream().map(digests::digest).collect(toImmutableList());
    }
}
