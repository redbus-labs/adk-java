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

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ResilientService}.
 *
 * @author Sandeep Belgavi
 * @since 2026-08-04
 */
@DisplayName("ResilientService Tests")
class ResilientServiceTest {

  @Test
  @DisplayName("combined retry + circuit breaker: retries succeed before circuit opens")
  void testCombinedRetryAndCircuitBreaker() throws Exception {
    RetryPolicy retryPolicy =
        RetryPolicy.builder().maxAttempts(3).initialDelayMs(10).backoffMultiplier(2.0).build();
    CircuitBreaker circuitBreaker =
        CircuitBreaker.builder().failureThreshold(5).openDurationMs(30000).build();

    ResilientService service =
        ResilientService.builder().retryPolicy(retryPolicy).circuitBreaker(circuitBreaker).build();

    AtomicInteger callCount = new AtomicInteger(0);

    // Fails once, succeeds on retry — circuit should stay closed
    String result =
        service.execute(
            () -> {
              int attempt = callCount.incrementAndGet();
              if (attempt == 1) {
                throw new RuntimeException("transient failure");
              }
              return "recovered";
            });

    assertThat(result).isEqualTo("recovered");
    assertThat(callCount.get()).isEqualTo(2);
    assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
  }

  @Test
  @DisplayName("circuit breaker opens after retries exhaust across multiple calls")
  void testCircuitBreakerOpensAfterRetriesExhaust() {
    RetryPolicy retryPolicy =
        RetryPolicy.builder().maxAttempts(2).initialDelayMs(10).backoffMultiplier(2.0).build();
    CircuitBreaker circuitBreaker =
        CircuitBreaker.builder().failureThreshold(3).openDurationMs(30000).build();

    ResilientService service =
        ResilientService.builder().retryPolicy(retryPolicy).circuitBreaker(circuitBreaker).build();

    // Each call to execute will exhaust retries (2 attempts each) and then fail,
    // recording 1 failure in the circuit breaker per execute() call.
    for (int i = 0; i < 3; i++) {
      assertThrows(
          RuntimeException.class,
          () ->
              service.execute(
                  () -> {
                    throw new RuntimeException("always fails");
                  }));
    }

    // Circuit should now be open
    assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

    // Further calls should be rejected immediately
    CircuitBreakerOpenException ex =
        assertThrows(
            CircuitBreakerOpenException.class, () -> service.execute(() -> "should not execute"));

    assertThat(ex).isInstanceOf(CircuitBreakerOpenException.class);
  }

  @Test
  @DisplayName("successful call with combined retry + circuit breaker")
  void testSuccessfulCallWithCombined() throws Exception {
    ResilientService service = ResilientService.builder().build();

    String result = service.execute(() -> "hello");

    assertThat(result).isEqualTo("hello");
    assertThat(service.getCircuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
  }

  @Test
  @DisplayName("async execution works with combined retry + circuit breaker")
  void testAsyncExecution() {
    RetryPolicy retryPolicy =
        RetryPolicy.builder().maxAttempts(3).initialDelayMs(10).backoffMultiplier(2.0).build();
    CircuitBreaker circuitBreaker =
        CircuitBreaker.builder().failureThreshold(5).openDurationMs(30000).build();

    ResilientService service =
        ResilientService.builder().retryPolicy(retryPolicy).circuitBreaker(circuitBreaker).build();

    AtomicInteger callCount = new AtomicInteger(0);

    String result =
        service
            .executeAsync(
                () -> {
                  int attempt = callCount.incrementAndGet();
                  if (attempt == 1) {
                    throw new RuntimeException("transient");
                  }
                  return "async-recovered";
                })
            .blockingGet();

    assertThat(result).isEqualTo("async-recovered");
    assertThat(callCount.get()).isEqualTo(2);
  }
}
