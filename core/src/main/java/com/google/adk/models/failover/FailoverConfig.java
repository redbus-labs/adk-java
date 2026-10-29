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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Tuning knobs for {@link FailoverLlm}. */
public final class FailoverConfig {

  private final Duration attemptTimeout;
  private final boolean treatEmptyResponseAsFailure;
  @Nullable private final Set<LlmFailureCategory> retryableCategories;
  @Nullable private final DragonflyCircuitBreaker circuitBreaker;
  private final SessionRoutingStore sessionRoutingStore;

  private FailoverConfig(Builder builder) {
    this.attemptTimeout = builder.attemptTimeout;
    this.treatEmptyResponseAsFailure = builder.treatEmptyResponseAsFailure;
    this.retryableCategories =
        builder.retryableCategories == null
            ? null
            : Collections.unmodifiableSet(EnumSet.copyOf(builder.retryableCategories));
    this.circuitBreaker = builder.circuitBreaker;
    this.sessionRoutingStore =
        builder.sessionRoutingStore == null
            ? new SessionRoutingStore()
            : builder.sessionRoutingStore;
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

  /** Returns the circuit breaker instance, if configured. */
  public Optional<DragonflyCircuitBreaker> circuitBreaker() {
    return Optional.ofNullable(circuitBreaker);
  }

  /** Shared by all wrappers using this config; applications can supply a longer-lived store. */
  public SessionRoutingStore sessionRoutingStore() {
    return sessionRoutingStore;
  }

  /** Builder for {@link FailoverConfig}. */
  public static final class Builder {
    private Duration attemptTimeout = Duration.ZERO;
    private boolean treatEmptyResponseAsFailure = true;
    @Nullable private Set<LlmFailureCategory> retryableCategories;
    @Nullable private DragonflyCircuitBreaker circuitBreaker;
    @Nullable private SessionRoutingStore sessionRoutingStore;

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

    /** Sets the ADK circuit breaker backed by application-provided Redis connections. */
    public Builder circuitBreaker(@Nullable DragonflyCircuitBreaker circuitBreaker) {
      this.circuitBreaker = circuitBreaker;
      return this;
    }

    /** Sets the application-owned store used across recreated model and agent instances. */
    public Builder sessionRoutingStore(SessionRoutingStore sessionRoutingStore) {
      this.sessionRoutingStore = Objects.requireNonNull(sessionRoutingStore, "sessionRoutingStore");
      return this;
    }

    public FailoverConfig build() {
      return new FailoverConfig(this);
    }
  }
}
