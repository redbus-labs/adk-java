package com.google.adk.models;

import static com.google.adk.models.RedbusADG.cleanForIdentifierPattern;
import static com.google.common.collect.ImmutableList.toImmutableList;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.adk.tools.BaseTool;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Flowable;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BaseLlm implementation for Sarvam AI models.
 *
 * <p>Sarvam AI exposes an OpenAI-compatible chat completions API. The base URL is read from the
 * {@code SARVAM_API_BASE} environment variable (default {@code https://api.sarvam.ai/v1}) and the
 * API key from {@code SARVAM_API_KEY}.
 *
 * @author Sandeep Belgavi
 */
public class SarvamBaseLM extends BaseLlm {

  public static final String SARVAM_API_BASE_ENV = "SARVAM_API_BASE";
  public static final String SARVAM_API_KEY_ENV = "SARVAM_API_KEY";
  private static final String DEFAULT_BASE_URL = "https://api.sarvam.ai/v1";

  private final String baseUrl;
  private static final Logger logger = LoggerFactory.getLogger(SarvamBaseLM.class);

  private static final String CONTINUE_OUTPUT_MESSAGE =
      "Continue output. DO NOT look at this line. ONLY look at the content before this line and"
          + " system instruction.";

  public SarvamBaseLM(String model) {
    super(model);
    this.baseUrl = null;
  }

  public SarvamBaseLM(String model, String baseUrl) {
    super(model);
    this.baseUrl = baseUrl;
  }

  private String resolveBaseUrl() {
    if (baseUrl != null) {
      return baseUrl;
    }
    String envUrl = System.getenv(SARVAM_API_BASE_ENV);
    return envUrl != null ? envUrl : DEFAULT_BASE_URL;
  }

  private String resolveApiKey() {
    return System.getenv(SARVAM_API_KEY_ENV);
  }

  @Override
  public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
    if (stream) {
      return generateContentStream(llmRequest);
    }

    List<Content> contents = llmRequest.contents();
    if (contents.isEmpty() || !Iterables.getLast(contents).role().orElse("").equals("user")) {
      Content userContent = Content.fromParts(Part.fromText(CONTINUE_OUTPUT_MESSAGE));
      contents =
          Stream.concat(contents.stream(), Stream.of(userContent)).collect(toImmutableList());
    }

    String systemText = extractSystemText(llmRequest);
    JSONArray messages = buildMessages(systemText, llmRequest.contents());
    JSONArray functions = buildTools(llmRequest);

    boolean lastRespToolExecuted =
        Iterables.getLast(Iterables.getLast(contents).parts().get()).functionResponse().isPresent();

    float temperature =
        llmRequest.config().flatMap(GenerateContentConfig::temperature).orElse(0.7f);

    JSONObject response =
        callChatCompletions(
            this.model(),
            messages,
            lastRespToolExecuted ? null : (functions.length() > 0 ? functions : null),
            temperature,
            false);

    GenerateContentResponseUsageMetadata usageMetadata = extractUsageMetadata(response);

    JSONArray choices = response.optJSONArray("choices");
    if (choices == null || choices.length() == 0) {
      logger.error("Sarvam API returned no choices: {}", response);
      return Flowable.just(
          LlmResponse.builder()
              .content(Content.builder().role("model").parts(Part.fromText("")).build())
              .build());
    }

    JSONObject message = choices.getJSONObject(0).getJSONObject("message");
    Part part = openAiMessageToPart(message);

    LlmResponse.Builder responseBuilder = LlmResponse.builder();

    if (part.functionCall().isPresent()) {
      responseBuilder.content(
          Content.builder()
              .role("model")
              .parts(ImmutableList.of(Part.builder().functionCall(part.functionCall().get()).build()))
              .build());
    } else {
      responseBuilder.content(
          Content.builder().role("model").parts(ImmutableList.of(part)).build());
    }

    if (usageMetadata != null) {
      responseBuilder.usageMetadata(usageMetadata);
    }

    return Flowable.just(responseBuilder.build());
  }

  private Flowable<LlmResponse> generateContentStream(LlmRequest llmRequest) {
    List<Content> contents = llmRequest.contents();
    if (contents.isEmpty() || !Iterables.getLast(contents).role().orElse("").equals("user")) {
      Content userContent = Content.fromParts(Part.fromText(CONTINUE_OUTPUT_MESSAGE));
      contents =
          Stream.concat(contents.stream(), Stream.of(userContent)).collect(toImmutableList());
    }

    String systemText = extractSystemText(llmRequest);
    JSONArray messages = buildMessages(systemText, llmRequest.contents());
    JSONArray functions = buildTools(llmRequest);

    final List<Content> finalContents = contents;
    boolean lastRespToolExecuted =
        Iterables.getLast(Iterables.getLast(finalContents).parts().get())
            .functionResponse()
            .isPresent();

    float temperature =
        llmRequest.config().flatMap(GenerateContentConfig::temperature).orElse(0.7f);

    final StringBuilder accumulatedText = new StringBuilder();
    final StringBuilder functionCallName = new StringBuilder();
    final StringBuilder functionCallArgs = new StringBuilder();
    final AtomicBoolean inFunctionCall = new AtomicBoolean(false);
    final AtomicBoolean streamCompleted = new AtomicBoolean(false);
    final AtomicInteger inputTokens = new AtomicInteger(0);
    final AtomicInteger outputTokens = new AtomicInteger(0);

    return Flowable.generate(
        () ->
            callChatCompletionsStream(
                this.model(),
                messages,
                lastRespToolExecuted ? null : (functions.length() > 0 ? functions : null),
                temperature),
        (reader, emitter) -> {
          try {
            if (reader == null || streamCompleted.get()) {
              emitter.onComplete();
              return;
            }

            String line = reader.readLine();
            if (line == null) {
              if (accumulatedText.length() > 0) {
                emitter.onNext(createTextResponse(accumulatedText.toString(), false));
              }
              emitter.onComplete();
              return;
            }

            if (line.isEmpty() || line.equals("data: [DONE]")) {
              if (line.equals("data: [DONE]")) {
                streamCompleted.set(true);
                GenerateContentResponseUsageMetadata usageMetadata =
                    buildUsageMetadata(inputTokens.get(), outputTokens.get());

                if (inFunctionCall.get() && functionCallName.length() > 0) {
                  try {
                    Map<String, Object> args = new JSONObject(functionCallArgs.toString()).toMap();
                    FunctionCall fc =
                        FunctionCall.builder().name(functionCallName.toString()).args(args).build();
                    Part part = Part.builder().functionCall(fc).build();

                    LlmResponse.Builder funcResponseBuilder =
                        LlmResponse.builder()
                            .content(
                                Content.builder()
                                    .role("model")
                                    .parts(ImmutableList.of(part))
                                    .build());
                    if (usageMetadata != null) {
                      funcResponseBuilder.usageMetadata(usageMetadata);
                    }
                    emitter.onNext(funcResponseBuilder.build());
                  } catch (Exception funcEx) {
                    logger.error("Error creating function call response", funcEx);
                  }
                } else if (accumulatedText.length() > 0) {
                  LlmResponse.Builder finalBuilder =
                      LlmResponse.builder()
                          .content(
                              Content.builder()
                                  .role("model")
                                  .parts(Part.fromText(accumulatedText.toString()))
                                  .build())
                          .partial(false);
                  if (usageMetadata != null) {
                    finalBuilder.usageMetadata(usageMetadata);
                  }
                  emitter.onNext(finalBuilder.build());
                }
                emitter.onComplete();
              }
              return;
            }

            if (!line.startsWith("data: ")) {
              return;
            }

            String jsonStr = line.substring(6);
            JSONObject chunk;
            try {
              chunk = new JSONObject(jsonStr);
            } catch (Exception parseEx) {
              logger.warn("Failed to parse Sarvam SSE chunk: {}", jsonStr, parseEx);
              return;
            }

            if (chunk.has("usage")) {
              JSONObject usage = chunk.getJSONObject("usage");
              inputTokens.set(usage.optInt("prompt_tokens", 0));
              outputTokens.set(usage.optInt("completion_tokens", 0));
            }

            JSONArray choices = chunk.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
              return;
            }

            JSONObject choice = choices.getJSONObject(0);
            JSONObject delta = choice.optJSONObject("delta");
            if (delta == null) {
              return;
            }

            if (delta.has("content") && !delta.isNull("content")) {
              String text = delta.getString("content");
              if (!text.isEmpty()) {
                accumulatedText.append(text);
                emitter.onNext(createTextResponse(text, true));
              }
            }

            if (delta.has("tool_calls")) {
              inFunctionCall.set(true);
              JSONArray toolCalls = delta.getJSONArray("tool_calls");
              if (toolCalls.length() > 0) {
                JSONObject toolCall = toolCalls.getJSONObject(0);
                JSONObject function = toolCall.optJSONObject("function");
                if (function != null) {
                  if (function.has("name") && !function.isNull("name")) {
                    functionCallName.append(function.getString("name"));
                  }
                  if (function.has("arguments") && !function.isNull("arguments")) {
                    functionCallArgs.append(function.getString("arguments"));
                  }
                }
              }
            }
          } catch (Exception e) {
            logger.error("Error in Sarvam streaming", e);
            emitter.onError(e);
          }
        },
        reader -> {
          try {
            if (reader != null) {
              reader.close();
            }
          } catch (IOException e) {
            logger.error("Error closing stream reader", e);
          }
        });
  }

  private String extractSystemText(LlmRequest llmRequest) {
    Optional<GenerateContentConfig> configOpt = llmRequest.config();
    if (configOpt.isPresent()) {
      Optional<Content> systemInstructionOpt = configOpt.get().systemInstruction();
      if (systemInstructionOpt.isPresent()) {
        String text =
            systemInstructionOpt.get().parts().orElse(ImmutableList.of()).stream()
                .filter(p -> p.text().isPresent())
                .map(p -> p.text().get())
                .collect(Collectors.joining("\n"));
        if (!text.isEmpty()) {
          return text;
        }
      }
    }
    return "";
  }

  private JSONArray buildMessages(String systemText, List<Content> contents) {
    JSONArray messages = new JSONArray();

    if (!systemText.isEmpty()) {
      JSONObject systemMsg = new JSONObject();
      systemMsg.put("role", "system");
      systemMsg.put("content", systemText);
      messages.put(systemMsg);
    }

    for (Content item : contents) {
      JSONObject msg = new JSONObject();
      String role = item.role().orElse("user");
      msg.put("role", role.equals("model") ? "assistant" : role);

      if (item.parts().isPresent() && !item.parts().get().isEmpty()) {
        Part firstPart = item.parts().get().get(0);
        if (firstPart.functionResponse().isPresent()) {
          msg.put(
              "content",
              new JSONObject(firstPart.functionResponse().get().response().get()).toString());
          msg.put("role", "tool");
          msg.put("tool_call_id", firstPart.functionResponse().get().name().orElse("unknown"));
        } else {
          msg.put("content", item.text());
        }
      } else {
        msg.put("content", item.text());
      }
      messages.put(msg);
    }
    return messages;
  }

  private JSONArray buildTools(LlmRequest llmRequest) {
    JSONArray functions = new JSONArray();
    llmRequest
        .tools()
        .forEach(
            (name, baseTool) -> {
              Optional<FunctionDeclaration> declOpt = baseTool.declaration();
              if (declOpt.isEmpty()) {
                logger.warn("Skipping tool '{}' with missing declaration.", baseTool.name());
                return;
              }

              FunctionDeclaration decl = declOpt.get();
              Map<String, Object> funcMap = new HashMap<>();
              funcMap.put("name", cleanForIdentifierPattern(decl.name().get()));
              funcMap.put("description", cleanForIdentifierPattern(decl.description().orElse("")));

              Optional<Schema> paramsOpt = decl.parameters();
              if (paramsOpt.isPresent()) {
                Schema paramsSchema = paramsOpt.get();
                Map<String, Object> paramsMap = new HashMap<>();
                paramsMap.put("type", "object");

                Optional<Map<String, Schema>> propsOpt = paramsSchema.properties();
                if (propsOpt.isPresent()) {
                  Map<String, Object> propsMap = new HashMap<>();
                  ObjectMapper mapper = new ObjectMapper();
                  mapper.registerModule(new Jdk8Module());

                  propsOpt
                      .get()
                      .forEach(
                          (key, schema) -> {
                            Map<String, Object> schemaMap =
                                mapper.convertValue(
                                    schema, new TypeReference<Map<String, Object>>() {});
                            normalizeTypeStrings(schemaMap);
                            propsMap.put(key, schemaMap);
                          });
                  paramsMap.put("properties", propsMap);
                }

                paramsSchema
                    .required()
                    .ifPresent(requiredList -> paramsMap.put("required", requiredList));
                funcMap.put("parameters", paramsMap);
              }

              JSONObject toolWrapper = new JSONObject();
              toolWrapper.put("type", "function");
              toolWrapper.put("function", new JSONObject(funcMap));
              functions.put(toolWrapper);
            });
    return functions;
  }

  private JSONObject callChatCompletions(
      String model, JSONArray messages, JSONArray tools, float temperature, boolean stream) {
    try {
      String apiUrl = resolveBaseUrl() + "/chat/completions";
      String apiKey = resolveApiKey();

      JSONObject payload = new JSONObject();
      payload.put("model", model);
      payload.put("messages", messages);
      payload.put("temperature", temperature);
      payload.put("stream", stream);

      if (tools != null && tools.length() > 0) {
        payload.put("tools", tools);
        payload.put("tool_choice", "auto");
      }

      String jsonString = payload.toString();
      logger.debug("Sarvam request: {}", jsonString);

      URL url = new URL(apiUrl);
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod("POST");
      conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
      if (apiKey != null && !apiKey.isEmpty()) {
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
      }
      conn.setDoOutput(true);
      conn.setFixedLengthStreamingMode(jsonString.getBytes("UTF-8").length);

      try (OutputStream os = conn.getOutputStream();
          OutputStreamWriter writer = new OutputStreamWriter(os, "UTF-8")) {
        writer.write(jsonString);
        writer.flush();
      }

      int responseCode = conn.getResponseCode();
      logger.info("Sarvam response code: {} for model: {}", responseCode, model);

      InputStream inputStream =
          (responseCode < 400) ? conn.getInputStream() : conn.getErrorStream();
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(inputStream, "UTF-8"))) {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
          sb.append(line);
        }
        JSONObject responseJson = new JSONObject(sb.toString());
        conn.disconnect();
        return responseJson;
      }
    } catch (Exception ex) {
      logger.error("Error calling Sarvam chat completions API", ex);
      return new JSONObject();
    }
  }

  private BufferedReader callChatCompletionsStream(
      String model, JSONArray messages, JSONArray tools, float temperature) {
    try {
      String apiUrl = resolveBaseUrl() + "/chat/completions";
      String apiKey = resolveApiKey();

      JSONObject payload = new JSONObject();
      payload.put("model", model);
      payload.put("messages", messages);
      payload.put("temperature", temperature);
      payload.put("stream", true);

      if (tools != null && tools.length() > 0) {
        payload.put("tools", tools);
        payload.put("tool_choice", "auto");
      }

      String jsonString = payload.toString();

      URL url = new URL(apiUrl);
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod("POST");
      conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
      conn.setRequestProperty("Accept", "text/event-stream");
      if (apiKey != null && !apiKey.isEmpty()) {
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
      }
      conn.setDoOutput(true);
      conn.setFixedLengthStreamingMode(jsonString.getBytes("UTF-8").length);

      try (OutputStream os = conn.getOutputStream();
          OutputStreamWriter writer = new OutputStreamWriter(os, "UTF-8")) {
        writer.write(jsonString);
        writer.flush();
      }

      int responseCode = conn.getResponseCode();
      logger.info("Sarvam streaming response code: {} for model: {}", responseCode, model);

      if (responseCode >= 200 && responseCode < 300) {
        return new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
      } else {
        try (InputStream errorStream = conn.getErrorStream();
            BufferedReader errorReader =
                new BufferedReader(new InputStreamReader(errorStream, "UTF-8"))) {
          StringBuilder errorResponse = new StringBuilder();
          String errorLine;
          while ((errorLine = errorReader.readLine()) != null) {
            errorResponse.append(errorLine);
          }
          logger.error(
              "Sarvam streaming request failed: status={} body={}",
              responseCode,
              errorResponse);
        }
        conn.disconnect();
        return null;
      }
    } catch (IOException ex) {
      logger.error("Error in Sarvam streaming request", ex);
      return null;
    }
  }

  private LlmResponse createTextResponse(String text, boolean partial) {
    return LlmResponse.builder()
        .content(Content.builder().role("model").parts(Part.fromText(text)).build())
        .partial(partial)
        .build();
  }

  private GenerateContentResponseUsageMetadata extractUsageMetadata(JSONObject response) {
    if (response == null || !response.has("usage")) {
      return null;
    }
    try {
      JSONObject usage = response.getJSONObject("usage");
      int promptTokens = usage.optInt("prompt_tokens", 0);
      int completionTokens = usage.optInt("completion_tokens", 0);
      int totalTokens = usage.optInt("total_tokens", promptTokens + completionTokens);

      if (totalTokens > 0 || promptTokens > 0 || completionTokens > 0) {
        logger.info(
            "Sarvam token counts: prompt={}, completion={}, total={}",
            promptTokens,
            completionTokens,
            totalTokens);
        return GenerateContentResponseUsageMetadata.builder()
            .promptTokenCount(promptTokens)
            .candidatesTokenCount(completionTokens)
            .totalTokenCount(totalTokens)
            .build();
      }
    } catch (Exception e) {
      logger.warn("Failed to parse token usage from Sarvam response", e);
    }
    return null;
  }

  private GenerateContentResponseUsageMetadata buildUsageMetadata(
      int promptTokens, int completionTokens) {
    int totalTokens = promptTokens + completionTokens;
    if (totalTokens > 0 || promptTokens > 0 || completionTokens > 0) {
      return GenerateContentResponseUsageMetadata.builder()
          .promptTokenCount(promptTokens)
          .candidatesTokenCount(completionTokens)
          .totalTokenCount(totalTokens)
          .build();
    }
    return null;
  }

  static Part openAiMessageToPart(JSONObject message) {
    if (message.has("tool_calls")) {
      JSONArray toolCalls = message.optJSONArray("tool_calls");
      if (toolCalls != null && toolCalls.length() > 0) {
        JSONObject toolCall = toolCalls.getJSONObject(0);
        JSONObject function = toolCall.optJSONObject("function");
        if (function != null) {
          String name = function.optString("name", null);
          String argsStr = function.optString("arguments", "{}");
          if (name != null) {
            Map<String, Object> args = new JSONObject(argsStr).toMap();
            FunctionCall fc = FunctionCall.builder().name(name).args(args).build();
            return Part.builder().functionCall(fc).build();
          }
        }
      }
    }

    if (message.has("content") && !message.isNull("content")) {
      return Part.builder().text(message.getString("content")).build();
    }

    return Part.builder().text("").build();
  }

  private void normalizeTypeStrings(Map<String, Object> valueDict) {
    if (valueDict == null) {
      return;
    }
    if (valueDict.containsKey("type")) {
      valueDict.put("type", ((String) valueDict.get("type")).toLowerCase());
    }
    if (valueDict.containsKey("items")) {
      Object items = valueDict.get("items");
      if (items instanceof Map) {
        normalizeTypeStrings((Map<String, Object>) items);
        Map<String, Object> itemsMap = (Map<String, Object>) items;
        if (itemsMap.containsKey("properties")) {
          Map<String, Object> properties = (Map<String, Object>) itemsMap.get("properties");
          if (properties != null) {
            for (Object value : properties.values()) {
              if (value instanceof Map) {
                normalizeTypeStrings((Map<String, Object>) value);
              }
            }
          }
        }
      }
    }
  }

  @Override
  public BaseLlmConnection connect(LlmRequest llmRequest) {
    return new GenericLlmConnection(this, llmRequest);
  }
}
