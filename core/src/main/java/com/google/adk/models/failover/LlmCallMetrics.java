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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pluggable sink for LLM failover telemetry. The platform deliberately avoids a hard dependency on
 * any specific metrics library; applications (e.g. rae) can register a {@link Recorder} that
 * bridges to Micrometer, Prometheus, Datadog, etc.
 *
 * <p>The default {@link LoggingRecorder} emits structured SLF4J logs so that provider error codes
 * are tracked out of the box even before an application wires its own metrics backend.
 */
public final class LlmCallMetrics {

  /** Receives failover lifecycle events. All methods have no-op defaults. */
  public interface Recorder {
    /** Invoked for every failed attempt, whether or not a fallback follows. */
    default void onFailure(LlmError error, int attemptIndex, String primaryModel) {}

    /** Invoked when a fallback model succeeds after the primary (or an earlier fallback) failed. */
    default void onFallbackSuccess(String succeededModel, String primaryModel, int attemptIndex) {}

    /** Invoked when every model in the chain has failed and the error is about to propagate. */
    default void onExhausted(LlmError lastError, String primaryModel, int totalAttempts) {}
  }

  private static volatile Recorder recorder = new LoggingRecorder();

  private LlmCallMetrics() {}

  /** Replaces the active recorder. Pass an application-specific implementation at startup. */
  public static void setRecorder(Recorder newRecorder) {
    recorder = newRecorder == null ? new LoggingRecorder() : newRecorder;
  }

  /** Returns the active recorder. */
  public static Recorder recorder() {
    return recorder;
  }

  static void recordFailure(LlmError error, int attemptIndex, String primaryModel) {
    try {
      recorder.onFailure(error, attemptIndex, primaryModel);
    } catch (RuntimeException e) {
      LoggingRecorder.LOGGER.warn("LlmCallMetrics recorder threw on onFailure", e);
    }
  }

  static void recordFallbackSuccess(String succeededModel, String primaryModel, int attemptIndex) {
    try {
      recorder.onFallbackSuccess(succeededModel, primaryModel, attemptIndex);
    } catch (RuntimeException e) {
      LoggingRecorder.LOGGER.warn("LlmCallMetrics recorder threw on onFallbackSuccess", e);
    }
  }

  static void recordExhausted(LlmError lastError, String primaryModel, int totalAttempts) {
    try {
      recorder.onExhausted(lastError, primaryModel, totalAttempts);
    } catch (RuntimeException e) {
      LoggingRecorder.LOGGER.warn("LlmCallMetrics recorder threw on onExhausted", e);
    }
  }

  /** Default recorder that logs structured failover telemetry via SLF4J. */
  public static final class LoggingRecorder implements Recorder {
    static final Logger LOGGER = LoggerFactory.getLogger("com.google.adk.models.failover");

    @Override
    public void onFailure(LlmError error, int attemptIndex, String primaryModel) {
      LOGGER.warn(
          "LLM attempt failed [primary={}, attemptModel={}, attemptIndex={}, provider={},"
              + " httpStatus={}, providerCode={}, category={}, retryable={}, message={}]",
          primaryModel,
          error.modelName(),
          attemptIndex,
          error.provider().orElse("unknown"),
          error.httpStatus(),
          error.providerCode().orElse("n/a"),
          error.category(),
          error.isRetryable(),
          error.message());
    }

    @Override
    public void onFallbackSuccess(String succeededModel, String primaryModel, int attemptIndex) {
      LOGGER.info(
          "LLM fallback succeeded [primary={}, succeededModel={}, attemptIndex={}]",
          primaryModel,
          succeededModel,
          attemptIndex);
    }

    @Override
    public void onExhausted(LlmError lastError, String primaryModel, int totalAttempts) {
      LOGGER.error(
          "LLM failover exhausted [primary={}, totalAttempts={}, lastCategory={}, lastHttpStatus={},"
              + " lastMessage={}]",
          primaryModel,
          totalAttempts,
          lastError.category(),
          lastError.httpStatus(),
          lastError.message());
    }
  }
}
