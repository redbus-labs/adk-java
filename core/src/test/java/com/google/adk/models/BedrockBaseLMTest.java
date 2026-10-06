/*
 * Copyright 2025 Google LLC
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

import com.google.adk.models.failover.LlmHttpException;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.model.AccessDeniedException;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockDelta;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockStart;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamResponseHandler;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;
import software.amazon.awssdk.services.bedrockruntime.model.TokenUsage;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlockDelta;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlockStart;
import software.amazon.awssdk.services.bedrockruntime.model.conversestreamoutput.DefaultContentBlockDelta;
import software.amazon.awssdk.services.bedrockruntime.model.conversestreamoutput.DefaultContentBlockStart;
import software.amazon.awssdk.services.bedrockruntime.model.conversestreamoutput.DefaultMetadata;

@RunWith(JUnit4.class)
public final class BedrockBaseLMTest {

  @Test
  public void getBearerToken_prefersExistingCompatibilityOrder() {
    Map<String, String> environment = new HashMap<>();
    environment.put("AWS_BEARER_TOKEN_BEDROCK", "aws-token");
    environment.put("BEDROCK_API_KEY", "fmis-token");

    assertThat(BedrockBaseLM.getBearerToken(environment)).isEqualTo("fmis-token");
    assertThat(BedrockBaseLM.getBearerToken(Map.of())).isNull();
  }

  @Test
  public void resolveBedrockRegion_prefersBedrockThenAwsAndDefaults() {
    assertThat(
            BedrockBaseLM.resolveBedrockRegion(
                Map.of("BEDROCK_REGION", "us-west-2", "AWS_REGION", "eu-west-1")))
        .isEqualTo(Region.US_WEST_2);
    assertThat(BedrockBaseLM.resolveBedrockRegion(Map.of("AWS_REGION", "eu-west-1")))
        .isEqualTo(Region.EU_WEST_1);
  }

  @Test
  public void buildSdkConverseRequest_preservesMessagesToolsAndToolResults() {
    JSONArray messages =
        new JSONArray()
            .put(
                new JSONObject()
                    .put("role", "user")
                    .put(
                        "content", new JSONArray().put(new JSONObject().put("text", "find a bus"))))
            .put(
                new JSONObject()
                    .put("role", "assistant")
                    .put(
                        "content",
                        new JSONArray()
                            .put(
                                new JSONObject()
                                    .put(
                                        "toolUse",
                                        new JSONObject()
                                            .put("toolUseId", "tool-1")
                                            .put("name", "search")
                                            .put("input", new JSONObject().put("from", "BLR"))))))
            .put(
                new JSONObject()
                    .put("role", "user")
                    .put(
                        "content",
                        new JSONArray()
                            .put(
                                new JSONObject()
                                    .put(
                                        "toolResult",
                                        new JSONObject()
                                            .put("toolUseId", "tool-1")
                                            .put("status", "success")
                                            .put(
                                                "content",
                                                new JSONArray()
                                                    .put(
                                                        new JSONObject()
                                                            .put(
                                                                "json",
                                                                new JSONObject()
                                                                    .put("count", 2))))))));
    JSONArray tools =
        new JSONArray()
            .put(
                new JSONObject()
                    .put(
                        "toolSpec",
                        new JSONObject()
                            .put("name", "search")
                            .put("description", "Search buses")
                            .put(
                                "inputSchema",
                                new JSONObject()
                                    .put(
                                        "json",
                                        new JSONObject()
                                            .put("type", "object")
                                            .put("properties", new JSONObject())))));

    ConverseRequest request =
        BedrockBaseLM.buildSdkConverseRequest(
            "model-id",
            new JSONArray().put(new JSONObject().put("text", "system")),
            messages,
            tools);

    assertThat(request.modelId()).isEqualTo("model-id");
    assertThat(request.messages()).hasSize(3);
    assertThat(request.messages().get(1).content().get(0).toolUse().name()).isEqualTo("search");
    assertThat(request.messages().get(2).content().get(0).toolResult().toolUseId())
        .isEqualTo("tool-1");
    assertThat(request.toolConfig().tools()).hasSize(1);
    assertThat(request.system().get(0).text()).isEqualTo("system");
  }

  @Test
  public void sdkConverseResponseToJson_preservesToolCallAndUsage() {
    Message message =
        Message.builder()
            .role("assistant")
            .content(
                ContentBlock.fromText("checking"),
                ContentBlock.fromToolUse(
                    ToolUseBlock.builder()
                        .toolUseId("tool-1")
                        .name("search")
                        .input(Document.fromMap(Map.of("from", Document.fromString("BLR"))))
                        .build()))
            .build();
    ConverseResponse response =
        ConverseResponse.builder()
            .output(ConverseOutput.fromMessage(message))
            .stopReason(StopReason.TOOL_USE)
            .usage(
                TokenUsage.builder()
                    .inputTokens(2)
                    .cacheReadInputTokens(10)
                    .cacheWriteInputTokens(3)
                    .outputTokens(4)
                    .totalTokens(16)
                    .build())
            .build();

    JSONObject json = BedrockBaseLM.sdkConverseResponseToJson(response);

    assertThat(json.getJSONObject("usage").getInt("totalTokens")).isEqualTo(16);
    assertThat(json.getJSONObject("usage").getInt("cacheReadInputTokens")).isEqualTo(10);
    assertThat(json.getJSONObject("usage").getInt("cacheWriteInputTokens")).isEqualTo(3);
    assertThat(BedrockBaseLM.getUsageMetadata(json).cachedContentTokenCount()).hasValue(10);
    JSONObject toolUse =
        json.getJSONObject("output")
            .getJSONObject("message")
            .getJSONArray("content")
            .getJSONObject(1)
            .getJSONObject("toolUse");
    assertThat(toolUse.getString("name")).isEqualTo("search");
    assertThat(toolUse.getJSONObject("input").getString("from")).isEqualTo("BLR");
  }

  @Test
  public void awsSdkStreamingResponse_emitsTextToolCallAndUsage() {
    BedrockRuntimeAsyncClient client =
        new BedrockRuntimeAsyncClient() {
          @Override
          public CompletableFuture<Void> converseStream(
              ConverseStreamRequest request, ConverseStreamResponseHandler handler) {
            List<ConverseStreamOutput> events =
                List.of(
                    DefaultContentBlockDelta.builder()
                        .contentBlockIndex(0)
                        .delta(ContentBlockDelta.fromText("hello"))
                        .build(),
                    DefaultContentBlockStart.builder()
                        .contentBlockIndex(1)
                        .start(
                            ContentBlockStart.fromToolUse(
                                ToolUseBlockStart.builder()
                                    .toolUseId("tool-1")
                                    .name("search")
                                    .build()))
                        .build(),
                    DefaultContentBlockDelta.builder()
                        .contentBlockIndex(1)
                        .delta(
                            ContentBlockDelta.fromToolUse(
                                ToolUseBlockDelta.builder().input("{\"from\":\"BLR\"}").build()))
                        .build(),
                    DefaultMetadata.builder()
                        .usage(
                            TokenUsage.builder()
                                .inputTokens(2)
                                .cacheReadInputTokens(5773)
                                .cacheWriteInputTokens(0)
                                .outputTokens(132)
                                .totalTokens(5907)
                                .build())
                        .build());
            handler.onEventStream(
                subscriber ->
                    subscriber.onSubscribe(
                        new org.reactivestreams.Subscription() {
                          private boolean delivered;

                          @Override
                          public void request(long count) {
                            if (delivered) {
                              return;
                            }
                            delivered = true;
                            events.forEach(subscriber::onNext);
                            subscriber.onComplete();
                            handler.complete();
                          }

                          @Override
                          public void cancel() {}
                        }));
            return CompletableFuture.completedFuture(null);
          }

          @Override
          public String serviceName() {
            return "bedrock-runtime";
          }

          @Override
          public void close() {}
        };
    BedrockBaseLM model =
        new BedrockBaseLM("model-id") {
          @Override
          BedrockRuntimeAsyncClient createBedrockRuntimeAsyncClient() {
            return client;
          }
        };

    List<LlmResponse> responses =
        model
            .createAwsSdkStreamingResponse(
                "model-id",
                new JSONArray(),
                new JSONArray()
                    .put(
                        new JSONObject()
                            .put("role", "user")
                            .put(
                                "content",
                                new JSONArray().put(new JSONObject().put("text", "hi")))),
                null)
            .toList()
            .blockingGet();

    assertThat(responses).hasSize(2);
    assertThat(responses.get(0).partial()).hasValue(true);
    List<Part> finalParts = responses.get(1).content().get().parts().get();
    assertThat(finalParts).hasSize(2);
    assertThat(finalParts.get(0).text()).hasValue("hello");
    assertThat(finalParts.get(1).functionCall().get().name()).hasValue("search");
    assertThat(finalParts.get(1).functionCall().get().args().get()).containsEntry("from", "BLR");
    assertThat(responses.get(1).usageMetadata().get().cachedContentTokenCount()).hasValue(5773);
  }

  @Test
  public void mapAwsException_credentialsFailureIsActionable() {
    RuntimeException mapped =
        BedrockBaseLM.mapAwsException(SdkClientException.create("IMDS unavailable"));

    assertThat(mapped).isInstanceOf(IllegalStateException.class);
    assertThat(mapped).hasMessageThat().contains("instance profile");
    assertThat(mapped).hasMessageThat().contains("IMDS unavailable");
  }

  @Test
  public void mapAwsException_serviceFailurePreservesHttpStatus() {
    RuntimeException mapped =
        BedrockBaseLM.mapAwsException(
            AccessDeniedException.builder().statusCode(403).message("not authorized").build());

    assertThat(mapped).isInstanceOf(LlmHttpException.class);
    assertThat(((LlmHttpException) mapped).statusCode()).isEqualTo(403);
    assertThat(mapped).hasMessageThat().contains("not authorized");
  }

  @Test
  public void contentBlockToParts_emptyContent_returnsNoParts() {
    JSONObject message = new JSONObject().put("role", "assistant").put("content", new JSONArray());

    List<Part> parts = BedrockBaseLM.ollamaContentBlockToParts(message);

    assertThat(parts).isEmpty();
  }

  @Test
  public void contentBlockToParts_redactedReasoning_createsOpaqueThoughtPart() {
    JSONObject reasoningContent = new JSONObject().put("redactedContent", "opaque-value");
    JSONObject message =
        new JSONObject()
            .put("role", "assistant")
            .put(
                "content",
                new JSONArray().put(new JSONObject().put("reasoningContent", reasoningContent)));

    List<Part> parts = BedrockBaseLM.ollamaContentBlockToParts(message);

    assertThat(parts).hasSize(1);
    Part part = parts.get(0);
    assertThat(part.text()).hasValue("");
    assertThat(part.thought()).hasValue(true);
    assertThat(part.partMetadata()).isPresent();
    assertThat(part.partMetadata().get()).containsKey("bedrockReasoningContent");
    assertThat(part.partMetadata().get().get("bedrockReasoningContent"))
        .isEqualTo(Map.of("redactedContent", "opaque-value"));
  }

  @Test
  public void contentBlockToParts_reasoningTextAndAnswer_preservesBothParts() {
    JSONObject reasoningText =
        new JSONObject().put("text", "internal reasoning").put("signature", "signature-value");
    JSONArray content =
        new JSONArray()
            .put(
                new JSONObject()
                    .put("reasoningContent", new JSONObject().put("reasoningText", reasoningText)))
            .put(new JSONObject().put("text", "final answer"));
    JSONObject message = new JSONObject().put("role", "assistant").put("content", content);

    List<Part> parts = BedrockBaseLM.ollamaContentBlockToParts(message);

    assertThat(parts).hasSize(2);
    assertThat(parts.get(0).text()).hasValue("internal reasoning");
    assertThat(parts.get(0).thought()).hasValue(true);
    assertThat(parts.get(1).text()).hasValue("final answer");
    assertThat(parts.get(1).thought()).isEmpty();
  }

  @Test
  public void reasoningContent_roundTripsBackToBedrockUnchanged() {
    JSONObject originalReasoningContent = new JSONObject().put("redactedContent", "opaque-value");
    JSONObject responseMessage =
        new JSONObject()
            .put("role", "assistant")
            .put(
                "content",
                new JSONArray()
                    .put(new JSONObject().put("reasoningContent", originalReasoningContent)));
    List<Part> parts = BedrockBaseLM.ollamaContentBlockToParts(responseMessage);
    Content adkContent = Content.builder().role("model").parts(parts).build();

    JSONObject requestMessage =
        BedrockBaseLM.contentToBedrockMessage(adkContent, List.of(adkContent), 0);

    JSONObject roundTrippedReasoning =
        requestMessage.getJSONArray("content").getJSONObject(0).getJSONObject("reasoningContent");
    assertThat(roundTrippedReasoning.toMap()).isEqualTo(originalReasoningContent.toMap());
    assertThat(requestMessage.getString("role")).isEqualTo("assistant");
  }

  @Test
  public void reasoningContent_isNotSerializedInUserMessage() {
    JSONObject responseMessage =
        new JSONObject()
            .put("role", "assistant")
            .put(
                "content",
                new JSONArray()
                    .put(
                        new JSONObject()
                            .put(
                                "reasoningContent",
                                new JSONObject().put("redactedContent", "opaque-value"))));
    List<Part> parts = BedrockBaseLM.ollamaContentBlockToParts(responseMessage);
    Content userContent = Content.builder().role("user").parts(parts).build();

    JSONObject requestMessage =
        BedrockBaseLM.contentToBedrockMessage(userContent, List.of(userContent), 0);

    assertThat(requestMessage.getString("role")).isEqualTo("user");
    assertThat(requestMessage.toString()).doesNotContain("reasoningContent");
    assertThat(requestMessage.getJSONArray("content")).isEmpty();
  }

  @Test
  public void buildMessagesFromContents_skipsBlankContentAndPreservesAlternatingRoles() {
    Content firstUser = Content.builder().role("user").parts(Part.fromText("first")).build();
    Content blankUser = Content.builder().role("user").parts(Part.fromText("   \n")).build();
    Content secondUser = Content.builder().role("user").parts(Part.fromText("second")).build();
    Content assistant =
        Content.builder().role("model").parts(Part.fromText("assistant answer")).build();
    Content blankFinalUser = Content.builder().role("user").parts(Part.fromText("")).build();

    JSONArray messages =
        BedrockBaseLM.buildMessagesFromContents(
            List.of(firstUser, blankUser, secondUser, assistant, blankFinalUser));

    assertThat(messages.length()).isEqualTo(3);
    assertThat(messages.getJSONObject(0).getString("role")).isEqualTo("user");
    assertThat(messages.getJSONObject(0).getJSONArray("content").length()).isEqualTo(2);
    assertThat(messages.getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text"))
        .isEqualTo("first");
    assertThat(messages.getJSONObject(0).getJSONArray("content").getJSONObject(1).getString("text"))
        .isEqualTo("second");
    assertThat(messages.getJSONObject(1).getString("role")).isEqualTo("assistant");
    assertThat(messages.getJSONObject(2).getString("role")).isEqualTo("user");
    assertThat(messages.getJSONObject(2).getJSONArray("content").getJSONObject(0).getString("text"))
        .isNotEmpty();
  }

  @Test
  public void buildMessagesFromContents_replacesUnsupportedOnlyConversation() {
    JSONObject responseMessage =
        new JSONObject()
            .put("role", "assistant")
            .put(
                "content",
                new JSONArray()
                    .put(
                        new JSONObject()
                            .put(
                                "reasoningContent",
                                new JSONObject().put("redactedContent", "opaque-value"))));
    Content unsupportedUser =
        Content.builder()
            .role("user")
            .parts(BedrockBaseLM.ollamaContentBlockToParts(responseMessage))
            .build();

    JSONArray messages = BedrockBaseLM.buildMessagesFromContents(List.of(unsupportedUser));

    assertThat(messages.length()).isEqualTo(1);
    assertThat(messages.getJSONObject(0).getString("role")).isEqualTo("user");
    assertThat(messages.getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text"))
        .isNotEmpty();
  }
}
