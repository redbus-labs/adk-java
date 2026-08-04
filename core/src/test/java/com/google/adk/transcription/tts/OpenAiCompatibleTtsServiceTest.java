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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.List;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OpenAiCompatibleTtsService}.
 *
 * @author Sandeep Belgavi
 * @since 2026-08-04
 */
@DisplayName("OpenAiCompatibleTtsService Tests")
class OpenAiCompatibleTtsServiceTest {

  private MockWebServer mockServer;
  private OpenAiCompatibleTtsService ttsService;
  private TtsConfig config;

  @BeforeEach
  void setUp() throws Exception {
    mockServer = new MockWebServer();
    mockServer.start(0);

    String baseUrl = mockServer.url("/").toString();
    if (baseUrl.endsWith("/")) {
      baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
    }

    ttsService = new OpenAiCompatibleTtsService(baseUrl);
    config =
        TtsConfig.builder()
            .endpoint(baseUrl)
            .voice("alloy")
            .model("tts-1")
            .outputFormat(TtsAudioFormat.WAV)
            .build();
  }

  @AfterEach
  void tearDown() throws Exception {
    mockServer.close();
  }

  @Test
  @DisplayName("Constructor throws on null endpoint")
  void testConstructorNullEndpoint() {
    assertThrows(IllegalArgumentException.class, () -> new OpenAiCompatibleTtsService(null));
  }

  @Test
  @DisplayName("Constructor throws on empty endpoint")
  void testConstructorEmptyEndpoint() {
    assertThrows(IllegalArgumentException.class, () -> new OpenAiCompatibleTtsService(""));
  }

  @Test
  @DisplayName("Constructor strips trailing slash from endpoint")
  void testConstructorStripsTrailingSlash() {
    OpenAiCompatibleTtsService service = new OpenAiCompatibleTtsService("http://localhost:8000/");
    assertThat(service.getEndpoint()).isEqualTo("http://localhost:8000");
  }

  @Test
  @DisplayName("synthesize returns audio bytes on success")
  void testSynthesizeSuccess() throws Exception {
    byte[] expectedAudio = new byte[] {0x52, 0x49, 0x46, 0x46, 0x01, 0x02, 0x03, 0x04};
    Buffer buffer = new Buffer();
    buffer.write(expectedAudio);
    // Capabilities probe: GET returns 404, HEAD returns 404 -> defaults used
    mockServer.enqueue(new MockResponse.Builder().code(404).build());
    mockServer.enqueue(new MockResponse.Builder().code(404).build());
    mockServer.enqueue(
        new MockResponse.Builder()
            .code(200)
            .addHeader("Content-Type", "application/octet-stream")
            .body(buffer)
            .build());

    byte[] result = ttsService.synthesize("Hello world", config);

    assertThat(result).isEqualTo(expectedAudio);

    // Skip capabilities probe requests
    mockServer.takeRequest();
    mockServer.takeRequest();
    RecordedRequest request = mockServer.takeRequest();
    assertThat(request.getMethod()).isEqualTo("POST");
    String body = request.getBody().utf8();
    assertThat(body).contains("\"input\":\"Hello world\"");
    assertThat(body).contains("\"voice\":\"alloy\"");
    assertThat(body).contains("\"model\":\"tts-1\"");
  }

  @Test
  @DisplayName("synthesize includes API key in Authorization header")
  void testSynthesizeWithApiKey() throws Exception {
    String baseUrl = mockServer.url("/").toString();
    if (baseUrl.endsWith("/")) {
      baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
    }
    OpenAiCompatibleTtsService serviceWithKey =
        new OpenAiCompatibleTtsService(baseUrl, "sk-test-key-123");

    byte[] audioData = new byte[] {0x01, 0x02};
    Buffer buffer = new Buffer();
    buffer.write(audioData);
    // Capabilities probe: GET returns 404, HEAD returns 404 -> defaults used
    mockServer.enqueue(new MockResponse.Builder().code(404).build());
    mockServer.enqueue(new MockResponse.Builder().code(404).build());
    mockServer.enqueue(new MockResponse.Builder().code(200).body(buffer).build());

    serviceWithKey.synthesize("Test", config);

    // Skip capabilities probe requests
    mockServer.takeRequest();
    mockServer.takeRequest();
    RecordedRequest request = mockServer.takeRequest();
    assertThat(request.getHeaders().get("Authorization")).isEqualTo("Bearer sk-test-key-123");
  }

  @Test
  @DisplayName("synthesize throws TtsException on HTTP error")
  void testSynthesizeHttpError() {
    // Capabilities probe: GET returns 404, HEAD returns 404 -> defaults used
    mockServer.enqueue(new MockResponse.Builder().code(404).build());
    mockServer.enqueue(new MockResponse.Builder().code(404).build());
    mockServer.enqueue(
        new MockResponse.Builder()
            .code(500)
            .body("{\"error\": \"Internal Server Error\"}")
            .build());

    TtsException exception =
        assertThrows(TtsException.class, () -> ttsService.synthesize("Hello", config));

    assertThat(exception.getMessage()).contains("500");
    assertThat(exception.getErrorCode()).isEqualTo("HTTP_500");
  }

  @Test
  @DisplayName("synthesize throws TtsException on HTTP 429 rate limit")
  void testSynthesizeRateLimitError() {
    // Capabilities probe: GET returns 404, HEAD returns 404 -> defaults used
    mockServer.enqueue(new MockResponse.Builder().code(404).build());
    mockServer.enqueue(new MockResponse.Builder().code(404).build());
    mockServer.enqueue(
        new MockResponse.Builder().code(429).body("{\"error\": \"Rate limit exceeded\"}").build());

    TtsException exception =
        assertThrows(TtsException.class, () -> ttsService.synthesize("Hello", config));

    assertThat(exception.getErrorCode()).isEqualTo("HTTP_429");
  }

  @Test
  @DisplayName("synthesize throws TtsException on null input")
  void testSynthesizeNullInput() {
    assertThrows(TtsException.class, () -> ttsService.synthesize(null, config));
  }

  @Test
  @DisplayName("synthesize throws TtsException on empty input")
  void testSynthesizeEmptyInput() {
    assertThrows(TtsException.class, () -> ttsService.synthesize("", config));
  }

  @Test
  @DisplayName("synthesizeAsync returns Single with audio bytes")
  void testSynthesizeAsync() {
    byte[] expectedAudio = new byte[] {0x10, 0x20, 0x30};
    Buffer buffer = new Buffer();
    buffer.write(expectedAudio);
    // Capabilities probe: GET returns 404, HEAD returns 404 -> defaults used
    mockServer.enqueue(new MockResponse.Builder().code(404).build());
    mockServer.enqueue(new MockResponse.Builder().code(404).build());
    mockServer.enqueue(new MockResponse.Builder().code(200).body(buffer).build());

    Single<byte[]> result = ttsService.synthesizeAsync("Hello async", config);
    byte[] audioBytes = result.blockingGet();

    assertThat(audioBytes).isEqualTo(expectedAudio);
  }

  @Test
  @DisplayName("synthesizeAsync propagates error as Single error")
  void testSynthesizeAsyncError() {
    // Capabilities probe: GET returns 404, HEAD returns 404 -> defaults used
    mockServer.enqueue(new MockResponse.Builder().code(404).build());
    mockServer.enqueue(new MockResponse.Builder().code(404).build());
    mockServer.enqueue(new MockResponse.Builder().code(503).body("Service Unavailable").build());

    Single<byte[]> result = ttsService.synthesizeAsync("Hello", config);

    assertThrows(RuntimeException.class, result::blockingGet);
  }

  @Test
  @DisplayName("synthesizeStream returns Flowable of audio chunks")
  void testSynthesizeStream() {
    byte[] largeAudio = new byte[8192];
    for (int i = 0; i < largeAudio.length; i++) {
      largeAudio[i] = (byte) (i % 256);
    }
    Buffer buffer = new Buffer();
    buffer.write(largeAudio);
    mockServer.enqueue(
        new MockResponse.Builder()
            .code(200)
            .addHeader("Content-Type", "application/octet-stream")
            .body(buffer)
            .build());

    Flowable<byte[]> stream = ttsService.synthesizeStream("Stream this text", config);
    List<byte[]> chunks = new ArrayList<>();
    stream.blockingForEach(chunks::add);

    assertThat(chunks).isNotEmpty();
    int totalBytes = chunks.stream().mapToInt(c -> c.length).sum();
    assertThat(totalBytes).isEqualTo(largeAudio.length);
  }

  @Test
  @DisplayName("synthesizeStream emits error on HTTP failure")
  void testSynthesizeStreamError() {
    mockServer.enqueue(
        new MockResponse.Builder().code(500).body("{\"error\": \"Server error\"}").build());

    Flowable<byte[]> stream = ttsService.synthesizeStream("Hello", config);

    assertThrows(RuntimeException.class, () -> stream.blockingFirst());
  }

  @Test
  @DisplayName("synthesizeStream emits error on null input")
  void testSynthesizeStreamNullInput() {
    Flowable<byte[]> stream = ttsService.synthesizeStream(null, config);

    assertThrows(RuntimeException.class, () -> stream.blockingFirst());
  }

  @Test
  @DisplayName("isAvailable returns true when server responds OK")
  void testIsAvailableTrue() {
    mockServer.enqueue(new MockResponse.Builder().code(200).build());

    boolean available = ttsService.isAvailable();

    assertThat(available).isTrue();
  }

  @Test
  @DisplayName("isAvailable returns false when server returns error")
  void testIsAvailableFalseOnError() {
    mockServer.enqueue(new MockResponse.Builder().code(503).build());

    boolean available = ttsService.isAvailable();

    assertThat(available).isFalse();
  }

  @Test
  @DisplayName("isAvailable returns false when server is unreachable")
  void testIsAvailableFalseWhenUnreachable() throws Exception {
    mockServer.close();

    boolean available = ttsService.isAvailable();

    assertThat(available).isFalse();
  }

  @Test
  @DisplayName("getHealth returns healthy status when server is up")
  void testGetHealthHealthy() {
    mockServer.enqueue(new MockResponse.Builder().code(200).build());

    var health = ttsService.getHealth();

    assertThat(health.isAvailable()).isTrue();
    assertThat(health.getMessage().isPresent()).isTrue();
    assertThat(health.getMessage().get()).contains("healthy");
    assertThat(health.getResponseTimeMs().isPresent()).isTrue();
    assertThat(health.getResponseTimeMs().get()).isAtLeast(0L);
  }

  @Test
  @DisplayName("getHealth returns unhealthy when server is down")
  void testGetHealthUnhealthy() throws Exception {
    mockServer.close();

    var health = ttsService.getHealth();

    assertThat(health.isAvailable()).isFalse();
    assertThat(health.getMessage().isPresent()).isTrue();
    assertThat(health.getMessage().get()).contains("failed");
  }
}
