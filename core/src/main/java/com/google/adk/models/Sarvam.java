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
package com.google.adk.models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.adk.tools.BaseTool;
import com.google.common.base.Strings;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.subjects.PublishSubject;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Sarvam AI LLM implementation. Uses the OpenAI-compatible chat completion endpoint.
 *
 * @author Sandeep Belgavi
 * @since 2026-02-11
 */
public class Sarvam extends BaseLlm {

  private static final String API_URL = "https://api.sarvam.ai/chat/completions";
  private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

  private final String apiKey;
  private final OkHttpClient client;
  private final ObjectMapper objectMapper;

  public Sarvam(String model) {
    this(model, null);
  }

  public Sarvam(String model, String apiKey) {
    super(model);
    if (Strings.isNullOrEmpty(apiKey)) {
      this.apiKey = System.getenv("SARVAM_API_KEY");
    } else {
      this.apiKey = apiKey;
    }

    if (Strings.isNullOrEmpty(this.apiKey)) {
      throw new IllegalArgumentException(
          "Sarvam API key is required. Set SARVAM_API_KEY env variable or pass it to constructor.");
    }

    this.client = new OkHttpClient();
    this.objectMapper = new ObjectMapper();
  }

  @Override
  public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
    return Flowable.create(
        emitter -> {
          try {
            ObjectNode jsonBody = objectMapper.createObjectNode();
            jsonBody.put("model", model());
            jsonBody.put("stream", stream);

            ArrayNode messages = jsonBody.putArray("messages");

            // Add system instructions if present
            for (String instruction : llmRequest.getSystemInstructions()) {
              ObjectNode systemMsg = messages.addObject();
              systemMsg.put("role", "system");
              systemMsg.put("content", instruction);
            }

            // Add conversation history
            for (Content content : llmRequest.contents()) {
              ObjectNode message = messages.addObject();
              String role = content.role().orElse("user");
              // Map "model" to "assistant" for OpenAI compatibility
              if ("model".equals(role)) {
                role = "assistant";
              }
              message.put("role", role);

              StringBuilder textBuilder = new StringBuilder();
              content
                  .parts()
                  .ifPresent(
                      parts -> {
                        for (Part part : parts) {
                          part.text().ifPresent(textBuilder::append);
                        }
                      });
              message.put("content", textBuilder.toString());
            }

            // Add tool definitions if present
            if (llmRequest.tools() != null && !llmRequest.tools().isEmpty()) {
              ArrayNode toolsArray = jsonBody.putArray("tools");
              for (BaseTool tool : llmRequest.tools().values()) {
                ObjectNode toolNode = toolsArray.addObject();
                toolNode.put("type", "function");
                ObjectNode functionNode = toolNode.putObject("function");
                functionNode.put("name", tool.name());
                functionNode.put("description", tool.description());

                tool.declaration()
                    .flatMap(decl -> decl.parameters())
                    .ifPresent(
                        params -> {
                          try {
                            String paramsJson = objectMapper.writeValueAsString(params);
                            functionNode.set("parameters", objectMapper.readTree(paramsJson));
                          } catch (Exception e) {
                            // Ignore or log error
                          }
                        });
              }
            }

            RequestBody body = RequestBody.create(jsonBody.toString(), JSON);
            Request request =
                new Request.Builder()
                    .url(API_URL)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("api-subscription-key", apiKey)
                    .post(body)
                    .build();

            if (stream) {
              try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                  emitter.onError(
                      new IOException(
                          "Unexpected code "
                              + response
                              + " body: "
                              + (response.body() != null ? response.body().string() : "")));
                  return;
                }

                if (response.body() == null) {
                  emitter.onError(new IOException("Response body is null"));
                  return;
                }

                BufferedReader reader = new BufferedReader(response.body().charStream());
                String line;
                while ((line = reader.readLine()) != null) {
                  if (line.startsWith("data: ")) {
                    String data = line.substring(6).trim();
                    if ("[DONE]".equals(data)) {
                      break;
                    }
                    try {
                      JsonNode chunk = objectMapper.readTree(data);
                      JsonNode choices = chunk.path("choices");
                      if (choices.isArray() && choices.size() > 0) {
                        JsonNode delta = choices.get(0).path("delta");
                        if (delta.has("content")) {
                          String contentPart = delta.get("content").asText();

                          Content content =
                              Content.builder()
                                  .role("model")
                                  .parts(Part.fromText(contentPart))
                                  .build();

                          LlmResponse llmResponse =
                              LlmResponse.builder().content(content).partial(true).build();
                          emitter.onNext(llmResponse);
                        }
                      }
                    } catch (Exception e) {
                      // Ignore parse errors for keep-alive or malformed lines
                    }
                  }
                }
                emitter.onComplete();
              }
            } else {
              try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                  emitter.onError(
                      new IOException(
                          "Unexpected code "
                              + response
                              + " body: "
                              + (response.body() != null ? response.body().string() : "")));
                  return;
                }
                if (response.body() == null) {
                  emitter.onError(new IOException("Response body is null"));
                  return;
                }
                String responseBody = response.body().string();
                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode choices = root.path("choices");
                if (choices.isArray() && choices.size() > 0) {
                  JsonNode message = choices.get(0).path("message");
                  String contentText = message.path("content").asText();

                  Content content =
                      Content.builder().role("model").parts(Part.fromText(contentText)).build();

                  LlmResponse llmResponse = LlmResponse.builder().content(content).build();
                  emitter.onNext(llmResponse);
                  emitter.onComplete();
                } else {
                  emitter.onError(new IOException("Empty choices in response"));
                }
              }
            }
          } catch (Exception e) {
            emitter.onError(e);
          }
        },
        BackpressureStrategy.BUFFER);
  }

  @Override
  public BaseLlmConnection connect(LlmRequest llmRequest) {
    return new SarvamConnection(llmRequest);
  }

  private class SarvamConnection implements BaseLlmConnection {
    private final LlmRequest initialRequest;
    private final List<Content> history = new ArrayList<>();
    private final PublishSubject<LlmResponse> responseSubject = PublishSubject.create();

    public SarvamConnection(LlmRequest llmRequest) {
      this.initialRequest = llmRequest;
      this.history.addAll(llmRequest.contents());
    }

    @Override
    public Completable sendContent(Content content) {
      return Completable.fromAction(
          () -> {
            history.add(content);
            generate();
          });
    }

    @Override
    public Completable sendHistory(List<Content> history) {
      return Completable.fromAction(
          () -> {
            this.history.clear();
            this.history.addAll(history);
            generate();
          });
    }

    @Override
    public Completable sendRealtime(Blob blob) {
      return Completable.error(
          new UnsupportedOperationException("Realtime not supported for Sarvam"));
    }

    private void generate() {
      LlmRequest.Builder builder =
          LlmRequest.builder().contents(new ArrayList<>(history)).tools(initialRequest.tools());
      builder.appendInstructions(initialRequest.getSystemInstructions());
      LlmRequest request = builder.build();

      StringBuilder fullContent = new StringBuilder();
      generateContent(request, true)
          .subscribe(
              response -> {
                responseSubject.onNext(response);
                response
                    .content()
                    .flatMap(Content::parts)
                    .ifPresent(
                        parts -> {
                          for (Part part : parts) {
                            part.text().ifPresent(fullContent::append);
                          }
                        });
              },
              responseSubject::onError,
              () -> {
                Content responseContent =
                    Content.builder()
                        .role("model")
                        .parts(Part.fromText(fullContent.toString()))
                        .build();
                history.add(responseContent);
              });
    }

    @Override
    public Flowable<LlmResponse> receive() {
      return responseSubject.toFlowable(BackpressureStrategy.BUFFER);
    }

    @Override
    public void close() {
      responseSubject.onComplete();
    }

    @Override
    public void close(Throwable throwable) {
      responseSubject.onError(throwable);
    }
  }
}
