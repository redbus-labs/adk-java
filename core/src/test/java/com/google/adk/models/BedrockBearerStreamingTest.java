/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.adk.models;

import static com.google.common.truth.Truth.assertThat;

import com.google.adk.models.failover.FailoverLlm;
import com.google.adk.models.failover.LlmHttpException;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.sun.net.httpserver.HttpServer;
import io.reactivex.rxjava3.core.Flowable;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.http.Protocol;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.eventstream.HeaderValue;
import software.amazon.eventstream.Message;

/** Exercises actual bearer HTTP authentication and binary decoding, without AWS credentials. */
class BedrockBearerStreamingTest {
  private HttpServer server;
  private BedrockRuntimeAsyncClient fixtureClient;
  private byte[] response;
  private int status = 200;
  private String previousRegion;
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private final AtomicReference<String> path = new AtomicReference<>();
  private final AtomicInteger calls = new AtomicInteger();

  @BeforeEach
  void startServer() throws Exception {
    previousRegion = System.getProperty("aws.region");
    System.setProperty("aws.region", "ap-south-1");
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          calls.incrementAndGet();
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          path.set(exchange.getRequestURI().getPath());
          exchange.getRequestBody().readAllBytes();
          exchange
              .getResponseHeaders()
              .set(
                  "Content-Type",
                  status == 200 ? "application/vnd.amazon.eventstream" : "application/json");
          if (status != 200) {
            exchange.getResponseHeaders().set("x-amzn-errortype", "AccessDeniedException");
          }
          exchange.sendResponseHeaders(status, 0);
          try (var output = exchange.getResponseBody()) {
            // HTTP chunks deliberately split AWS frame headers and payloads at arbitrary positions.
            for (int offset = 0; offset < response.length; offset += 7) {
              output.write(response, offset, Math.min(7, response.length - offset));
              output.flush();
            }
          }
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    if (fixtureClient != null) fixtureClient.close();
    if (server != null) server.stop(0);
    if (previousRegion == null) System.clearProperty("aws.region");
    else System.setProperty("aws.region", previousRegion);
  }

  @Test
  void bearerStreamDecodesFragmentedTextAndUsageAndCompletes() {
    response =
        frames(
            event("messageStart", "{\"role\":\"assistant\"}"),
            event("contentBlockDelta", "{\"contentBlockIndex\":0,\"delta\":{\"text\":\"hello \"}}"),
            event("contentBlockDelta", "{\"contentBlockIndex\":0,\"delta\":{\"text\":\"world\"}}"),
            event("contentBlockStop", "{\"contentBlockIndex\":0}"),
            event("messageStop", "{\"stopReason\":\"end_turn\"}"),
            event(
                "metadata",
                "{\"usage\":{\"inputTokens\":2,\"outputTokens\":3,\"totalTokens\":5,\"cacheReadInputTokens\":4},\"metrics\":{\"latencyMs\":1}}"));

    List<LlmResponse> responses = collect(model());

    assertThat(responses).hasSize(3);
    assertThat(responses.get(0).partial()).hasValue(true);
    assertThat(responses.get(1).partial()).hasValue(true);
    LlmResponse last = responses.get(2);
    assertThat(last.partial()).hasValue(false);
    assertThat(last.content().orElseThrow().parts().orElseThrow().get(0).text())
        .hasValue("hello world");
    assertThat(last.usageMetadata().orElseThrow().totalTokenCount()).hasValue(5);
    assertThat(last.usageMetadata().orElseThrow().cachedContentTokenCount()).hasValue(4);
    assertThat(authorization.get()).isEqualTo("Bearer regression-test-token");
    assertThat(path.get()).isEqualTo("/model/regression-model/converse-stream");
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void bearerStreamReassemblesToolArgumentsAcrossFrames() {
    response =
        frames(
            event("messageStart", "{\"role\":\"assistant\"}"),
            event(
                "contentBlockStart",
                "{\"contentBlockIndex\":0,\"start\":{\"toolUse\":{\"toolUseId\":\"tool-1\",\"name\":\"search\"}}}"),
            event(
                "contentBlockDelta",
                "{\"contentBlockIndex\":0,\"delta\":{\"toolUse\":{\"input\":\"{\\\"from\\\":\"}}}"),
            event(
                "contentBlockDelta",
                "{\"contentBlockIndex\":0,\"delta\":{\"toolUse\":{\"input\":\"\\\"BLR\\\"}\"}}}"),
            event("contentBlockStop", "{\"contentBlockIndex\":0}"),
            event("messageStop", "{\"stopReason\":\"tool_use\"}"));

    List<LlmResponse> responses = collect(model());
    assertThat(responses).hasSize(1);
    var call =
        responses
            .get(0)
            .content()
            .orElseThrow()
            .parts()
            .orElseThrow()
            .get(0)
            .functionCall()
            .orElseThrow();
    assertThat(call.id()).hasValue("tool-1");
    assertThat(call.name()).hasValue("search");
    assertThat(call.args().orElseThrow()).containsEntry("from", "BLR");
  }

  @Test
  void bearerHttpErrorPropagatesWithStatusInsteadOfStalling() {
    status = 403;
    response = "{\"message\":\"test access denied\"}".getBytes(StandardCharsets.UTF_8);
    model()
        .generateContent(request(), true)
        .test()
        .awaitDone(10, TimeUnit.SECONDS)
        .assertError(
            error ->
                error instanceof LlmHttpException && ((LlmHttpException) error).statusCode() == 403)
        .assertNoValues()
        .assertNotComplete();
  }

  @Test
  void corruptEventStreamFailsInsteadOfSilentlyDiscardingData() {
    response =
        frames(
            event("contentBlockDelta", "{\"contentBlockIndex\":0,\"delta\":{\"text\":\"hello\"}}"));
    response[response.length - 1] ^= 1; // Invalidate the AWS message CRC.
    model()
        .generateContent(request(), true)
        .test()
        .awaitDone(10, TimeUnit.SECONDS)
        .assertError(Throwable.class)
        .assertNoValues()
        .assertNotComplete();
  }

  @Test
  void primaryFailureSuccessfullyFailsOverToBearerEventStream() {
    AtomicInteger primaryCalls = new AtomicInteger();
    BaseLlm primary =
        new BaseLlm("primary") {
          @Override
          public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
            primaryCalls.incrementAndGet();
            return Flowable.error(new LlmHttpException(503, "forced primary failure", null));
          }

          @Override
          public BaseLlmConnection connect(LlmRequest request) {
            throw new UnsupportedOperationException();
          }
        };
    response =
        frames(
            event(
                "contentBlockDelta",
                "{\"contentBlockIndex\":0,\"delta\":{\"text\":\"fallback succeeded\"}}"),
            event("messageStop", "{\"stopReason\":\"end_turn\"}"));

    List<LlmResponse> responses = collect(new FailoverLlm(primary, model()));
    LlmResponse last = responses.get(responses.size() - 1);
    assertThat(last.content().orElseThrow().parts().orElseThrow().get(0).text())
        .hasValue("fallback succeeded");
    assertThat(last.modelVersion()).hasValue("regression-model");
    assertThat(last.partial()).hasValue(false);
    assertThat(primaryCalls.get()).isEqualTo(1);
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void completedStreamsReuseTheSameLiveClientAcrossRecreatedModels() {
    response =
        frames(
            event("contentBlockDelta", "{\"contentBlockIndex\":0,\"delta\":{\"text\":\"ok\"}}"),
            event("messageStop", "{\"stopReason\":\"end_turn\"}"));
    for (int i = 0; i < 10; i++) {
      var result = collect(model());
      assertThat(result.get(result.size() - 1).partial()).hasValue(false);
    }
    assertThat(calls.get()).isEqualTo(10);
  }

  private BedrockBaseLM model() {
    return new BedrockBaseLM(
        "regression-model", "http://127.0.0.1:" + server.getAddress().getPort() + "/model") {
      @Override
      BedrockRuntimeAsyncClient createBedrockRuntimeAsyncClient() {
        synchronized (BedrockBearerStreamingTest.this) {
          if (fixtureClient == null) {
            fixtureClient =
                super.createBedrockRuntimeAsyncClient(
                    "regression-test-token",
                    BedrockRuntimeAsyncClient.builder()
                        .httpClientBuilder(
                            NettyNioAsyncHttpClient.builder().protocol(Protocol.HTTP1_1)));
          }
          return fixtureClient;
        }
      }
    };
  }

  private static List<LlmResponse> collect(BaseLlm model) {
    return model
        .generateContent(request(), true)
        .timeout(10, TimeUnit.SECONDS)
        .toList()
        .blockingGet();
  }

  private static LlmRequest request() {
    return LlmRequest.builder()
        .model("regression-model")
        .contents(List.of(Content.builder().role("user").parts(Part.fromText("hello")).build()))
        .build();
  }

  private static Message event(String type, String payload) {
    return new Message(
        Map.of(
            ":message-type",
            HeaderValue.fromString("event"),
            ":event-type",
            HeaderValue.fromString(type),
            ":content-type",
            HeaderValue.fromString("application/json")),
        payload.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] frames(Message... messages) {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    for (Message message : messages) message.encode(output);
    return output.toByteArray();
  }
}
