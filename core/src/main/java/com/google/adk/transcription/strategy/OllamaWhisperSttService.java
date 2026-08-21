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

package com.google.adk.transcription.strategy;

import com.google.adk.transcription.ServiceHealth;
import com.google.adk.transcription.ServiceType;
import com.google.adk.transcription.TranscriptionConfig;
import com.google.adk.transcription.TranscriptionEvent;
import com.google.adk.transcription.TranscriptionException;
import com.google.adk.transcription.TranscriptionResult;
import com.google.adk.transcription.TranscriptionService;
import com.google.adk.transcription.processor.AudioChunkAggregator;
import com.google.adk.transcription.resilience.CircuitBreaker;
import com.google.adk.transcription.resilience.ResilientService;
import com.google.adk.transcription.resilience.RetryPolicy;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ollama/Whisper-compatible STT service that calls any server exposing the OpenAI-compatible {@code
 * /v1/audio/transcriptions} endpoint.
 *
 * <p>Compatible with:
 *
 * <ul>
 *   <li>faster-whisper-server
 *   <li>whisper.cpp HTTP server
 *   <li>Ollama with whisper support
 *   <li>Any OpenAI-compatible audio transcription API
 * </ul>
 *
 * @author Sandeep Belgavi
 * @since 2026-08-04
 */
public class OllamaWhisperSttService implements TranscriptionService {

  private static final Logger logger = LoggerFactory.getLogger(OllamaWhisperSttService.class);

  private static final String DEFAULT_MODEL = "whisper-1";
  private static final String TRANSCRIPTIONS_PATH = "/v1/audio/transcriptions";
  private static final String BOUNDARY = "----ADKMultipartBoundary" + System.currentTimeMillis();
  private static final String CRLF = "\r\n";
  private static final int CONNECT_TIMEOUT_MS = 5000;
  private static final int READ_TIMEOUT_MS = 60000;

  private final String endpoint;
  private final String model;
  private final Optional<String> apiKey;
  private volatile ResilientService resilientService;

  /**
   * Creates a new OllamaWhisperSttService.
   *
   * @param endpoint Base URL of the transcription server (e.g., "http://localhost:8080")
   * @param model Model name to use (e.g., "whisper-1"), or null for default
   * @param apiKey Optional API key for authentication, or null if not required
   */
  public OllamaWhisperSttService(String endpoint, String model, String apiKey) {
    if (endpoint == null || endpoint.isEmpty()) {
      throw new IllegalArgumentException("Endpoint is required");
    }
    // Strip trailing slash
    this.endpoint =
        endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    this.model = (model != null && !model.isEmpty()) ? model : DEFAULT_MODEL;
    this.apiKey = Optional.ofNullable(apiKey);
    logger.info(
        "Initialized OllamaWhisperSttService with endpoint={}, model={}",
        this.endpoint,
        this.model);
  }

  /**
   * Creates a new OllamaWhisperSttService with default model and no API key.
   *
   * @param endpoint Base URL of the transcription server
   */
  public OllamaWhisperSttService(String endpoint) {
    this(endpoint, null, null);
  }

  @Override
  public TranscriptionResult transcribe(byte[] audioData, TranscriptionConfig config)
      throws TranscriptionException {
    if (audioData == null || audioData.length == 0) {
      throw new TranscriptionException("Audio data is null or empty");
    }

    if (resilientService != null) {
      try {
        return resilientService.execute(() -> doTranscribe(audioData, config));
      } catch (TranscriptionException e) {
        throw e;
      } catch (Exception e) {
        throw new TranscriptionException("Resilient transcription failed: " + e.getMessage(), e);
      }
    }

    return doTranscribe(audioData, config);
  }

  private TranscriptionResult doTranscribe(byte[] audioData, TranscriptionConfig config)
      throws TranscriptionException {
    try {
      String transcriptionUrl = endpoint + TRANSCRIPTIONS_PATH;
      logger.debug(
          "Sending {} bytes to {} with model={}", audioData.length, transcriptionUrl, model);

      HttpURLConnection connection = createMultipartConnection(transcriptionUrl);
      writeMultipartBody(connection, audioData, config);

      int responseCode = connection.getResponseCode();
      if (responseCode != HttpURLConnection.HTTP_OK) {
        String errorBody = readErrorResponse(connection);
        logger.error("Transcription request failed with status {}: {}", responseCode, errorBody);
        throw new TranscriptionException(
            String.format("Transcription failed with HTTP %d: %s", responseCode, errorBody));
      }

      String responseBody = readResponse(connection);
      logger.debug("Transcription response: {}", responseBody);

      return parseTranscriptionResponse(responseBody, config);
    } catch (TranscriptionException e) {
      throw e;
    } catch (Exception e) {
      logger.error("Error during transcription", e);
      throw new TranscriptionException("Transcription failed: " + e.getMessage(), e);
    }
  }

  @Override
  public Single<TranscriptionResult> transcribeAsync(byte[] audioData, TranscriptionConfig config) {
    return Single.fromCallable(() -> transcribe(audioData, config)).subscribeOn(Schedulers.io());
  }

  @Override
  public Flowable<TranscriptionEvent> transcribeStream(
      Flowable<byte[]> audioStream, TranscriptionConfig config) {
    AudioChunkAggregator aggregator =
        new AudioChunkAggregator(
            config.getAudioFormat(), Duration.ofMillis(config.getChunkSizeMs()));

    return audioStream
        .buffer(config.getChunkSizeMs(), TimeUnit.MILLISECONDS)
        .map(
            chunks -> {
              byte[] aggregated = aggregator.aggregate(chunks);
              try {
                return transcribe(aggregated, config);
              } catch (TranscriptionException e) {
                logger.error("Stream transcription error", e);
                throw new RuntimeException(e);
              }
            })
        .map(this::mapToTranscriptionEvent);
  }

  @Override
  public boolean isAvailable() {
    try {
      URL url = new URL(endpoint);
      HttpURLConnection connection = (HttpURLConnection) url.openConnection();
      connection.setRequestMethod("GET");
      connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
      connection.setReadTimeout(CONNECT_TIMEOUT_MS);
      apiKey.ifPresent(key -> connection.setRequestProperty("Authorization", "Bearer " + key));

      int responseCode = connection.getResponseCode();
      connection.disconnect();

      // Consider any non-5xx response as available (server is reachable)
      boolean available = responseCode < 500;
      logger.debug("Availability check for {}: {} (HTTP {})", endpoint, available, responseCode);
      return available;
    } catch (Exception e) {
      logger.debug("Availability check failed for {}: {}", endpoint, e.getMessage());
      return false;
    }
  }

  @Override
  public ServiceType getServiceType() {
    return ServiceType.OLLAMA_WHISPER;
  }

  @Override
  public ServiceHealth getHealth() {
    long startTime = System.currentTimeMillis();
    boolean available = isAvailable();
    long responseTime = System.currentTimeMillis() - startTime;

    return ServiceHealth.builder()
        .available(available)
        .serviceType(ServiceType.OLLAMA_WHISPER)
        .responseTimeMs(responseTime)
        .message(available ? "Service reachable" : "Service unreachable")
        .build();
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
  public OllamaWhisperSttService withResilience(RetryPolicy retry, CircuitBreaker cb) {
    ResilientService.Builder builder = ResilientService.builder();
    if (retry != null) {
      builder.retryPolicy(retry);
    }
    if (cb != null) {
      builder.circuitBreaker(cb);
    }
    this.resilientService = builder.build();
    logger.info("Resilience configured for STT service at endpoint: {}", endpoint);
    return this;
  }

  // ---- Private helper methods ----

  private HttpURLConnection createMultipartConnection(String url) throws IOException {
    HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
    connection.setRequestMethod("POST");
    connection.setDoOutput(true);
    connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
    connection.setReadTimeout(READ_TIMEOUT_MS);
    connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);
    apiKey.ifPresent(key -> connection.setRequestProperty("Authorization", "Bearer " + key));
    return connection;
  }

  private void writeMultipartBody(
      HttpURLConnection connection, byte[] audioData, TranscriptionConfig config)
      throws IOException {
    try (OutputStream outputStream = connection.getOutputStream()) {
      // File field
      writeMultipartFileField(outputStream, "file", "audio.wav", "audio/wav", audioData);

      // Model field
      writeMultipartTextField(outputStream, "model", model);

      // Response format field
      writeMultipartTextField(outputStream, "response_format", "json");

      // Language field (optional)
      String language = config.getLanguage();
      if (language != null && !language.isEmpty() && !"auto".equalsIgnoreCase(language)) {
        writeMultipartTextField(outputStream, "language", language);
      }

      // End boundary
      outputStream.write(("--" + BOUNDARY + "--" + CRLF).getBytes(StandardCharsets.UTF_8));
      outputStream.flush();
    }
  }

  private void writeMultipartTextField(OutputStream out, String fieldName, String value)
      throws IOException {
    StringBuilder sb = new StringBuilder();
    sb.append("--").append(BOUNDARY).append(CRLF);
    sb.append("Content-Disposition: form-data; name=\"")
        .append(fieldName)
        .append("\"")
        .append(CRLF);
    sb.append(CRLF);
    sb.append(value).append(CRLF);
    out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
  }

  private void writeMultipartFileField(
      OutputStream out, String fieldName, String fileName, String mimeType, byte[] fileData)
      throws IOException {
    StringBuilder sb = new StringBuilder();
    sb.append("--").append(BOUNDARY).append(CRLF);
    sb.append("Content-Disposition: form-data; name=\"")
        .append(fieldName)
        .append("\"; filename=\"")
        .append(fileName)
        .append("\"")
        .append(CRLF);
    sb.append("Content-Type: ").append(mimeType).append(CRLF);
    sb.append(CRLF);
    out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
    out.write(fileData);
    out.write(CRLF.getBytes(StandardCharsets.UTF_8));
  }

  private String readResponse(HttpURLConnection connection) throws IOException {
    try (InputStream inputStream = connection.getInputStream()) {
      return readStream(inputStream);
    }
  }

  private String readErrorResponse(HttpURLConnection connection) {
    try {
      InputStream errorStream = connection.getErrorStream();
      if (errorStream != null) {
        return readStream(errorStream);
      }
    } catch (IOException e) {
      logger.debug("Could not read error stream", e);
    }
    return "No error body";
  }

  private String readStream(InputStream inputStream) throws IOException {
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    byte[] buffer = new byte[4096];
    int length;
    while ((length = inputStream.read(buffer)) != -1) {
      result.write(buffer, 0, length);
    }
    return result.toString(StandardCharsets.UTF_8.name());
  }

  private TranscriptionResult parseTranscriptionResponse(
      String responseBody, TranscriptionConfig config) throws TranscriptionException {
    // Parse minimal JSON: {"text": "..."} or {"text": "...", "segments": [...]}
    // Using simple parsing to avoid external JSON dependency
    String text = extractJsonStringField(responseBody, "text");
    if (text == null) {
      throw new TranscriptionException(
          "Failed to parse transcription response: no 'text' field found in: " + responseBody);
    }

    TranscriptionResult.Builder builder = TranscriptionResult.builder().text(text);

    // Set language if available from config
    String language = config.getLanguage();
    if (language != null && !language.isEmpty() && !"auto".equalsIgnoreCase(language)) {
      builder.language(language);
    }

    // Try to extract language from response (some APIs return it)
    String responseLanguage = extractJsonStringField(responseBody, "language");
    if (responseLanguage != null && !responseLanguage.isEmpty()) {
      builder.language(responseLanguage);
    }

    return builder.build();
  }

  /**
   * Extracts a string field value from a JSON string using simple parsing. This avoids requiring an
   * external JSON library for this single use case.
   *
   * @param json JSON string
   * @param fieldName Field name to extract
   * @return Field value or null if not found
   */
  private String extractJsonStringField(String json, String fieldName) {
    // Look for "fieldName": "value" or "fieldName":"value"
    String searchKey = "\"" + fieldName + "\"";
    int keyIndex = json.indexOf(searchKey);
    if (keyIndex == -1) {
      return null;
    }

    int colonIndex = json.indexOf(':', keyIndex + searchKey.length());
    if (colonIndex == -1) {
      return null;
    }

    // Find the opening quote of the value
    int valueStart = json.indexOf('"', colonIndex + 1);
    if (valueStart == -1) {
      return null;
    }

    // Find the closing quote, handling escaped quotes
    int valueEnd = valueStart + 1;
    while (valueEnd < json.length()) {
      char c = json.charAt(valueEnd);
      if (c == '\\') {
        valueEnd += 2; // Skip escaped character
      } else if (c == '"') {
        break;
      } else {
        valueEnd++;
      }
    }

    if (valueEnd >= json.length()) {
      return null;
    }

    // Unescape basic JSON escape sequences
    String raw = json.substring(valueStart + 1, valueEnd);
    return raw.replace("\\\"", "\"")
        .replace("\\\\", "\\")
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t");
  }

  private TranscriptionEvent mapToTranscriptionEvent(TranscriptionResult result) {
    return TranscriptionEvent.builder()
        .text(result.getText())
        .finished(true)
        .timestamp(result.getTimestamp())
        .language(result.getLanguage().orElse(null))
        .build();
  }
}
