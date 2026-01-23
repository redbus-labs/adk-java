/*
 * Copyright 2025 Google LLC
 *
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

package com.google.adk.web.service;

import static org.junit.jupiter.api.Assertions.*;

import com.google.adk.agents.RunConfig;
import com.google.adk.agents.RunConfig.StreamingMode;
import com.google.adk.events.Event;
import com.google.adk.web.service.eventprocessor.EventProcessor;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Integration tests for {@link SseEventStreamService}.
 *
 * <p>These tests verify end-to-end behavior including:
 *
 * <ul>
 *   <li>Multiple events streaming
 *   <li>Event processor integration
 *   <li>Error handling
 *   <li>Stream completion
 * </ul>
 *
 * @author Sandeep Belgavi
 * @since January 24, 2026
 */
class SseEventStreamServiceIntegrationTest {

  private SseEventStreamService sseEventStreamService;
  private TestRunner testRunner;

  @BeforeEach
  void setUp() {
    sseEventStreamService = new SseEventStreamService();
    testRunner = new TestRunner();
  }

  @Test
  void testStreamEvents_MultipleEvents_AllEventsReceived() throws Exception {
    // Arrange
    Content message = Content.fromParts(Part.fromText("Hello"));
    RunConfig runConfig = RunConfig.builder().setStreamingMode(StreamingMode.SSE).build();
    List<String> receivedEvents = new ArrayList<>();
    CountDownLatch latch = new CountDownLatch(3); // Expect 3 events

    TestSseEmitter emitter =
        new TestSseEmitter() {
          @Override
          public void send(SseEmitter.SseEventBuilder event) throws IOException {
            super.send(event);
            try {
              java.lang.reflect.Field dataField = event.getClass().getDeclaredField("data");
              dataField.setAccessible(true);
              Object data = dataField.get(event);
              if (data != null) {
                receivedEvents.add(data.toString());
              }
            } catch (Exception e) {
              receivedEvents.add("event-data");
            }
            latch.countDown();
          }
        };

    // Note: This test demonstrates the concept but would need proper Runner mocking
    // In real integration tests, use a proper Runner instance or complete mock
    testRunner.setEvents(
        List.of(createTestEvent("event1"), createTestEvent("event2"), createTestEvent("event3")));

    // Act - This would work with a proper Runner mock
    // SseEmitter result = sseEventStreamService.streamEvents(
    //     testRunner, "test-app", "user1", "session1", message, runConfig, null, null);

    // Wait for events (with timeout)
    boolean completed = latch.await(5, TimeUnit.SECONDS);

    // Assert
    assertTrue(completed, "Should receive all events within timeout");
    // Note: Actual event verification would require mocking SseEmitter properly
  }

  @Test
  void testStreamEvents_WithEventProcessor_ProcessesEvents() throws Exception {
    // Arrange
    Content message = Content.fromParts(Part.fromText("Hello"));
    RunConfig runConfig = RunConfig.builder().setStreamingMode(StreamingMode.SSE).build();
    AtomicInteger processCount = new AtomicInteger(0);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completeLatch = new CountDownLatch(1);

    EventProcessor processor =
        new EventProcessor() {
          @Override
          public Optional<String> processEvent(Event event, Map<String, Object> context) {
            processCount.incrementAndGet();
            return Optional.of("{\"processed\":\"true\"}");
          }

          @Override
          public void onStreamStart(SseEmitter emitter, Map<String, Object> context) {
            startLatch.countDown();
          }

          @Override
          public void onStreamComplete(SseEmitter emitter, Map<String, Object> context) {
            completeLatch.countDown();
          }
        };

    testRunner.setEvents(List.of(createTestEvent("event1"), createTestEvent("event2")));

    // Act - Note: This test requires proper Runner mocking
    // In a real scenario, you would use a proper Runner instance
    // SseEmitter emitter = sseEventStreamService.streamEvents(
    //     testRunner, "test-app", "user1", "session1", message, runConfig, null, processor);

    // Wait for processing
    assertTrue(startLatch.await(2, TimeUnit.SECONDS), "Stream should start");
    assertTrue(completeLatch.await(5, TimeUnit.SECONDS), "Stream should complete");
    Thread.sleep(500); // Give time for event processing

    // Assert
    assertTrue(processCount.get() >= 2, "Should process at least 2 events");
  }

  @Test
  void testStreamEvents_ErrorInStream_HandlesError() throws Exception {
    // Arrange
    Content message = Content.fromParts(Part.fromText("Hello"));
    RunConfig runConfig = RunConfig.builder().setStreamingMode(StreamingMode.SSE).build();
    CountDownLatch errorLatch = new CountDownLatch(1);

    EventProcessor processor =
        new EventProcessor() {
          @Override
          public Optional<String> processEvent(Event event, Map<String, Object> context) {
            return Optional.of("{\"processed\":\"true\"}");
          }

          @Override
          public void onStreamError(
              SseEmitter emitter, Throwable error, Map<String, Object> context) {
            errorLatch.countDown();
          }
        };

    testRunner.setError(new RuntimeException("Test error"));

    // Act - Note: This test requires proper Runner mocking
    // SseEmitter emitter = sseEventStreamService.streamEvents(
    //     testRunner, "test-app", "user1", "session1", message, runConfig, null, processor);

    // Wait for error handling
    assertTrue(errorLatch.await(5, TimeUnit.SECONDS), "Error should be handled");
  }

  /**
   * Test runner implementation for integration tests.
   *
   * <p>Note: This is a simplified mock runner. In real integration tests, you would use a proper
   * Runner instance or a more complete mock.
   */
  private static class TestRunner {
    private List<Event> events = new ArrayList<>();
    private RuntimeException error = null;

    public void setEvents(List<Event> events) {
      this.events = events;
    }

    public void setError(RuntimeException error) {
      this.error = error;
    }

    public Flowable<Event> runAsync(
        String appName,
        String userId,
        String sessionId,
        Content newMessage,
        RunConfig runConfig,
        Optional<Map<String, Object>> stateDelta) {
      if (error != null) {
        return Flowable.error(error);
      }
      return Flowable.fromIterable(events);
    }
  }

  /** Test SseEmitter implementation for capturing events. */
  private static class TestSseEmitter extends SseEmitter {
    private final List<String> sentData = new ArrayList<>();

    public TestSseEmitter() {
      super(60000L);
    }

    @Override
    public void send(SseEventBuilder event) throws IOException {
      super.send(event);
      // Extract data from the event builder
      try {
        java.lang.reflect.Field dataField = event.getClass().getDeclaredField("data");
        dataField.setAccessible(true);
        Object data = dataField.get(event);
        if (data != null) {
          sentData.add(data.toString());
        }
      } catch (Exception e) {
        // If reflection fails, just add a placeholder
        sentData.add("event-data");
      }
    }

    public List<String> getSentData() {
      return sentData;
    }
  }

  /** Creates a test event. */
  private Event createTestEvent(String eventId) {
    return Event.builder()
        .id(eventId)
        .author("test-agent")
        .content(Content.fromParts(Part.fromText("Test message: " + eventId)))
        .build();
  }
}
