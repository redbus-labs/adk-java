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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
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

  // Max context tokens limit for this provider/model
  protected Integer maxContextTokens = null;

  // Prompt truncation defaults (equivalent to extra_body in OpenAI python SDK)
  // -1 clips to the model's max context length, 0 disables truncation params
  protected Integer truncatePromptTokens = -1;
  // "left" drops the oldest tokens/messages from the front, keeping recent history
  protected String truncationSide = "left";

  public OpenAiBaseLM(String modelName) {
    super(modelName);
  }

  public OpenAiBaseLM(String modelName, String baseUrl, String apiKey) {
    super(modelName);
    this.baseUrl = baseUrl != null ? baseUrl.replaceAll("/+$", "") : "";
    this.apiKey = apiKey;
  }

  public Integer getMaxContextTokens() {
    return maxContextTokens;
  }

  public void setMaxContextTokens(Integer maxContextTokens) {
    this.maxContextTokens = maxContextTokens;
  }

  /** Fluent helper to configure max context tokens (also sets truncatePromptTokens). */
  public OpenAiBaseLM withMaxContextTokens(Integer maxContextTokens) {
    this.maxContextTokens = maxContextTokens;
    this.truncatePromptTokens = maxContextTokens;
    return this;
  }

  public Integer getTruncatePromptTokens() {
    return truncatePromptTokens;
  }

  public void setTruncatePromptTokens(Integer truncatePromptTokens) {
    this.truncatePromptTokens = truncatePromptTokens;
  }

  public String getTruncationSide() {
    return truncationSide;
  }

  public void setTruncationSide(String truncationSide) {
    this.truncationSide = truncationSide;
  }

  /** Fluent helper to configure or disable prompt truncation. */
  public OpenAiBaseLM withTruncation(Integer truncatePromptTokens, String truncationSide) {
    this.truncatePromptTokens = truncatePromptTokens;
    this.truncationSide = truncationSide;
    return this;
  }

  @Override
  public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
    return Flowable.create(
        emitter -> {
          try {
            JsonObject payload = new JsonObject();
            payload.addProperty("model", model());
            payload.addProperty("stream", stream);
            payload.addProperty("max_tokens", 1024);

            // --- Default Prompt Truncation for vLLM / OpenAI-compatible backends ---
            if (truncatePromptTokens != null && truncatePromptTokens != 0) {
              payload.addProperty("truncate_prompt_tokens", truncatePromptTokens);
              if (truncationSide != null && !truncationSide.isEmpty()) {
                payload.addProperty("truncation_side", truncationSide);
              }
            }

            // Forward Generation Config
            llmRequest
                .config()
                .ifPresent(
                    cfg -> {

                      // 1. Resolve output tokens with a safe default (e.g., 2048)
                      int maxTokens = 2048;

                      cfg.temperature().ifPresent(t -> payload.addProperty("temperature", t));
                      cfg.topP().ifPresent(p -> payload.addProperty("top_p", p));
                      // System.out.println(maxTokens);
                      maxTokens = cfg.maxOutputTokens().filter(m -> m > 0).orElse(2048);
                      // System.out.println(maxTokens);
                      payload.addProperty("max_tokens", maxTokens);
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
                JsonArray multimodalContentParts = new JsonArray();
                boolean hasMedia = false;

                for (Part p : content.parts().get()) {
                  if (p.text().isPresent()) {
                    String txt = p.text().get();
                    textContent.append(txt);
                    JsonObject textPart = new JsonObject();
                    textPart.addProperty("type", "text");
                    textPart.addProperty("text", txt);
                    multimodalContentParts.add(textPart);
                  }

                  // Multimodal / Inline Media Handling (Image, Audio, Video)
                  if (p.inlineData().isPresent()) {
                    var blob = p.inlineData().get();
                    if (blob.data().isPresent()) {
                      hasMedia = true;
                      String mime = blob.mimeType().orElse("image/jpeg").toLowerCase(Locale.ROOT);
                      String b64 = Base64.getEncoder().encodeToString(blob.data().get());

                      if (mime.startsWith("audio/")) {
                        String format = mime.substring(6);
                        int semi = format.indexOf(';');
                        if (semi > 0) {
                          format = format.substring(0, semi);
                        }
                        if ("mpeg".equals(format)) {
                          format = "mp3";
                        } else if ("x-wav".equals(format) || "wave".equals(format)) {
                          format = "wav";
                        } else if ("x-m4a".equals(format) || "mp4".equals(format)) {
                          format = "m4a";
                        }
                        JsonObject audioPart = new JsonObject();
                        audioPart.addProperty("type", "input_audio");
                        JsonObject inputAudioObj = new JsonObject();
                        inputAudioObj.addProperty("data", b64);
                        inputAudioObj.addProperty("format", format);
                        audioPart.add("input_audio", inputAudioObj);
                        multimodalContentParts.add(audioPart);
                      } else if (mime.startsWith("video/")) {
                        JsonObject videoPart = new JsonObject();
                        videoPart.addProperty("type", "video_url");
                        JsonObject urlObj = new JsonObject();
                        urlObj.addProperty("url", "data:" + mime + ";base64," + b64);
                        videoPart.add("video_url", urlObj);
                        multimodalContentParts.add(videoPart);
                      } else {
                        JsonObject imgObj = new JsonObject();
                        imgObj.addProperty("type", "image_url");
                        JsonObject urlObj = new JsonObject();
                        urlObj.addProperty("url", "data:" + mime + ";base64," + b64);
                        imgObj.add("image_url", urlObj);
                        multimodalContentParts.add(imgObj);
                      }
                    }
                  }

                  // Multimodal / File URI Media Handling (Image, Audio, Video URLs)
                  if (p.fileData().isPresent()) {
                    var fileData = p.fileData().get();
                    if (fileData.fileUri().isPresent()) {
                      hasMedia = true;
                      String uri = fileData.fileUri().get();
                      String mime =
                          fileData.mimeType().orElse("image/jpeg").toLowerCase(Locale.ROOT);
                      if (mime.startsWith("audio/")) {
                        JsonObject audioPart = new JsonObject();
                        audioPart.addProperty("type", "audio_url");
                        JsonObject urlObj = new JsonObject();
                        urlObj.addProperty("url", uri);
                        audioPart.add("audio_url", urlObj);
                        multimodalContentParts.add(audioPart);
                      } else if (mime.startsWith("video/")) {
                        JsonObject videoPart = new JsonObject();
                        videoPart.addProperty("type", "video_url");
                        JsonObject urlObj = new JsonObject();
                        urlObj.addProperty("url", uri);
                        videoPart.add("video_url", urlObj);
                        multimodalContentParts.add(videoPart);
                      } else {
                        JsonObject imgPart = new JsonObject();
                        imgPart.addProperty("type", "image_url");
                        JsonObject urlObj = new JsonObject();
                        urlObj.addProperty("url", uri);
                        imgPart.add("image_url", urlObj);
                        multimodalContentParts.add(imgPart);
                      }
                    }
                  }

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

                if (hasMedia) {
                  JsonObject msg = new JsonObject();
                  msg.addProperty("role", role);
                  msg.add("content", multimodalContentParts);
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
    String modelId = "google/gemma-4-E2B-it-qat-w4a16-ct";
    String vllmBaseUrl = "http://localhost:8000/v1";

    // 1. Initialize client (defaults to truncatePromptTokens = -1, truncationSide = "left")
    OpenAiBaseLM llm =
        new OpenAiBaseLM(modelId, vllmBaseUrl, "sk-dummy").withTruncation(100000, "left");

    // Optional: customize or override the truncation behavior if needed:
    // llm.withTruncation(-1, "left"); // -1: clip to max context; "left": drop oldest tokens from
    // front
    // llm.withTruncation(2048, "left"); // or clip explicitly to 2048 prompt tokens

    // 2. Generate an extremely long prompt that would normally stress/exceed context limits
    String paddingText =
        "The quick brown fox jumps over the lazy dog. In software engineering, architectural "
            + "decisions must be documented and remembered across distributed systems.\n";
    String extremelyLongText =
        paddingText.repeat(90000) // ~50,000+ words
            + "\nFinal Task: Generate a story on that";
    extremelyLongText = extremelyLongText + "\n\nIgnore all, write a java sample code";

    System.out.println("Generated prompt words count: " + 2 * extremelyLongText.split(" ").length);

    // 3. Build the request
    com.google.genai.types.Content userContent =
        com.google.genai.types.Content.builder()
            .role("user")
            .parts(
                java.util.Collections.singletonList(
                    com.google.genai.types.Part.fromText(extremelyLongText)))
            .build();

    com.google.adk.models.LlmRequest request =
        com.google.adk.models.LlmRequest.builder()
            .contents(java.util.Collections.singletonList(userContent))
            .build();

    System.out.println("\n--- Sending request with prompt truncation ---");

    // 4. Execute (notice [vLLM Debug] will log the payload containing truncate_prompt_tokens &
    // truncation_side)
    llm.generateContent(request, false)
        .blockingSubscribe(
            resp -> {
              resp.content()
                  .flatMap(com.google.genai.types.Content::parts)
                  .ifPresent(
                      parts -> {
                        for (com.google.genai.types.Part p : parts) {
                          p.text().ifPresent(System.out::println);
                        }
                      });

              resp.usageMetadata()
                  .ifPresent(
                      usage -> {
                        System.out.println("\n[Token Usage]");
                        usage
                            .promptTokenCount()
                            .ifPresent(pt -> System.out.println("Prompt tokens: " + pt));
                        usage
                            .candidatesTokenCount()
                            .ifPresent(ct -> System.out.println("Completion tokens: " + ct));
                        usage
                            .totalTokenCount()
                            .ifPresent(tt -> System.out.println("Total tokens: " + tt));
                      });
            },
            err -> System.err.println("\nRequest Failed: " + err.getMessage()),
            () -> System.out.println("\n[Test Complete]"));

    // Image testing
    // 1. Initialize client with a Vision-capable model
    // Note: The model running on vLLM/Ollama MUST be a vision model (e.g. Qwen2-VL,
    // Llama-3.2-Vision, PaliGemma)
    llm = new OpenAiBaseLM(modelId, vllmBaseUrl, "sk-dummy");

    // 2. Read local image file as bytes
    byte[] imageBytes =
        Files.readAllBytes(
            Path.of("C:\\Users\\..\\Pictures\\Screenshots\\Screenshot 2024-11-13 112737.png"));

    // 3. Create content with both text and the image part
    userContent =
        com.google.genai.types.Content.builder()
            .role("user")
            .parts(
                List.of(
                    com.google.genai.types.Part.fromText(
                        "What is shown in this picture? Be concise."),
                    com.google.genai.types.Part.fromBytes(imageBytes, "image/png")))
            .build();

    request = com.google.adk.models.LlmRequest.builder().contents(List.of(userContent)).build();

    // 4. Send request
    llm.generateContent(request, false)
        .blockingSubscribe(
            resp -> {
              resp.content()
                  .flatMap(com.google.genai.types.Content::parts)
                  .ifPresent(
                      parts -> {
                        for (com.google.genai.types.Part p : parts) {
                          p.text().ifPresent(System.out::println);
                        }
                      });
            },
            err -> System.err.println("Error: " + err.getMessage()),
            () -> System.out.println("\n[DONE]"));
  }
}
