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

// MODIFIED BY Sandeep Belgavi, 2026-02-11
package com.google.adk.transcription.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.transcription.ServiceHealth;
import com.google.adk.transcription.ServiceType;
import com.google.adk.transcription.TranscriptionConfig;
import com.google.adk.transcription.TranscriptionEvent;
import com.google.adk.transcription.TranscriptionException;
import com.google.adk.transcription.TranscriptionResult;
import com.google.adk.transcription.TranscriptionService;
import com.google.adk.transcription.processor.AudioChunkAggregator;
import com.google.common.base.Strings;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sarvam AI transcription service implementation.
 *
 * @author Sandeep Belgavi
 * @since 2026-02-11
 */
public class SarvamTranscriptionService implements TranscriptionService {
  private static final Logger logger = LoggerFactory.getLogger(SarvamTranscriptionService.class);
  private static final String API_URL = "https://api.sarvam.ai/speech-to-text";

  private final OkHttpClient client;
  private final String apiKey;
  private final ObjectMapper objectMapper;

  public SarvamTranscriptionService() {
    this(null);
  }

  public SarvamTranscriptionService(String apiKey) {
    if (Strings.isNullOrEmpty(apiKey)) {
      this.apiKey = System.getenv("SARVAM_API_KEY");
    } else {
      this.apiKey = apiKey;
    }

    if (Strings.isNullOrEmpty(this.apiKey)) {
      logger.warn("Sarvam API key not found. STT will fail.");
    }

    this.client =
        new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();
    this.objectMapper = new ObjectMapper();
  }

  @Override
  public TranscriptionResult transcribe(byte[] audioData, TranscriptionConfig requestConfig)
      throws TranscriptionException {
    try {
      RequestBody fileBody = RequestBody.create(audioData, MediaType.parse("audio/wav"));

      MultipartBody requestBody =
          new MultipartBody.Builder()
              .setType(MultipartBody.FORM)
              .addFormDataPart("file", "audio.wav", fileBody)
              .addFormDataPart("model", "saaras_v3")
              .addFormDataPart("language_code", requestConfig.getLanguage())
              .build();

      Request request =
          new Request.Builder()
              .url(API_URL)
              .addHeader("api-subscription-key", apiKey)
              .post(requestBody)
              .build();

      try (Response response = client.newCall(request).execute()) {
        if (!response.isSuccessful()) {
          String errorBody = response.body() != null ? response.body().string() : "";
          throw new IOException("Unexpected code " + response + " body: " + errorBody);
        }

        JsonNode root = objectMapper.readTree(response.body().string());
        String transcript = root.path("transcript").asText();

        return TranscriptionResult.builder()
            .text(transcript)
            .timestamp(System.currentTimeMillis())
            .build();
      }
    } catch (Exception e) {
      logger.error("Error transcribing audio with Sarvam", e);
      throw new TranscriptionException("Transcription failed", e);
    }
  }

  @Override
  public Single<TranscriptionResult> transcribeAsync(
      byte[] audioData, TranscriptionConfig requestConfig) {
    return Single.fromCallable(() -> transcribe(audioData, requestConfig))
        .subscribeOn(io.reactivex.rxjava3.schedulers.Schedulers.io());
  }

  @Override
  public Flowable<TranscriptionEvent> transcribeStream(
      Flowable<byte[]> audioStream, TranscriptionConfig requestConfig) {
    AudioChunkAggregator aggregator =
        new AudioChunkAggregator(
            requestConfig.getAudioFormat(), Duration.ofMillis(requestConfig.getChunkSizeMs()));

    return audioStream
        .buffer(requestConfig.getChunkSizeMs(), TimeUnit.MILLISECONDS)
        .map(
            chunks -> {
              byte[] aggregated = aggregator.aggregate(chunks);
              try {
                TranscriptionResult result = transcribe(aggregated, requestConfig);
                return mapToTranscriptionEvent(result);
              } catch (TranscriptionException e) {
                logger.error("Stream transcription error", e);
                throw new RuntimeException(e);
              }
            });
  }

  @Override
  public boolean isAvailable() {
    return !Strings.isNullOrEmpty(apiKey);
  }

  @Override
  public ServiceType getServiceType() {
    return ServiceType.SARVAM;
  }

  @Override
  public ServiceHealth getHealth() {
    return ServiceHealth.builder().available(isAvailable()).serviceType(ServiceType.SARVAM).build();
  }

  private TranscriptionEvent mapToTranscriptionEvent(TranscriptionResult result) {
    return TranscriptionEvent.builder()
        .text(result.getText())
        .finished(true)
        .timestamp(result.getTimestamp())
        .build();
  }
}
