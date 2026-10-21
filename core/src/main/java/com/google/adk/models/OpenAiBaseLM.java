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

package com.google.adk.models;

import com.google.adk.tools.BaseTool;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import com.google.genai.types.Tool;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Base implementation for OpenAI-compatible LLMs (vLLM, Ollama /v1, OpenAI, LiteLLM). */
public class OpenAiBaseLM extends BaseLlm {

  private static final Logger logger = LoggerFactory.getLogger(OpenAiBaseLM.class);

  protected String baseUrl;
  protected String apiKey;

  public OpenAiBaseLM(String modelName) {
    super(modelName);
  }

  public OpenAiBaseLM(String modelName, String baseUrl, String apiKey) {
    super(modelName);
    this.baseUrl = baseUrl != null ? baseUrl.replaceAll("/+$", "") : "";
    this.apiKey = apiKey;
  }

  @Override
  public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
    return Flowable.create(
        emitter -> {
          try {
            JsonObject payload = new JsonObject();
            payload.addProperty("model", model());
            payload.addProperty("stream", stream);

            // Forward Generation Config
            llmRequest
                .config()
                .ifPresent(
                    cfg -> {
                      cfg.temperature().ifPresent(t -> payload.addProperty("temperature", t));
                      cfg.topP().ifPresent(p -> payload.addProperty("top_p", p));
                      cfg.maxOutputTokens().ifPresent(m -> payload.addProperty("max_tokens", m));
                    });

            JsonArray messages = new JsonArray();

            // 1. System Instructions
            List<String> sysInsts = new ArrayList<>(llmRequest.getSystemInstructions());
            llmRequest
                .config()
                .flatMap(GenerateContentConfig::systemInstruction)
                .flatMap(Content::parts)
                .ifPresent(
                    parts ->
                        parts.stream()
                            .filter(p -> p.text().isPresent())
                            .forEach(p -> sysInsts.add(p.text().get())));

            if (!sysInsts.isEmpty()) {
              JsonObject msg = new JsonObject();
              msg.addProperty("role", "system");
              msg.addProperty("content", String.join("\n", sysInsts));
              messages.add(msg);
            }

            // 2. Chat Contents
            for (Content content : llmRequest.contents()) {
              String role = "user";
              if (content.role().isPresent()) {
                String r = content.role().get();
                if ("model".equals(r) || "assistant".equals(r)) {
                  role = "assistant";
                } else if ("system".equals(r)) {
                  role = "system";
                }
              }

              if (content.parts().isPresent()) {
                StringBuilder textContent = new StringBuilder();
                JsonArray toolCalls = new JsonArray();
                List<JsonObject> toolResponseMessages = new ArrayList<>();
                JsonArray imageParts = new JsonArray();

                for (Part p : content.parts().get()) {
                  if (p.text().isPresent()) {
                    textContent.append(p.text().get());
                  }

                  // Multimodal / Inline Image Handling
                  p.inlineData()
                      .ifPresent(
                          blob -> {
                            if (blob.data().isPresent()) {
                              String mime = blob.mimeType().orElse("image/jpeg");
                              String b64 = Base64.getEncoder().encodeToString(blob.data().get());
                              JsonObject imgObj = new JsonObject();
                              imgObj.addProperty("type", "image_url");
                              JsonObject urlObj = new JsonObject();
                              urlObj.addProperty("url", "data:" + mime + ";base64," + b64);
                              imgObj.add("image_url", urlObj);
                              imageParts.add(imgObj);
                            }
                          });

                  // Assistant Tool Calls
                  if (p.functionCall().isPresent()) {
                    FunctionCall fc = p.functionCall().get();
                    JsonObject call = new JsonObject();
                    call.addProperty("type", "function");
                    String callId =
                        fc.id()
                            .orElse(
                                "call_"
                                    + UUID.randomUUID()
                                        .toString()
                                        .replace("-", "")
                                        .substring(0, 9));
                    call.addProperty("id", callId);

                    JsonObject func = new JsonObject();
                    func.addProperty("name", fc.name().orElse(""));
                    if (fc.args() != null && fc.args().isPresent()) {
                      try {
                        String argsJson =
                            com.google.adk.JsonBaseModel.getMapper()
                                .writeValueAsString(fc.args().get());
                        func.addProperty("arguments", argsJson);
                      } catch (Exception e) {
                        func.addProperty("arguments", "{}");
                      }
                    } else {
                      func.addProperty("arguments", "{}");
                    }
                    call.add("function", func);
                    toolCalls.add(call);
                  }

                  // Tool Responses
                  if (p.functionResponse().isPresent()) {
                    var fr = p.functionResponse().get();
                    JsonObject respMsg = new JsonObject();
                    respMsg.addProperty("role", "tool");
                    String callId = fr.id().orElse("call_" + fr.name().orElse("unknown"));
                    respMsg.addProperty("tool_call_id", callId);

                    if (fr.response() != null && fr.response().isPresent()) {
                      try {
                        String respJson =
                            com.google.adk.JsonBaseModel.getMapper()
                                .writeValueAsString(fr.response().get());
                        respMsg.addProperty("content", respJson);
                      } catch (Exception e) {
                        respMsg.addProperty("content", String.valueOf(fr.response().get()));
                      }
                    } else {
                      respMsg.addProperty("content", "{}");
                    }
                    toolResponseMessages.add(respMsg);
                  }
                }

                boolean hasText = textContent.length() > 0;
                boolean hasToolCalls = toolCalls.size() > 0;
                boolean hasImages = imageParts.size() > 0;

                if (hasImages) {
                  JsonObject msg = new JsonObject();
                  msg.addProperty("role", role);
                  JsonArray contentArray = new JsonArray();
                  if (hasText) {
                    JsonObject textObj = new JsonObject();
                    textObj.addProperty("type", "text");
                    textObj.addProperty("text", textContent.toString());
                    contentArray.add(textObj);
                  }
                  imageParts.forEach(contentArray::add);
                  msg.add("content", contentArray);
                  if (hasToolCalls) {
                    msg.add("tool_calls", toolCalls);
                  }
                  messages.add(msg);
                } else if (hasText || hasToolCalls) {
                  JsonObject msg = new JsonObject();
                  msg.addProperty("role", role);
                  if (hasText) {
                    msg.addProperty("content", textContent.toString());
                  } else {
                    msg.add("content", JsonNull.INSTANCE);
                  }
                  if (hasToolCalls) {
                    msg.add("tool_calls", toolCalls);
                  }
                  messages.add(msg);
                }

                toolResponseMessages.forEach(messages::add);
              }
            }

            // Ensure messages is not empty
            if (messages.size() == 0) {
              JsonObject fallbackMsg = new JsonObject();
              fallbackMsg.addProperty("role", "user");
              fallbackMsg.addProperty("content", "Hello");
              messages.add(fallbackMsg);
            }

            payload.add("messages", messages);

            // 3. Collect and Normalize Tools
            JsonArray toolsArr = new JsonArray();

            // From llmRequest.tools() (BaseTool map)
            if (llmRequest.tools() != null) {
              for (Map.Entry<String, BaseTool> entry : llmRequest.tools().entrySet()) {
                BaseTool baseTool = entry.getValue();
                baseTool
                    .declaration()
                    .ifPresent(decl -> toolsArr.add(convertDeclarationToToolJson(decl)));
              }
            }

            // From llmRequest.config().tools() (GenAI Tool list)
            llmRequest
                .config()
                .flatMap(GenerateContentConfig::tools)
                .ifPresent(
                    toolList -> {
                      for (Tool tool : toolList) {
                        tool.functionDeclarations()
                            .ifPresent(
                                declList -> {
                                  for (FunctionDeclaration fd : declList) {
                                    toolsArr.add(convertDeclarationToToolJson(fd));
                                  }
                                });
                      }
                    });

            if (toolsArr.size() > 0) {
              payload.add("tools", toolsArr);
              payload.addProperty("tool_choice", "auto");
            }

            String jsonBody = payload.toString();

            HttpRequest.Builder reqBuilder =
                HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .version(HttpClient.Version.HTTP_1_1)
                    .timeout(Duration.ofMinutes(5))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));

            if (apiKey != null && !apiKey.isEmpty()) {
              reqBuilder.header("Authorization", "Bearer " + apiKey);
            }

            HttpClient client =
                HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<InputStream> response =
                client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() >= 400) {
              try (InputStream is = response.body()) {
                String err = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                emitter.onError(
                    new RuntimeException(
                        "OpenAI API Error (" + response.statusCode() + "): " + err));
              }
              return;
            }

            if (stream) {
              handleStreamingResponse(response.body(), emitter);
            } else {
              handleNonStreamingResponse(response.body(), emitter);
            }

            emitter.onComplete();
          } catch (Exception e) {
            if (!emitter.isCancelled()) {
              emitter.onError(e);
            }
          }
        },
        BackpressureStrategy.BUFFER);
  }

  private void handleStreamingResponse(
      InputStream inputStream, io.reactivex.rxjava3.core.FlowableEmitter<LlmResponse> emitter) {
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

      Map<Integer, StreamToolCallAccumulator> toolAccumulators = new HashMap<>();
      String line;

      while ((line = reader.readLine()) != null && !emitter.isCancelled()) {
        if (!line.startsWith("data: ")) {
          continue;
        }
        String data = line.substring(6).trim();
        if ("[DONE]".equals(data)) {
          break;
        }

        try {
          JsonObject json = JsonParser.parseString(data).getAsJsonObject();
          JsonArray choices = json.getAsJsonArray("choices");

          if (choices != null && choices.size() > 0) {
            JsonObject choice = choices.get(0).getAsJsonObject();
            JsonObject delta = choice.getAsJsonObject("delta");

            List<Part> responseParts = new ArrayList<>();

            if (delta != null) {
              // 1. Thinking / Reasoning delta
              if (delta.has("reasoning_content") && !delta.get("reasoning_content").isJsonNull()) {
                String thought = delta.get("reasoning_content").getAsString();
                if (!thought.isEmpty()) {
                  responseParts.add(Part.builder().thought(true).text(thought).build());
                }
              }

              // 2. Text delta
              if (delta.has("content") && !delta.get("content").isJsonNull()) {
                String chunk = delta.get("content").getAsString();
                if (!chunk.isEmpty()) {
                  responseParts.add(Part.fromText(chunk));
                }
              }

              // 3. Tool Calls streaming delta
              if (delta.has("tool_calls") && !delta.get("tool_calls").isJsonNull()) {
                JsonArray deltaCalls = delta.getAsJsonArray("tool_calls");
                for (JsonElement tcElem : deltaCalls) {
                  JsonObject tcObj = tcElem.getAsJsonObject();
                  int index = tcObj.has("index") ? tcObj.get("index").getAsInt() : 0;
                  StreamToolCallAccumulator acc =
                      toolAccumulators.computeIfAbsent(index, k -> new StreamToolCallAccumulator());

                  if (tcObj.has("id")) {
                    acc.id = tcObj.get("id").getAsString();
                  }
                  if (tcObj.has("function")) {
                    JsonObject fn = tcObj.getAsJsonObject("function");
                    if (fn.has("name")) {
                      acc.name = fn.get("name").getAsString();
                    }
                    if (fn.has("arguments")) {
                      acc.arguments.append(fn.get("arguments").getAsString());
                    }
                  }
                }
              }
            }

            if (!responseParts.isEmpty()) {
              LlmResponse resp =
                  LlmResponse.builder()
                      .content(
                          Content.builder()
                              .role("model")
                              .parts(ImmutableList.copyOf(responseParts))
                              .build())
                      .partial(true)
                      .modelVersion(model())
                      .build();
              emitter.onNext(resp);
            }
          }

          // Parse token usage metadata if provided on final chunk
          if (json.has("usage") && !json.get("usage").isJsonNull()) {
            GenerateContentResponseUsageMetadata usage =
                parseUsageMetadata(json.getAsJsonObject("usage"));
            if (usage != null) {
              emitter.onNext(
                  LlmResponse.builder()
                      .usageMetadata(usage)
                      .partial(true)
                      .modelVersion(model())
                      .build());
            }
          }
        } catch (Exception e) {
          logger.trace("Error parsing streaming chunk: {}", data, e);
        }
      }

      // Emit accumulated tool calls at the end of the stream
      if (!toolAccumulators.isEmpty()) {
        List<Part> toolParts = new ArrayList<>();
        for (StreamToolCallAccumulator acc : toolAccumulators.values()) {
          Map<String, Object> args = new HashMap<>();
          try {
            args =
                com.google.adk.JsonBaseModel.getMapper()
                    .readValue(acc.arguments.toString(), Map.class);
          } catch (Exception ignored) {
          }

          toolParts.add(
              Part.builder()
                  .functionCall(
                      FunctionCall.builder()
                          .name(acc.name != null ? acc.name : "")
                          .args(args)
                          .id(acc.id)
                          .build())
                  .build());
        }

        emitter.onNext(
            LlmResponse.builder()
                .content(
                    Content.builder().role("model").parts(ImmutableList.copyOf(toolParts)).build())
                .partial(false)
                .modelVersion(model())
                .build());
      }
    } catch (Exception e) {
      throw new RuntimeException("Failed reading streaming response", e);
    }
  }

  private void handleNonStreamingResponse(
      InputStream inputStream, io.reactivex.rxjava3.core.FlowableEmitter<LlmResponse> emitter) {
    try (InputStream is = inputStream) {
      String respBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
      JsonObject json = JsonParser.parseString(respBody).getAsJsonObject();
      JsonArray choices = json.getAsJsonArray("choices");

      if (choices != null && choices.size() > 0) {
        JsonObject choice = choices.get(0).getAsJsonObject();
        JsonObject message = choice.getAsJsonObject("message");

        if (message != null) {
          List<Part> responseParts = new ArrayList<>();

          // 1. Reasoning / Thinking
          if (message.has("reasoning_content") && !message.get("reasoning_content").isJsonNull()) {
            String thought = message.get("reasoning_content").getAsString();
            if (!thought.isEmpty()) {
              responseParts.add(Part.builder().thought(true).text(thought).build());
            }
          }

          // 2. Text Content
          if (message.has("content") && !message.get("content").isJsonNull()) {
            String text = message.get("content").getAsString();
            if (!text.isEmpty()) {
              responseParts.add(Part.fromText(text));
            }
          }

          // 3. Tool Calls
          if (message.has("tool_calls") && !message.get("tool_calls").isJsonNull()) {
            JsonArray tCalls = message.getAsJsonArray("tool_calls");
            for (JsonElement elem : tCalls) {
              JsonObject tc = elem.getAsJsonObject();
              if (tc.has("function") && !tc.get("function").isJsonNull()) {
                JsonObject func = tc.getAsJsonObject("function");
                String fName =
                    func.has("name") && !func.get("name").isJsonNull()
                        ? func.get("name").getAsString()
                        : "";
                String fArgsStr =
                    func.has("arguments") && !func.get("arguments").isJsonNull()
                        ? func.get("arguments").getAsString()
                        : "{}";
                String fId =
                    tc.has("id") && !tc.get("id").isJsonNull() ? tc.get("id").getAsString() : "";

                Map<String, Object> argsMap = new HashMap<>();
                try {
                  argsMap = com.google.adk.JsonBaseModel.getMapper().readValue(fArgsStr, Map.class);
                } catch (Exception ignored) {
                }

                responseParts.add(
                    Part.builder()
                        .functionCall(
                            FunctionCall.builder().name(fName).args(argsMap).id(fId).build())
                        .build());
              }
            }
          }

          LlmResponse.Builder responseBuilder =
              LlmResponse.builder()
                  .content(
                      Content.builder()
                          .role("model")
                          .parts(ImmutableList.copyOf(responseParts))
                          .build())
                  .partial(false)
                  .modelVersion(model());

          if (json.has("usage") && !json.get("usage").isJsonNull()) {
            GenerateContentResponseUsageMetadata usage =
                parseUsageMetadata(json.getAsJsonObject("usage"));
            if (usage != null) {
              responseBuilder.usageMetadata(usage);
            }
          }

          emitter.onNext(responseBuilder.build());
        }
      }
    } catch (Exception e) {
      throw new RuntimeException("Failed parsing non-streaming response", e);
    }
  }

  private JsonObject convertDeclarationToToolJson(FunctionDeclaration fd) {
    JsonObject tObj = new JsonObject();
    tObj.addProperty("type", "function");
    JsonObject fObj = new JsonObject();
    fObj.addProperty("name", fd.name().orElse(""));
    fObj.addProperty("description", fd.description().orElse(""));

    if (fd.parameters().isPresent()) {
      try {
        String schemaJson =
            com.google.adk.JsonBaseModel.getMapper().writeValueAsString(fd.parameters().get());
        JsonElement parsed = JsonParser.parseString(schemaJson);
        normalizeJsonSchemaTypes(parsed);
        fObj.add("parameters", parsed);
      } catch (Exception e) {
        fObj.add("parameters", createEmptyObjectSchema());
      }
    } else {
      fObj.add("parameters", createEmptyObjectSchema());
    }

    tObj.add("function", fObj);
    return tObj;
  }

  /**
   * Recursively converts uppercase Google GenAI schema types (STRING, OBJECT, etc.) to standard
   * lowercase JSON Schema types (string, object, etc.).
   */
  private static void normalizeJsonSchemaTypes(JsonElement element) {
    if (element == null) {
      return;
    }
    if (element.isJsonObject()) {
      JsonObject obj = element.getAsJsonObject();
      if (obj.has("type") && obj.get("type").isJsonPrimitive()) {
        obj.addProperty("type", obj.get("type").getAsString().toLowerCase(Locale.ROOT));
      }
      for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
        normalizeJsonSchemaTypes(entry.getValue());
      }
    } else if (element.isJsonArray()) {
      for (JsonElement child : element.getAsJsonArray()) {
        normalizeJsonSchemaTypes(child);
      }
    }
  }

  private static JsonObject createEmptyObjectSchema() {
    JsonObject empty = new JsonObject();
    empty.addProperty("type", "object");
    empty.add("properties", new JsonObject());
    return empty;
  }

  private GenerateContentResponseUsageMetadata parseUsageMetadata(JsonObject usageObj) {
    if (usageObj == null) {
      return null;
    }
    int promptTokens = usageObj.has("prompt_tokens") ? usageObj.get("prompt_tokens").getAsInt() : 0;
    int completionTokens =
        usageObj.has("completion_tokens") ? usageObj.get("completion_tokens").getAsInt() : 0;
    int totalTokens =
        usageObj.has("total_tokens")
            ? usageObj.get("total_tokens").getAsInt()
            : promptTokens + completionTokens;

    if (totalTokens > 0 || promptTokens > 0 || completionTokens > 0) {
      return GenerateContentResponseUsageMetadata.builder()
          .promptTokenCount(promptTokens)
          .candidatesTokenCount(completionTokens)
          .totalTokenCount(totalTokens)
          .build();
    }
    return null;
  }

  private static class StreamToolCallAccumulator {
    String id = "";
    String name = "";
    StringBuilder arguments = new StringBuilder();
  }

  @Override
  public BaseLlmConnection connect(LlmRequest llmRequest) {
    return new GenericLlmConnection(this, llmRequest);
  }

  public static void main(String[] args) throws Exception {
    OpenAiBaseLM llm =
        new OpenAiBaseLM(
            "google/gemma-4-E4B-it-qat-w4a16-ct", "http://localhost:8000/v1", "sk-dummy");

    // 1. Build tool parameter schema using SDK types
    com.google.genai.types.Schema locationProperty =
        com.google.genai.types.Schema.builder()
            .type(com.google.genai.types.Type.Known.STRING)
            .description("The city name")
            .build();

    com.google.genai.types.Schema params =
        com.google.genai.types.Schema.builder()
            .type(com.google.genai.types.Type.Known.OBJECT) // Fixed: OBJECT, not STRING
            .properties(Collections.singletonMap("location", locationProperty))
            .required(Collections.singletonList("location"))
            .build();

    // 2. Define the tool
    FunctionDeclaration weatherFunc =
        FunctionDeclaration.builder()
            .name("get_weather")
            .description("Get the current weather in a given location")
            .parameters(params)
            .build();

    Tool tool = Tool.builder().functionDeclarations(Collections.singletonList(weatherFunc)).build();

    // 3. User message
    Content userContent =
        Content.builder()
            .role("user")
            .parts(Collections.singletonList(Part.fromText("What is the weather in Paris?")))
            .build();

    // 4. Build Request
    LlmRequest request =
        LlmRequest.builder()
            .contents(Collections.singletonList(userContent))
            .config(GenerateContentConfig.builder().tools(Collections.singletonList(tool)).build())
            .build();

    System.out.println("Starting OpenAI/vLLM Test...");

    llm.generateContent(request, false)
        .blockingSubscribe(
            resp -> {
              resp.content()
                  .flatMap(Content::parts)
                  .ifPresent(
                      parts -> {
                        for (Part p : parts) {
                          p.text().ifPresent(System.out::print);
                          p.functionCall()
                              .ifPresent(
                                  fc -> {
                                    String name = fc.name().orElse("unknown");
                                    Object argsMap = fc.args().orElse(Collections.emptyMap());
                                    System.out.println(
                                        "\n[TOOL CALL] " + name + " args: " + argsMap);
                                  });
                        }
                      });
            },
            err -> System.err.println("\nERROR: " + err.getMessage()),
            () -> System.out.println("\n[DONE]"));
  }
}
