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

package com.google.adk.models.failover;

import java.time.Duration;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Tuning knobs for {@link FailoverLlm}. */
public final class FailoverConfig {

  private final Duration attemptTimeout;
  private final boolean treatEmptyResponseAsFailure;
  @Nullable private final Set<LlmFailureCategory> retryableCategories;

  private FailoverConfig(Builder builder) {
    this.attemptTimeout = builder.attemptTimeout;
    this.treatEmptyResponseAsFailure = builder.treatEmptyResponseAsFailure;
    this.retryableCategories =
        builder.retryableCategories == null
            ? null
            : Collections.unmodifiableSet(EnumSet.copyOf(builder.retryableCategories));
  }

  /** Default config: no timeout, empty responses treated as failures, default retryable set. */
  public static FailoverConfig defaults() {
    return builder().build();
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Per-attempt latency budget. {@link Duration#ZERO} (the default) disables timeout-based
   * failover. When set, this bounds both time-to-first-response and the gap between streamed
   * chunks; exceeding it raises a {@link java.util.concurrent.TimeoutException} which is classified
   * as {@link LlmFailureCategory#TIMEOUT} and triggers failover.
   */
  public Duration attemptTimeout() {
    return attemptTimeout;
  }

  public boolean isTimeoutEnabled() {
    return attemptTimeout != null && !attemptTimeout.isZero() && !attemptTimeout.isNegative();
  }

  /**
   * Whether an attempt that completes without emitting any response should be treated as a failure
   * and trigger failover. This catches providers (Azure/Bedrock streaming) that historically
   * completed silently on a non-2xx response.
   */
  public boolean treatEmptyResponseAsFailure() {
    return treatEmptyResponseAsFailure;
  }

  /** Decides whether a given error should trigger failover to the next model in the chain. */
  public boolean shouldFailover(LlmError error) {
    if (retryableCategories != null) {
      return retryableCategories.contains(error.category());
    }
    return error.isRetryable();
  }

  /** Builder for {@link FailoverConfig}. */
  public static final class Builder {
    private Duration attemptTimeout = Duration.ZERO;
    private boolean treatEmptyResponseAsFailure = true;
    @Nullable private Set<LlmFailureCategory> retryableCategories;

    private Builder() {}

    public Builder attemptTimeout(Duration attemptTimeout) {
      this.attemptTimeout = attemptTimeout == null ? Duration.ZERO : attemptTimeout;
      return this;
    }

    public Builder attemptTimeoutMillis(long millis) {
      this.attemptTimeout = Duration.ofMillis(Math.max(0, millis));
      return this;
    }

    public Builder treatEmptyResponseAsFailure(boolean treatEmptyResponseAsFailure) {
      this.treatEmptyResponseAsFailure = treatEmptyResponseAsFailure;
      return this;
    }

    /**
     * Overrides which categories trigger failover. When unset, each category's default ({@link
     * LlmFailureCategory#isRetryableByDefault()}) is used.
     */
    public Builder retryableCategories(@Nullable Set<LlmFailureCategory> retryableCategories) {
      this.retryableCategories = retryableCategories;
      return this;
    }

    public FailoverConfig build() {
      return new FailoverConfig(this);
    }
  }
}
