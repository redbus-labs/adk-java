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

/**
 * Normalized classification of an LLM call failure across heterogeneous providers (Gemini/genai,
 * Azure, Bedrock, Claude, Ollama, etc.).
 *
 * <p>The {@code retryableByDefault} flag expresses whether a failure of this category is, in
 * general, worth failing over to another model. Transient/availability failures are retryable;
 * deterministic client-side failures (bad request, auth, content blocked) are not, because retrying
 * the same request against a different model would almost certainly fail the same way.
 */
public enum LlmFailureCategory {
  /** HTTP 429 / quota exhausted. Worth failing over to a different model or provider. */
  RATE_LIMIT(true),

  /** HTTP 503 / provider explicitly unavailable or overloaded. */
  UNAVAILABLE(true),

  /** HTTP 5xx (500, 502, 504, ...) server-side error. */
  SERVER_ERROR(true),

  /** Call exceeded the configured latency budget (RxJava {@code TimeoutException}). */
  TIMEOUT(true),

  /** Connection reset, DNS, socket errors, or other transport-level IO failures. */
  NETWORK(true),

  /** HTTP 401 / 403 authentication or authorization failure. */
  AUTH(false),

  /** HTTP 400 / 404 / 422 and similar deterministic client errors. */
  BAD_REQUEST(false),

  /** Prompt or response blocked by safety / recitation / content filters. */
  CONTENT_BLOCKED(false),

  /** Failure that could not be classified into any of the above. */
  UNKNOWN(true);

  private final boolean retryableByDefault;

  LlmFailureCategory(boolean retryableByDefault) {
    this.retryableByDefault = retryableByDefault;
  }

  /**
   * Whether failing over to another model is sensible for this category by default. Callers may
   * override this decision via configuration.
   */
  public boolean isRetryableByDefault() {
    return retryableByDefault;
  }
}
