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

package com.google.adk.transcription.tts;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating and caching TTS service instances. Services are lazily created and cached by
 * endpoint for reuse.
 *
 * <p>Supports environment variable overrides:
 *
 * <ul>
 *   <li>{@code ADK_TTS_ENDPOINT} - Default TTS service endpoint
 *   <li>{@code ADK_TTS_API_KEY} - Default API key for TTS service
 * </ul>
 *
 * @author Sandeep Belgavi
 * @since 2026-08-04
 */
public class TtsServiceFactory {

  private static final Logger logger = LoggerFactory.getLogger(TtsServiceFactory.class);

  /** Environment variable for the default TTS endpoint. */
  public static final String ENV_TTS_ENDPOINT = "ADK_TTS_ENDPOINT";

  /** Environment variable for the default TTS API key. */
  public static final String ENV_TTS_API_KEY = "ADK_TTS_API_KEY";

  /** Supported TTS service types. */
  public enum TtsServiceType {
    /** OpenAI-compatible TTS service (works with Piper, AllTalk, Kokoro, OpenAI). */
    OPENAI_COMPATIBLE
  }

  // Cache for service instances (lazy loading)
  private static final ConcurrentHashMap<String, TtsService> serviceCache =
      new ConcurrentHashMap<>();

  private static final ReentrantLock lock = new ReentrantLock();

  private TtsServiceFactory() {
    // Utility class - no instantiation
  }

  /**
   * Creates or retrieves a cached TTS service instance based on configuration. Uses lazy loading -
   * the service is only created when first needed.
   *
   * <p>If the config does not specify an endpoint or apiKey, the factory checks the environment
   * variables {@code ADK_TTS_ENDPOINT} and {@code ADK_TTS_API_KEY}.
   *
   * @param config TTS configuration
   * @return TtsService instance (cached by endpoint)
   * @throws IllegalArgumentException if no endpoint can be resolved
   */
  public static TtsService getOrCreate(TtsConfig config) {
    TtsConfig resolvedConfig = resolveConfig(config);
    String cacheKey = generateCacheKey(resolvedConfig);

    // Double-check locking for thread safety
    TtsService service = serviceCache.get(cacheKey);
    if (service != null) {
      return service;
    }

    lock.lock();
    try {
      // Check again after acquiring lock
      service = serviceCache.get(cacheKey);
      if (service != null) {
        return service;
      }

      // Create new service
      service = createService(resolvedConfig);
      serviceCache.put(cacheKey, service);
      logger.info("Created TTS service for endpoint: {}", resolvedConfig.getEndpoint());
      return service;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Creates a new TTS service instance without caching. Use {@link #getOrCreate(TtsConfig)} for
   * normal usage.
   *
   * @param config TTS configuration
   * @return new TtsService instance
   * @throws IllegalArgumentException if no endpoint can be resolved
   */
  public static TtsService create(TtsConfig config) {
    TtsConfig resolvedConfig = resolveConfig(config);
    return createService(resolvedConfig);
  }

  /**
   * Determines the TTS service type for a given config. Currently defaults to OPENAI_COMPATIBLE.
   *
   * @param config TTS configuration
   * @return the determined service type
   */
  public static TtsServiceType determineServiceType(TtsConfig config) {
    // Future: infer from endpoint patterns or add explicit type to config
    return TtsServiceType.OPENAI_COMPATIBLE;
  }

  private static TtsService createService(TtsConfig config) {
    TtsServiceType serviceType = determineServiceType(config);

    switch (serviceType) {
      case OPENAI_COMPATIBLE:
        return createOpenAiCompatibleService(config);
      default:
        throw new IllegalArgumentException("Unsupported TTS service type: " + serviceType);
    }
  }

  private static TtsService createOpenAiCompatibleService(TtsConfig config) {
    String endpoint = config.getEndpoint();
    String apiKey = config.getApiKey().orElse(null);

    logger.debug(
        "Creating OpenAI-compatible TTS service: endpoint={}, apiKey present={}",
        endpoint,
        apiKey != null);

    return new OpenAiCompatibleTtsService(endpoint, apiKey);
  }

  /**
   * Resolves the config by falling back to environment variables for endpoint and API key if not
   * provided in the config.
   */
  private static TtsConfig resolveConfig(TtsConfig config) {
    String endpoint = config.getEndpoint();
    String apiKey = config.getApiKey().orElse(null);

    // Check environment variables if not set in config
    if (endpoint == null || endpoint.isEmpty()) {
      endpoint = System.getenv(ENV_TTS_ENDPOINT);
    }
    if (apiKey == null || apiKey.isEmpty()) {
      String envApiKey = System.getenv(ENV_TTS_API_KEY);
      if (envApiKey != null && !envApiKey.isEmpty()) {
        apiKey = envApiKey;
      }
    }

    if (endpoint == null || endpoint.isEmpty()) {
      throw new IllegalArgumentException(
          "TTS endpoint is required. Set it in TtsConfig or via environment variable "
              + ENV_TTS_ENDPOINT);
    }

    // Rebuild config with resolved values
    TtsConfig.Builder builder =
        TtsConfig.builder()
            .endpoint(endpoint)
            .voice(config.getVoice())
            .language(config.getLanguage())
            .outputFormat(config.getOutputFormat())
            .sampleRate(config.getSampleRate())
            .speed(config.getSpeed());

    if (config.getModel() != null) {
      builder.model(config.getModel());
    }
    if (apiKey != null) {
      builder.apiKey(apiKey);
    }

    return builder.build();
  }

  private static String generateCacheKey(TtsConfig config) {
    return String.format(
        "%s:%s:%s", determineServiceType(config), config.getEndpoint(), config.getVoice());
  }

  /** Clears the service cache. Useful for testing. */
  public static void clearCache() {
    lock.lock();
    try {
      serviceCache.clear();
      logger.debug("TTS service cache cleared");
    } finally {
      lock.unlock();
    }
  }
}
