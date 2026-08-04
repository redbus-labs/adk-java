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

package com.google.adk.transcription.resilience;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CircuitBreaker}.
 *
 * @author Sandeep Belgavi
 * @since 2026-08-04
 */
@DisplayName("CircuitBreaker Tests")
class CircuitBreakerTest {

  @Test
  @DisplayName("CLOSED state passes calls through successfully")
  void testClosedStatePassesCalls() throws Exception {
    CircuitBreaker breaker =
        CircuitBreaker.builder().failureThreshold(5).openDurationMs(1000).build();

    String result = breaker.execute(() -> "hello");

    assertThat(result).isEqualTo("hello");
    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
  }

  @Test
  @DisplayName("CLOSED state allows multiple successful calls")
  void testClosedStateMultipleSuccesses() throws Exception {
    CircuitBreaker breaker =
        CircuitBreaker.builder().failureThreshold(5).openDurationMs(1000).build();

    for (int i = 0; i < 10; i++) {
      String result = breaker.execute(() -> "ok");
      assertThat(result).isEqualTo("ok");
    }

    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
  }

  @Test
  @DisplayName("transitions to OPEN after failureThreshold failures")
  void testTransitionsToOpenAfterThreshold() {
    CircuitBreaker breaker =
        CircuitBreaker.builder()
            .failureThreshold(3)
            .openDurationMs(30000) // long enough that it won't transition
            .build();

    // Cause 3 failures
    for (int i = 0; i < 3; i++) {
      assertThrows(
          RuntimeException.class,
          () ->
              breaker.execute(
                  () -> {
                    throw new RuntimeException("fail");
                  }));
    }

    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
  }

  @Test
  @DisplayName("OPEN state rejects calls with CircuitBreakerOpenException")
  void testOpenStateRejects() {
    CircuitBreaker breaker =
        CircuitBreaker.builder().failureThreshold(2).openDurationMs(30000).build();

    // Trip the breaker
    for (int i = 0; i < 2; i++) {
      assertThrows(
          RuntimeException.class,
          () ->
              breaker.execute(
                  () -> {
                    throw new RuntimeException("fail");
                  }));
    }

    // Now it should reject
    CircuitBreakerOpenException ex =
        assertThrows(CircuitBreakerOpenException.class, () -> breaker.execute(() -> "blocked"));

    assertThat(ex.getMessage()).contains("OPEN");
  }

  @Test
  @DisplayName("transitions to HALF_OPEN after openDurationMs")
  void testTransitionsToHalfOpenAfterDuration() throws Exception {
    CircuitBreaker breaker =
        CircuitBreaker.builder()
            .failureThreshold(2)
            .openDurationMs(50) // very short for testing
            .halfOpenMaxAttempts(1)
            .build();

    // Trip the breaker
    for (int i = 0; i < 2; i++) {
      assertThrows(
          RuntimeException.class,
          () ->
              breaker.execute(
                  () -> {
                    throw new RuntimeException("fail");
                  }));
    }

    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

    // Wait for open duration to elapse
    Thread.sleep(100);

    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
  }

  @Test
  @DisplayName("HALF_OPEN success transitions to CLOSED")
  void testHalfOpenSuccessTransitionsToClosed() throws Exception {
    CircuitBreaker breaker =
        CircuitBreaker.builder()
            .failureThreshold(2)
            .openDurationMs(50)
            .halfOpenMaxAttempts(1)
            .build();

    // Trip the breaker
    for (int i = 0; i < 2; i++) {
      assertThrows(
          RuntimeException.class,
          () ->
              breaker.execute(
                  () -> {
                    throw new RuntimeException("fail");
                  }));
    }

    // Wait for transition to HALF_OPEN
    Thread.sleep(100);
    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

    // Successful call in HALF_OPEN should close the circuit
    String result = breaker.execute(() -> "recovered");
    assertThat(result).isEqualTo("recovered");
    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
  }

  @Test
  @DisplayName("HALF_OPEN failure transitions back to OPEN")
  void testHalfOpenFailureTransitionsToOpen() throws Exception {
    CircuitBreaker breaker =
        CircuitBreaker.builder()
            .failureThreshold(2)
            .openDurationMs(50)
            .halfOpenMaxAttempts(2)
            .build();

    // Trip the breaker
    for (int i = 0; i < 2; i++) {
      assertThrows(
          RuntimeException.class,
          () ->
              breaker.execute(
                  () -> {
                    throw new RuntimeException("fail");
                  }));
    }

    // Wait for transition to HALF_OPEN
    Thread.sleep(100);
    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

    // Failed call in HALF_OPEN should reopen the circuit
    assertThrows(
        RuntimeException.class,
        () ->
            breaker.execute(
                () -> {
                  throw new RuntimeException("still failing");
                }));

    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
  }

  @Test
  @DisplayName("reset() returns circuit breaker to CLOSED state")
  void testResetReturnsToClosed() {
    CircuitBreaker breaker =
        CircuitBreaker.builder().failureThreshold(2).openDurationMs(30000).build();

    // Trip the breaker
    for (int i = 0; i < 2; i++) {
      assertThrows(
          RuntimeException.class,
          () ->
              breaker.execute(
                  () -> {
                    throw new RuntimeException("fail");
                  }));
    }
    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

    // Reset should bring it back to CLOSED
    breaker.reset();
    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
  }

  @Test
  @DisplayName("reset() allows calls again after being in OPEN state")
  void testResetAllowsCallsAgain() throws Exception {
    CircuitBreaker breaker =
        CircuitBreaker.builder().failureThreshold(2).openDurationMs(30000).build();

    // Trip the breaker
    for (int i = 0; i < 2; i++) {
      assertThrows(
          RuntimeException.class,
          () ->
              breaker.execute(
                  () -> {
                    throw new RuntimeException("fail");
                  }));
    }

    // Reset and verify calls work
    breaker.reset();
    String result = breaker.execute(() -> "back in action");
    assertThat(result).isEqualTo("back in action");
  }

  @Test
  @DisplayName("thread safety: concurrent calls do not corrupt state")
  void testThreadSafety() throws Exception {
    CircuitBreaker breaker =
        CircuitBreaker.builder().failureThreshold(10).openDurationMs(30000).build();

    int threadCount = 20;
    int callsPerThread = 50;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);
    List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

    for (int t = 0; t < threadCount; t++) {
      final int threadId = t;
      executor.submit(
          () -> {
            try {
              for (int i = 0; i < callsPerThread; i++) {
                try {
                  breaker.execute(
                      () -> {
                        // Alternate success/failure
                        if (threadId % 2 == 0) {
                          return "ok";
                        } else {
                          throw new RuntimeException("fail");
                        }
                      });
                  successCount.incrementAndGet();
                } catch (CircuitBreakerOpenException e) {
                  // Expected once circuit opens
                  failureCount.incrementAndGet();
                } catch (RuntimeException e) {
                  // Expected from failing calls
                  failureCount.incrementAndGet();
                } catch (Exception e) {
                  errors.add(e);
                }
              }
            } finally {
              latch.countDown();
            }
          });
    }

    latch.await(10, TimeUnit.SECONDS);
    executor.shutdown();

    // No unexpected errors
    assertThat(errors).isEmpty();
    // Total calls should sum to expected count
    assertThat(successCount.get() + failureCount.get()).isEqualTo(threadCount * callsPerThread);
    // State should be valid (CLOSED, OPEN, or HALF_OPEN)
    CircuitBreaker.State finalState = breaker.getState();
    assertThat(finalState)
        .isAnyOf(
            CircuitBreaker.State.CLOSED, CircuitBreaker.State.OPEN, CircuitBreaker.State.HALF_OPEN);
  }

  @Test
  @DisplayName("successful calls reset failure count")
  void testSuccessResetsFailureCount() throws Exception {
    CircuitBreaker breaker =
        CircuitBreaker.builder().failureThreshold(3).openDurationMs(30000).build();

    // 2 failures (below threshold)
    for (int i = 0; i < 2; i++) {
      assertThrows(
          RuntimeException.class,
          () ->
              breaker.execute(
                  () -> {
                    throw new RuntimeException("fail");
                  }));
    }

    // Success should reset failure count
    breaker.execute(() -> "success");

    // 2 more failures should NOT trip the breaker since count was reset
    for (int i = 0; i < 2; i++) {
      assertThrows(
          RuntimeException.class,
          () ->
              breaker.execute(
                  () -> {
                    throw new RuntimeException("fail");
                  }));
    }

    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
  }

  @Test
  @DisplayName("builder defaults are sensible")
  void testBuilderDefaults() {
    CircuitBreaker breaker = CircuitBreaker.builder().build();

    assertThat(breaker.getFailureThreshold()).isEqualTo(5);
    assertThat(breaker.getOpenDurationMs()).isEqualTo(30000);
    assertThat(breaker.getHalfOpenMaxAttempts()).isEqualTo(2);
  }
}
