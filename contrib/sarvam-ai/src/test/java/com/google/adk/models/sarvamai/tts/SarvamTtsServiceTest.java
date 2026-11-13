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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.adk.models.sarvamai.SarvamAiConfig;
import com.google.adk.models.sarvamai.SarvamAiException;
import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * @author Sandeep Belgavi
 */
class SarvamTtsServiceTest {

  private MockWebServer server;
  private SarvamTtsService ttsService;

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();

    SarvamAiConfig config =
        SarvamAiConfig.builder()
            .apiKey("test-tts-key")
            .ttsEndpoint(server.url("/text-to-speech").toString())
            .ttsModel("bulbul:v3")
            .ttsSpeaker("shubh")
            .build();

    ttsService = new SarvamTtsService(config, new OkHttpClient());
  }

  @AfterEach
  void tearDown() throws IOException {
    server.shutdown();
  }

  @Test
  void synthesize_success() throws InterruptedException {
    byte[] expectedAudio = "fake-audio-data".getBytes();
    String base64Audio = Base64.getEncoder().encodeToString(expectedAudio);
    String responseBody =
        String.format("{\"request_id\":\"req-456\",\"audios\":[\"%s\"]}", base64Audio);

    server.enqueue(new MockResponse().setBody(responseBody));

    byte[] audio = ttsService.synthesize("Hello world", "en-IN");

    assertThat(audio).isEqualTo(expectedAudio);

    RecordedRequest recorded = server.takeRequest(5, TimeUnit.SECONDS);
    assertThat(recorded.getHeader("api-subscription-key")).isEqualTo("test-tts-key");
    String body = recorded.getBody().readUtf8();
    assertThat(body).contains("\"model\":\"bulbul:v3\"");
    assertThat(body).contains("\"speaker\":\"shubh\"");
    assertThat(body).contains("\"target_language_code\":\"en-IN\"");
  }

  @Test
  void synthesize_serverError_throwsException() {
    server.enqueue(new MockResponse().setResponseCode(500).setBody("Server error"));

    assertThrows(SarvamAiException.class, () -> ttsService.synthesize("Hello", "en-IN"));
  }

  @Test
  void synthesize_emptyAudio_throwsException() {
    server.enqueue(new MockResponse().setBody("{\"request_id\":\"req-789\",\"audios\":[]}"));

    assertThrows(SarvamAiException.class, () -> ttsService.synthesize("Hello", "en-IN"));
  }

  @Test
  void synthesize_nullText_throwsNpe() {
    assertThrows(NullPointerException.class, () -> ttsService.synthesize(null, "en-IN"));
  }

  @Test
  void httpStream_preservesSamplesAcrossOddChunksAndUsesConfiguredRequest() throws Exception {
    byte[] pcm = new byte[100];
    for (int i = 0; i < pcm.length; i++) pcm[i] = (byte) i;
    server.enqueue(
        new MockResponse()
            .setHeader("Content-Type", "application/octet-stream")
            .setChunkedBody(new okio.Buffer().write(pcm), 3)
            .throttleBody(3, 5, TimeUnit.MILLISECONDS));
    SarvamAiConfig config =
        SarvamAiConfig.builder()
            .apiKey("stream-key")
            .ttsEndpoint(server.url("/tts/").toString())
            .ttsSpeaker("ishita")
            .ttsSampleRate(16000)
            .ttsPace(1.1)
            .ttsTemperature(0.6)
            .build();
    var subscriber =
        new SarvamTtsService(config, new OkHttpClient())
            .synthesizeHttpStream("नमस्ते", "hi-IN")
            .test();
    subscriber.awaitDone(5, TimeUnit.SECONDS).assertComplete().assertNoErrors();
    var actual = new java.io.ByteArrayOutputStream();
    for (byte[] chunk : subscriber.values()) {
      assertThat(chunk.length % 2).isEqualTo(0);
      actual.write(chunk);
    }
    assertThat(actual.toByteArray()).isEqualTo(pcm);
    RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
    assertThat(request.getPath()).isEqualTo("/tts/stream");
    assertThat(request.getHeader("api-subscription-key")).isEqualTo("stream-key");
    var body =
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(request.getBody().readUtf8());
    assertThat(body.path("language_code").asText()).isEqualTo("hi-IN");
    assertThat(body.path("speaker").asText()).isEqualTo("ishita");
    assertThat(body.path("model").asText()).isEqualTo("bulbul:v3");
    assertThat(body.path("speech_sample_rate").asInt()).isEqualTo(16000);
    assertThat(body.path("output_audio_codec").asText()).isEqualTo("linear16");
    assertThat(body.path("temperature").asDouble()).isEqualTo(0.6);
    assertThat(body.path("pace").asDouble()).isEqualTo(1.1);
  }

  @Test
  void httpStream_rejectsTruncatedSampleWithoutReplay() {
    server.enqueue(new MockResponse().setBody(new okio.Buffer().write(new byte[] {1, 2, 3})));
    var subscriber = ttsService.synthesizeHttpStream("Hello", "en-IN").test();
    subscriber.awaitDone(3, TimeUnit.SECONDS).assertError(IOException.class).assertValueCount(1);
    assertThat(subscriber.values().get(0)).isEqualTo(new byte[] {1, 2});
    assertThat(server.getRequestCount()).isEqualTo(1);
  }

  @Test
  void httpStream_rejectsJsonAndEmptyAudio() {
    server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{}"));
    ttsService
        .synthesizeHttpStream("Hello", "en-IN")
        .test()
        .awaitDone(3, TimeUnit.SECONDS)
        .assertError(IOException.class)
        .assertNoValues();
    server.enqueue(new MockResponse().setBody(""));
    ttsService
        .synthesizeHttpStream("Hello", "en-IN")
        .test()
        .awaitDone(3, TimeUnit.SECONDS)
        .assertError(IOException.class)
        .assertNoValues();
  }

  @Test
  void httpStream_cancellationAbortsPendingHeaders() throws Exception {
    server.enqueue(
        new MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE));
    OkHttpClient client = new OkHttpClient();
    SarvamAiConfig config =
        SarvamAiConfig.builder().apiKey("key").ttsEndpoint(server.url("/tts").toString()).build();
    var subscriber =
        new SarvamTtsService(config, client).synthesizeHttpStream("Hello", "en-IN").test();
    assertThat(server.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
    subscriber.cancel();
    awaitNoRunningCalls(client);
    subscriber.assertNoValues().assertNoErrors();
  }

  @Test
  void httpStream_cancellationAbortsStalledBody() throws Exception {
    server.enqueue(
        new MockResponse()
            .setBody(new okio.Buffer().write(new byte[16384]))
            .throttleBody(8192, 2, TimeUnit.SECONDS));
    OkHttpClient client = new OkHttpClient();
    SarvamAiConfig config =
        SarvamAiConfig.builder().apiKey("key").ttsEndpoint(server.url("/tts").toString()).build();
    var subscriber =
        new SarvamTtsService(config, client).synthesizeHttpStream("Hello", "en-IN").test();
    subscriber.awaitCount(1);
    assertThat(subscriber.values()).isNotEmpty();
    subscriber.cancel();
    awaitNoRunningCalls(client);
    subscriber.assertNoErrors();
  }

  @Test
  void httpStream_deadlineIncludesBodyRead() {
    server.enqueue(
        new MockResponse()
            .setBody(new okio.Buffer().write(new byte[16384]))
            .throttleBody(8192, 2, TimeUnit.SECONDS));
    SarvamAiConfig config =
        SarvamAiConfig.builder()
            .apiKey("key")
            .ttsEndpoint(server.url("/tts").toString())
            .readTimeout(java.time.Duration.ofMillis(200))
            .build();
    var subscriber =
        new SarvamTtsService(config, new OkHttpClient())
            .synthesizeHttpStream("Hello", "en-IN")
            .test();
    subscriber.awaitDone(3, TimeUnit.SECONDS).assertError(IOException.class);
    assertThat(subscriber.values().stream().mapToInt(chunk -> chunk.length).sum()).isEqualTo(8192);
  }

  private static void awaitNoRunningCalls(OkHttpClient client) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (client.dispatcher().runningCallsCount() > 0 && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertThat(client.dispatcher().runningCallsCount()).isEqualTo(0);
  }

  @Test
  void isAvailable_returnsTrue() {
    assertThat(ttsService.isAvailable()).isTrue();
  }
}
