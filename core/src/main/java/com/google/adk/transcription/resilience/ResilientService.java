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

import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composes a {@link RetryPolicy} and a {@link CircuitBreaker} into a single resilient execution
 * wrapper. The circuit breaker wraps the retry policy, which wraps the actual operation:
 *
 * <pre>
 *   CircuitBreaker → RetryPolicy → Operation
 * </pre>
 *
 * <p>This ensures that:
 *
 * <ul>
 *   <li>Transient failures are retried within the retry policy's limits.
 *   <li>Persistent failures trip the circuit breaker to prevent cascading failures.
 *   <li>When the circuit is open, calls are rejected immediately without retries.
 * </ul>
 *
 * <p>Usage example:
 *
 * <pre>{@code
 * ResilientService resilient = ResilientService.builder()
 *     .retryPolicy(RetryPolicy.builder().maxAttempts(3).build())
 *     .circuitBreaker(CircuitBreaker.builder().failureThreshold(5).build())
 *     .build();
 *
 * byte[] result = resilient.execute(() -> callRemoteService());
 * }</pre>
 *
 * @author Sandeep Belgavi
 * @since 2026-08-04
 */
public class ResilientService {

  private static final Logger logger = LoggerFactory.getLogger(ResilientService.class);

  private final RetryPolicy retryPolicy;
  private final CircuitBreaker circuitBreaker;

  private ResilientService(Builder builder) {
    this.retryPolicy = builder.retryPolicy;
    this.circuitBreaker = builder.circuitBreaker;
  }

  /**
   * Executes the given operation with circuit breaker protection and retry logic. The circuit
   * breaker evaluates whether the call should proceed, and if so, the retry policy handles
   * transient failures.
   *
   * @param <T> the return type of the operation
   * @param operation the operation to execute
   * @return the result of the operation
   * @throws Exception if the circuit is open or all retries are exhausted
   */
  public <T> T execute(Callable<T> operation) throws Exception {
    return circuitBreaker.execute(() -> retryPolicy.execute(operation));
  }

  /**
   * Executes the given operation asynchronously with circuit breaker protection and retry logic,
   * wrapped in an RxJava Single.
   *
   * @param <T> the return type of the operation
   * @param operation the operation to execute
   * @return a Single that emits the result or an error
   */
  public <T> Single<T> executeAsync(Callable<T> operation) {
    return Single.fromCallable(() -> execute(operation)).subscribeOn(Schedulers.io());
  }

  /** Returns the configured retry policy. */
  public RetryPolicy getRetryPolicy() {
    return retryPolicy;
  }

  /** Returns the configured circuit breaker. */
  public CircuitBreaker getCircuitBreaker() {
    return circuitBreaker;
  }

  /**
   * Creates a new builder for ResilientService.
   *
   * @return a new Builder instance
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder for {@link ResilientService}. */
  public static class Builder {
    private RetryPolicy retryPolicy = RetryPolicy.builder().build();
    private CircuitBreaker circuitBreaker = CircuitBreaker.builder().build();

    /**
     * Sets the retry policy.
     *
     * @param retryPolicy the retry policy to use
     * @return this builder
     */
    public Builder retryPolicy(RetryPolicy retryPolicy) {
      if (retryPolicy == null) {
        throw new IllegalArgumentException("retryPolicy must not be null");
      }
      this.retryPolicy = retryPolicy;
      return this;
    }

    /**
     * Sets the circuit breaker.
     *
     * @param circuitBreaker the circuit breaker to use
     * @return this builder
     */
    public Builder circuitBreaker(CircuitBreaker circuitBreaker) {
      if (circuitBreaker == null) {
        throw new IllegalArgumentException("circuitBreaker must not be null");
      }
      this.circuitBreaker = circuitBreaker;
      return this;
    }

    /**
     * Builds the ResilientService.
     *
     * @return a new ResilientService instance
     */
    public ResilientService build() {
      return new ResilientService(this);
    }
  }
}
