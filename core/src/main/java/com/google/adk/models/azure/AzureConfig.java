package com.google.adk.models.azure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared configuration for all Azure transports (REST, Realtime voice, Realtime translate).
 *
 * <p>Each API contract has its own endpoint environment variable. {@code AZURE_MODEL_ENDPOINT} is
 * kept as a legacy fallback when a contract-specific variable is not set.
 *
 * <p>Environment variables:
 *
 * <ul>
 *   <li>{@code AZURE_RESPONSE_ENDPOINT} — HTTP Responses API
 *   <li>{@code AZURE_REALTIME_ENDPOINT} — WebSocket voice-agent Realtime API
 *   <li>{@code AZURE_TRANSLATE_ENDPOINT} — WebSocket GPT Realtime Translate
 *   <li>{@code AZURE_MODEL_ENDPOINT} — (legacy) fallback for all of the above
 *   <li>{@code AZURE_OPENAI_API_KEY} — API key
 *   <li>{@code AZURE_REALTIME_VOICE} — (optional) voice for realtime models, defaults to "alloy"
 *   <li>{@code AZURE_TRANSLATE_TARGET_LANGUAGE} — (optional) default target language, defaults to
 *       "en"
 * </ul>
 */
public final class AzureConfig {

  private static final Logger logger = LoggerFactory.getLogger(AzureConfig.class);

  /**
   * @deprecated Use contract-specific endpoint variables.
   */
  public static final String LEGACY_ENDPOINT_ENV = "AZURE_MODEL_ENDPOINT";

  /**
   * @deprecated Use {@link #LEGACY_ENDPOINT_ENV} or contract-specific variables.
   */
  @Deprecated public static final String ENDPOINT_ENV = LEGACY_ENDPOINT_ENV;

  public static final String RESPONSE_ENDPOINT_ENV = "AZURE_RESPONSE_ENDPOINT";
  public static final String REALTIME_ENDPOINT_ENV = "AZURE_REALTIME_ENDPOINT";
  public static final String TRANSLATE_ENDPOINT_ENV = "AZURE_TRANSLATE_ENDPOINT";

  public static final String API_KEY_ENV = "AZURE_OPENAI_API_KEY";
  public static final String VOICE_ENV = "AZURE_REALTIME_VOICE";
  public static final String TRANSLATE_TARGET_LANGUAGE_ENV = "AZURE_TRANSLATE_TARGET_LANGUAGE";

  private static final String DEFAULT_VOICE = "alloy";
  private static final String DEFAULT_TRANSLATE_LANGUAGE = "en";

  private final String modelName;
  private final String responseEndpoint;
  private final String realtimeEndpoint;
  private final String translateEndpoint;
  private final String apiKey;
  private final String voice;
  private final String translateTargetLanguage;

  private AzureConfig(
      String modelName,
      String responseEndpoint,
      String realtimeEndpoint,
      String translateEndpoint,
      String apiKey,
      String voice,
      String translateTargetLanguage) {
    this.modelName = modelName;
    this.responseEndpoint = responseEndpoint;
    this.realtimeEndpoint = realtimeEndpoint;
    this.translateEndpoint = translateEndpoint;
    this.apiKey = apiKey;
    this.voice = voice;
    this.translateTargetLanguage = translateTargetLanguage;
  }

  public static AzureConfig fromEnvironment(String modelName) {
    String legacy = resolveOptionalEnv(LEGACY_ENDPOINT_ENV);
    String responseEndpoint =
        resolveContractEndpoint(RESPONSE_ENDPOINT_ENV, legacy, "Responses API");
    String realtimeEndpoint =
        resolveContractEndpoint(REALTIME_ENDPOINT_ENV, legacy, "Realtime voice API");
    String translateEndpoint = resolveTranslateEndpoint(legacy, modelName);

    String apiKey = resolveRequired(API_KEY_ENV);
    String voice = resolveOptional(VOICE_ENV, DEFAULT_VOICE);
    String translateTargetLanguage =
        resolveOptional(TRANSLATE_TARGET_LANGUAGE_ENV, DEFAULT_TRANSLATE_LANGUAGE);

    logger.info(
        "AzureConfig for model={}: response={}, realtime={}, translate={}",
        modelName,
        maskEndpoint(responseEndpoint),
        maskEndpoint(realtimeEndpoint),
        maskEndpoint(translateEndpoint));

    return new AzureConfig(
        modelName,
        responseEndpoint,
        realtimeEndpoint,
        translateEndpoint,
        apiKey,
        voice,
        translateTargetLanguage);
  }

  public String modelName() {
    return modelName;
  }

  /** HTTP endpoint for the Azure Responses API (REST). */
  public String responseEndpoint() {
    return responseEndpoint;
  }

  /**
   * @deprecated Use {@link #responseEndpoint()}, {@link #realtimeWebSocketUrl()}, or {@link
   *     #translationsWebSocketUrl()}.
   */
  @Deprecated
  public String endpoint() {
    return responseEndpoint;
  }

  public String apiKey() {
    return apiKey;
  }

  public String voice() {
    return voice;
  }

  public String translateTargetLanguage() {
    return translateTargetLanguage;
  }

  public AzureConfig withTranslateTargetLanguage(String language) {
    String lang =
        (language != null && !language.isBlank()) ? language.trim() : translateTargetLanguage;
    return new AzureConfig(
        modelName, responseEndpoint, realtimeEndpoint, translateEndpoint, apiKey, voice, lang);
  }

  /** WebSocket URL for bidirectional voice-agent Realtime. Uses {@link #REALTIME_ENDPOINT_ENV}. */
  public String realtimeWebSocketUrl() {
    String ws = toWebSocketUrl(realtimeEndpoint);
    if (ws.contains("deployment=") || ws.contains("model=")) {
      return ws;
    }
    String param = realtimeEndpoint.contains("/v1/") ? "model" : "deployment";
    String separator = ws.contains("?") ? "&" : "?";
    return ws + separator + param + "=" + modelName;
  }

  /** WebSocket URL for GPT Realtime Translate. Uses {@link #TRANSLATE_ENDPOINT_ENV}. */
  public String translationsWebSocketUrl() {
    if (translateEndpoint == null || translateEndpoint.isBlank()) {
      throw new IllegalStateException(
          TRANSLATE_ENDPOINT_ENV
              + " is not set. Example:"
              + " wss://<resource>.openai.azure.com/openai/v1/realtime/translations?model="
              + modelName);
    }
    String normalized = normalizeTranslateWebSocketUrl(translateEndpoint, modelName);
    if (!normalized.equals(toWebSocketUrl(translateEndpoint))) {
      logger.warn(
          "Normalized {} (was: {}). Use GA format:"
              + " wss://<host>/openai/v1/realtime/translations?model=<deployment> — no api-version.",
          maskEndpoint(normalized),
          maskEndpoint(translateEndpoint));
    }
    return normalized;
  }

  /**
   * Forces GA translate URL shape: {@code /openai/v1/realtime/translations?model=} without {@code
   * api-version}. Preview-style URLs ({@code /openai/realtime/translations?api-version=...}) return
   * HTTP 400.
   */
  static String normalizeTranslateWebSocketUrl(String raw, String modelName) {
    String ws = toWebSocketUrl(raw);
    String http = ws.replaceFirst("^wss://", "https://").replaceFirst("^ws://", "http://");
    java.net.URI uri = java.net.URI.create(http);
    String host = uri.getHost();
    if (host == null || host.isBlank()) {
      throw new IllegalStateException("Invalid translate endpoint (no host): " + raw);
    }
    String modelParam =
        extractQueryParam(raw, "model", extractQueryParam(raw, "deployment", modelName));
    return "wss://" + host + "/openai/v1/realtime/translations?model=" + modelParam;
  }

  private static String resolveContractEndpoint(
      String specificEnv, String legacyFallback, String label) {
    String val = resolveOptionalEnv(specificEnv);
    if (val == null) {
      val = legacyFallback;
    }
    if (val == null || val.isBlank()) {
      throw new IllegalStateException(
          "Azure "
              + label
              + " endpoint not configured. Set "
              + specificEnv
              + " or "
              + LEGACY_ENDPOINT_ENV);
    }
    return val;
  }

  private static String resolveTranslateEndpoint(String legacyFallback, String modelName) {
    String explicit = resolveOptionalEnv(TRANSLATE_ENDPOINT_ENV);
    if (explicit != null) {
      return normalizeTranslateWebSocketUrl(explicit, modelName);
    }

    String base = resolveOptionalEnv(REALTIME_ENDPOINT_ENV);
    if (base == null) {
      base = legacyFallback;
    }
    if (base == null || base.isBlank()) {
      return null;
    }

    return normalizeTranslateWebSocketUrl(base, modelName);
  }

  private static String extractQueryParam(String url, String key, String defaultValue) {
    int q = url.indexOf('?');
    if (q < 0) {
      return defaultValue;
    }
    for (String param : url.substring(q + 1).split("&")) {
      if (param.startsWith(key + "=")) {
        return param.substring((key + "=").length());
      }
    }
    return defaultValue;
  }

  private static String toWebSocketUrl(String url) {
    return url.replaceFirst("^https://", "wss://").replaceFirst("^http://", "ws://");
  }

  private static String resolveRequired(String envVar) {
    String val = System.getenv(envVar);
    if (val == null || val.isBlank()) {
      throw new IllegalStateException(envVar + " environment variable is not set.");
    }
    return val.replaceAll("/+$", "");
  }

  private static String resolveOptional(String envVar, String defaultValue) {
    String val = System.getenv(envVar);
    return (val != null && !val.isBlank()) ? val : defaultValue;
  }

  private static String resolveOptionalEnv(String envVar) {
    String val = System.getenv(envVar);
    return (val != null && !val.isBlank()) ? val.replaceAll("/+$", "") : null;
  }

  private static String maskEndpoint(String url) {
    if (url == null) {
      return "unset";
    }
    try {
      java.net.URI u =
          java.net.URI.create(
              url.replaceFirst("^wss://", "https://").replaceFirst("^ws://", "http://"));
      return (u.getHost() != null ? u.getHost() : "?") + (u.getPath() != null ? u.getPath() : "");
    } catch (Exception e) {
      return "(configured)";
    }
  }
}
