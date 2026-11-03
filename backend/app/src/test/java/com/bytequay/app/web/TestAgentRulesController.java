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

import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.bytequay.app.service.rules.AgentRuleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestAgentRulesController
{
    private static final String RULE = """
            ---
            name: Review PRs that need me
            when: review-requested
            does: review
            agrees-when: reviewed
            ---
            Review it for correctness first, then nits. Draft comments only.
            """;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final AgentRuleService service = new AgentRuleService(new InMemoryAppSettingsStore(), mapper,
            Clock.fixed(Instant.parse("2026-11-03T08:00:00Z"), ZoneOffset.UTC));
    private final MockMvc mvc = standaloneSetup(new AgentRulesController(service))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void checksSavesPausesEditsAndRemovesARule()
            throws Exception
    {
        mvc.perform(post("/api/settings/agent-rules/check").contentType(MediaType.APPLICATION_JSON).content(body(RULE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Review PRs that need me"))
                .andExpect(jsonPath("$.event").value("REVIEW_REQUESTED"))
                .andExpect(jsonPath("$.action").value("REVIEW"))
                .andExpect(jsonPath("$.agreement").value("REVIEWED"))
                .andExpect(jsonPath("$.withinDays").value(3));
        mvc.perform(get("/api/settings/agent-rules")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        mvc.perform(post("/api/settings/agent-rules").contentType(MediaType.APPLICATION_JSON).content(body(RULE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].mode").value("OBSERVE"))
                .andExpect(jsonPath("$[0].paused").value(false))
                .andExpect(jsonPath("$[0].rule.name").value("Review PRs that need me"));
        String id = service.list().getFirst().id();

        mvc.perform(put("/api/settings/agent-rules/" + id + "/paused").contentType(MediaType.APPLICATION_JSON).content("{\"paused\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].paused").value(true));
        mvc.perform(put("/api/settings/agent-rules/" + id).contentType(MediaType.APPLICATION_JSON).content(body(RULE.replace("then nits", "then style"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].rule.body").value("Review it for correctness first, then style. Draft comments only."));
        mvc.perform(delete("/api/settings/agent-rules/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aTextThatIsNotARuleIsABadRequestThatSaysWhy()
            throws Exception
    {
        mvc.perform(post("/api/settings/agent-rules/check").contentType(MediaType.APPLICATION_JSON).content(body(RULE.replace("does: review\n", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Add a \"does\" line")));
        mvc.perform(post("/api/settings/agent-rules").contentType(MediaType.APPLICATION_JSON).content(body(RULE.replace("review-requested", "issue-closed"))))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/settings/agent-rules")).andExpect(jsonPath("$.length()").value(0));
    }

    private String body(String text)
            throws Exception
    {
        return mapper.writeValueAsString(ImmutableMap.of("text", text));
    }
}
