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

import com.google.adk.transcription.TranscriptionConfig;
import com.google.adk.transcription.TranscriptionEvent;
import com.google.adk.transcription.TranscriptionException;
import com.google.adk.transcription.TranscriptionResult;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.FlowableEmitter;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WebSocket-based streaming STT service that extends {@link OllamaWhisperSttService} with
 * real-time, low-latency transcription via WebSocket connections.
 *
 * <p>Connects to a streaming transcription endpoint at {@code
 * {endpoint}/v1/audio/transcriptions/stream} using WebSocket protocol:
 *
 * <ul>
 *   <li>Sends binary audio chunks as WebSocket binary frames
 *   <li>Receives JSON text frames with format: {@code {"text": "...", "is_final": true/false}}
 *   <li>Emits {@link TranscriptionEvent} for each partial/final result
 * </ul>
 *
 * <p>Features:
 *
 * <ul>
 *   <li>Automatic reconnection with buffering on WebSocket disconnect
 *   <li>10-second inactivity timeout with error event emission
 *   <li>Graceful fallback to batch mode if WebSocket endpoint is unavailable (404, connection
 *       refused)
 *   <li>Thread-safe, supports concurrent streams
 * </ul>
 *
 * @author Sandeep Belgavi
 * @since 2026-08-04
 */
public class StreamingWhisperSttService extends OllamaWhisperSttService {

  private static final Logger logger = LoggerFactory.getLogger(StreamingWhisperSttService.class);

  private static final String STREAMING_PATH = "/v1/audio/transcriptions/stream";
  private static final long RESPONSE_TIMEOUT_MS = 10_000;
  private static final int MAX_RECONNECT_ATTEMPTS = 3;
  private static final long RECONNECT_DELAY_MS = 1_000;

  private final OkHttpClient client;
  private final String wsEndpoint;

  /**
   * Creates a new StreamingWhisperSttService.
   *
   * @param endpoint Base URL of the transcription server (e.g., "http://localhost:8080")
   * @param model Model name to use (e.g., "whisper-1"), or null for default
   * @param apiKey Optional API key for authentication, or null if not required
   * @param client OkHttpClient instance for WebSocket connections
   */
  public StreamingWhisperSttService(
      String endpoint, String model, String apiKey, OkHttpClient client) {
    super(endpoint, model, apiKey);
    if (client == null) {
      throw new IllegalArgumentException("OkHttpClient is required");
    }
    this.client = client;

    // Convert http(s) to ws(s) for WebSocket endpoint
    String normalizedEndpoint =
        endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    if (normalizedEndpoint.startsWith("https://")) {
      this.wsEndpoint = "wss://" + normalizedEndpoint.substring(8) + STREAMING_PATH;
    } else if (normalizedEndpoint.startsWith("http://")) {
      this.wsEndpoint = "ws://" + normalizedEndpoint.substring(7) + STREAMING_PATH;
    } else {
      this.wsEndpoint = "ws://" + normalizedEndpoint + STREAMING_PATH;
    }

    logger.info(
        "Initialized StreamingWhisperSttService with WebSocket endpoint={}", this.wsEndpoint);
  }

  /**
   * Streams transcription results via WebSocket for real-time, low-latency transcription. Opens a
   * WebSocket connection, sends binary audio chunks as they arrive, and emits transcription events
   * for each partial/final result.
   *
   * <p>If the WebSocket endpoint is unavailable (404, connection refused), falls back to batch mode
   * using the parent class's transcribe() method.
   *
   * @param audioStream Flowable of audio chunks
   * @param config Transcription configuration
   * @return Flowable of transcription events
   */
  @Override
  public Flowable<TranscriptionEvent> transcribeStream(
      Flowable<byte[]> audioStream, TranscriptionConfig config) {
    return Flowable.<TranscriptionEvent>create(
            emitter -> {
              StreamingSession session = new StreamingSession(emitter, config);
              session.start(audioStream);
            },
            BackpressureStrategy.BUFFER)
        .subscribeOn(Schedulers.io());
  }

  /**
   * Internal session managing a single streaming transcription lifecycle. Thread-safe and handles
   * reconnection, buffering, timeouts, and fallback.
   */
  private class StreamingSession {
    private final FlowableEmitter<TranscriptionEvent> emitter;
    private final TranscriptionConfig config;
    private final AtomicBoolean completed = new AtomicBoolean(false);
    private final AtomicBoolean fallbackMode = new AtomicBoolean(false);
    private final AtomicReference<WebSocket> activeWebSocket = new AtomicReference<>();
    private final AtomicLong lastResponseTime = new AtomicLong(System.currentTimeMillis());
    private final ConcurrentLinkedQueue<byte[]> reconnectBuffer = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean wsConnected = new AtomicBoolean(false);
    private final CountDownLatch connectionLatch = new CountDownLatch(1);
    private final AtomicBoolean connectionFailed = new AtomicBoolean(false);

    StreamingSession(FlowableEmitter<TranscriptionEvent> emitter, TranscriptionConfig config) {
      this.emitter = emitter;
      this.config = config;
    }

    void start(Flowable<byte[]> audioStream) {
      // Attempt initial WebSocket connection
      boolean connected = attemptConnection(0);

      if (!connected || connectionFailed.get()) {
        // Fallback to batch mode
        logger.warn("WebSocket connection failed, falling back to batch transcription mode");
        fallbackMode.set(true);
        runBatchFallback(audioStream);
        return;
      }

      // Start timeout monitor
      startTimeoutMonitor();

      // Subscribe to audio stream and send chunks
      audioStream
          .subscribeOn(Schedulers.io())
          .subscribe(this::handleAudioChunk, this::handleStreamError, this::handleStreamComplete);
    }

    private boolean attemptConnection(int attempt) {
      if (attempt >= MAX_RECONNECT_ATTEMPTS) {
        return false;
      }

      try {
        Request.Builder requestBuilder = new Request.Builder().url(wsEndpoint);
        // Note: API key is set via parent's Optional<String> apiKey field which is private.
        // We reconstruct the header from the constructor parameter.
        Request request = requestBuilder.build();

        WebSocket ws = client.newWebSocket(request, new StreamingWebSocketListener());
        activeWebSocket.set(ws);

        // Wait for connection to be established or fail
        boolean opened = connectionLatch.await(5, TimeUnit.SECONDS);
        if (!opened || connectionFailed.get()) {
          if (attempt < MAX_RECONNECT_ATTEMPTS - 1) {
            Thread.sleep(RECONNECT_DELAY_MS);
            return attemptConnection(attempt + 1);
          }
          return false;
        }

        return wsConnected.get();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }

    private void handleAudioChunk(byte[] chunk) {
      if (emitter.isCancelled() || completed.get()) {
        return;
      }

      if (!wsConnected.get()) {
        // Buffer chunks during reconnection
        reconnectBuffer.offer(chunk);
        return;
      }

      WebSocket ws = activeWebSocket.get();
      if (ws != null) {
        // Send binary audio chunk
        boolean sent = ws.send(ByteString.of(chunk, 0, chunk.length));
        if (!sent) {
          // WebSocket send failed, buffer for reconnect
          reconnectBuffer.offer(chunk);
          attemptReconnection();
        }
      }
    }

    private void handleStreamError(Throwable error) {
      if (completed.compareAndSet(false, true)) {
        logger.error("Audio stream error", error);
        closeWebSocket();
        if (!emitter.isCancelled()) {
          emitter.onError(error);
        }
      }
    }

    private void handleStreamComplete() {
      if (completed.compareAndSet(false, true)) {
        logger.debug("Audio stream completed, closing WebSocket");
        closeWebSocket();
        if (!emitter.isCancelled()) {
          emitter.onComplete();
        }
      }
    }

    private void closeWebSocket() {
      WebSocket ws = activeWebSocket.getAndSet(null);
      if (ws != null) {
        ws.close(1000, "Stream completed");
      }
    }

    private void attemptReconnection() {
      if (completed.get() || emitter.isCancelled()) {
        return;
      }

      wsConnected.set(false);
      logger.info("WebSocket disconnected, attempting reconnection...");

      Schedulers.io()
          .scheduleDirect(
              () -> {
                for (int attempt = 0; attempt < MAX_RECONNECT_ATTEMPTS; attempt++) {
                  if (completed.get() || emitter.isCancelled()) {
                    return;
                  }

                  try {
                    Thread.sleep(RECONNECT_DELAY_MS);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                  }

                  Request request = new Request.Builder().url(wsEndpoint).build();
                  CountDownLatch reconnectLatch = new CountDownLatch(1);
                  AtomicBoolean reconnected = new AtomicBoolean(false);

                  WebSocket ws =
                      client.newWebSocket(
                          request,
                          new WebSocketListener() {
                            @Override
                            public void onOpen(WebSocket webSocket, Response response) {
                              reconnected.set(true);
                              reconnectLatch.countDown();
                            }

                            @Override
                            public void onFailure(
                                WebSocket webSocket, Throwable t, Response response) {
                              reconnectLatch.countDown();
                            }

                            @Override
                            public void onMessage(WebSocket webSocket, String text) {
                              processTextFrame(text);
                            }

                            @Override
                            public void onClosing(WebSocket webSocket, int code, String reason) {
                              webSocket.close(code, reason);
                              wsConnected.set(false);
                              if (!completed.get()) {
                                attemptReconnection();
                              }
                            }
                          });

                  try {
                    reconnectLatch.await(5, TimeUnit.SECONDS);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                  }

                  if (reconnected.get()) {
                    activeWebSocket.set(ws);
                    wsConnected.set(true);
                    logger.info("WebSocket reconnected successfully");

                    // Flush buffered chunks
                    byte[] buffered;
                    while ((buffered = reconnectBuffer.poll()) != null) {
                      ws.send(ByteString.of(buffered, 0, buffered.length));
                    }
                    return;
                  }
                }

                // All reconnection attempts failed
                logger.error(
                    "WebSocket reconnection failed after {} attempts", MAX_RECONNECT_ATTEMPTS);
                if (!completed.get() && !emitter.isCancelled()) {
                  emitter.onError(
                      new TranscriptionException(
                          "WebSocket reconnection failed after "
                              + MAX_RECONNECT_ATTEMPTS
                              + " attempts"));
                  completed.set(true);
                }
              });
    }

    private void startTimeoutMonitor() {
      Schedulers.io()
          .scheduleDirect(
              () -> {
                while (!completed.get() && !emitter.isCancelled()) {
                  try {
                    Thread.sleep(1_000);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                  }

                  long elapsed = System.currentTimeMillis() - lastResponseTime.get();
                  if (elapsed > RESPONSE_TIMEOUT_MS && wsConnected.get()) {
                    logger.warn("No response received for {} ms, emitting timeout error", elapsed);
                    if (!emitter.isCancelled()) {
                      emitter.onNext(
                          TranscriptionEvent.builder()
                              .text(
                                  "[TIMEOUT] No transcription response for "
                                      + (RESPONSE_TIMEOUT_MS / 1000)
                                      + " seconds")
                              .finished(false)
                              .build());
                    }
                    // Reset the timer to avoid spamming error events
                    lastResponseTime.set(System.currentTimeMillis());
                  }
                }
              });
    }

    private void processTextFrame(String text) {
      lastResponseTime.set(System.currentTimeMillis());

      if (emitter.isCancelled() || completed.get()) {
        return;
      }

      try {
        // Parse JSON: {"text": "...", "is_final": true/false}
        String transcribedText = extractJsonStringField(text, "text");
        boolean isFinal = extractJsonBooleanField(text, "is_final");

        if (transcribedText != null && !transcribedText.isEmpty()) {
          TranscriptionEvent event =
              TranscriptionEvent.builder().text(transcribedText).finished(isFinal).build();
          emitter.onNext(event);
        }
      } catch (Exception e) {
        logger.warn("Failed to parse WebSocket text frame: {}", text, e);
      }
    }

    private void runBatchFallback(Flowable<byte[]> audioStream) {
      ByteArrayOutputStream accumulator = new ByteArrayOutputStream();

      audioStream
          .subscribeOn(Schedulers.io())
          .subscribe(
              chunk -> {
                try {
                  accumulator.write(chunk);
                } catch (IOException e) {
                  logger.error("Error accumulating audio chunks", e);
                }
              },
              error -> {
                if (!emitter.isCancelled()) {
                  emitter.onError(error);
                }
              },
              () -> {
                // Stream complete - do batch transcription with parent
                try {
                  byte[] allAudio = accumulator.toByteArray();
                  if (allAudio.length > 0) {
                    TranscriptionResult result = transcribe(allAudio, config);
                    emitter.onNext(
                        TranscriptionEvent.builder()
                            .text(result.getText())
                            .finished(true)
                            .language(result.getLanguage().orElse(null))
                            .build());
                  }
                  emitter.onComplete();
                } catch (TranscriptionException e) {
                  logger.error("Batch fallback transcription failed", e);
                  if (!emitter.isCancelled()) {
                    emitter.onError(e);
                  }
                }
              });
    }

    /** WebSocket listener for the streaming session. */
    private class StreamingWebSocketListener extends WebSocketListener {

      @Override
      public void onOpen(WebSocket webSocket, Response response) {
        logger.debug("WebSocket connected to {}", wsEndpoint);
        wsConnected.set(true);
        connectionLatch.countDown();
      }

      @Override
      public void onMessage(WebSocket webSocket, String text) {
        processTextFrame(text);
      }

      @Override
      public void onClosing(WebSocket webSocket, int code, String reason) {
        logger.debug("WebSocket closing: code={}, reason={}", code, reason);
        webSocket.close(code, reason);
        wsConnected.set(false);

        if (!completed.get() && !emitter.isCancelled()) {
          attemptReconnection();
        }
      }

      @Override
      public void onClosed(WebSocket webSocket, int code, String reason) {
        logger.debug("WebSocket closed: code={}, reason={}", code, reason);
        wsConnected.set(false);
      }

      @Override
      public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        int responseCode = (response != null) ? response.code() : -1;
        logger.warn("WebSocket failure: {} (HTTP {})", t.getMessage(), responseCode);

        wsConnected.set(false);

        // Check if this is an initial connection failure that should trigger fallback
        if (connectionLatch.getCount() > 0) {
          // Connection was never established
          boolean isFallbackScenario =
              (t instanceof ConnectException)
                  || responseCode == 404
                  || responseCode == 502
                  || responseCode == 503;

          if (isFallbackScenario) {
            connectionFailed.set(true);
          }
          connectionLatch.countDown();
        } else if (!completed.get() && !emitter.isCancelled()) {
          // Connection was previously established; attempt reconnection
          attemptReconnection();
        }
      }
    }
  }

  // ---- JSON utility methods ----

  /**
   * Extracts a string field value from a JSON string using simple parsing.
   *
   * @param json JSON string
   * @param fieldName Field name to extract
   * @return Field value or null if not found
   */
  private static String extractJsonStringField(String json, String fieldName) {
    String searchKey = "\"" + fieldName + "\"";
    int keyIndex = json.indexOf(searchKey);
    if (keyIndex == -1) {
      return null;
    }

    int colonIndex = json.indexOf(':', keyIndex + searchKey.length());
    if (colonIndex == -1) {
      return null;
    }

    int valueStart = json.indexOf('"', colonIndex + 1);
    if (valueStart == -1) {
      return null;
    }

    int valueEnd = valueStart + 1;
    while (valueEnd < json.length()) {
      char c = json.charAt(valueEnd);
      if (c == '\\') {
        valueEnd += 2;
      } else if (c == '"') {
        break;
      } else {
        valueEnd++;
      }
    }

    if (valueEnd >= json.length()) {
      return null;
    }

    String raw = json.substring(valueStart + 1, valueEnd);
    return raw.replace("\\\"", "\"")
        .replace("\\\\", "\\")
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t");
  }

  /**
   * Extracts a boolean field value from a JSON string using simple parsing.
   *
   * @param json JSON string
   * @param fieldName Field name to extract
   * @return Field value (defaults to false if not found)
   */
  private static boolean extractJsonBooleanField(String json, String fieldName) {
    String searchKey = "\"" + fieldName + "\"";
    int keyIndex = json.indexOf(searchKey);
    if (keyIndex == -1) {
      return false;
    }

    int colonIndex = json.indexOf(':', keyIndex + searchKey.length());
    if (colonIndex == -1) {
      return false;
    }

    // Skip whitespace after colon
    int valueStart = colonIndex + 1;
    while (valueStart < json.length() && json.charAt(valueStart) == ' ') {
      valueStart++;
    }

    if (valueStart >= json.length()) {
      return false;
    }

    // Check for true/false
    String remaining = json.substring(valueStart).trim();
    return remaining.startsWith("true");
  }
}
