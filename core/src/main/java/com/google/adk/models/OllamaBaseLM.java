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

/**
 *
 * @author ryzen
 */
public class OllamaBaseLM extends BaseLlm {

    public static String OLLAMA_EP = "http://localhost:11434";//"http://192.168.1.8:11434";// "https://eb28-122-176-48-130.ngrok-free.app";//

    private static final Logger logger = LoggerFactory.getLogger(Claude.class);
    
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

        String toolSupportedModel =this.model();// "devstral";//"llama3.2:3b-instruct-q2_K";//"llama3.2"; // The 1b doesn't support tool
        //Introduce agent to create Ontology
        //String agentresponse = agentManager.sendMessageOllama(noteMaker.getName(), toolSupportedModel, "Temperature in Bangalore?");

        //agentresponse = agentManager.sendMessageOllama(noteMaker.getName(), toolSupportedModel, Ontology_Prompt + "\n" + template_JSON);
        //Search the Ontology
        String userQuestion = "I want to know 8 detils, What are parts of a car ?";
        JSONArray messagesToSend = new JSONArray();//Order is important

        JSONObject llmMessageJson1 = new JSONObject();
        llmMessageJson1.put("role", "system");
        llmMessageJson1.put("content", systemText);
        messagesToSend.put(llmMessageJson1);//Agent system prompt is always added

        JSONObject userMessageJson = new JSONObject();
        userMessageJson.put("role", "user");
        userMessageJson.put("content", llmRequest.contents().get(0).text());//Do better eork here
        messagesToSend.put(userMessageJson);//Agent system prompt is always added

        JSONObject agentresponse = callLLMChat(userQuestion, toolSupportedModel, messagesToSend, null);

        String llmResponse = agentresponse.getJSONObject("message").getString("content");

        LlmResponse.Builder responseBuilder = LlmResponse.builder();
        List<Part> parts = new ArrayList<>();
        Part part = ollamaContentBlockToPart(agentresponse.getJSONObject("message"));
        parts.add(part);

        responseBuilder.content(
                Content.builder().role("model").parts(ImmutableList.copyOf(parts)).build());

        logger.debug("Ollama response: {}", llmResponse);

        return Flowable.just(responseBuilder.build());
    }

    @Override
    public BaseLlmConnection connect(LlmRequest llmRequest) {
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
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

    private Part ollamaContentBlockToPart(JSONObject blockJson) {
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
                // If tool_calls array is present but malformed or empty,
                // it might fall through to check content or throw.
                // Based on original code, falling through to unsupported might be appropriate
                // if no valid tool call was found despite the key being present.
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
     * @param prompt
     * @param model
     * @param messages
     * @param tools
     * @return
     */
    public static JSONObject callLLMChat(String prompt, String model, JSONArray messages, JSONArray tools) {
        JSONObject responseJ = new JSONObject();
        try {

            // API endpoint URL
            String apiUrl = OLLAMA_EP + "/api/chat";

            // Constructing the JSON payload
            JSONObject payload = new JSONObject();
            payload.put("model", model);
            payload.put("stream", false);

            JSONObject options = new JSONObject();
            options.put("num_ctx", 4096);

//            JSONArray messages = new JSONArray();
//            JSONObject message = new JSONObject();
//            message.put("role", "user");
//            message.put("content", prompt);
//            messages.put(message);
            payload.put("messages", messages);
            if (tools != null) {
                payload.put("tools", tools);
            }
            payload.put("options", options);

            // Convert payload to string
            String jsonString = payload.toString();
            //System.out.println(payload.toString(1));

            // Create URL object
            URL url = new URL(apiUrl);

            // Open connection
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();

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
                System.out.println("Response Body: " + response.toString());

                responseJ = new JSONObject(response.toString());

            }

            // Close connection
            connection.disconnect();

        } catch (MalformedURLException ex) {
            java.util.logging.Logger.getLogger(OllamaBaseLM.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IOException ex) {
            java.util.logging.Logger.getLogger(OllamaBaseLM.class.getName()).log(Level.SEVERE, null, ex);
        }
        return responseJ;
    }

}
