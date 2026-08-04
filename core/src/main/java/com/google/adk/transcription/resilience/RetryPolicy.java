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
 * Configurable retry policy with exponential backoff. Provides both synchronous and asynchronous
 * (RxJava-based) retry execution for transient failure recovery.
 *
 * <p>Usage example:
 *
 * <pre>{@code
 * RetryPolicy policy = RetryPolicy.builder()
 *     .maxAttempts(5)
 *     .initialDelayMs(1000)
 *     .backoffMultiplier(2.0)
 *     .maxDelayMs(10000)
 *     .build();
 *
 * String result = policy.execute(() -> callRemoteService());
 * }</pre>
 *
 * @author Sandeep Belgavi
 * @since 2026-08-04
 */
public class RetryPolicy {

  private static final Logger logger = LoggerFactory.getLogger(RetryPolicy.class);

  private final int maxAttempts;
  private final long initialDelayMs;
  private final double backoffMultiplier;
  private final long maxDelayMs;

  private RetryPolicy(Builder builder) {
    this.maxAttempts = builder.maxAttempts;
    this.initialDelayMs = builder.initialDelayMs;
    this.backoffMultiplier = builder.backoffMultiplier;
    this.maxDelayMs = builder.maxDelayMs;
  }

  /**
   * Executes the given operation with retry logic and exponential backoff.
   *
   * @param <T> the return type of the operation
   * @param operation the operation to execute
   * @return the result of the operation
   * @throws Exception if all retry attempts are exhausted
   */
  public <T> T execute(Callable<T> operation) throws Exception {
    Exception lastException = null;

    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        return operation.call();
      } catch (Exception e) {
        lastException = e;
        if (attempt < maxAttempts) {
          long delay = calculateDelay(attempt);
          logger.warn(
              "Retry attempt {}/{} failed, retrying in {}ms. Error: {}",
              attempt,
              maxAttempts,
              delay,
              e.getMessage());
          try {
            Thread.sleep(delay);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new Exception("Retry interrupted", ie);
          }
        } else {
          logger.error(
              "All {} retry attempts exhausted. Last error: {}", maxAttempts, e.getMessage());
        }
      }
    }

    throw lastException;
  }

  /**
   * Executes the given operation asynchronously with retry logic, wrapped in an RxJava Single.
   *
   * @param <T> the return type of the operation
   * @param operation the operation to execute
   * @return a Single that emits the result or an error after all retries are exhausted
   */
  public <T> Single<T> executeAsync(Callable<T> operation) {
    return Single.fromCallable(() -> execute(operation)).subscribeOn(Schedulers.io());
  }

  /**
   * Calculates the delay for the given attempt using exponential backoff.
   *
   * @param attempt the current attempt number (1-based)
   * @return the delay in milliseconds, capped at maxDelayMs
   */
  long calculateDelay(int attempt) {
    long delay = (long) (initialDelayMs * Math.pow(backoffMultiplier, attempt - 1));
    return Math.min(delay, maxDelayMs);
  }

  /** Returns the maximum number of attempts. */
  public int getMaxAttempts() {
    return maxAttempts;
  }

  /** Returns the initial delay in milliseconds. */
  public long getInitialDelayMs() {
    return initialDelayMs;
  }

  /** Returns the backoff multiplier. */
  public double getBackoffMultiplier() {
    return backoffMultiplier;
  }

  /** Returns the maximum delay in milliseconds. */
  public long getMaxDelayMs() {
    return maxDelayMs;
  }

  /**
   * Creates a new builder for RetryPolicy.
   *
   * @return a new Builder instance
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder for {@link RetryPolicy}. */
  public static class Builder {
    private int maxAttempts = 3;
    private long initialDelayMs = 500;
    private double backoffMultiplier = 2.0;
    private long maxDelayMs = 5000;

    /**
     * Sets the maximum number of attempts (including the initial attempt).
     *
     * @param maxAttempts maximum attempts, must be at least 1
     * @return this builder
     */
    public Builder maxAttempts(int maxAttempts) {
      if (maxAttempts < 1) {
        throw new IllegalArgumentException("maxAttempts must be at least 1");
      }
      this.maxAttempts = maxAttempts;
      return this;
    }

    /**
     * Sets the initial delay before the first retry.
     *
     * @param initialDelayMs initial delay in milliseconds, must be non-negative
     * @return this builder
     */
    public Builder initialDelayMs(long initialDelayMs) {
      if (initialDelayMs < 0) {
        throw new IllegalArgumentException("initialDelayMs must be non-negative");
      }
      this.initialDelayMs = initialDelayMs;
      return this;
    }

    /**
     * Sets the backoff multiplier applied to the delay after each retry.
     *
     * @param backoffMultiplier multiplier, must be at least 1.0
     * @return this builder
     */
    public Builder backoffMultiplier(double backoffMultiplier) {
      if (backoffMultiplier < 1.0) {
        throw new IllegalArgumentException("backoffMultiplier must be at least 1.0");
      }
      this.backoffMultiplier = backoffMultiplier;
      return this;
    }

    /**
     * Sets the maximum delay between retries.
     *
     * @param maxDelayMs maximum delay in milliseconds, must be non-negative
     * @return this builder
     */
    public Builder maxDelayMs(long maxDelayMs) {
      if (maxDelayMs < 0) {
        throw new IllegalArgumentException("maxDelayMs must be non-negative");
      }
      this.maxDelayMs = maxDelayMs;
      return this;
    }

    /**
     * Builds the RetryPolicy.
     *
     * @return a new RetryPolicy instance
     */
    public RetryPolicy build() {
      return new RetryPolicy(this);
    }
  }
}
