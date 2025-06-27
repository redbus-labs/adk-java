/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.google.adk.models;

import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;

import io.reactivex.rxjava3.core.Flowable;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.stream.Collectors;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Collections;


/**
 *
 * @author ryzen
 * @author Manoj Kumar, Sandeep Belgavi
 * @date 2025-06-27
 */
public class OllamaBaseLM extends BaseLlm {

    // The Ollama endpoint is already correctly set as requested.
    public static String OLLAMA_EP = "OLLAMA_API_BASE";

    // Corrected the logger name to use OllamaBaseLM.class
    private static final Logger logger = LoggerFactory.getLogger(OllamaBaseLM.class);

    public OllamaBaseLM(String model) {

        super(model);
    }

    @Override
    public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {

        String systemText = "";
        Optional<GenerateContentConfig> configOpt = llmRequest.config();
        if (configOpt.isPresent()) {
            Optional<Content> systemInstructionOpt = configOpt.get().systemInstruction();
            if (systemInstructionOpt.isPresent()) {
                // Extract system instruction text if present
                String extractedSystemText
                        = systemInstructionOpt.get().parts().orElse(ImmutableList.of()).stream()
                        .filter(p -> p.text().isPresent())
                        .map(p -> p.text().get())
                        .collect(Collectors.joining("\n"));
                if (!extractedSystemText.isEmpty()) {
                    systemText = extractedSystemText;
                }
            }
        }

        String toolSupportedModel = this.model(); // Use the model passed to the constructor

        JSONArray messagesToSend = new JSONArray(); // Stores messages for the Ollama API

        // Add the system instruction as the first message if it exists
        if (!systemText.isEmpty()) {
            JSONObject systemMessageJson = new JSONObject();
            systemMessageJson.put("role", "system");
            systemMessageJson.put("content", systemText);
            messagesToSend.put(systemMessageJson);
        }
        List<Content> contents = Optional.ofNullable(llmRequest.contents())
                .orElse(Collections.emptyList());

        // Process the user's content from the LlmRequest
        // Assuming LlmRequest.contents() contains the actual chat history/prompt
        if (contents != null && !contents.isEmpty()) {
            for (Content content : contents) {
                JSONObject messageJson = new JSONObject();
                messageJson.put("role", content.role().orElse("user")); // Default to 'user' if role is not present

                // Handle different parts of the content (e.g., text, function calls, etc.)
                if (content.parts().isPresent()) {
                    StringBuilder textContent = new StringBuilder();
                    // For simplicity, concatenating all text parts into a single content string
                    // You might need more sophisticated handling if there are mixed content types
                    for (Part part : content.parts().get()) {
                        if (part.text().isPresent()) {
                            textContent.append(part.text().get());
                        }
                        // Add handling for other part types (e.g., function calls, blob) if needed by Ollama
                        // For example, if it's a function_call, you'd add it to a 'tool_calls' array
                        // Ollama expects tool calls to be separate from 'content' in a message.
                        if (part.functionCall().isPresent()) {
                            FunctionCall functionCall = part.functionCall().get();
                            JSONObject funcCallJson = new JSONObject();
                            funcCallJson.put("name", functionCall.name());
                            funcCallJson.put("arguments", new JSONObject(functionCall.args())); // Convert Map to JSONObject

                            JSONArray toolCallsArray = new JSONArray();
                            JSONObject toolCallObject = new JSONObject();
                            toolCallObject.put("function", funcCallJson);
                            toolCallsArray.put(toolCallObject);
                            messageJson.put("tool_calls", toolCallsArray);
                        }
                    }
                    if (textContent.length() > 0) {
                        messageJson.put("content", textContent.toString());
                    }
                }
                messagesToSend.put(messageJson);
            }
        }

        // Call the LLM chat method
        // The 'prompt' parameter in callLLMChat is not directly used for the message content
        // when 'messages' are provided. It's better to rely solely on 'messages'.
        JSONObject agentresponse = callLLMChat("", toolSupportedModel, messagesToSend, null);
        System.out.println("Ollama Response: " + agentresponse.toString(1)); // For debugging
        // Extract the response content from the Ollama JSON
        String llmResponseContent = "";
        LlmResponse.Builder responseBuilder = LlmResponse.builder();
        List<Part> parts = new ArrayList<>();

        if (agentresponse.has("message")) {
            JSONObject messageObject = agentresponse.getJSONObject("message");
            // Use the utility method to convert Ollama's message JSON to a Part
            Part part = ollamaContentBlockToPart(messageObject);
            parts.add(part);

            // If the part contains text, extract it for logging
            if (part.text().isPresent()) {
                llmResponseContent = part.text().get();
            }
        } else {
            logger.warn("Ollama response did not contain a 'message' object.");
            // Handle error or no content scenario appropriately
            parts.add(Part.builder().text("Error: No message content from Ollama.").build());
        }

        responseBuilder.content(
                Content.builder().role("model").parts(ImmutableList.copyOf(parts)).build());

        logger.debug("Ollama response: {}", llmResponseContent);

        // This implementation returns a single response, not a stream, despite the 'stream' parameter.
        // If actual streaming is required, the callLLMChat method and this flow would need significant changes.
        return Flowable.just(responseBuilder.build());
    }

    @Override
    public BaseLlmConnection connect(LlmRequest llmRequest) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

    /**
     * This method appears to be unused in the current context.
     * It's typically used for modifying JSON schemas, which is not directly related
     * to sending chat messages to Ollama. You might consider removing it if it's
     * no longer needed.
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
                Map<String, Object> properties
                        = (Map<String, Object>) ((Map) valueDict.get("items")).get("properties");
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
            JSONArray toolCalls = blockJson.optJSONArray("tool_calls"); // Use optJSONArray for null safety
            if (toolCalls != null && toolCalls.length() > 0) {
                // Based on the provided structure and LangChain4j Part,
                // we typically handle one function call per Part.
                // We will process the first tool call in the array.
                JSONObject toolCall = toolCalls.optJSONObject(0); // Use optJSONObject for null safety

                if (toolCall != null && toolCall.has("function")) {
                    JSONObject function = toolCall.optJSONObject("function"); // Use optJSONObject for null safety

                    if (function != null && function.has("name") && function.has("arguments")) {
                        String name = function.optString("name", null); // Use optString for null safety
                        JSONObject argsJson = function.optJSONObject("arguments"); // Use optJSONObject for null safety

                        if (name != null && argsJson != null) {
                            // Convert JSONObject arguments to Map<String, Object>
                            // Assuming org.json.JSONObject.toMap() is available
                            Map<String, Object> args = argsJson.toMap();

                            // Build the FunctionCall Part
                            // The provided JSON does not include an 'id' for the tool call, so omitting it.
                            FunctionCall functionCall = FunctionCall.builder()
                                    .name(name)
                                    .args(args)
                                    .build();

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
        throw new UnsupportedOperationException("Unsupported content block format or missing required fields: " + blockJson.toString());
    }

    /**
     * Use prompt parameter to moderate the questions is prompt!=null, using the
     * generate "options": { "num_ctx": 4096 }
     *
     * @param prompt (Note: This 'prompt' is largely superseded by 'messages'
     * for chat APIs, keep for compatibility if needed elsewhere)
     * @param model   The Ollama model to use (e.g., "llama3")
     * @param messages The JSONArray of messages representing the chat history
     * @param tools    Optional JSONArray of tool definitions
     * @return JSONObject representing the Ollama API response
     */
    public static JSONObject callLLMChat(String prompt, String model, JSONArray messages, JSONArray tools) {
        JSONObject responseJ = new JSONObject();
        try {
            // API endpoint URL //OLLAMA_API_BASE
            String apiUrl = System.getenv(OLLAMA_EP);
             apiUrl = apiUrl + "/api/chat";

            // Constructing the JSON payload
            JSONObject payload = new JSONObject();
            payload.put("model", model);
            payload.put("stream", false); // Assuming non-streaming as per current generateContent implementation

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
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                String responseBody = response.toString();
                System.out.println("Response Body: " + responseBody);

                responseJ = new JSONObject(responseBody);

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

}
