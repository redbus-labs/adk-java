package com.google.adk.models.azure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared configuration for all Azure transports (REST, Realtime, future).
 *
 * <p>Resolves environment variables once at construction time and exposes them as simple accessors.
 * All Azure transports read from this single config rather than duplicating env-var logic.
 *
 * <p>Environment variables:
 *
 * <ul>
 *   <li>{@code AZURE_MODEL_ENDPOINT} — full Azure endpoint URL (includes api-version if needed)
 *   <li>{@code AZURE_OPENAI_API_KEY} — API key for authentication
 *   <li>{@code AZURE_REALTIME_VOICE} — (optional) voice for realtime models, defaults to "alloy"
 * </ul>
 */
public final class AzureConfig {

  private static final Logger logger = LoggerFactory.getLogger(AzureConfig.class);

  public static final String ENDPOINT_ENV = "AZURE_MODEL_ENDPOINT";
  public static final String API_KEY_ENV = "AZURE_OPENAI_API_KEY";
  public static final String VOICE_ENV = "AZURE_REALTIME_VOICE";

  private static final String DEFAULT_VOICE = "alloy";

  private final String modelName;
  private final String endpoint;
  private final String apiKey;
  private final String voice;

  private AzureConfig(String modelName, String endpoint, String apiKey, String voice) {
    this.modelName = modelName;
    this.endpoint = endpoint;
    this.apiKey = apiKey;
    this.voice = voice;
  }

  /**
   * Creates an AzureConfig by reading environment variables.
   *
   * @param modelName the Azure deployment/model name
   * @return a fully resolved config
   */
  public static AzureConfig fromEnvironment(String modelName) {
    String endpoint = resolveRequired(ENDPOINT_ENV);
    String apiKey = resolveRequired(API_KEY_ENV);
    String voice = resolveOptional(VOICE_ENV, DEFAULT_VOICE);
    return new AzureConfig(modelName, endpoint, apiKey, voice);
  }

  public String modelName() {
    return modelName;
  }

  public String endpoint() {
    return endpoint;
  }

  public String apiKey() {
    return apiKey;
  }

  public String voice() {
    return voice;
  }

  private static String resolveRequired(String envVar) {
    String val = System.getenv(envVar);
    if (val == null || val.isBlank()) {
      logger.warn("{} is not set. Azure API calls will fail.", envVar);
      throw new IllegalStateException(envVar + " environment variable is not set.");
    }
    return val.replaceAll("/+$", "");
  }

  private static String resolveOptional(String envVar, String defaultValue) {
    String val = System.getenv(envVar);
    return (val != null && !val.isBlank()) ? val : defaultValue;
  }
}
