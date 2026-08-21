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

import com.google.adk.transcription.ServiceHealth;
import com.google.adk.transcription.ServiceType;
import com.google.adk.transcription.resilience.CircuitBreaker;
import com.google.adk.transcription.resilience.ResilientService;
import com.google.adk.transcription.resilience.RetryPolicy;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TTS service implementation compatible with the OpenAI /v1/audio/speech API. Works with any server
 * exposing the OpenAI TTS endpoint, including Piper, AllTalk, Kokoro, and actual OpenAI.
 *
 * <p>Uses OkHttpClient for HTTP communication. Supports synchronous, asynchronous (via RxJava
 * Single), and streaming (via RxJava Flowable) synthesis modes.
 *
 * @author Sandeep Belgavi
 * @since 2026-08-04
 */
public class OpenAiCompatibleTtsService implements TtsService {

  private static final Logger logger = LoggerFactory.getLogger(OpenAiCompatibleTtsService.class);
  private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");
  private static final String SPEECH_PATH = "/v1/audio/speech";
  private static final String CAPABILITIES_PATH = "/v1/audio/speech/capabilities";
  private static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;
  private static final int DEFAULT_READ_TIMEOUT_SECONDS = 60;
  private static final int DEFAULT_WRITE_TIMEOUT_SECONDS = 30;
  private static final int STREAM_CHUNK_SIZE = 4096;
  private static final int HEALTH_CHECK_TIMEOUT_SECONDS = 5;
  private static final int CAPABILITIES_TIMEOUT_SECONDS = 5;

  /** Preferred format fallback order when the requested format is not supported. */
  private static final List<TtsAudioFormat> FORMAT_FALLBACK_ORDER =
      Arrays.asList(TtsAudioFormat.MP3, TtsAudioFormat.WAV, TtsAudioFormat.PCM, TtsAudioFormat.OGG);

  private final String endpoint;
  private final Optional<String> apiKey;
  private final OkHttpClient httpClient;
  private final AtomicReference<TtsCapabilities> cachedCapabilities = new AtomicReference<>();
  private volatile ResilientService resilientService;

  /**
   * Creates an OpenAI-compatible TTS service.
   *
   * @param endpoint the base URL of the TTS server (e.g., "http://localhost:8000")
   */
  public OpenAiCompatibleTtsService(String endpoint) {
    this(endpoint, null);
  }

  /**
   * Creates an OpenAI-compatible TTS service with API key authentication.
   *
   * @param endpoint the base URL of the TTS server (e.g., "https://api.openai.com")
   * @param apiKey the API key for authentication, or null if not required
   */
  public OpenAiCompatibleTtsService(String endpoint, String apiKey) {
    if (endpoint == null || endpoint.isEmpty()) {
      throw new IllegalArgumentException("Endpoint must not be null or empty");
    }
    // Strip trailing slash for consistent URL building
    this.endpoint =
        endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    this.apiKey = Optional.ofNullable(apiKey);
    this.httpClient =
        new OkHttpClient.Builder()
            .connectTimeout(DEFAULT_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(DEFAULT_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(DEFAULT_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build();

    logger.info(
        "Initialized OpenAI-compatible TTS service at endpoint: {}, apiKey present: {}",
        this.endpoint,
        this.apiKey.isPresent());
  }

  @Override
  public byte[] synthesize(String text, TtsConfig config) throws TtsException {
    if (text == null || text.isEmpty()) {
      throw new TtsException("Text must not be null or empty", "INVALID_INPUT");
    }

    if (resilientService != null) {
      try {
        return resilientService.execute(() -> doSynthesize(text, config));
      } catch (TtsException e) {
        throw e;
      } catch (Exception e) {
        throw new TtsException("Resilient synthesis failed: " + e.getMessage(), e);
      }
    }

    return doSynthesize(text, config);
  }

  private byte[] doSynthesize(String text, TtsConfig config) throws TtsException {
    TtsCapabilities caps = capabilities();
    TtsAudioFormat negotiatedFormat = negotiateFormat(config, caps);

    String speechUrl = endpoint + SPEECH_PATH;
    String jsonBody = buildRequestBody(text, config, negotiatedFormat);

    logger.debug(
        "Synthesizing text ({} chars) via {} with format {}",
        text.length(),
        speechUrl,
        negotiatedFormat.getValue());

    Request request = buildHttpRequest(speechUrl, jsonBody);

    try (Response response = httpClient.newCall(request).execute()) {
      if (!response.isSuccessful()) {
        String errorBody = extractErrorBody(response);
        throw new TtsException(
            String.format("TTS synthesis failed with HTTP %d: %s", response.code(), errorBody),
            "HTTP_" + response.code());
      }

      ResponseBody body = response.body();
      if (body == null) {
        throw new TtsException("Empty response body from TTS service", "EMPTY_RESPONSE");
      }

      byte[] audioData = body.bytes();
      logger.debug("Synthesis complete, received {} bytes", audioData.length);
      return audioData;

    } catch (IOException e) {
      throw new TtsException("Failed to communicate with TTS service: " + e.getMessage(), e);
    }
  }

  @Override
  public Single<byte[]> synthesizeAsync(String text, TtsConfig config) {
    return Single.fromCallable(() -> synthesize(text, config)).subscribeOn(Schedulers.io());
  }

  @Override
  public Flowable<byte[]> synthesizeStream(String text, TtsConfig config) {
    return Flowable.<byte[]>create(
            emitter -> {
              if (text == null || text.isEmpty()) {
                emitter.onError(
                    new TtsException("Text must not be null or empty", "INVALID_INPUT"));
                return;
              }

              String speechUrl = endpoint + SPEECH_PATH;
              String jsonBody = buildRequestBody(text, config);
              Request request = buildHttpRequest(speechUrl, jsonBody);

              logger.debug(
                  "Starting streaming synthesis ({} chars) via {}", text.length(), speechUrl);

              Response response = null;
              try {
                response = httpClient.newCall(request).execute();

                if (!response.isSuccessful()) {
                  String errorBody = extractErrorBody(response);
                  emitter.onError(
                      new TtsException(
                          String.format(
                              "TTS streaming failed with HTTP %d: %s", response.code(), errorBody),
                          "HTTP_" + response.code()));
                  return;
                }

                ResponseBody body = response.body();
                if (body == null) {
                  emitter.onError(
                      new TtsException("Empty response body from TTS service", "EMPTY_RESPONSE"));
                  return;
                }

                try (InputStream inputStream = body.byteStream()) {
                  byte[] buffer = new byte[STREAM_CHUNK_SIZE];
                  int bytesRead;
                  while ((bytesRead = inputStream.read(buffer)) != -1) {
                    if (emitter.isCancelled()) {
                      logger.debug("Streaming synthesis cancelled by subscriber");
                      break;
                    }
                    byte[] chunk = new byte[bytesRead];
                    System.arraycopy(buffer, 0, chunk, 0, bytesRead);
                    emitter.onNext(chunk);
                  }
                }

                if (!emitter.isCancelled()) {
                  emitter.onComplete();
                }

              } catch (IOException e) {
                if (!emitter.isCancelled()) {
                  emitter.onError(
                      new TtsException("Failed to stream from TTS service: " + e.getMessage(), e));
                }
              } finally {
                if (response != null) {
                  response.close();
                }
              }
            },
            io.reactivex.rxjava3.core.BackpressureStrategy.BUFFER)
        .subscribeOn(Schedulers.io());
  }

  @Override
  public boolean isAvailable() {
    try {
      Request request = new Request.Builder().url(endpoint).head().build();

      OkHttpClient healthClient =
          httpClient
              .newBuilder()
              .connectTimeout(HEALTH_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
              .readTimeout(HEALTH_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
              .build();

      try (Response response = healthClient.newCall(request).execute()) {
        boolean available = response.isSuccessful();
        logger.debug("TTS service availability check: {} (HTTP {})", available, response.code());
        return available;
      }
    } catch (IOException e) {
      logger.debug("TTS service unavailable: {}", e.getMessage());
      return false;
    }
  }

  @Override
  public ServiceHealth getHealth() {
    long startTime = System.currentTimeMillis();
    try {
      Request request = new Request.Builder().url(endpoint).head().build();

      OkHttpClient healthClient =
          httpClient
              .newBuilder()
              .connectTimeout(HEALTH_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
              .readTimeout(HEALTH_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
              .build();

      try (Response response = healthClient.newCall(request).execute()) {
        long latency = System.currentTimeMillis() - startTime;
        boolean available = response.isSuccessful();

        return ServiceHealth.builder()
            .available(available)
            .serviceType(ServiceType.WHISPER) // Closest match for generic TTS
            .responseTimeMs(latency)
            .message(
                available
                    ? "OpenAI-compatible TTS service is healthy"
                    : String.format("Service returned HTTP %d", response.code()))
            .build();
      }
    } catch (IOException e) {
      long latency = System.currentTimeMillis() - startTime;
      return ServiceHealth.builder()
          .available(false)
          .serviceType(ServiceType.WHISPER)
          .responseTimeMs(latency)
          .message("Health check failed: " + e.getMessage())
          .build();
    }
  }

  /**
   * Probes the TTS server for its capabilities by querying the capabilities endpoint. Results are
   * cached after the first successful probe.
   *
   * <p>If the probe fails (404, timeout, or other error), returns default capabilities assuming all
   * formats are supported with a maximum text length of 4096.
   *
   * @return the server's capabilities, possibly cached
   */
  @Override
  public TtsCapabilities capabilities() {
    TtsCapabilities cached = cachedCapabilities.get();
    if (cached != null) {
      return cached;
    }

    TtsCapabilities probed = probeCapabilities();
    cachedCapabilities.compareAndSet(null, probed);
    return cachedCapabilities.get();
  }

  /**
   * Negotiates the audio output format based on the requested config and server capabilities.
   *
   * <p>If the requested format is supported, it is returned directly. Otherwise, formats are tried
   * in the preferred fallback order: MP3, WAV, PCM, OGG. If no fallback is supported, the
   * originally requested format is returned as a last resort.
   *
   * @param config the TTS configuration with the requested format
   * @param caps the server's capabilities
   * @return the negotiated audio format
   */
  private TtsAudioFormat negotiateFormat(TtsConfig config, TtsCapabilities caps) {
    TtsAudioFormat requested = config.getOutputFormat();

    if (caps.isFormatSupported(requested)) {
      logger.debug("Requested format {} is supported", requested.getValue());
      return requested;
    }

    logger.info(
        "Requested format {} not supported by server, attempting fallback", requested.getValue());

    for (TtsAudioFormat fallback : FORMAT_FALLBACK_ORDER) {
      if (caps.isFormatSupported(fallback)) {
        logger.info("Falling back to supported format: {}", fallback.getValue());
        return fallback;
      }
    }

    // Last resort: return the requested format and let the server handle it
    logger.warn(
        "No fallback format found in server capabilities, using requested format: {}",
        requested.getValue());
    return requested;
  }

  /**
   * Probes the server's capabilities endpoint via GET /v1/audio/speech/capabilities. Falls back to
   * a HEAD request if the GET fails. Returns default capabilities if probing is not possible.
   *
   * @return the probed or default capabilities
   */
  private TtsCapabilities probeCapabilities() {
    String capabilitiesUrl = endpoint + CAPABILITIES_PATH;

    OkHttpClient probeClient =
        httpClient
            .newBuilder()
            .connectTimeout(CAPABILITIES_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(CAPABILITIES_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build();

    // Try GET request first
    Request.Builder requestBuilder = new Request.Builder().url(capabilitiesUrl).get();
    apiKey.ifPresent(key -> requestBuilder.addHeader("Authorization", "Bearer " + key));
    Request request = requestBuilder.build();

    try (Response response = probeClient.newCall(request).execute()) {
      if (response.isSuccessful() && response.body() != null) {
        String responseBody = response.body().string();
        TtsCapabilities parsed = parseCapabilitiesResponse(responseBody);
        logger.info("Successfully probed TTS capabilities from {}", capabilitiesUrl);
        return parsed;
      }

      logger.debug("Capabilities GET returned HTTP {}, trying HEAD request", response.code());
    } catch (IOException e) {
      logger.debug("Capabilities GET failed: {}, trying HEAD request", e.getMessage());
    }

    // Fallback: try HEAD request to at least confirm the endpoint exists
    Request headRequest = new Request.Builder().url(capabilitiesUrl).head().build();
    try (Response response = probeClient.newCall(headRequest).execute()) {
      if (response.isSuccessful()) {
        logger.info("Capabilities HEAD succeeded, returning default capabilities");
      } else {
        logger.debug("Capabilities HEAD returned HTTP {}", response.code());
      }
    } catch (IOException e) {
      logger.debug("Capabilities HEAD failed: {}", e.getMessage());
    }

    // Return default capabilities (all formats supported, 4096 max text)
    logger.info(
        "Could not probe server capabilities, returning defaults (all formats, maxText=4096)");
    return TtsCapabilities.builder().build();
  }

  /**
   * Parses the JSON response from the capabilities endpoint.
   *
   * @param responseBody the JSON response body
   * @return parsed TtsCapabilities
   */
  private TtsCapabilities parseCapabilitiesResponse(String responseBody) {
    TtsCapabilities.Builder builder = TtsCapabilities.builder();

    try {
      JSONObject json = new JSONObject(responseBody);

      if (json.has("supported_formats")) {
        JSONArray formatsArray = json.getJSONArray("supported_formats");
        List<TtsAudioFormat> formats = new ArrayList<>();
        for (int i = 0; i < formatsArray.length(); i++) {
          try {
            formats.add(TtsAudioFormat.fromString(formatsArray.getString(i)));
          } catch (IllegalArgumentException e) {
            logger.debug("Ignoring unknown format: {}", formatsArray.getString(i));
          }
        }
        if (!formats.isEmpty()) {
          builder.supportedFormats(formats);
        }
      }

      if (json.has("supported_voices")) {
        JSONArray voicesArray = json.getJSONArray("supported_voices");
        List<String> voices = new ArrayList<>();
        for (int i = 0; i < voicesArray.length(); i++) {
          voices.add(voicesArray.getString(i));
        }
        builder.supportedVoices(voices);
      }

      if (json.has("supported_models")) {
        JSONArray modelsArray = json.getJSONArray("supported_models");
        List<String> models = new ArrayList<>();
        for (int i = 0; i < modelsArray.length(); i++) {
          models.add(modelsArray.getString(i));
        }
        builder.supportedModels(models);
      }

      if (json.has("max_text_length")) {
        builder.maxTextLength(json.getInt("max_text_length"));
      }

      if (json.has("supports_streaming")) {
        builder.supportsStreaming(json.getBoolean("supports_streaming"));
      }

    } catch (Exception e) {
      logger.warn("Failed to parse capabilities response, using defaults: {}", e.getMessage());
      return TtsCapabilities.builder().build();
    }

    return builder.build();
  }

  /**
   * Gets the configured endpoint.
   *
   * @return the TTS service endpoint URL
   */
  public String getEndpoint() {
    return endpoint;
  }

  /**
   * Configures this service with resilience support (retry and circuit breaker). Returns this
   * instance for fluent configuration. If either parameter is null, the corresponding default
   * policy is used.
   *
   * @param retry the retry policy, or null for default
   * @param cb the circuit breaker, or null for default
   * @return this service instance configured with resilience
   */
  public OpenAiCompatibleTtsService withResilience(RetryPolicy retry, CircuitBreaker cb) {
    ResilientService.Builder builder = ResilientService.builder();
    if (retry != null) {
      builder.retryPolicy(retry);
    }
    if (cb != null) {
      builder.circuitBreaker(cb);
    }
    this.resilientService = builder.build();
    logger.info("Resilience configured for TTS service at endpoint: {}", endpoint);
    return this;
  }

  /**
   * Builds the JSON request body for the OpenAI /v1/audio/speech API.
   *
   * @param text the text to synthesize
   * @param config TTS configuration
   * @return JSON string
   */
  private String buildRequestBody(String text, TtsConfig config) {
    return buildRequestBody(text, config, config.getOutputFormat());
  }

  /**
   * Builds the JSON request body for the OpenAI /v1/audio/speech API with a specific output format.
   *
   * @param text the text to synthesize
   * @param config TTS configuration
   * @param format the negotiated audio output format
   * @return JSON string
   */
  private String buildRequestBody(String text, TtsConfig config, TtsAudioFormat format) {
    JSONObject body = new JSONObject();
    body.put("input", text);
    body.put("voice", config.getVoice());
    body.put("response_format", format.getValue());
    body.put("speed", config.getSpeed());

    // Use model from config, default to "tts-1" if not specified
    String model = config.getModel();
    body.put("model", (model != null && !model.isEmpty()) ? model : "tts-1");

    return body.toString();
  }

  /**
   * Builds the HTTP request with appropriate headers.
   *
   * @param url the target URL
   * @param jsonBody the JSON request body
   * @return configured OkHttp Request
   */
  private Request buildHttpRequest(String url, String jsonBody) {
    Request.Builder builder =
        new Request.Builder()
            .url(url)
            .post(RequestBody.create(jsonBody, JSON_MEDIA_TYPE))
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/octet-stream");

    apiKey.ifPresent(key -> builder.addHeader("Authorization", "Bearer " + key));

    return builder.build();
  }

  /**
   * Extracts error body from a failed response.
   *
   * @param response the failed HTTP response
   * @return error message string
   */
  private String extractErrorBody(Response response) {
    try {
      ResponseBody body = response.body();
      if (body != null) {
        return body.string();
      }
    } catch (IOException e) {
      logger.debug("Could not read error response body", e);
    }
    return "No error body";
  }
}
