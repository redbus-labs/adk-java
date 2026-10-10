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

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A thread-safe circuit breaker implementation that protects downstream services from cascading
 * failures. The circuit breaker transitions between three states:
 *
 * <ul>
 *   <li><b>CLOSED</b> — Normal operation. Calls pass through and failures are counted.
 *   <li><b>OPEN</b> — Failure threshold exceeded. Calls are immediately rejected with {@link
 *       CircuitBreakerOpenException}.
 *   <li><b>HALF_OPEN</b> — After the open duration elapses, a limited number of test calls are
 *       allowed. If they succeed, the circuit closes. If they fail, it opens again.
 * </ul>
 *
 * <p>Usage example:
 *
 * <pre>{@code
 * CircuitBreaker breaker = CircuitBreaker.builder()
 *     .failureThreshold(5)
 *     .openDurationMs(30000)
 *     .halfOpenMaxAttempts(2)
 *     .build();
 *
 * String result = breaker.execute(() -> callRemoteService());
 * }</pre>
 *
 * @author Sandeep Belgavi
 * @since 2026-08-04
 */
public class CircuitBreaker {

  private static final Logger logger = LoggerFactory.getLogger(CircuitBreaker.class);

  /** Represents the state of the circuit breaker. */
  public enum State {
    CLOSED,
    OPEN,
    HALF_OPEN
  }

  private final int failureThreshold;
  private final long openDurationMs;
  private final int halfOpenMaxAttempts;

  private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
  private final AtomicInteger failureCount = new AtomicInteger(0);
  private final AtomicLong lastFailureTime = new AtomicLong(0);
  private final AtomicInteger halfOpenAttempts = new AtomicInteger(0);
  private final AtomicInteger halfOpenSuccesses = new AtomicInteger(0);

  private CircuitBreaker(Builder builder) {
    this.failureThreshold = builder.failureThreshold;
    this.openDurationMs = builder.openDurationMs;
    this.halfOpenMaxAttempts = builder.halfOpenMaxAttempts;
  }

  /**
   * Executes the given operation through the circuit breaker. If the circuit is OPEN, the call is
   * rejected immediately. If HALF_OPEN, limited test calls are allowed.
   *
   * @param <T> the return type of the operation
   * @param operation the operation to execute
   * @return the result of the operation
   * @throws Exception if the operation fails or the circuit is open
   */
  public <T> T execute(Callable<T> operation) throws Exception {
    State currentState = evaluateState();

    switch (currentState) {
      case OPEN:
        logger.debug("Circuit breaker is OPEN, rejecting call");
        throw new CircuitBreakerOpenException();

      case HALF_OPEN:
        return executeInHalfOpen(operation);

      case CLOSED:
      default:
        return executeInClosed(operation);
    }
  }

  /**
   * Returns the current state of the circuit breaker after evaluating time-based transitions.
   *
   * @return the current circuit breaker state
   */
  public State getState() {
    return evaluateState();
  }

  /** Resets the circuit breaker to its initial CLOSED state. */
  public void reset() {
    state.set(State.CLOSED);
    failureCount.set(0);
    lastFailureTime.set(0);
    halfOpenAttempts.set(0);
    halfOpenSuccesses.set(0);
    logger.info("Circuit breaker has been reset to CLOSED state");
  }

  /** Returns the configured failure threshold. */
  public int getFailureThreshold() {
    return failureThreshold;
  }

  /** Returns the configured open duration in milliseconds. */
  public long getOpenDurationMs() {
    return openDurationMs;
  }

  /** Returns the configured maximum number of half-open attempts. */
  public int getHalfOpenMaxAttempts() {
    return halfOpenMaxAttempts;
  }

  /**
   * Creates a new builder for CircuitBreaker.
   *
   * @return a new Builder instance
   */
  public static Builder builder() {
    return new Builder();
  }

  // ---- Private methods ----

  private State evaluateState() {
    State current = state.get();
    if (current == State.OPEN) {
      long elapsed = System.currentTimeMillis() - lastFailureTime.get();
      if (elapsed >= openDurationMs) {
        if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
          halfOpenAttempts.set(0);
          halfOpenSuccesses.set(0);
          logger.info("Circuit breaker transitioning from OPEN to HALF_OPEN after {}ms", elapsed);
        }
        return State.HALF_OPEN;
      }
    }
    return state.get();
  }

  private <T> T executeInClosed(Callable<T> operation) throws Exception {
    try {
      T result = operation.call();
      onSuccess();
      return result;
    } catch (Exception e) {
      onFailure();
      throw e;
    }
  }

  private <T> T executeInHalfOpen(Callable<T> operation) throws Exception {
    int attempts = halfOpenAttempts.incrementAndGet();
    if (attempts > halfOpenMaxAttempts) {
      logger.debug("Circuit breaker HALF_OPEN: max test attempts reached, rejecting call");
      throw new CircuitBreakerOpenException(
          "Circuit breaker is HALF_OPEN but max test attempts reached");
    }

    try {
      T result = operation.call();
      int successes = halfOpenSuccesses.incrementAndGet();
      logger.debug(
          "Circuit breaker HALF_OPEN: test call succeeded ({}/{})", successes, halfOpenMaxAttempts);
      if (successes >= halfOpenMaxAttempts) {
        transitionToClosed();
      }
      return result;
    } catch (Exception e) {
      logger.warn("Circuit breaker HALF_OPEN: test call failed, transitioning back to OPEN");
      transitionToOpen();
      throw e;
    }
  }

  private void onSuccess() {
    failureCount.set(0);
  }

  private void onFailure() {
    int failures = failureCount.incrementAndGet();
    lastFailureTime.set(System.currentTimeMillis());
    logger.debug("Circuit breaker failure count: {}/{}", failures, failureThreshold);

    if (failures >= failureThreshold) {
      transitionToOpen();
    }
  }

  private void transitionToOpen() {
    State previous = state.getAndSet(State.OPEN);
    lastFailureTime.set(System.currentTimeMillis());
    if (previous != State.OPEN) {
      logger.warn(
          "Circuit breaker transitioning to OPEN (failures: {}/{})",
          failureCount.get(),
          failureThreshold);
    }
  }

  private void transitionToClosed() {
    state.set(State.CLOSED);
    failureCount.set(0);
    halfOpenAttempts.set(0);
    halfOpenSuccesses.set(0);
    logger.info("Circuit breaker transitioning to CLOSED — service recovered");
  }

  /** Builder for {@link CircuitBreaker}. */
  public static class Builder {
    private int failureThreshold = 5;
    private long openDurationMs = 30000;
    private int halfOpenMaxAttempts = 2;

    /**
     * Sets the number of consecutive failures before the circuit opens.
     *
     * @param failureThreshold failure count threshold, must be at least 1
     * @return this builder
     */
    public Builder failureThreshold(int failureThreshold) {
      if (failureThreshold < 1) {
        throw new IllegalArgumentException("failureThreshold must be at least 1");
      }
      this.failureThreshold = failureThreshold;
      return this;
    }

    /**
     * Sets the duration the circuit remains open before transitioning to half-open.
     *
     * @param openDurationMs duration in milliseconds, must be positive
     * @return this builder
     */
    public Builder openDurationMs(long openDurationMs) {
      if (openDurationMs <= 0) {
        throw new IllegalArgumentException("openDurationMs must be positive");
      }
      this.openDurationMs = openDurationMs;
      return this;
    }

    /**
     * Sets the maximum number of test calls allowed in the HALF_OPEN state.
     *
     * @param halfOpenMaxAttempts max attempts, must be at least 1
     * @return this builder
     */
    public Builder halfOpenMaxAttempts(int halfOpenMaxAttempts) {
      if (halfOpenMaxAttempts < 1) {
        throw new IllegalArgumentException("halfOpenMaxAttempts must be at least 1");
      }
      this.halfOpenMaxAttempts = halfOpenMaxAttempts;
      return this;
    }

    /**
     * Builds the CircuitBreaker.
     *
     * @return a new CircuitBreaker instance
     */
    public CircuitBreaker build() {
      return new CircuitBreaker(this);
    }
  }
}
