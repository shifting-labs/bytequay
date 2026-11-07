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

import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.spi.ProviderAgentEvent.MessageKind;
import com.bytequay.agent.spi.ProviderAgentEvent.MessagePhase;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

import static com.bytequay.agent.core.AgentEventSource.root;
import static org.assertj.core.api.Assertions.assertThat;

class TestAgentSseSubscriber
{
    @Test
    void keepsAReplayPageOfDemandAndRejectsDuplicateSubscriptions()
    {
        AgentSseSubscriber subscriber = new AgentSseSubscriber(new SseEmitter());
        RecordingSubscription subscription = new RecordingSubscription();
        subscriber.onSubscribe(subscription);
        assertThat(subscription.requested).isEqualTo(8);

        subscriber.onNext(event());
        // Each delivered event is asked for again, so eight stay outstanding.
        assertThat(subscription.requested).isEqualTo(9);

        RecordingSubscription duplicate = new RecordingSubscription();
        subscriber.onSubscribe(duplicate);
        assertThat(duplicate.cancelled).isTrue();
    }

    @Test
    void aDisconnectedClientStopsEventsWithoutCompletingTheRequest()
    {
        AtomicReference<Throwable> completedWith = new AtomicReference<>();
        AgentSseSubscriber subscriber = new AgentSseSubscriber(new SseEmitter()
        {
            @Override
            public void send(SseEventBuilder builder)
                    throws IOException
            {
                throw new IOException("Broken pipe");
            }

            @Override
            public synchronized void completeWithError(Throwable failure)
            {
                completedWith.set(failure);
            }
        });
        RecordingSubscription subscription = new RecordingSubscription();
        subscriber.onSubscribe(subscription);

        subscriber.onNext(event());

        assertThat(subscription.cancelled).isTrue();
        assertThat(completedWith).hasNullValue();
    }

    private static AgentEventEnvelope event()
    {
        return new AgentEventEnvelope(
                AgentEventEnvelope.CURRENT_SCHEMA_VERSION,
                EventId.random(),
                1,
                TaskId.random(),
                Optional.empty(),
                root(),
                Instant.parse("2026-08-16T00:00:00Z"),
                new AgentEventPayload.AssistantMessage(MessageKind.ANSWER, MessagePhase.FINAL, "done"));
    }

    private static final class RecordingSubscription
            implements Flow.Subscription
    {
        private long requested;
        private boolean cancelled;

        @Override
        public void request(long count)
        {
            requested += count;
        }

        @Override
        public void cancel()
        {
            cancelled = true;
        }
    }
}
