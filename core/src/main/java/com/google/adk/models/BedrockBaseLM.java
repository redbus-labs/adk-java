/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.google.adk.models;

import static com.google.adk.models.RedbusADG.callLLMChat;
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
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Flowable;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author ryzen
 * @author Manoj Kumar, Sandeep Belgavi
 * @date 2025-06-27
 */
public class BedrockBaseLM extends BaseLlm {

  // Use a constant for the environment variable name
  public static final String BEDROCK_ENV_VAR = "BEDROCK_URL";
  public String D_URL = null;

  // Corrected the logger name to use OllamaBaseLM.class
  private static final Logger logger = LoggerFactory.getLogger(BedrockBaseLM.class);

  private static final String CONTINUE_OUTPUT_MESSAGE =
      "Continue output. DO NOT look at this line. ONLY look at the content before this line and"
          + " system instruction.";

  public BedrockBaseLM(String model) {

    super(model);
  }

  /**
   * @param model The model ID (e.g., "openai.gpt-oss-20b-1:0")
   * @param BEDROCK_EP The base Bedrock endpoint (e.g.,
   *     "https://bedrock-runtime.us-west-2.amazonaws.com/model")
   */
  public BedrockBaseLM(String model, String BEDROCK_EP) {
    super(model);
    this.D_URL = BEDROCK_EP;
  }

  @Override
  public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
    if (stream) {
      return generateContentStream(llmRequest);
    }

    List<Content> contents = llmRequest.contents();
    // Last content must be from the user, otherwise the model won't respond.
    if (contents.isEmpty() || !Iterables.getLast(contents).role().orElse("").equals("user")) {
      Content userContent = Content.fromParts(Part.fromText(CONTINUE_OUTPUT_MESSAGE));
      contents =
          Stream.concat(contents.stream(), Stream.of(userContent)).collect(toImmutableList());
    }

    String systemText = "";
    Optional<GenerateContentConfig> configOpt = llmRequest.config();
    if (configOpt.isPresent()) {
      Optional<Content> systemInstructionOpt = configOpt.get().systemInstruction();
      if (systemInstructionOpt.isPresent()) {
        String extractedSystemText =
            systemInstructionOpt.get().parts().orElse(ImmutableList.of()).stream()
                .filter(p -> p.text().isPresent())
                .map(p -> p.text().get())
                .collect(Collectors.joining("\n"));
        if (!extractedSystemText.isEmpty()) {
          systemText = extractedSystemText;
        }
      }
    }

    /** "messages": [ { "role": "user", "content": [{"text": "Hello"}] } ], */
    JSONArray messages = new JSONArray();

    JSONObject llmMessageJson1 = new JSONObject();
    llmMessageJson1.put("role", "assistant");
    JSONObject txtMsg = new JSONObject();
    txtMsg.put("text", systemText);
    JSONArray contentArray = new JSONArray();
    contentArray.put(txtMsg);
    llmMessageJson1.put("content", contentArray);
    messages.put(llmMessageJson1); // Agent system prompt is always added

    llmRequest.contents().stream()
        .forEach(
            item -> {
              //   return new MessageParam(content.role().get().equals("model") ||
              // content.role().get().equals("assistant") ? "" : "",content.text());
              JSONObject messageQuantum = new JSONObject();
              messageQuantum.put(
                  "role",
                  item.role().get().equals("model") || item.role().get().equals("assistant")
                      ? "assistant"
                      : "user");

              // Additinal override work to add function response
              if (item.parts().get().get(0).functionResponse().isPresent()) {
                JSONObject txtMsg3 = new JSONObject();
                txtMsg3.put(
                    "text",
                    item.parts().get().get(0).functionResponse().get().name().get()
                        + " responded with these values, "
                        + new JSONObject(
                                item.parts().get().get(0).functionResponse().get().response().get())
                            .toString());

                JSONArray contentArray2 = new JSONArray();
                contentArray2.put(txtMsg3);

                messageQuantum.put("content", contentArray2);
              } else if (item.parts().get().get(0).functionCall().isPresent()) {
                JSONObject txtMsg3 = new JSONObject();
                txtMsg3.put(
                    "text",
                    item.parts().get().get(0).functionCall().get().name().get()
                        + " is to be called with these arguments, "
                        + new JSONObject(
                                item.parts().get().get(0).functionCall().get().args().get())
                            .toString());

                JSONArray contentArray2 = new JSONArray();
                contentArray2.put(txtMsg3);

                messageQuantum.put("content", contentArray2);
              } else {

                JSONObject txtMsg3 = new JSONObject();
                txtMsg3.put("text", item.text());
                JSONArray contentArray2 = new JSONArray();
                contentArray2.put(txtMsg3);
                messageQuantum.put("content", contentArray2);
              }
              messages.put(messageQuantum);
            });

    // Tools
    // Define the required pattern for the name
    JSONArray functions = new JSONArray();
    llmRequest
        .tools()
        .entrySet()
        .forEach(
            tooldetail -> {
              BaseTool baseTool = tooldetail.getValue();

              // Get the function declaration from the base tool
              Optional<FunctionDeclaration> declarationOptional = baseTool.declaration();

              // Skip this tool if there is no function declaration
              if (!declarationOptional.isPresent()) {
                // Log a warning or handle appropriately
                System.err.println(
                    "Skipping tool '" + baseTool.name() + "' with missing declaration.");
                // continue; // If inside a loop
                return; // If processing a single tool outside a loop
              }

              FunctionDeclaration functionDeclaration = declarationOptional.get();

              // Build the top-level map representing the tool JSON structure
              Map<String, Object> toolMap = new HashMap<>();

              // Add the tool's name and description from the function declaration
              toolMap.put("name", cleanForIdentifierPattern(functionDeclaration.name().get()));
              toolMap.put(
                  "description",
                  cleanForIdentifierPattern(
                      functionDeclaration
                          .description()
                          .orElse(""))); // Use description from declaration, handle Optional

              // Build the 'parameters' object if parameters are defined
              Optional<Schema> parametersOptional = functionDeclaration.parameters();
              if (parametersOptional.isPresent()) {
                Schema parametersSchema = parametersOptional.get();

                Map<String, Object> parametersMap = new HashMap<>();
                parametersMap.put(
                    "type", "object"); // Function parameters schema type is typically "object"

                // Build the 'properties' map within 'parameters'
                Optional<Map<String, Schema>> propertiesOptional = parametersSchema.properties();
                if (propertiesOptional.isPresent()) {
                  Map<String, Object> propertiesMap = new HashMap<>();
                  // Create ObjectMapper instance once for the loop
                  ObjectMapper objectMapper = new ObjectMapper();
                  objectMapper.registerModule(
                      new Jdk8Module()); // Register module for Java 8 Optionals, etc.

                  propertiesOptional
                      .get()
                      .forEach(
                          (key, schema) -> {
                            // Convert the library's Schema object for a parameter to a generic Map
                            Map<String, Object> schemaMap =
                                objectMapper.convertValue(
                                    schema, new TypeReference<Map<String, Object>>() {});

                            // Apply your custom logic to update the type string
                            // !!! This function updateTypeString(schemaMap) is required and not
                            // provided !!!
                            updateTypeString(
                                schemaMap); // Ensure this modifies schemaMap in place or returns
                            // the modified map

                            propertiesMap.put(key, schemaMap);
                          });
                  parametersMap.put("properties", propertiesMap);
                }

                // Add the 'required' list within 'parameters' if present
                parametersSchema
                    .required()
                    .ifPresent(
                        requiredList ->
                            parametersMap.put(
                                "required", requiredList)); // Assuming required() returns
                // Optional<List<String>>

                // Add the completed 'parameters' map to the main tool map
                JSONObject inputSchema = new JSONObject();
                inputSchema.put("json", parametersMap);
                toolMap.put("inputSchema", inputSchema);
              }

              // Convert the complete tool map into an org.json.JSONObject
              JSONObject jsonToolW = new JSONObject();

              JSONObject jsonTool = new JSONObject(toolMap);

              jsonToolW.put("toolSpec", jsonTool);

              // Add the generated tool JSON object to your functions list/array
              functions.put(jsonToolW);
            });

    // Check if the tool is executed, then parse and response.

    logger.debug("functions: {}", functions.toString(1));

    String modelId =
        this.model(); // "devstral";//"llama3.2:3b-instruct-q2_K";//"llama3.2"; // The 1b doesn't
    // support tool

    // If last user response has the function reponse, then function calla is not needed.
    boolean LAST_RESP_TOOl_EXECUTED =
        Iterables.getLast(Iterables.getLast(contents).parts().get()).functionResponse().isPresent();

    JSONObject agentresponse =
        callLLMChat(
            modelId,
            messages,
            LAST_RESP_TOOl_EXECUTED
                ? null
                : (functions.length() > 0
                    ? functions
                    : null)); // Tools/functions can not be of 0 length

    JSONObject responseQuantum = agentresponse.getJSONObject("output").getJSONObject("message");

    // Check if tool call is required
    // Tools call
    LlmResponse.Builder responseBuilder = LlmResponse.builder();
    List<Part> parts = new ArrayList<>();
    Part part = ollamaContentBlockToPart(responseQuantum);
    parts.add(part);

    // Call tool
    if (!part.functionCall().isEmpty()
        && part.functionResponse().isEmpty()
        && !LAST_RESP_TOOl_EXECUTED) {

      responseBuilder.content(
          Content.builder()
              .role("assistant")
              .parts(
                  ImmutableList.of(Part.builder().functionCall(part.functionCall().get()).build()))
              .build());

      //  responseBuilder.partial(false).turnComplete(false);

    } else {
      responseBuilder.content(
          Content.builder().role("assistant").parts(ImmutableList.copyOf(parts)).build());
    }

    return Flowable.just(responseBuilder.build());
  }

  public Flowable<LlmResponse> generateContentStream(LlmRequest llmRequest) {
    List<Content> contents = llmRequest.contents();
    // Last content must be from the user, otherwise the model won't respond.
    if (contents.isEmpty() || !Iterables.getLast(contents).role().orElse("").equals("user")) {
      Content userContent = Content.fromParts(Part.fromText(CONTINUE_OUTPUT_MESSAGE));
      contents =
          Stream.concat(contents.stream(), Stream.of(userContent)).collect(toImmutableList());
    }

    String systemText = "";
    Optional<GenerateContentConfig> configOpt = llmRequest.config();
    if (configOpt.isPresent()) {
      Optional<Content> systemInstructionOpt = configOpt.get().systemInstruction();
      if (systemInstructionOpt.isPresent()) {
        String extractedSystemText =
            systemInstructionOpt.get().parts().orElse(ImmutableList.of()).stream()
                .filter(p -> p.text().isPresent())
                .map(p -> p.text().get())
                .collect(Collectors.joining("\n"));
        if (!extractedSystemText.isEmpty()) {
          systemText = extractedSystemText;
        }
      }
    }

    // Messages
    JSONArray messages = new JSONArray();

    JSONObject llmMessageJson1 = new JSONObject();
    llmMessageJson1.put("role", "assistant");
    llmMessageJson1.put("content", systemText);
    messages.put(llmMessageJson1); // Agent system prompt is always added

    final List<Content> finalContents = contents;
    finalContents.stream()
        .forEach(
            item -> {
              //   return new MessageParam(content.role().get().equals("model") ||
              // content.role().get().equals("assistant") ? "" : "",content.text());
              JSONObject messageQuantum = new JSONObject();
              messageQuantum.put(
                  "role",
                  item.role().get().equals("model") || item.role().get().equals("assistant")
                      ? "assistant"
                      : "user");

              // Additinal override work to add function response
              if (item.parts().get().get(0).functionResponse().isPresent()) {
                JSONObject txtMsg3 = new JSONObject();
                txtMsg3.put(
                    "text",
                    item.parts().get().get(0).functionResponse().get().name().get()
                        + " responded with these values, "
                        + new JSONObject(
                                item.parts().get().get(0).functionResponse().get().response().get())
                            .toString());

                JSONArray contentArray2 = new JSONArray();
                contentArray2.put(txtMsg3);

                messageQuantum.put("content", contentArray2);
              } else if (item.parts().get().get(0).functionCall().isPresent()) {
                JSONObject txtMsg3 = new JSONObject();
                txtMsg3.put(
                    "text",
                    item.parts().get().get(0).functionCall().get().name().get()
                        + " is to be called with these arguments, "
                        + new JSONObject(
                                item.parts().get().get(0).functionCall().get().args().get())
                            .toString());

                JSONArray contentArray2 = new JSONArray();
                contentArray2.put(txtMsg3);

                messageQuantum.put("content", contentArray2);
              } else {

                JSONObject txtMsg3 = new JSONObject();
                txtMsg3.put("text", item.text());
                JSONArray contentArray2 = new JSONArray();
                contentArray2.put(txtMsg3);
                messageQuantum.put("content", contentArray2);
              }
              messages.put(messageQuantum);
            });

    // Tools
    JSONArray functions = new JSONArray();
    llmRequest
        .tools()
        .entrySet()
        .forEach(
            tooldetail -> {
              BaseTool baseTool = tooldetail.getValue();
              Optional<FunctionDeclaration> declarationOptional = baseTool.declaration();
              if (!declarationOptional.isPresent()) {
                System.err.println(
                    "Skipping tool '" + baseTool.name() + "' with missing declaration.");
                return;
              }
              FunctionDeclaration functionDeclaration = declarationOptional.get();
              Map<String, Object> toolMap = new HashMap<>();
              toolMap.put("name", cleanForIdentifierPattern(functionDeclaration.name().get()));
              toolMap.put(
                  "description",
                  cleanForIdentifierPattern(functionDeclaration.description().orElse("")));
              Optional<Schema> parametersOptional = functionDeclaration.parameters();
              if (parametersOptional.isPresent()) {
                Schema parametersSchema = parametersOptional.get();
                Map<String, Object> parametersMap = new HashMap<>();
                parametersMap.put("type", "object");
                Optional<Map<String, Schema>> propertiesOptional = parametersSchema.properties();
                if (propertiesOptional.isPresent()) {
                  Map<String, Object> propertiesMap = new HashMap<>();
                  ObjectMapper objectMapper = new ObjectMapper();
                  objectMapper.registerModule(new Jdk8Module());
                  propertiesOptional
                      .get()
                      .forEach(
                          (key, schema) -> {
                            Map<String, Object> schemaMap =
                                objectMapper.convertValue(
                                    schema, new TypeReference<Map<String, Object>>() {});
                            updateTypeString(schemaMap);
                            propertiesMap.put(key, schemaMap);
                          });
                  parametersMap.put("properties", propertiesMap);
                }
                parametersSchema
                    .required()
                    .ifPresent(requiredList -> parametersMap.put("required", requiredList));
                toolMap.put("parameters", parametersMap);
              }
              // Convert the complete tool map into an org.json.JSONObject
              JSONObject jsonToolW = new JSONObject();
              jsonToolW.put("type", "function");

              JSONObject jsonTool = new JSONObject(toolMap);
              jsonToolW.put("function", jsonTool);

              // Add the generated tool JSON object to your functions list/array
              functions.put(jsonToolW);
            });

    String modelId = this.model();

    boolean LAST_RESP_TOOl_EXECUTED =
        Iterables.getLast(Iterables.getLast(finalContents).parts().get())
            .functionResponse()
            .isPresent();

    final StringBuilder functionCallName = new StringBuilder();
    final StringBuilder functionCallArgs = new StringBuilder();
    final AtomicBoolean inFunctionCall = new AtomicBoolean(false);

    return Flowable.generate(
        () ->
            callLLMChatStream(
                modelId,
                messages,
                LAST_RESP_TOOl_EXECUTED ? null : (functions.length() > 0 ? functions : null)),
        (reader, emitter) -> {
          try {
            if (reader == null) {
              emitter.onComplete();
              return;
            }
            String line = reader.readLine();
            if (line == null) {
              emitter.onComplete();
              return;
            }
            if (line.isEmpty()) {
              return;
            }

            JSONObject responseJson = new JSONObject(line);
            JSONObject message = responseJson.optJSONObject("message");

            List<Part> parts = new ArrayList<>();

            if (message != null) {
              if (message.has("content") && message.get("content") instanceof String) {
                String text = message.getString("content");
                if (!text.isEmpty()) {
                  Part part = Part.fromText(text);
                  parts.add(part);
                  LlmResponse llmResponse =
                      LlmResponse.builder()
                          .content(
                              Content.builder()
                                  .role("model")
                                  .parts(ImmutableList.copyOf(parts))
                                  .build())
                          .partial(true)
                          .build();
                  emitter.onNext(llmResponse);
                }
              }

              if (message.has("tool_calls")) {
                inFunctionCall.set(true);
                JSONArray toolCalls = message.getJSONArray("tool_calls");
                if (toolCalls.length() > 0) {
                  JSONObject toolCall = toolCalls.getJSONObject(0);
                  JSONObject function = toolCall.getJSONObject("function");
                  if (function.has("name")) {
                    functionCallName.append(function.getString("name"));
                  }
                  if (function.has("arguments")) {
                    JSONObject argsJson =
                        function.optJSONObject("arguments"); // Use optJSONObject for null safety*/
                    functionCallArgs.append(argsJson.toString());
                  }
                }
              }
            }

            if (responseJson.optBoolean("done", false)) {
              if (inFunctionCall.get()) {
                Map<String, Object> args = new JSONObject(functionCallArgs.toString()).toMap();
                FunctionCall fc =
                    FunctionCall.builder().name(functionCallName.toString()).args(args).build();
                Part part = Part.builder().functionCall(fc).build();
                parts.add(part);
                LlmResponse llmResponse =
                    LlmResponse.builder()
                        .content(
                            Content.builder()
                                .role("model")
                                .parts(ImmutableList.copyOf(parts))
                                .build())
                        .build();
                emitter.onNext(llmResponse);
              }
              emitter.onComplete();
            }
          } catch (Exception e) {
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

  public BufferedReader callLLMChatStream(String model, JSONArray messages, JSONArray tools) {
    try {
      String apiUrl =
          (D_URL != null ? D_URL : System.getenv(BEDROCK_ENV_VAR)) + "/" + model + "/converse";
      String AWS_BEARER_TOKEN_BEDROCK = System.getenv("AWS_BEARER_TOKEN_BEDROCK");
      System.out.println("Using Bedrock URL: " + apiUrl);
      JSONObject payload = new JSONObject();
      payload.put("model", model);
      payload.put("stream", true);
      payload.put("messages", messages);
      if (tools != null) {
        payload.put("tools", tools);
      }

      String jsonString = payload.toString();

      URL url = new URL(apiUrl);
      HttpURLConnection connection = (HttpURLConnection) url.openConnection();
      connection.setRequestMethod("POST");
      connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
      connection.setRequestProperty("Authorization", "Bearer " + AWS_BEARER_TOKEN_BEDROCK);
      connection.setDoOutput(true);
      connection.setFixedLengthStreamingMode(jsonString.getBytes("UTF-8").length);

      try (OutputStream outputStream = connection.getOutputStream();
          OutputStreamWriter writer = new OutputStreamWriter(outputStream, "UTF-8")) {
        writer.write(jsonString);
        writer.flush();
      }

      int responseCode = connection.getResponseCode();
      System.out.println("Response Code: " + responseCode);

      if (responseCode >= 200 && responseCode < 300) {
        return new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
      } else {
        try (InputStream errorStream = connection.getErrorStream();
            BufferedReader errorReader =
                new BufferedReader(new InputStreamReader(errorStream, "UTF-8"))) {
          StringBuilder errorResponse = new StringBuilder();
          String errorLine;
          while ((errorLine = errorReader.readLine()) != null) {
            errorResponse.append(errorLine);
          }
          System.err.println("Error Response Body: " + errorResponse.toString());
        } catch (IOException errorEx) {
          logger.error("Error reading error stream", errorEx);
        }
        connection.disconnect();
        return null;
      }
    } catch (IOException ex) {
      logger.error("Error in callLLMChatStream", ex);
      return null;
    }
  }

  @Override
  public BaseLlmConnection connect(LlmRequest llmRequest) {
    return new GenericLlmConnection(this, llmRequest);
  }

  /**
   * This method appears to be unused in the current context. It's typically used for modifying JSON
   * schemas, which is not directly related to sending chat messages to Ollama. You might consider
   * removing it if it's no longer needed.
   */
  private void updateTypeString(Map<String, Object> valueDict) {
    if (valueDict == null) {
      return;
    }
    if (valueDict.containsKey("type")) {
      valueDict.put("type", ((String) valueDict.get("type")).toLowerCase());
    }

    if (valueDict.containsKey("items")) {
      updateTypeString((Map<String, Object>) valueDict.get("items"));

      if (valueDict.get("items") instanceof Map
          && ((Map) valueDict.get("items")).containsKey("properties")) {
        Map<String, Object> properties =
            (Map<String, Object>) ((Map) valueDict.get("items")).get("properties");
        if (properties != null) {
          for (Object value : properties.values()) {
            if (value instanceof Map) {
              updateTypeString((Map<String, Object>) value);
            }
          }
        }
      }
    }
  }

  public static Part ollamaContentBlockToPart(JSONObject blockJson) {
    // Check for tool_calls first, as the example with tool_calls had empty content

    // If no valid tool_calls were processed, check for text content
    if (blockJson.has("content")) {
      JSONArray contentArray = blockJson.getJSONArray("content");
      for (int i = 0; i < contentArray.length(); i++) {
        JSONObject tempObj = contentArray.getJSONObject(i);
        if (tempObj.has("text")) {
          return Part.builder().text(tempObj.getString("text")).build();
        }

        if (tempObj.has("toolUse")) {
          JSONObject toolUse = tempObj.getJSONObject("toolUse"); // Use optJSONArray for null safety
          if (toolUse != null) {
            // Based on the provided structure and LangChain4j Part,
            // we typically handle one function call per Part.
            // We will process the first tool call in the array.
            // JSONObject toolCall = toolUse.optJSONObject("toolUse"); // Use optJSONObject for null
            // safety

            if (toolUse.has("name")) {
              JSONObject input =
                  toolUse.optJSONObject("input"); // Use optJSONObject for null safety
              Map<String, Object> args = input.toMap();
              FunctionCall functionCall =
                  FunctionCall.builder().name(toolUse.getString("name")).args(args).build();

              return Part.builder().functionCall(functionCall).build();
            }
          }
        }
      }

      // If 'content' key exists but value is not a String, might be unsupported.
    }

    // If neither usable tool_calls nor String content was found
    // This covers cases like malformed JSON matching the structure,
    // or structures not covered (e.g., image parts, other types).
    throw new UnsupportedOperationException(
        "Unsupported content block format or missing required fields: " + blockJson.toString());
  }

  /**
   * Makes a POST request to a specified URL with a dynamic JSON body. Fetches username and password
   * from environment variables.
   *
   * @param model
   * @param messages The list of messages for the "request.messages" field.
   * @param tools
   * @return The response body as a String.
   * @throws RuntimeException If environment variables are not set or JSON creation fails.
   */
  public JSONObject callLLMChat(String model, JSONArray messages, JSONArray tools) {
    try {
      JSONObject responseJ = new JSONObject();
      // API endpoint URL //OLLAMA_API_BASE
      String apiUrl = D_URL != null ? D_URL : System.getenv(BEDROCK_ENV_VAR);
      String AWS_BEARER_TOKEN_BEDROCK = System.getenv("AWS_BEARER_TOKEN_BEDROCK");
      // apiUrl = apiUrl + "/api/chat";

      // Constructing the JSON payload
      JSONObject payload = new JSONObject();
      payload.put("model", model);
      payload.put("stream", false); // Non-streaming
      payload.put("messages", messages); // Use same structure as streaming
      if (tools != null) {
        payload.put("tools", tools);
      }

      String jsonString = payload.toString();
      URL url = new URL(apiUrl);
      HttpURLConnection connection = (HttpURLConnection) url.openConnection();
      connection.setRequestMethod("POST");
      connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
      connection.setRequestProperty("Authorization", "Bearer " + AWS_BEARER_TOKEN_BEDROCK);
      connection.setDoOutput(true);
      connection.setFixedLengthStreamingMode(jsonString.getBytes("UTF-8").length);

      try (OutputStream outputStream = connection.getOutputStream();
          OutputStreamWriter writer = new OutputStreamWriter(outputStream, "UTF-8")) {
        writer.write(jsonString);
        writer.flush();
      } catch (IOException ex) {
        java.util.logging.Logger.getLogger(RedbusADG.class.getName()).log(Level.SEVERE, null, ex);
      }

      int responseCode = connection.getResponseCode();
      System.out.println("Response Code: " + responseCode);

      InputStream inputStream =
          (responseCode < 400) ? connection.getInputStream() : connection.getErrorStream();
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(inputStream, "UTF-8"))) {
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
          response.append(line);
        }
        responseJ = new JSONObject(response.toString());
      } catch (IOException ex) {
        java.util.logging.Logger.getLogger(RedbusADG.class.getName()).log(Level.SEVERE, null, ex);
      }
      connection.disconnect();
      return responseJ;

    } catch (MalformedURLException ex) {
      java.util.logging.Logger.getLogger(RedbusADG.class.getName()).log(Level.SEVERE, null, ex);
    } catch (ProtocolException ex) {
      java.util.logging.Logger.getLogger(RedbusADG.class.getName()).log(Level.SEVERE, null, ex);
    } catch (IOException ex) {
      java.util.logging.Logger.getLogger(RedbusADG.class.getName()).log(Level.SEVERE, null, ex);
    }
    return new JSONObject();
  }

  /**
   * Use prompt parameter to moderate the questions is prompt!=null, using the generate "options": {
   * "num_ctx": 4096 }
   *
   * @param prompt (Note: This 'prompt' is largely superseded by 'messages' for chat APIs, keep for
   *     compatibility if needed elsewhere)
   * @param model The Ollama model to use (e.g., "llama3")
   * @param messages The JSONArray of messages representing the chat history
   * @param tools Optional JSONArray of tool definitions
   * @return JSONObject representing the Ollama API response
   */
  public static JSONObject callLLMChat(
      boolean stream, String prompt, String model, JSONArray messages, JSONArray tools) {
    JSONObject responseJ = new JSONObject();
    try {
      // API endpoint URL //OLLAMA_API_BASE
      String apiUrl = System.getenv(BEDROCK_ENV_VAR);
      String AWS_BEARER_TOKEN_BEDROCK = System.getenv(BEDROCK_ENV_VAR);
      apiUrl = apiUrl + "/api/chat";

      // Constructing the JSON payload
      JSONObject payload = new JSONObject();
      payload.put("model", model);
      payload.put(
          "stream", false); // Assuming non-streaming as per current generateContent implementation
      payload.put("think", false);

      JSONObject options = new JSONObject();
      options.put("num_ctx", 4096);
      payload.put("options", options);

      // Add messages to the payload
      payload.put("messages", messages);

      // Add tools if provided
      if (tools != null) {
        payload.put("tools", tools);
      }

      // Convert payload to string
      String jsonString = payload.toString();

      // Create URL object
      URL url = new URL(apiUrl);

      // Open connection
      HttpURLConnection connection = (HttpURLConnection) url.openConnection();
      System.out.print("HTTP Connection to Ollama API: " + apiUrl.toString());
      // Set request method
      connection.setRequestMethod("POST");

      // Set headers
      connection.setRequestProperty("Content-Type", "application/json");
      connection.setRequestProperty(
          "Authorization",
          "Bearer " + AWS_BEARER_TOKEN_BEDROCK); // This header is less standard than

      // Enable output and set content length
      connection.setDoOutput(true);
      connection.setFixedLengthStreamingMode(jsonString.getBytes().length);

      // Write JSON data to output stream
      try (DataOutputStream outputStream = new DataOutputStream(connection.getOutputStream())) {
        outputStream.writeBytes(jsonString);
        outputStream.flush();
      }

      // Read response
      int responseCode = connection.getResponseCode();
      System.out.println("Response Code: " + responseCode);

      // Read response body
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
        StringBuilder response = new StringBuilder();
        String line;

        if (stream) {
          StringBuilder streamOutput = new StringBuilder();
          // Read each line (JSON object) from the stream
          while ((line = reader.readLine()) != null) {
            // Parse each line as a JSON object
            JSONObject jsonObject = new JSONObject(line);

            /**
             * { "model": "llama3.2", "created_at": "2023-08-04T08:52:19.385406455-07:00",
             * "message": { "role": "assistant", "content": "The", "images": null }, "done": false }
             */
            // Extract values from the JSON object
            String responseText = jsonObject.getJSONObject("message").getString("content");
            boolean done = jsonObject.getBoolean("done");
            streamOutput.append(responseText);

            // Display the parsed data
            System.out.println("Model: " + model);
            System.out.println("Response Text: " + responseText);
            System.out.println("Done: " + done);
            System.out.println("----------");

            // Break if response is marked as done
            if (done) {
              break;
            }
          }

          // reconstruct for further processing.
          responseJ = new JSONObject();
          // getJSONObject("message").getString("content");
          JSONObject message = new JSONObject();
          message.put("content", streamOutput.toString());
          responseJ.put("message", message);

        } else {

          while ((line = reader.readLine()) != null) {
            response.append(line);
          }
          String responseBody = response.toString();
          System.out.println("Response Body: " + responseBody);

          responseJ = new JSONObject(responseBody);
        }
      }

      // Close connection
      connection.disconnect();

    } catch (MalformedURLException ex) {
      logger.error("Malformed URL for Ollama API.", ex);
      java.util.logging.Logger.getLogger(BedrockBaseLM.class.getName()).log(Level.SEVERE, null, ex);
    } catch (IOException ex) {
      logger.error("IO Exception when calling Ollama API.", ex);
      java.util.logging.Logger.getLogger(BedrockBaseLM.class.getName()).log(Level.SEVERE, null, ex);
    } catch (Exception ex) { // Catch any other unexpected exceptions
      logger.error("An unexpected error occurred when calling Ollama API.", ex);
      java.util.logging.Logger.getLogger(BedrockBaseLM.class.getName()).log(Level.SEVERE, null, ex);
    }
    return responseJ;
  }

  /**
   * Streams the response from the Bedrock LLM API as JSON objects, emitting each line as it
   * arrives.
   *
   * @param model The model name.
   * @param messages The messages to send.
   * @param tools The tools to use.
   * @param stream If true, enables streaming mode.
   * @return Flowable emitting each response chunk as a JSONObject.
   */
  public Flowable<JSONObject> generateContent(
      String model, JSONArray messages, JSONArray tools, boolean stream) {
    return Flowable.create(
        emitter -> {
          try {
            String apiUrl = D_URL != null ? D_URL : System.getenv(BEDROCK_ENV_VAR);
            String AWS_BEARER_TOKEN_BEDROCK = System.getenv("AWS_BEARER_TOKEN_BEDROCK");
            JSONObject payload = new JSONObject();
            payload.put("messages", messages);
            if (tools != null) {
              payload.put("tools", tools);
            }
            String jsonString = payload.toString();
            URL url = new URL(apiUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("Authorization", "Bearer " + AWS_BEARER_TOKEN_BEDROCK);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(jsonString.getBytes("UTF-8").length);
            try (OutputStream outputStream = connection.getOutputStream();
                OutputStreamWriter writer = new OutputStreamWriter(outputStream, "UTF-8")) {
              writer.write(jsonString);
              writer.flush();
            }
            int responseCode = connection.getResponseCode();
            InputStream inputStream =
                (responseCode < 400) ? connection.getInputStream() : connection.getErrorStream();
            try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(inputStream, "UTF-8"))) {
              String line;
              StringBuilder responseBuilder = new StringBuilder();
              while ((line = reader.readLine()) != null) {
                if (stream) {
                  // Try to parse each line as JSON and emit
                  try {
                    JSONObject chunk = new JSONObject(line);
                    emitter.onNext(chunk);
                  } catch (Exception e) {
                    // If not valid JSON, accumulate for later
                    responseBuilder.append(line);
                  }
                } else {
                  responseBuilder.append(line);
                }
              }
              if (!stream) {
                try {
                  JSONObject responseJ = new JSONObject(responseBuilder.toString());
                  emitter.onNext(responseJ);
                } catch (Exception e) {
                  emitter.onError(e);
                }
              }
              emitter.onComplete();
            }
          } catch (Exception ex) {
            emitter.onError(ex);
          }
        },
        io.reactivex.rxjava3.core.BackpressureStrategy.BUFFER);
  }

  public static void main(String[] args) {
    // --- Create the 'messages' part of the JSON using org.json ---
    String messagesJsonString =
        """
    [
        {
            "role": "assistant",
            "content": [ { "text": "You are a helpful assistant." } ]
        },
        {
            "role": "user",
            "content": [ { "text": "Write a story about a curious cat named Tommy who explores a mysterious garden. Make the story at least 10 lines long, with each line describing a new discovery or adventure Tommy has in the garden. End with Tommy finding a new friend." } ]
        }
    ]
    """;

    JSONArray messagesArray;
    try {
      messagesArray = new JSONArray(messagesJsonString);
    } catch (Exception e) {
      System.err.println("Failed to parse JSON string into JSONArray: " + e.getMessage());
      return;
    }

    String modelId = "openai.gpt-oss-120b-1:0"; // Example model ID for Bedrock
    String bedrockBaseUrl =
        "https://bedrock-runtime.us-west-2.amazonaws.com/model"; // Base URL only
    BedrockBaseLM ollamaLlm = new BedrockBaseLM(modelId, bedrockBaseUrl);

    // --- Test Streaming Call ---
    System.out.println("--- Testing Streaming API Call ---");
    try {
      System.out.println("Attempting to call Ollama API (Streaming)...");
      System.out.println("Using model ID: " + modelId);
      System.out.println("Fetching Ollama endpoint from environment variable: " + BEDROCK_ENV_VAR);

      BufferedReader responseReader = ollamaLlm.callLLMChatStream(modelId, messagesArray, null);

      if (responseReader != null) {
        System.out.println("\nAPI Call Successful! Streaming response:");
        String line;
        int rawLineCount = 0;
        int partCount = 0;
        boolean foundDone = false;
        while ((line = responseReader.readLine()) != null) {
          rawLineCount++;
          boolean isJson = false;
          try {
            JSONObject chunk = new JSONObject(line);
            isJson = true;
            if (chunk.optBoolean("done", false)) {
              foundDone = true;
              break;
            }
            if (chunk.has("output")) {
              JSONObject output = chunk.getJSONObject("output");
              if (output.has("message")) {
                JSONObject message = output.getJSONObject("message");
                if (message.has("content")) {
                  JSONArray contentArr = message.getJSONArray("content");
                  for (int i = 0; i < contentArr.length(); i++) {
                    partCount++;
                    JSONObject contentObj = contentArr.getJSONObject(i);
                    // Print the full JSON structure of each part
                    if (contentObj.has("text")) {
                      System.out.print(contentObj.getString("text"));
                    }
                    if (contentObj.has("part")) {
                      System.out.print(" [part: " + contentObj.getString("part") + "]");
                    }
                  }
                }
              }
            }
          } catch (Exception e) {
            System.out.println("[Line " + rawLineCount + "] Raw: " + line);
          }
        }
        System.out.println(); // Print newline at end
        System.out.println("Total raw lines received: " + rawLineCount);
        System.out.println("Total parts processed: " + partCount);
      } else {
        System.err.println("Streaming API Call failed. Check logs for details.");
      }

    } catch (RuntimeException e) {
      System.err.println("Error during Streaming API call (Runtime): " + e.getMessage());
      System.err.println(
          "Please ensure the environment variable '" + BEDROCK_ENV_VAR + "' is set correctly.");
      e.printStackTrace();
    } catch (Exception e) {
      System.err.println(
          "An unexpected error occurred during Streaming API call: " + e.getMessage());
      e.printStackTrace();
    }

    System.out.println("\n\n--- Testing Non-Streaming API Call ---");
    // --- Test Non-Streaming Call ---
    try {
      System.out.println("Attempting to call Ollama API (Non-Streaming)...");
      System.out.println("Using model ID: " + modelId);

      JSONObject responseJson = ollamaLlm.callLLMChat(modelId, messagesArray, null);

      if (responseJson != null && !responseJson.isEmpty()) {
        System.out.println("\nAPI Call Successful! Non-Streaming response:");
        System.out.println(responseJson.toString(4)); // Pretty print JSON
      } else {
        System.err.println("Non-Streaming API Call failed. Check logs for details.");
      }

    } catch (RuntimeException e) {
      System.err.println("Error during Non-Streaming API call (Runtime): " + e.getMessage());
      e.printStackTrace();
    } catch (Exception e) {
      System.err.println(
          "An unexpected error occurred during Non-Streaming API call: " + e.getMessage());
      e.printStackTrace();
    }
  }
}
