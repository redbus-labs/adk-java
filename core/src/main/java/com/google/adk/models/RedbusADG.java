/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.google.adk.models;

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
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redbus AD Gateway to access Azure LLMs
 *
 * @author manoj.kumar
 */
public class RedbusADG extends BaseLlm {

  private static final String DEFAULT_API_URL = "ADURL"; // https://abc.com";
  private static final String USERNAME_ENV_VAR = "ADU"; // Username
  private static final String PASSWORD_ENV_VAR = "ADP"; // Password
  private static final String FORBIDDEN_CHARACTERS_REGEX = "[^a-zA-Z0-9_\\.-]";
  private static final String CONTINUE_OUTPUT_MESSAGE =
      "Continue output. DO NOT look at this line. ONLY look at the content before this line and"
          + " system instruction.";

  /**
   * Cleans a string by removing any characters that are not allowed by the pattern [a-zA-Z0-9_\.-].
   * This pattern is typically required for names or identifiers.
   *
   * @param input The string to clean. Can be null.
   * @return The cleaned string, containing only allowed characters. Returns null if the input was
   *     null.
   */
  public static String cleanForIdentifierPattern(String input) {
    if (input == null) {
      return null;
    }
    // Replace all characters that do NOT match the allowed set with an empty string
    return input.replaceAll(FORBIDDEN_CHARACTERS_REGEX, "");
  }

  private static final Logger logger = LoggerFactory.getLogger(RedbusADG.class);

  public RedbusADG(String model) {
    super(model);
  }

  @Override
  public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
    if (stream) {
      return generateContentStream(llmRequest);
    } else {
      return generateContentStd(llmRequest);
    }
  }

  public Flowable<LlmResponse> generateContentStd(LlmRequest llmRequest) {

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
    llmMessageJson1.put("role", "system");
    llmMessageJson1.put("content", systemText);
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
                messageQuantum.put(
                    "content",
                    new JSONObject(
                            item.parts().get().get(0).functionResponse().get().response().get())
                        .toString(1));
              } else {
                messageQuantum.put("content", item.text());
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
                toolMap.put("parameters", parametersMap);
              }

              // Convert the complete tool map into an org.json.JSONObject
              JSONObject jsonTool = new JSONObject(toolMap);

              // Add the generated tool JSON object to your functions list/array
              functions.put(jsonTool);
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
            LAST_RESP_TOOl_EXECUTED ? null : (functions.length() > 0 ? functions : null),
            false); // Tools/functions can not be of 0 length
    JSONObject responseQuantum =
        agentresponse.has("response")
            ? agentresponse
                .getJSONObject("response")
                .getJSONObject("openAIResponse")
                .getJSONArray("choices")
                .getJSONObject(0)
            : new JSONObject();

    // Check if tool call is required
    // Tools call
    LlmResponse.Builder responseBuilder = LlmResponse.builder();
    List<Part> parts = new ArrayList<>();
    Part part = oaiContentBlockToPart(responseQuantum);
    parts.add(part);

    // Call tool
    if (responseQuantum.has("finish_reason")
        && "function_call".contentEquals(responseQuantum.getString("finish_reason"))) {

      responseBuilder.content(
          Content.builder()
              .role("model")
              .parts(
                  ImmutableList.of(Part.builder().functionCall(part.functionCall().get()).build()))
              .build());

      //  responseBuilder.partial(false).turnComplete(false);

    } else {
      responseBuilder.content(
          Content.builder().role("model").parts(ImmutableList.copyOf(parts)).build());
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
    llmMessageJson1.put("role", "system");
    llmMessageJson1.put("content", systemText);
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
                messageQuantum.put(
                    "content",
                    new JSONObject(
                            item.parts().get().get(0).functionResponse().get().response().get())
                        .toString(1));
              } else {
                messageQuantum.put("content", item.text());
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
                toolMap.put("parameters", parametersMap);
              }

              // Convert the complete tool map into an org.json.JSONObject
              JSONObject jsonTool = new JSONObject(toolMap);

              // Add the generated tool JSON object to your functions list/array
              functions.put(jsonTool);
            });

    // Check if the tool is executed, then parse and response.

    logger.debug("functions: {}", functions.toString(1));

    String modelId =
        this.model(); // "devstral";//"llama3.2:3b-instruct-q2_K";//"llama3.2"; // The 1b doesn't
    // support tool

    // If last user response has the function reponse, then function calla is not needed.
    boolean LAST_RESP_TOOl_EXECUTED =
        Iterables.getLast(Iterables.getLast(contents).parts().get()).functionResponse().isPresent();

    return Flowable.generate(
        () ->
            callLLMChatStream(
                modelId,
                messages,
                LAST_RESP_TOOl_EXECUTED
                    ? null
                    : (functions.length() > 0
                        ? functions
                        : null), // Tools/functions can not be of 0 length
                true),
        (reader, emitter) -> {
          try {
            String line = reader.readLine();
            if (line == null || line.contains("[DONE]")) {
              emitter.onComplete();
              return;
            }
            if (line.isEmpty()) {
              return;
            }
            line = line.replace("data:", "");
            JSONObject responseQuantum = new JSONObject(line);

            // Check if tool call is required
            // Tools call
            LlmResponse.Builder responseBuilder = LlmResponse.builder();
            List<Part> parts = new ArrayList<>();
            Part part = oaiStreamContentBlockToPart(responseQuantum);
            parts.add(part);

            // Call tool
            if (responseQuantum.has("finish_reason")
                && "function_call".contentEquals(responseQuantum.getString("finish_reason"))) {

              responseBuilder.content(
                  Content.builder()
                      .role("model")
                      .parts(
                          ImmutableList.of(
                              Part.builder().functionCall(part.functionCall().get()).build()))
                      .build());

              //  responseBuilder.partial(false).turnComplete(false);

            } else {
              responseBuilder.content(
                  Content.builder().role("model").parts(ImmutableList.copyOf(parts)).build());
            }
            emitter.onNext(responseBuilder.build());

          } catch (Exception e) {
            emitter.onError(e);
          }
        },
        reader -> {
          try {
            reader.close();
          } catch (IOException e) {
            logger.error("Error closing stream reader", e);
          }
        });
  }

  public static Part oaiStreamContentBlockToPart(JSONObject choice0) {
    // Check for tool_calls first, as the example with tool_calls had empty content

    JSONObject blockJson = choice0.getJSONArray("choices").getJSONObject(0).getJSONObject("delta");
    if (blockJson.has("function_call")) {

      // Based on the provided structure and LangChain4j Part,
      // we typically handle one function call per Part.
      // We will process the first tool call in the array.
      JSONObject function =
          blockJson.getJSONObject("function_call"); // Use optJSONObject for null safety

      if (function != null && function.has("name") && function.has("arguments")) {
        String name = function.optString("name", null); // Use optString for null safety
        JSONObject argsJson =
            new JSONObject(function.getString("arguments")); // Use optJSONObject for null safety

        if (name != null && argsJson != null) {
          // Convert JSONObject arguments to Map<String, Object>
          // Assuming org.json.JSONObject.toMap() is available
          Map<String, Object> args = argsJson.toMap();

          // Build the FunctionCall Part
          // The provided JSON does not include an 'id' for the tool call, so omitting it.
          FunctionCall functionCall = FunctionCall.builder().name(name).args(args).build();

          return Part.builder().functionCall(functionCall).build();
        }
      }

      // If tool_calls array is present but malformed or empty,
      // it might fall through to check content or throw.
      // Based on original code, falling through to unsupported might be appropriate
      // if no valid tool call was found despite the key being present.
    }

    // If no valid tool_calls were processed, check for text content
    if (blockJson.has("content")) {
      Object content = blockJson.opt("content"); // Use opt for null safety
      if (content instanceof String) {
        String text = (String) content;
        // Return a text Part, even if the string is empty (matches empty content example)
        return Part.builder().text(text).build();
      }
      // If 'content' key exists but value is not a String, might be unsupported.
    }

    String llmResponse = blockJson.getString("content"); // Kept same for readiblity

    logger.debug("redBus response: {}", llmResponse);

    // If neither usable tool_calls nor String content was found
    // This covers cases like malformed JSON matching the structure,
    // or structures not covered (e.g., image parts, other types).
    throw new UnsupportedOperationException(
        "Unsupported content block format or missing required fields: " + blockJson.toString());
  }

  public static Part oaiContentBlockToPart(JSONObject choice0) {
    // Check for tool_calls first, as the example with tool_calls had empty content

    JSONObject blockJson = choice0.getJSONObject("message");
    if (blockJson.has("function_call")) {

      // Based on the provided structure and LangChain4j Part,
      // we typically handle one function call per Part.
      // We will process the first tool call in the array.
      JSONObject function =
          blockJson.getJSONObject("function_call"); // Use optJSONObject for null safety

      if (function != null && function.has("name") && function.has("arguments")) {
        String name = function.optString("name", null); // Use optString for null safety
        JSONObject argsJson =
            new JSONObject(function.getString("arguments")); // Use optJSONObject for null safety

        if (name != null && argsJson != null) {
          // Convert JSONObject arguments to Map<String, Object>
          // Assuming org.json.JSONObject.toMap() is available
          Map<String, Object> args = argsJson.toMap();

          // Build the FunctionCall Part
          // The provided JSON does not include an 'id' for the tool call, so omitting it.
          FunctionCall functionCall = FunctionCall.builder().name(name).args(args).build();

          return Part.builder().functionCall(functionCall).build();
        }
      }

      // If tool_calls array is present but malformed or empty,
      // it might fall through to check content or throw.
      // Based on original code, falling through to unsupported might be appropriate
      // if no valid tool call was found despite the key being present.
    }

    // If no valid tool_calls were processed, check for text content
    if (blockJson.has("content")) {
      Object content = blockJson.opt("content"); // Use opt for null safety
      if (content instanceof String) {
        String text = (String) content;
        // Return a text Part, even if the string is empty (matches empty content example)
        return Part.builder().text(text).build();
      }
      // If 'content' key exists but value is not a String, might be unsupported.
    }

    String llmResponse = blockJson.getString("content"); // Kept same for readiblity

    logger.debug("redBus response: {}", llmResponse);

    // If neither usable tool_calls nor String content was found
    // This covers cases like malformed JSON matching the structure,
    // or structures not covered (e.g., image parts, other types).
    throw new UnsupportedOperationException(
        "Unsupported content block format or missing required fields: " + blockJson.toString());
  }

  // Create a shared HttpClient instance (thread-safe and efficient)
  private static final HttpClient httpClient =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_2) // Or HTTP_1_1
          .connectTimeout(Duration.ofSeconds(60)) // Example timeout
          .build();

  public static BufferedReader callLLMChatStream(
      String model, JSONArray messages, JSONArray tools, boolean stream) {
    // 1. Get username and password from environment variables
    String username = System.getenv(USERNAME_ENV_VAR);
    String password = System.getenv(PASSWORD_ENV_VAR);
    String apiUrl = System.getenv(DEFAULT_API_URL);

    if (username == null || username.isEmpty()) {
      throw new RuntimeException("Environment variable '" + USERNAME_ENV_VAR + "' not set.");
    }
    if (password == null || password.isEmpty()) {
      throw new RuntimeException("Environment variable '" + PASSWORD_ENV_VAR + "' not set.");
    }
    if (apiUrl == null || apiUrl.isEmpty()) {
      throw new RuntimeException("Environment variable '" + DEFAULT_API_URL + "' not set.");
    }

    // Constructing the JSON payload using the same structure
    JSONObject payload = new JSONObject();
    payload.put("username", username);
    payload.put("password", password);
    payload.put("api", model); // This parameter takes id of model, not actual model name

    JSONObject request = new JSONObject();
    request.put("messages", messages);
    if (tools != null) {
      request.put("functions", tools);
    }
    request.put("temperature", 0.9);
    request.put("stream", stream);

    payload.put("request", request);

    // Convert payload to string
    String jsonString = payload.toString();

    try {
      // Build the HttpRequest
      HttpRequest httpRequest =
          HttpRequest.newBuilder()
              .uri(URI.create(apiUrl)) // Use URI
              .header(
                  "Content-Type",
                  "application/json; charset=UTF-8") // Explicitly set content type with charset
              // Use BodyPublishers.ofString with StandardCharsets.UTF_8
              .POST(HttpRequest.BodyPublishers.ofString(jsonString, StandardCharsets.UTF_8))
              .build();

      // Send the request and get the response body as a String, decoded with UTF-8
      HttpResponse<InputStream> response =
          httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());

      int statusCode = response.statusCode();
      //      String responseBody = response.body();

      System.out.println("Response Code: " + statusCode);
      //      System.out.println(
      //          "Response Body: "
      //              + responseBody); // Response body is already a String decoded as UTF-8

      if (statusCode >= 200 && statusCode < 300) {
        // Success
        return new BufferedReader(new InputStreamReader(response.body()));
      } else {
        return null;
      }

    } catch (IOException | InterruptedException ex) {
      // Handle network errors, timeouts, or thread interruptions
      // Logger.getLogger(RedbusADG.class.getName()).log(Level.SEVERE, "HTTP request failed", ex);
      return null; // Return empty JSON on error
    } catch (Exception ex) {
      // Catch other potential exceptions like JSON parsing issues from the *response*
      // logger.getLogger(RedbusADG.class.getName()).log(Level.SEVERE, "An unexpected error
      // occurred", ex);
      return null; // Return empty JSON on error
    }
  }

  /**
   * Makes a POST request to a specified URL with a dynamic JSON body using HttpClient. Fetches
   * username and password from environment variables.
   *
   * @param model The model ID (used in the "api" field of the request payload).
   * @param messages The list of messages for the "request.messages" field.
   * @param tools The list of tools/functions for the "request.functions" field (can be null).
   * @return The response body as a JSONObject, or an empty JSONObject in case of failure.
   * @throws RuntimeException If environment variables are not set.
   */
  public static JSONObject callLLMChat(
      String model, JSONArray messages, JSONArray tools, boolean stream) {
    // 1. Get username and password from environment variables
    String username = System.getenv(USERNAME_ENV_VAR);
    String password = System.getenv(PASSWORD_ENV_VAR);
    String apiUrl = System.getenv(DEFAULT_API_URL);

    if (username == null || username.isEmpty()) {
      throw new RuntimeException("Environment variable '" + USERNAME_ENV_VAR + "' not set.");
    }
    if (password == null || password.isEmpty()) {
      throw new RuntimeException("Environment variable '" + PASSWORD_ENV_VAR + "' not set.");
    }
    if (apiUrl == null || apiUrl.isEmpty()) {
      throw new RuntimeException("Environment variable '" + DEFAULT_API_URL + "' not set.");
    }

    // Constructing the JSON payload using the same structure
    JSONObject payload = new JSONObject();
    payload.put("username", username);
    payload.put("password", password);
    payload.put("api", model); // This parameter takes id of model, not actual model name

    JSONObject request = new JSONObject();
    request.put("messages", messages);
    if (tools != null) {
      request.put("functions", tools);
    }
    request.put("temperature", 0.9);
    request.put("stream", stream);

    payload.put("request", request);

    // Convert payload to string
    String jsonString = payload.toString();

    try {
      // Build the HttpRequest
      HttpRequest httpRequest =
          HttpRequest.newBuilder()
              .uri(URI.create(apiUrl)) // Use URI
              .header(
                  "Content-Type",
                  "application/json; charset=UTF-8") // Explicitly set content type with charset
              // Use BodyPublishers.ofString with StandardCharsets.UTF_8
              .POST(HttpRequest.BodyPublishers.ofString(jsonString, StandardCharsets.UTF_8))
              .build();

      // Send the request and get the response body as a String, decoded with UTF-8
      HttpResponse<String> response =
          httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

      int statusCode = response.statusCode();
      String responseBody = response.body();

      System.out.println("Response Code: " + statusCode);
      System.out.println(
          "Response Body: " + responseBody); // Response body is already a String decoded as UTF-8

      if (statusCode >= 200 && statusCode < 300) {
        // Success
        return new JSONObject(responseBody);
      } else {
        // Handle error responses (status code 4xx or 5xx)
        // Logger.getLogger(RedbusADG.class.getName()).log(Level.SEVERE,  "HTTP request failed with
        // status code " + statusCode + ": " + responseBody);
        // Depending on the API, the error details might be in the responseBody
        // even for error status codes. You can try to parse it if needed.
        try {
          return new JSONObject(responseBody); // Attempt to parse error body if it's JSON
        } catch (Exception jsonEx) {
          //  Logger.getLogger(RedbusADG.class.getName()).log(Level.WARNING,  "Could not parse error
          // response body as JSON.", jsonEx);
          return new JSONObject(); // Return empty JSON on parse failure
        }
      }

    } catch (IOException | InterruptedException ex) {
      // Handle network errors, timeouts, or thread interruptions
      // Logger.getLogger(RedbusADG.class.getName()).log(Level.SEVERE, "HTTP request failed", ex);
      return new JSONObject(); // Return empty JSON on error
    } catch (Exception ex) {
      // Catch other potential exceptions like JSON parsing issues from the *response*
      // logger.getLogger(RedbusADG.class.getName()).log(Level.SEVERE, "An unexpected error
      // occurred", ex);
      return new JSONObject(); // Return empty JSON on error
    }
  }

  @Override
  public BaseLlmConnection connect(LlmRequest llmRequest) {
    return new GenericLlmConnection(this, llmRequest);
  }

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

  public static void main(String[] args) {
    // --- Create the 'messages' part of the JSON using org.json ---
    String messagesJsonString =
        """
    [
        {
            "role": "system",
            "content": "You are a helpful assistant."
        },
        {
            "role": "user",
            "content": "Does Azure OpenAI support customer managed keys?"
        },
        {
            "role": "assistant",
            "content": "Yes, customer managed keys are supported by Azure OpenAI."
        },
        {
            "role": "user",
            "content": "Do other Azure AI services support this too?"
        },
        {
                      "role": "system",
                      "content": "Help user in determining the weather."
                  },
                  {
                      "role": "user",
                      "content": "What is weather in bangalore?"
                  }
    ]
    """;

    String toolsJsonString =
        """
    [
                {
                        "name": "get_current_weather",
                        "description": "Get the current weather in a given location",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "location": {
                                    "type": "string",
                                    "description": "The city and state, e.g. San Francisco, CA"
                                }
                            },
                            "required": [
                                "location"
                            ]
                        }
                    }
            ]
    """;

    JSONArray messagesArray;
    JSONArray toolsArray;
    try {
      // Parse the JSON string directly into a JSONArray
      messagesArray = new JSONArray(messagesJsonString);
      toolsArray = new JSONArray(toolsJsonString);

    } catch (Exception e) { // org.json.JSONArray constructor can throw various exceptions
      System.err.println("Failed to parse messages JSON string into JSONArray: " + e.getMessage());
      return; // Exit if messages JSON cannot be parsed
    }

    // --- Make the API Call ---
    // The makeApiCall expects 'model' as a String. Based on the comment
    // "This parameter takes id of model, not actual model name", let's
    // assume the ID "40" should be passed as a String.
    String modelId = "40"; // Example model ID as a String

    String targetUrl = DEFAULT_API_URL; // Using the default URL defined in the class

    try {
      System.out.println("Attempting to call API at " + targetUrl + "...");
      System.out.println("Using model ID: " + modelId);
      System.out.println(
          "Fetching credentials from environment variables: "
              + USERNAME_ENV_VAR
              + ", "
              + PASSWORD_ENV_VAR);

      // Call makeApiCall with correct arguments:
      // apiUrl (String), model (String), messages (JSONArray), tools (JSONArray or null)
      BufferedReader responseJson =
          callLLMChatStream(
              modelId, messagesArray, null, true); // Pass null for tools toolsArray/ null for test

      System.out.println("\nAPI Call Successful!");
      System.out.println("Response Body (JSONObject):");
      // Print the returned JSONObject. Using toString(4) for pretty printing.
      responseJson.lines().forEach(System.out::println);

    } catch (RuntimeException e) {
      System.err.println("Error during API call (Runtime): " + e.getMessage());
      System.err.println(
          "Please ensure environment variables '"
              + USERNAME_ENV_VAR
              + "' and '"
              + PASSWORD_ENV_VAR
              + "' are set.");
    } // Restore interrupt status
    catch (Exception e) { // Catch any other potential exceptions during processing
      System.err.println("An unexpected error occurred during API call: " + e.getMessage());
      e.printStackTrace();
    }
  }
}
