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
package com.bytequay.app.web;

import com.bytequay.app.discussion.TaskDiscussionService;
import com.bytequay.app.discussion.TaskDiscussionView;
import com.bytequay.core.domain.AgendaTarget;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static java.util.Objects.requireNonNull;

@RestController
public class AssistantResultController
{
    private final TaskDiscussionService discussions;

    public AssistantResultController(TaskDiscussionService discussions)
    {
        this.discussions = requireNonNull(discussions, "discussions is null");
    }

    @PostMapping("/api/assistant/result-discussions")
    public TaskDiscussionView open(@RequestBody OpenResult request)
    {
        return discussions.openResult(request.target(), request.sourceTurnId(), request.question());
    }

    public record OpenResult(AgendaTarget target, String sourceTurnId, String question) {}
}
