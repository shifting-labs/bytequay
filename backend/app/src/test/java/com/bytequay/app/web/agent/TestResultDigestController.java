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
import com.bytequay.app.web.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestResultDigestController
{
    private final ResultDigestService digests = mock(ResultDigestService.class);
    // Boot's own serialization settings, so the test asserts the wire format the renderer parses.
    private final MockMvc http = standaloneSetup(new ResultDigestController(digests)).setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(Jackson2ObjectMapperBuilder.json()
                    .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build()))
            .build();

    @Test
    void answersEachNoticeItWasAskedAbout()
            throws Exception
    {
        when(digests.digest(new ResultDigestService.Request("finished:turn", "workspace", Optional.empty(), Optional.of("owner/repo#62"), Optional.of("turn"))))
                .thenReturn(new ResultDigest("finished:turn", "GitHub", Optional.empty(), Optional.empty(), Optional.of("Moved session storage to Redis.")));

        http.perform(post("/agent/notices/digests").contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"noticeId\":\"finished:turn\",\"workspaceId\":\"workspace\",\"pullRequest\":\"owner/repo#62\",\"turnId\":\"turn\"}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].noticeId").value("finished:turn"))
                .andExpect(jsonPath("$[0].host").value("GitHub"))
                .andExpect(jsonPath("$[0].report").value("Moved session storage to Redis."));
    }

    @Test
    void refusesMoreNoticesThanAViewShows()
            throws Exception
    {
        String notice = "{\"noticeId\":\"finished:turn\",\"workspaceId\":\"workspace\"}";
        http.perform(post("/agent/notices/digests").contentType(MediaType.APPLICATION_JSON).content("[" + String.join(",", Collections.nCopies(101, notice)) + "]"))
                .andExpect(status().isBadRequest());
    }
}
