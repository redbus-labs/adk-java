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

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Normalized, provider-agnostic description of a single failed LLM attempt.
 *
 * <p>This is the unit recorded by {@link LlmCallMetrics} and inspected by {@link FailoverLlm} to
 * decide whether to fail over. It captures the HTTP status code where available, a provider-native
 * code/status string, the normalized {@link LlmFailureCategory}, a human readable message, and the
 * underlying cause.
 */
public final class LlmError {

  private final String modelName;
  @Nullable private final String provider;
  private final int httpStatus;
  @Nullable private final String providerCode;
  private final LlmFailureCategory category;
  private final String message;
  @Nullable private final Throwable cause;

  private LlmError(
      String modelName,
      @Nullable String provider,
      int httpStatus,
      @Nullable String providerCode,
      LlmFailureCategory category,
      String message,
      @Nullable Throwable cause) {
    this.modelName = modelName;
    this.provider = provider;
    this.httpStatus = httpStatus;
    this.providerCode = providerCode;
    this.category = category;
    this.message = message;
    this.cause = cause;
  }

  public static Builder builder(String modelName) {
    return new Builder(modelName);
  }

  /** The model name this failure is attributed to. */
  public String modelName() {
    return modelName;
  }

  /** Best-effort provider identifier (e.g. "gemini", "azure", "bedrock"). */
  public Optional<String> provider() {
    return Optional.ofNullable(provider);
  }

  /** HTTP status code, or {@code -1} if not applicable / unknown. */
  public int httpStatus() {
    return httpStatus;
  }

  /** Provider-native error code or status string (e.g. "RESOURCE_EXHAUSTED"). */
  public Optional<String> providerCode() {
    return Optional.ofNullable(providerCode);
  }

  /** Normalized failure category. */
  public LlmFailureCategory category() {
    return category;
  }

  /** Human readable failure message. */
  public String message() {
    return message;
  }

  /** Underlying throwable, if this error originated from an exception. */
  public Optional<Throwable> cause() {
    return Optional.ofNullable(cause);
  }

  /** Whether failing over to another model is sensible for this error, per its category. */
  public boolean isRetryable() {
    return category.isRetryableByDefault();
  }

  @Override
  public String toString() {
    return "LlmError{model="
        + modelName
        + ", provider="
        + provider
        + ", httpStatus="
        + httpStatus
        + ", providerCode="
        + providerCode
        + ", category="
        + category
        + ", message="
        + message
        + '}';
  }

  /** Mutable builder for {@link LlmError}. */
  public static final class Builder {
    private final String modelName;
    @Nullable private String provider;
    private int httpStatus = -1;
    @Nullable private String providerCode;
    private LlmFailureCategory category = LlmFailureCategory.UNKNOWN;
    private String message = "";
    @Nullable private Throwable cause;

    private Builder(String modelName) {
      this.modelName = modelName;
    }

    public Builder provider(@Nullable String provider) {
      this.provider = provider;
      return this;
    }

    public Builder httpStatus(int httpStatus) {
      this.httpStatus = httpStatus;
      return this;
    }

    public Builder providerCode(@Nullable String providerCode) {
      this.providerCode = providerCode;
      return this;
    }

    public Builder category(LlmFailureCategory category) {
      this.category = category;
      return this;
    }

    public Builder message(@Nullable String message) {
      this.message = message == null ? "" : message;
      return this;
    }

    public Builder cause(@Nullable Throwable cause) {
      this.cause = cause;
      return this;
    }

    public LlmError build() {
      return new LlmError(modelName, provider, httpStatus, providerCode, category, message, cause);
    }
  }
}
