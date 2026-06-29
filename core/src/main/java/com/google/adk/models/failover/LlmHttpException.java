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
 * Typed exception carrying an HTTP status code for providers that otherwise swallow or stringify
 * transport failures (e.g. the Azure and Bedrock REST transports).
 *
 * <p>Surfacing the status code as a first-class field lets {@link LlmErrorClassifier} reliably bin
 * to a {@link LlmFailureCategory} so that {@link FailoverLlm} can decide whether to fail over.
 */
public class LlmHttpException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final int statusCode;
  private final String responseBody;

  public LlmHttpException(int statusCode, String message, String responseBody) {
    super("HTTP " + statusCode + (message != null ? ": " + message : ""));
    this.statusCode = statusCode;
    this.responseBody = responseBody;
  }

  public LlmHttpException(int statusCode, String message, String responseBody, Throwable cause) {
    super("HTTP " + statusCode + (message != null ? ": " + message : ""), cause);
    this.statusCode = statusCode;
    this.responseBody = responseBody;
  }

  /** The HTTP status code returned by the provider, or {@code -1} if unknown. */
  public int statusCode() {
    return statusCode;
  }

  /** The raw response body, if captured. May be {@code null}. */
  public String responseBody() {
    return responseBody;
  }
}
