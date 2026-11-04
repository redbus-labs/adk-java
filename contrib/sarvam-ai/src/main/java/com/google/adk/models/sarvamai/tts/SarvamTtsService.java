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

package com.google.adk.models.sarvamai.tts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.sarvamai.SarvamAiConfig;
import com.google.adk.models.sarvamai.SarvamAiException;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sarvam AI Text-to-Speech service with both REST and WebSocket streaming support.
 *
 * <p>REST mode ({@link #synthesize}): Sends text and returns the complete audio as a byte array
 * (decoded from base64). Uses the Bulbul v3 model with 30+ speaker voices.
 *
 * <p>WebSocket streaming mode ({@link #synthesizeStream}): Opens a persistent WebSocket connection
 * for progressive audio chunk delivery with low latency. Audio chunks are emitted as they are
 * synthesized, enabling real-time playback.
 *
 * @author Sandeep Belgavi
 */
public final class SarvamTtsService {

  private static final Logger logger = LoggerFactory.getLogger(SarvamTtsService.class);
  private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

  private final SarvamAiConfig config;
  private final OkHttpClient httpClient;
  private final ObjectMapper objectMapper;

  public SarvamTtsService(SarvamAiConfig config, OkHttpClient httpClient) {
    this.config = Objects.requireNonNull(config);
    this.httpClient = Objects.requireNonNull(httpClient);
    this.objectMapper = new ObjectMapper();
  }

  /**
   * Synthesizes speech from text synchronously via the REST endpoint.
   *
   * @param text the text to convert to speech (max 2500 chars for bulbul:v3)
   * @param targetLanguageCode BCP-47 language code (e.g., "en-IN", "hi-IN")
   * @return decoded audio bytes (WAV format by default)
   */
  public byte[] synthesize(String text, String targetLanguageCode) {
    Objects.requireNonNull(text, "text must not be null");
    Objects.requireNonNull(targetLanguageCode, "targetLanguageCode must not be null");

    String model = config.ttsModel().orElse("bulbul:v3");
    String speaker = config.ttsSpeaker().orElse("shubh");
    Double pace = config.ttsPace().isPresent() ? config.ttsPace().getAsDouble() : null;
    Integer sampleRate =
        config.ttsSampleRate().isPresent() ? config.ttsSampleRate().getAsInt() : null;

    TtsRequest ttsRequest =
        new TtsRequest(text, targetLanguageCode, model, speaker, pace, sampleRate);

    try {
      String body = objectMapper.writeValueAsString(ttsRequest);

      Request request =
          new Request.Builder()
              .url(config.ttsEndpoint())
              .addHeader("api-subscription-key", config.apiKey())
              .addHeader("Content-Type", "application/json")
              .post(RequestBody.create(body, JSON_MEDIA_TYPE))
              .build();

      logger.debug(
          "Sending TTS request to {} with model={}, speaker={}",
          config.ttsEndpoint(),
          model,
          speaker);

      try (Response response = httpClient.newCall(request).execute()) {
        if (!response.isSuccessful()) {
          String errorBody = response.body() != null ? response.body().string() : "";
          throw new SarvamAiException(
              "TTS request failed: " + response.code() + " " + errorBody,
              response.code(),
              null,
              null);
        }

        TtsResponse ttsResponse =
            objectMapper.readValue(response.body().string(), TtsResponse.class);
        if (ttsResponse.getAudios() == null || ttsResponse.getAudios().isEmpty()) {
          throw new SarvamAiException("TTS response contained no audio data");
        }

        String combinedBase64 = String.join("", ttsResponse.getAudios());
        return Base64.getDecoder().decode(combinedBase64);
      }
    } catch (SarvamAiException e) {
      throw e;
    } catch (Exception e) {
      throw new SarvamAiException("TTS synthesis failed", e);
    }
  }

  /** Async version of {@link #synthesize}. */
  public Single<byte[]> synthesizeAsync(String text, String targetLanguageCode) {
    return Single.fromCallable(() -> synthesize(text, targetLanguageCode))
        .subscribeOn(Schedulers.io());
  }

  /**
   * Streams TTS audio via WebSocket for low-latency, progressive playback.
   *
   * <p>Opens a WebSocket to Sarvam's streaming TTS endpoint, sends config + text, and emits decoded
   * audio chunks as they arrive. Each chunk is a raw audio byte array ready for playback.
   *
   * @param text the text to synthesize
   * @param targetLanguageCode BCP-47 language code
   * @return a Flowable of audio byte[] chunks
   */
  public Flowable<byte[]> synthesizeStream(String text, String targetLanguageCode) {
    Objects.requireNonNull(text, "text must not be null");
    Objects.requireNonNull(targetLanguageCode, "targetLanguageCode must not be null");

    return Flowable.create(
        emitter -> {
          String model = config.ttsModel().orElse("bulbul:v3");
          String speaker = config.ttsSpeaker().orElse("shubh");

          String wsUrl = config.ttsWsEndpoint() + "?model=" + model;

          Request wsRequest =
              new Request.Builder()
                  .url(wsUrl)
                  .addHeader("api-subscription-key", config.apiKey())
                  .build();

          logger.debug("Opening TTS WebSocket to {}", wsUrl);

          WebSocket webSocket =
              httpClient.newWebSocket(
                  wsRequest,
                  new WebSocketListener() {
                    @Override
                    public void onOpen(WebSocket ws, Response response) {
                      logger.debug("TTS WebSocket connected");

                      String configMsg =
                          String.format(
                              "{\"type\":\"config\",\"data\":{\"speaker\":\"%s\","
                                  + "\"target_language_code\":\"%s\"}}",
                              speaker, targetLanguageCode);
                      ws.send(configMsg);

                      String textMsg =
                          String.format(
                              "{\"type\":\"text\",\"data\":{\"text\":\"%s\"}}",
                              text.replace("\"", "\\\""));
                      ws.send(textMsg);

                      ws.send("{\"type\":\"flush\"}");
                    }

                    @Override
                    public void onMessage(WebSocket ws, String messageText) {
                      try {
                        JsonNode node = objectMapper.readTree(messageText);
                        String type = node.path("type").asText("");

                        if ("audio".equals(type)) {
                          String audioBase64 = node.path("data").path("audio").asText("");
                          if (!audioBase64.isEmpty()) {
                            byte[] audioChunk = Base64.getDecoder().decode(audioBase64);
                            emitter.onNext(audioChunk);
                          }
                        } else if ("event".equals(type)) {
                          String eventType = node.path("data").path("event_type").asText("");
                          if ("final".equals(eventType)) {
                            ws.close(1000, "Synthesis complete");
                          }
                        }
                      } catch (Exception e) {
                        logger.warn("Failed to parse TTS WS message", e);
                      }
                    }

                    @Override
                    public void onClosing(WebSocket ws, int code, String reason) {
                      ws.close(code, reason);
                    }

                    @Override
                    public void onClosed(WebSocket ws, int code, String reason) {
                      logger.debug("TTS WebSocket closed: {} {}", code, reason);
                      emitter.onComplete();
                    }

                    @Override
                    public void onFailure(WebSocket ws, Throwable t, Response response) {
                      logger.error("TTS WebSocket failure", t);
                      if (!emitter.isCancelled()) {
                        emitter.onError(
                            new SarvamAiException("TTS WebSocket connection failed", t));
                      }
                    }
                  });

          emitter.setCancellable(() -> webSocket.close(1000, "Cancelled"));
        },
        BackpressureStrategy.BUFFER);
  }

  /**
   * Streams raw, little-endian PCM16 via {@code POST /text-to-speech/stream}.
   *
   * <p>Each subscription opens one request on the IO scheduler. Disposing the subscription cancels
   * both a pending connection and a blocked body read. Chunks always contain whole 16-bit samples;
   * a truncated final sample is an error. This method never retries or replays delivered audio.
   * Voice selection, language detection, turn ownership and playback interruption belong to
   * callers.
   *
   * @param text speech text, limited to 3500 characters
   * @param targetLanguageCode explicit BCP-47 language code
   */
  public Flowable<byte[]> synthesizeHttpStream(String text, String targetLanguageCode) {
    Objects.requireNonNull(text, "text must not be null");
    Objects.requireNonNull(targetLanguageCode, "targetLanguageCode must not be null");
    if (text.isBlank() || text.length() > 3500) {
      throw new IllegalArgumentException("Speech text must contain 1 to 3500 characters");
    }

    return Flowable.<byte[]>create(
            emitter -> {
              var body = objectMapper.createObjectNode();
              body.put("text", text.trim());
              body.put("language_code", targetLanguageCode);
              body.put("speaker", config.ttsSpeaker().orElse("shubh"));
              body.put("model", config.ttsModel().orElse("bulbul:v3"));
              body.put("speech_sample_rate", config.ttsSampleRate().orElse(24000));
              body.put("output_audio_codec", "linear16");
              config.ttsPace().ifPresent(value -> body.put("pace", value));
              config.ttsTemperature().ifPresent(value -> body.put("temperature", value));
              String endpoint = config.ttsEndpoint().replaceAll("/$", "") + "/stream";
              Request request =
                  new Request.Builder()
                      .url(endpoint)
                      .header("api-subscription-key", config.apiKey())
                      .post(
                          RequestBody.create(
                              objectMapper.writeValueAsString(body), JSON_MEDIA_TYPE))
                      .build();
              // Do not retry a speech request after any portion of its audio has been played.
              Call call =
                  httpClient.newBuilder().retryOnConnectionFailure(false).build().newCall(request);
              call.timeout().timeout(config.readTimeout().toMillis(), TimeUnit.MILLISECONDS);
              emitter.setCancellable(call::cancel);
              if (emitter.isCancelled()) return;
              try (Response response = call.execute()) {
                if (!response.isSuccessful()) {
                  throw new IOException(
                      "Sarvam streaming TTS failed with status " + response.code());
                }
                if (response.body() == null) throw new IOException("Missing streaming TTS body");
                String contentType = response.header("Content-Type", "").toLowerCase(Locale.ROOT);
                if (contentType.contains("json")) {
                  throw new IOException("Sarvam streaming TTS returned JSON instead of PCM");
                }
                try (InputStream input = response.body().byteStream()) {
                  byte[] buffer = new byte[8192];
                  int carry = -1;
                  boolean delivered = false;
                  int count;
                  while (!emitter.isCancelled() && (count = input.read(buffer)) != -1) {
                    if (count == 0) continue;
                    byte[] combined = new byte[count + (carry >= 0 ? 1 : 0)];
                    int offset = carry >= 0 ? 1 : 0;
                    if (offset == 1) combined[0] = (byte) carry;
                    System.arraycopy(buffer, 0, combined, offset, count);
                    int aligned = combined.length & ~1;
                    carry = aligned < combined.length ? combined[aligned] & 255 : -1;
                    if (aligned > 0 && !emitter.isCancelled()) {
                      emitter.onNext(Arrays.copyOf(combined, aligned));
                      delivered = true;
                    }
                  }
                  if (!emitter.isCancelled()) {
                    if (carry >= 0)
                      throw new IOException("Truncated PCM16 sample in streaming TTS");
                    if (!delivered) throw new IOException("Streaming TTS returned no audio");
                    emitter.onComplete();
                  }
                }
              } catch (Exception error) {
                emitter.tryOnError(error);
              }
            },
            BackpressureStrategy.BUFFER)
        .subscribeOn(Schedulers.io());
  }

  /**
   * Blocking callback adapter for callers with an existing per-turn cancellation signal.
   * Cancellation disposes the HTTP stream, including a pending connection or blocked read.
   */
  public void synthesizeHttpStream(
      String text,
      String language,
      java.util.function.Consumer<byte[]> onAudio,
      java.util.function.BooleanSupplier cancelled)
      throws IOException, InterruptedException {
    Objects.requireNonNull(onAudio);
    Objects.requireNonNull(cancelled);
    if (cancelled.getAsBoolean()) return;
    var complete = new java.util.concurrent.CountDownLatch(1);
    var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    var subscription =
        synthesizeHttpStream(text, language)
            .subscribe(
                chunk -> {
                  if (!cancelled.getAsBoolean()) onAudio.accept(chunk);
                },
                error -> {
                  failure.set(error);
                  complete.countDown();
                },
                complete::countDown);
    try {
      while (!complete.await(50, TimeUnit.MILLISECONDS)) {
        if (cancelled.getAsBoolean()) return;
      }
      if (!cancelled.getAsBoolean() && failure.get() != null) {
        Throwable error = failure.get();
        if (error instanceof IOException) throw (IOException) error;
        throw new IOException("Sarvam streaming TTS failed", error);
      }
    } finally {
      subscription.dispose();
    }
  }

  public boolean isAvailable() {
    return config.apiKey() != null && !config.apiKey().isEmpty();
  }
}
