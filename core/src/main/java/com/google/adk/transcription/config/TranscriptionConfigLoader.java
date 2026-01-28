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

package com.google.adk.transcription.config;

import com.google.adk.transcription.AudioFormat;
import com.google.adk.transcription.TranscriptionConfig;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads transcription configuration from environment variables. Follows 12-Factor App principles.
 *
 * <p>Transcription is an optional feature. If ADK_TRANSCRIPTION_ENDPOINT is not set, this returns
 * Optional.empty(), allowing the framework to work without transcription.
 *
 * @author Sandeep Belgavi
 * @since 2026-01-24
 */
public class TranscriptionConfigLoader {
  private static final Logger logger = LoggerFactory.getLogger(TranscriptionConfigLoader.class);

  // Environment variable names
  private static final String ENDPOINT_ENV = "ADK_TRANSCRIPTION_ENDPOINT";
  private static final String API_KEY_ENV = "ADK_TRANSCRIPTION_API_KEY";
  private static final String LANGUAGE_ENV = "ADK_TRANSCRIPTION_LANGUAGE";
  private static final String TIMEOUT_ENV = "ADK_TRANSCRIPTION_TIMEOUT_SECONDS";
  private static final String MAX_RETRIES_ENV = "ADK_TRANSCRIPTION_MAX_RETRIES";
  private static final String SERVICE_TYPE_ENV = "ADK_TRANSCRIPTION_SERVICE_TYPE";
  private static final String CHUNK_SIZE_ENV = "ADK_TRANSCRIPTION_CHUNK_SIZE_MS";

  /**
   * Loads configuration from environment variables. Returns Optional.empty() if transcription is
   * not configured (optional feature).
   *
   * @return Optional containing TranscriptionConfig if configured
   */
  public static Optional<TranscriptionConfig> loadFromEnvironment() {
    String endpoint = System.getenv(ENDPOINT_ENV);

    // Transcription is optional - return empty if not configured
    if (endpoint == null || endpoint.isEmpty()) {
      logger.debug("Transcription not configured ({} not set)", ENDPOINT_ENV);
      return Optional.empty();
    }

    TranscriptionConfig.Builder builder = TranscriptionConfig.builder().endpoint(endpoint);

    // Optional: API Key
    String apiKey = System.getenv(API_KEY_ENV);
    if (apiKey != null && !apiKey.isEmpty()) {
      builder.apiKey(apiKey);
    }

    // Optional: Language (default: auto)
    String language = System.getenv(LANGUAGE_ENV);
    if (language != null && !language.isEmpty()) {
      builder.language(language);
    }

    // Optional: Timeout (default: 30 seconds)
    String timeoutStr = System.getenv(TIMEOUT_ENV);
    if (timeoutStr != null) {
      try {
        int timeoutSeconds = Integer.parseInt(timeoutStr);
        if (timeoutSeconds > 0) {
          builder.timeout(Duration.ofSeconds(timeoutSeconds));
        }
      } catch (NumberFormatException e) {
        logger.warn("Invalid timeout value: {}, using default", timeoutStr);
      }
    }

    // Optional: Max retries (default: 3)
    String maxRetriesStr = System.getenv(MAX_RETRIES_ENV);
    if (maxRetriesStr != null) {
      try {
        int maxRetries = Integer.parseInt(maxRetriesStr);
        if (maxRetries >= 0) {
          builder.maxRetries(maxRetries);
        }
      } catch (NumberFormatException e) {
        logger.warn("Invalid max retries value: {}, using default", maxRetriesStr);
      }
    }

    // Optional: Chunk size (default: 500ms)
    String chunkSizeStr = System.getenv(CHUNK_SIZE_ENV);
    if (chunkSizeStr != null) {
      try {
        int chunkSizeMs = Integer.parseInt(chunkSizeStr);
        if (chunkSizeMs > 0) {
          builder.chunkSizeMs(chunkSizeMs);
        }
      } catch (NumberFormatException e) {
        logger.warn("Invalid chunk size value: {}, using default", chunkSizeStr);
      }
    }

    // Audio format (default: PCM 16kHz Mono)
    builder.audioFormat(AudioFormat.PCM_16KHZ_MONO);

    // Enable partial results for real-time streaming
    builder.enablePartialResults(true);

    TranscriptionConfig config = builder.build();
    logger.info(
        "Loaded transcription config: endpoint={}, service={}",
        config.getEndpoint(),
        System.getenv(SERVICE_TYPE_ENV));

    return Optional.of(config);
  }
}
