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

import com.bytequay.app.service.rules.AgentRuleService;
import com.bytequay.app.service.rules.AgentRuleView;
import com.bytequay.app.service.rules.RuleDefinition;
import com.bytequay.core.domain.RuleRun;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

@RestController
@RequestMapping("/api/settings/agent-rules")
public class AgentRulesController
{
    private final AgentRuleService rules;

    public AgentRulesController(AgentRuleService rules)
    {
        this.rules = requireNonNull(rules, "rules is null");
    }

    @GetMapping
    public List<AgentRuleView> list()
    {
        return rules.list();
    }

    @GetMapping("/runs")
    public List<RuleRun> recentRuns()
    {
        return rules.recentRuns();
    }

    @GetMapping("/{id}/runs")
    public List<RuleRun> record(@PathVariable String id)
    {
        return rules.record(id);
    }

    @PostMapping
    public List<AgentRuleView> add(@RequestBody RuleRequest request)
    {
        return rules.add(request.text());
    }

    @PostMapping("/check")
    public RuleDefinition check(@RequestBody RuleRequest request)
    {
        return rules.check(request.text());
    }

    @PutMapping("/{id}")
    public List<AgentRuleView> update(@PathVariable String id, @RequestBody RuleRequest request)
    {
        return rules.update(id, request.text());
    }

    @PutMapping("/{id}/paused")
    public List<AgentRuleView> pause(@PathVariable String id, @RequestBody PauseRequest request)
    {
        return rules.pause(id, request.paused());
    }

    @DeleteMapping("/{id}")
    public List<AgentRuleView> remove(@PathVariable String id)
    {
        return rules.remove(id);
    }

    @PostMapping("/{id}/activate")
    public List<AgentRuleView> activate(@PathVariable String id, @RequestBody ActivateRequest request)
    {
        return rules.activate(id, request.version());
    }

    @PostMapping("/{id}/move-up")
    public List<AgentRuleView> moveUp(@PathVariable String id)
    {
        return rules.moveUp(id);
    }

    @PostMapping("/{id}/move-down")
    public List<AgentRuleView> moveDown(@PathVariable String id)
    {
        return rules.moveDown(id);
    }

    @PostMapping("/{id}/keep-observing")
    public List<AgentRuleView> keepObserving(@PathVariable String id)
    {
        return rules.keepObserving(id);
    }

    @PostMapping("/{id}/dismiss")
    public List<AgentRuleView> dismiss(@PathVariable String id, @RequestBody DismissRequest request)
    {
        rules.dismiss(id, request.key());
        return rules.list();
    }

    public record RuleRequest(String text)
    {
        public RuleRequest
        {
            checkText(text, "Rule text");
        }
    }

    public record PauseRequest(boolean paused) {}

    public record ActivateRequest(@JsonProperty(required = true) long version)
    {
        public ActivateRequest
        {
            checkArgument(version >= 0, "Rule version must not be negative");
        }
    }

    public record DismissRequest(String key)
    {
        public DismissRequest
        {
            checkText(key, "Run key");
        }
    }
}
