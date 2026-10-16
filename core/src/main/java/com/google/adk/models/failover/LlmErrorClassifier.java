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

import com.google.adk.models.LlmResponse;
import com.google.genai.errors.ApiException;
import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes the heterogeneous ways adk-java model implementations report failures into a single
 * {@link LlmError}.
 *
 * <p>Failures arrive in three different shapes across providers:
 *
 * <ul>
 *   <li>Thrown exceptions on the {@code Flowable} (genai {@link ApiException} with an HTTP code,
 *       {@link LlmHttpException} from the Azure/Bedrock transports, {@link IOException}, generic
 *       {@link IllegalStateException} with the status embedded in the message, RxJava {@link
 *       TimeoutException}).
 *   <li>In-band "successful" emissions where {@link LlmResponse#errorCode()} is set (the genai path
 *       emits these instead of throwing).
 *   <li>Silent empty completion (handled by {@link FailoverLlm}, not here).
 * </ul>
 */
public final class LlmErrorClassifier {

  // Matches "status=429", "status: 503", "code 500", "HTTP 429", "(429)" etc.
  private static final Pattern STATUS_PATTERN =
      Pattern.compile("(?:status|code|http)[^0-9]{0,3}(\\d{3})", Pattern.CASE_INSENSITIVE);
  private static final Pattern BARE_STATUS_PATTERN = Pattern.compile("\\b([45]\\d{2})\\b");

  private LlmErrorClassifier() {}

  /** Classifies a throwable raised by a model call into a normalized {@link LlmError}. */
  public static LlmError classify(String modelName, Throwable throwable) {
    Throwable t = unwrap(throwable);
    LlmError.Builder builder =
        LlmError.builder(modelName).cause(t).message(messageOf(t)).provider(providerOf(modelName));

    if (t instanceof TimeoutException) {
      return builder.category(LlmFailureCategory.TIMEOUT).build();
    }

    if (t instanceof LlmHttpException) {
      int status = ((LlmHttpException) t).statusCode();
      return builder.httpStatus(status).category(categoryForStatus(status)).build();
    }

    if (t instanceof ApiException) {
      ApiException api = (ApiException) t;
      return builder
          .httpStatus(api.code())
          .providerCode(api.status())
          .category(categoryForStatus(api.code()))
          .build();
    }

    // genai wraps connectivity issues in GenAiIOException; detect by type hierarchy / name.
    if (t instanceof IOException || isGenAiIoException(t)) {
      Optional<Integer> status = extractStatus(messageOf(t));
      if (status.isPresent()) {
        return builder.httpStatus(status.get()).category(categoryForStatus(status.get())).build();
      }
      return builder.category(LlmFailureCategory.NETWORK).build();
    }

    // Azure/Bedrock legacy paths throw IllegalStateException with the status in the message.
    Optional<Integer> status = extractStatus(messageOf(t));
    if (status.isPresent()) {
      return builder.httpStatus(status.get()).category(categoryForStatus(status.get())).build();
    }

    return builder.category(LlmFailureCategory.UNKNOWN).build();
  }

  /**
   * Classifies an in-band error carried on a "successful" {@link LlmResponse} (i.e. {@link
   * LlmResponse#errorCode()} is present). Returns empty if the response is not an error.
   */
  public static Optional<LlmError> classifyInBand(String modelName, LlmResponse response) {
    if (response.errorCode().isEmpty()) {
      return Optional.empty();
    }
    String code = response.errorCode().get().toString();
    String msg = response.errorMessage().orElse(code);
    LlmFailureCategory category = categoryForFinishReason(code);
    return Optional.of(
        LlmError.builder(modelName)
            .provider(providerOf(modelName))
            .providerCode(code)
            .category(category)
            .message(msg)
            .build());
  }

  /** Maps an HTTP status code to a normalized failure category. */
  public static LlmFailureCategory categoryForStatus(int status) {
    if (status == 429) {
      return LlmFailureCategory.RATE_LIMIT;
    }
    if (status == 503) {
      return LlmFailureCategory.UNAVAILABLE;
    }
    if (status == 408) {
      return LlmFailureCategory.TIMEOUT;
    }
    if (status == 401 || status == 403) {
      return LlmFailureCategory.AUTH;
    }
    if (status >= 500 && status < 600) {
      return LlmFailureCategory.SERVER_ERROR;
    }
    if (status >= 400 && status < 500) {
      return LlmFailureCategory.BAD_REQUEST;
    }
    return LlmFailureCategory.UNKNOWN;
  }

  private static LlmFailureCategory categoryForFinishReason(String reason) {
    String upper = reason.toUpperCase(Locale.ROOT);
    if (upper.contains("SAFETY")
        || upper.contains("BLOCK")
        || upper.contains("RECITATION")
        || upper.contains("PROHIBITED")
        || upper.contains("SPII")) {
      return LlmFailureCategory.CONTENT_BLOCKED;
    }
    return LlmFailureCategory.UNKNOWN;
  }

  private static Optional<Integer> extractStatus(String message) {
    if (message == null || message.isEmpty()) {
      return Optional.empty();
    }
    Matcher m = STATUS_PATTERN.matcher(message);
    if (m.find()) {
      return Optional.of(Integer.parseInt(m.group(1)));
    }
    Matcher bare = BARE_STATUS_PATTERN.matcher(message);
    if (bare.find()) {
      return Optional.of(Integer.parseInt(bare.group(1)));
    }
    return Optional.empty();
  }

  private static Throwable unwrap(Throwable t) {
    Throwable current = t;
    while ((current instanceof CompletionException || current instanceof ExecutionException)
        && current.getCause() != null
        && current.getCause() != current) {
      current = current.getCause();
    }
    return current;
  }

  private static boolean isGenAiIoException(Throwable t) {
    return t.getClass().getName().equals("com.google.genai.errors.GenAiIOException");
  }

  private static String messageOf(Throwable t) {
    String msg = t.getMessage();
    return msg == null ? t.getClass().getSimpleName() : msg;
  }

  private static String providerOf(String modelName) {
    if (modelName == null) {
      return null;
    }
    String lower = modelName.toLowerCase(Locale.ROOT);
    if (lower.startsWith("gemini") || lower.startsWith("gemma")) {
      return "google";
    }
    if (lower.startsWith("azure") || lower.contains("realtime")) {
      return "azure";
    }
    if (lower.startsWith("bedrock") || lower.contains("anthropic") || lower.contains("claude")) {
      return "bedrock";
    }
    if (lower.startsWith("redbusadg")) {
      return "redbusadg";
    }
    if (lower.startsWith("ollama")) {
      return "ollama";
    }
    if (lower.startsWith("apigee")) {
      return "apigee";
    }
    if (lower.startsWith("gpt-oss")) {
      return "gpt-oss";
    }
    return null;
  }
}
