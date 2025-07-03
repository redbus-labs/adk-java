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
public class OllamaBaseLM extends BaseLlm {

  // The Ollama endpoint is already correctly set as requested.
  public static String OLLAMA_EP = "OLLAMA_API_BASE";
  public String D_URL = null;

  // Corrected the logger name to use OllamaBaseLM.class
  private static final Logger logger = LoggerFactory.getLogger(OllamaBaseLM.class);

  private static final String CONTINUE_OUTPUT_MESSAGE =
      "Continue output. DO NOT look at this line. ONLY look at the content before this line and"
          + " system instruction.";

  public OllamaBaseLM(String model) {

    super(model);
  }

  public OllamaBaseLM(String model, String OLLAMA_EP) {

    super(model);
    this.D_URL = OLLAMA_EP;
  }

  @Override
  public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
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

    if (stream) {
      //      logger.debug("Sending streaming generateContent request to model {}", modelId);
      //      CompletableFuture<ResponseStream<GenerateContentResponse>> streamFuture =
      //          apiClient.async.models.generateContentStream(effectiveModelName, contents,
      // config);
      //
      //         return Flowable.defer(
      //          () -> {
      //            final StringBuilder accumulatedText = new StringBuilder();
      //            // Array to bypass final local variable reassignment in lambda.
      //            final GenerateContentResponse[] lastRawResponseHolder = {null};
      //
      //            return Flowable.fromFuture(streamFuture)
      //                .flatMapIterable(iterable -> iterable)
      //                .concatMap(
      //                    rawResponse -> {
      //                      lastRawResponseHolder[0] = rawResponse;
      //                      logger.trace("Raw streaming response: {}", rawResponse);
      //
      //                      List<LlmResponse> responsesToEmit = new ArrayList<>();
      //                      LlmResponse currentProcessedLlmResponse =
      // LlmResponse.create(rawResponse);
      //                      String currentTextChunk =
      // getTextFromLlmResponse(currentProcessedLlmResponse);
      //
      //                      if (!currentTextChunk.isEmpty()) {
      //                        accumulatedText.append(currentTextChunk);
      //                        LlmResponse partialResponse =
      //                            currentProcessedLlmResponse.toBuilder().partial(true).build();
      //                        responsesToEmit.add(partialResponse);
      //                      } else {
      //                        if (accumulatedText.length() > 0
      //                            && shouldEmitAccumulatedText(currentProcessedLlmResponse)) {
      //                          LlmResponse aggregatedTextResponse =
      //                              LlmResponse.builder()
      //                                  .content(
      //                                      Content.builder()
      //                                          .parts(
      //                                              ImmutableList.of(
      //                                                  Part.builder()
      //                                                      .text(accumulatedText.toString())
      //                                                      .build()))
      //                                          .build())
      //                                  .build();
      //                          responsesToEmit.add(aggregatedTextResponse);
      //                          accumulatedText.setLength(0);
      //                        }
      //                        responsesToEmit.add(currentProcessedLlmResponse);
      //                      }
      //                      logger.debug("Responses to emit: {}", responsesToEmit);
      //                      return Flowable.fromIterable(responsesToEmit);
      //                    })
      //                .concatWith(
      //                    Flowable.defer(
      //                        () -> {
      //                          if (accumulatedText.length() > 0 && lastRawResponseHolder[0] !=
      // null) {
      //                            GenerateContentResponse finalRawResp = lastRawResponseHolder[0];
      //                            boolean isStop =
      //                                finalRawResp
      //                                    .candidates()
      //                                    .flatMap(
      //                                        candidates ->
      //                                            candidates.isEmpty()
      //                                                ? Optional.empty()
      //                                                : Optional.of(candidates.get(0)))
      //                                    .flatMap(Candidate::finishReason)
      //                                    .map(
      //                                        finishReason ->
      //                                            finishReason.equals(
      //                                                new FinishReason(FinishReason.Known.STOP)))
      //                                    .orElse(false);
      //
      //                            if (isStop) {
      //                              LlmResponse finalAggregatedTextResponse =
      //                                  LlmResponse.builder()
      //                                      .content(
      //                                          Content.builder()
      //                                              .parts(
      //                                                  ImmutableList.of(
      //                                                      Part.builder()
      //                                                          .text(accumulatedText.toString())
      //                                                          .build()))
      //                                              .build())
      //                                      .build();
      //                              return Flowable.just(finalAggregatedTextResponse);
      //                            }
      //                          }
      //                          return Flowable.empty();
      //                        }));
      //          });
    } else {
      JSONObject agentresponse =
          callLLMChat(
              modelId,
              messages,
              LAST_RESP_TOOl_EXECUTED
                  ? null
                  : (functions.length() > 0
                      ? functions
                      : null)); // Tools/functions can not be of 0 length
      JSONObject responseQuantum = agentresponse.getJSONObject("message");

      // Check if tool call is required
      // Tools call
      LlmResponse.Builder responseBuilder = LlmResponse.builder();
      List<Part> parts = new ArrayList<>();
      Part part = ollamaContentBlockToPart(responseQuantum);
      parts.add(part);

      // Call tool
      if (responseQuantum.has("tool_calls")
          && "stop".contentEquals(agentresponse.getString("done_reason"))) {

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

      return Flowable.just(responseBuilder.build());
    }
    return null; // Remove for prod
  }

  @Override
  public BaseLlmConnection connect(LlmRequest llmRequest) {
    throw new UnsupportedOperationException("Not supported yet."); // Generated from
    // nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
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
    if (blockJson.has("tool_calls")) {
      JSONArray toolCalls =
          blockJson.optJSONArray("tool_calls"); // Use optJSONArray for null safety
      if (toolCalls != null && toolCalls.length() > 0) {
        // Based on the provided structure and LangChain4j Part,
        // we typically handle one function call per Part.
        // We will process the first tool call in the array.
        JSONObject toolCall = toolCalls.optJSONObject(0); // Use optJSONObject for null safety

        if (toolCall != null && toolCall.has("function")) {
          JSONObject function =
              toolCall.optJSONObject("function"); // Use optJSONObject for null safety

          if (function != null && function.has("name") && function.has("arguments")) {
            String name = function.optString("name", null); // Use optString for null safety
            JSONObject argsJson =
                function.optJSONObject("arguments"); // Use optJSONObject for null safety

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
        }
      }
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
      String apiUrl = D_URL != null ? D_URL : System.getenv(OLLAMA_EP);
      apiUrl = apiUrl + "/api/chat";

      // Constructing the JSON payload
      JSONObject payload = new JSONObject();
      payload.put("model", model);
      payload.put(
          "stream", false); // Assuming non-streaming as per current generateContent implementation

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

      // Set request method
      connection.setRequestMethod("POST");

      // Set headers
      connection.setRequestProperty(
          "Content-Type",
          "application/json; charset=UTF-8"); // <-- Also good practice to specify charset here
      // connection.setRequestProperty("charset", "UTF-8"); // This header is less standard than
      // adding to Content-Type

      // Enable output
      connection.setDoOutput(true);
      // Optional: Set content length based on UTF-8 bytes
      connection.setFixedLengthStreamingMode(jsonString.getBytes("UTF-8").length);

      // Write JSON data to output stream using UTF-8
      try (OutputStream outputStream = connection.getOutputStream();
          OutputStreamWriter writer =
              new OutputStreamWriter(outputStream, "UTF-8")) { // <-- MODIFIED
        writer.write(jsonString); // <-- MODIFIED
        writer.flush();
      } catch (IOException ex) {
        java.util.logging.Logger.getLogger(RedbusADG.class.getName()).log(Level.SEVERE, null, ex);
      }

      // Read response
      int responseCode = connection.getResponseCode();
      System.out.println("Response Code: " + responseCode);

      // Read response body using UTF-8
      try (InputStream inputStream = connection.getInputStream();
          BufferedReader reader =
              new BufferedReader(new InputStreamReader(inputStream, "UTF-8"))) { // <-- MODIFIED
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
          response.append(line);
        }
        System.out.println("Response Body: " + response.toString());

        responseJ = new JSONObject(response.toString());

      } catch (IOException ex) {
        java.util.logging.Logger.getLogger(RedbusADG.class.getName()).log(Level.SEVERE, null, ex);
        // Handle error stream if responseCode is not 2xx
        if (responseCode >= 400) {
          try (InputStream errorStream = connection.getErrorStream();
              BufferedReader errorReader =
                  new BufferedReader(new InputStreamReader(errorStream, "UTF-8"))) {
            StringBuilder errorResponse = new StringBuilder();
            String errorLine;
            while ((errorLine = errorReader.readLine()) != null) {
              errorResponse.append(errorLine);
            }
            System.err.println("Error Response Body: " + errorResponse.toString());
            // You might want to parse the errorResponse as a JSON object too if the API returns
            // JSON errors
          } catch (IOException errorEx) {
            java.util.logging.Logger.getLogger(RedbusADG.class.getName())
                .log(Level.SEVERE, null, errorEx);
          }
        }
      }

      // Close connection
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
      String apiUrl = System.getenv(OLLAMA_EP);
      apiUrl = apiUrl + "/api/chat";

      // Constructing the JSON payload
      JSONObject payload = new JSONObject();
      payload.put("model", model);
      payload.put(
          "stream", false); // Assuming non-streaming as per current generateContent implementation

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
      java.util.logging.Logger.getLogger(OllamaBaseLM.class.getName()).log(Level.SEVERE, null, ex);
    } catch (IOException ex) {
      logger.error("IO Exception when calling Ollama API.", ex);
      java.util.logging.Logger.getLogger(OllamaBaseLM.class.getName()).log(Level.SEVERE, null, ex);
    } catch (Exception ex) { // Catch any other unexpected exceptions
      logger.error("An unexpected error occurred when calling Ollama API.", ex);
      java.util.logging.Logger.getLogger(OllamaBaseLM.class.getName()).log(Level.SEVERE, null, ex);
    }
    return responseJ;
  }

  //    ResponseStream<GenerateContentResponse> privateGenerateContentStream(
  //      String model, List<Content> contents, GenerateContentConfig config) {
  //
  //    GenerateContentParameters.Builder parameterBuilder = GenerateContentParameters.builder();
  //
  //    if (!Common.isZero(model)) {
  //      parameterBuilder.model(model);
  //    }
  //    if (!Common.isZero(contents)) {
  //      parameterBuilder.contents(contents);
  //    }
  //    if (!Common.isZero(config)) {
  //      parameterBuilder.config(config);
  //    }
  //    JsonNode parameterNode = JsonSerializable.toJsonNode(parameterBuilder.build());
  //
  //    ObjectNode body;
  //    String path;
  //    if (this.apiClient.vertexAI()) {
  //      body = generateContentParametersToVertex(this.apiClient, parameterNode, null);
  //      path = Common.formatMap("{model}:streamGenerateContent?alt=sse", body.get("_url"));
  //    } else {
  //      body = generateContentParametersToMldev(this.apiClient, parameterNode, null);
  //      if (body.get("_url") != null) {
  //        path = Common.formatMap("{model}:streamGenerateContent?alt=sse", body.get("_url"));
  //      } else {
  //        path = "{model}:streamGenerateContent?alt=sse";
  //      }
  //    }
  //    body.remove("_url");
  //
  //    JsonNode queryParams = body.get("_query");
  //    if (queryParams != null) {
  //      body.remove("_query");
  //      path = String.format("%s?%s", path, Common.urlEncode((ObjectNode) queryParams));
  //    }
  //
  //    // TODO: Remove the hack that removes config.
  //    body.remove("config");
  //
  //    Optional<HttpOptions> requestHttpOptions = Optional.empty();
  //    if (config != null) {
  //      requestHttpOptions = config.httpOptions();
  //    }
  //
  //    ApiResponse response =
  //        this.apiClient.request(
  //            "post", path, JsonSerializable.toJsonString(body), requestHttpOptions);
  //    String converterName;
  //
  //    if (this.apiClient.vertexAI()) {
  //      converterName = "generateContentResponseFromVertex";
  //    } else {
  //      converterName = "generateContentResponseFromMldev";
  //    }
  //    return new ResponseStream<GenerateContentResponse>( GenerateContentResponse.class, response,
  // this, converterName);
  //  }

}
