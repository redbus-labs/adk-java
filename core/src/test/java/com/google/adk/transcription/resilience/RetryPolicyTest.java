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

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RetryPolicy}.
 *
 * @author Sandeep Belgavi
 * @since 2026-08-04
 */
@DisplayName("RetryPolicy Tests")
class RetryPolicyTest {

  @Test
  @DisplayName("successful call returns immediately without retry")
  void testSuccessfulCallNoRetry() throws Exception {
    RetryPolicy policy =
        RetryPolicy.builder().maxAttempts(3).initialDelayMs(100).backoffMultiplier(2.0).build();

    AtomicInteger callCount = new AtomicInteger(0);
    String result =
        policy.execute(
            () -> {
              callCount.incrementAndGet();
              return "success";
            });

    assertThat(result).isEqualTo("success");
    assertThat(callCount.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("retry on transient failure succeeds on 2nd attempt")
  void testRetryOnTransientFailure() throws Exception {
    RetryPolicy policy =
        RetryPolicy.builder().maxAttempts(3).initialDelayMs(10).backoffMultiplier(2.0).build();

    AtomicInteger callCount = new AtomicInteger(0);
    String result =
        policy.execute(
            () -> {
              int attempt = callCount.incrementAndGet();
              if (attempt == 1) {
                throw new RuntimeException("transient failure");
              }
              return "recovered";
            });

    assertThat(result).isEqualTo("recovered");
    assertThat(callCount.get()).isEqualTo(2);
  }

  @Test
  @DisplayName("max retries exhausted throws the last exception")
  void testMaxRetriesExhausted() {
    RetryPolicy policy =
        RetryPolicy.builder().maxAttempts(3).initialDelayMs(10).backoffMultiplier(2.0).build();

    AtomicInteger callCount = new AtomicInteger(0);
    Callable<String> failingOp =
        () -> {
          callCount.incrementAndGet();
          throw new RuntimeException("persistent failure #" + callCount.get());
        };

    RuntimeException thrown = assertThrows(RuntimeException.class, () -> policy.execute(failingOp));

    assertThat(thrown.getMessage()).contains("persistent failure #3");
    assertThat(callCount.get()).isEqualTo(3);
  }

  @Test
  @DisplayName("exponential backoff timing: delay increases exponentially")
  void testExponentialBackoffTiming() {
    RetryPolicy policy =
        RetryPolicy.builder()
            .maxAttempts(5)
            .initialDelayMs(100)
            .backoffMultiplier(2.0)
            .maxDelayMs(5000)
            .build();

    // Verify delay calculation follows exponential pattern
    long delay1 = policy.calculateDelay(1); // 100 * 2^0 = 100
    long delay2 = policy.calculateDelay(2); // 100 * 2^1 = 200
    long delay3 = policy.calculateDelay(3); // 100 * 2^2 = 400
    long delay4 = policy.calculateDelay(4); // 100 * 2^3 = 800

    assertThat(delay1).isEqualTo(100);
    assertThat(delay2).isEqualTo(200);
    assertThat(delay3).isEqualTo(400);
    assertThat(delay4).isEqualTo(800);

    // Verify delay is capped at maxDelayMs
    RetryPolicy cappedPolicy =
        RetryPolicy.builder()
            .maxAttempts(5)
            .initialDelayMs(1000)
            .backoffMultiplier(3.0)
            .maxDelayMs(5000)
            .build();

    long delayAttempt4 = cappedPolicy.calculateDelay(4); // 1000 * 3^3 = 27000, capped at 5000
    assertThat(delayAttempt4).isEqualTo(5000);
  }

  @Test
  @DisplayName("actual delay timing increases between attempts")
  void testActualDelayIncreases() throws Exception {
    RetryPolicy policy =
        RetryPolicy.builder()
            .maxAttempts(3)
            .initialDelayMs(50)
            .backoffMultiplier(2.0)
            .maxDelayMs(5000)
            .build();

    AtomicInteger callCount = new AtomicInteger(0);
    long startTime = System.currentTimeMillis();

    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                policy.execute(
                    () -> {
                      callCount.incrementAndGet();
                      throw new RuntimeException("fail");
                    }));

    long elapsed = System.currentTimeMillis() - startTime;

    // Should have waited at least ~50ms (delay1) + ~100ms (delay2) = ~150ms total
    assertThat(elapsed).isAtLeast(100L); // generous lower bound for CI
    assertThat(callCount.get()).isEqualTo(3);
  }

  @Test
  @DisplayName("interrupted during backoff throws exception with InterruptedException cause")
  void testInterruptedDuringBackoff() {
    RetryPolicy policy =
        RetryPolicy.builder()
            .maxAttempts(3)
            .initialDelayMs(5000) // long delay so we can interrupt
            .backoffMultiplier(2.0)
            .build();

    AtomicInteger callCount = new AtomicInteger(0);

    Thread testThread = Thread.currentThread();

    // Schedule interrupt after a short delay
    Thread interrupter =
        new Thread(
            () -> {
              try {
                Thread.sleep(100);
              } catch (InterruptedException e) {
                // ignore
              }
              testThread.interrupt();
            });
    interrupter.start();

    Exception thrown =
        assertThrows(
            Exception.class,
            () ->
                policy.execute(
                    () -> {
                      callCount.incrementAndGet();
                      throw new RuntimeException("transient");
                    }));

    assertThat(thrown.getMessage()).contains("Retry interrupted");
    assertThat(callCount.get()).isEqualTo(1);

    // Clear interrupted status
    Thread.interrupted();
  }

  @Test
  @DisplayName("builder defaults are sensible")
  void testBuilderDefaults() {
    RetryPolicy policy = RetryPolicy.builder().build();

    assertThat(policy.getMaxAttempts()).isEqualTo(3);
    assertThat(policy.getInitialDelayMs()).isEqualTo(500);
    assertThat(policy.getBackoffMultiplier()).isWithin(0.001).of(2.0);
    assertThat(policy.getMaxDelayMs()).isEqualTo(5000);
  }

  @Test
  @DisplayName("builder validates maxAttempts < 1")
  void testBuilderValidatesMaxAttempts() {
    assertThrows(
        IllegalArgumentException.class, () -> RetryPolicy.builder().maxAttempts(0).build());
  }

  @Test
  @DisplayName("builder validates negative initialDelayMs")
  void testBuilderValidatesNegativeDelay() {
    assertThrows(
        IllegalArgumentException.class, () -> RetryPolicy.builder().initialDelayMs(-1).build());
  }

  @Test
  @DisplayName("builder validates backoffMultiplier < 1.0")
  void testBuilderValidatesBackoffMultiplier() {
    assertThrows(
        IllegalArgumentException.class, () -> RetryPolicy.builder().backoffMultiplier(0.5).build());
  }
}
