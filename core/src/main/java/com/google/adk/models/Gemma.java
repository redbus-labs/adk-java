package com.google.adk.models;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import com.google.genai.types.Tool;
import io.reactivex.rxjava3.core.Flowable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Gemma extends Gemini {
  private static final Logger logger = LoggerFactory.getLogger(Gemma.class);
  private static final ObjectMapper objectMapper = new ObjectMapper();

  public Gemma(String modelName, Client apiClient) {
    super(modelName, apiClient);
  }

  public Gemma(String modelName, String apiKey) {
    super(modelName, apiKey);
  }

  public Gemma(String modelName, VertexCredentials vertexCredentials) {
    super(modelName, vertexCredentials);
  }

  public List<String> supportedModels() {
    return ImmutableList.of("gemma-3.*");
  }

  protected void preprocessRequest(LlmRequest llmRequest) {
    llmRequest = moveFunctionCallsIntoSystemInstruction(llmRequest);

    if (llmRequest.config().flatMap(GenerateContentConfig::systemInstruction).isPresent()) {
      List<Content> contents = new ArrayList<>(llmRequest.contents());
      Content instructionContent =
          Content.builder()
              .role("user")
              .parts(
                  ImmutableList.of(
                      Part.fromText(
                          llmRequest
                              .config()
                              .flatMap(GenerateContentConfig::systemInstruction)
                              .get()
                              .parts()
                              .get()
                              .get(0)
                              .text()
                              .orElse(""))))
              .build();

      if (!contents.isEmpty()) {
        if (!contents.get(0).equals(instructionContent)) {
          contents.add(0, instructionContent);
          llmRequest = llmRequest.toBuilder().contents(contents).build();
        }
      } else {
        llmRequest = llmRequest.toBuilder().contents(ImmutableList.of(instructionContent)).build();
      }
      llmRequest =
          llmRequest.toBuilder()
              .config(
                  llmRequest
                      .config()
                      .map(cfg -> cfg.toBuilder().systemInstruction((Content) null).build())
                      .orElse(null))
              .build();
    }
    preprocessRequest(llmRequest);
  }

  @Override
  public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
    if (!llmRequest.model().orElse("").startsWith("gemma-")) {
      throw new IllegalArgumentException(
          "Requesting a non-Gemma model ("
              + llmRequest.model().orElse("")
              + ") with the Gemma LLM is not supported.");
    }
    return super.generateContent(llmRequest, stream).map(this::extractFunctionCallsFromResponse);
  }

  private LlmRequest moveFunctionCallsIntoSystemInstruction(LlmRequest llmRequest) {
    if (llmRequest.model().orElse(null) == null
        || !llmRequest.model().orElse("").startsWith("gemma-3")) {
      return llmRequest;
    }

    List<Content> newContents = new ArrayList<>();
    for (Content contentItem : llmRequest.contents()) {
      ContentPartsConversionResult result = convertContentPartsForGemma(contentItem);

      if (result.hasFunctionResponsePart) {
        if (!result.newParts.isEmpty()) {
          newContents.add(Content.builder().role("user").parts(result.newParts).build());
        }
      } else if (result.hasFunctionCallPart) {
        if (!result.newParts.isEmpty()) {
          newContents.add(Content.builder().role("model").parts(result.newParts).build());
        }
      } else {
        newContents.add(contentItem);
      }
    }
    llmRequest = llmRequest.toBuilder().contents(newContents).build();

    if (llmRequest
        .config()
        .flatMap(GenerateContentConfig::tools)
        .orElse(ImmutableList.of())
        .isEmpty()) {
      return llmRequest;
    }

    List<FunctionDeclaration> allFunctionDeclarations = new ArrayList<>();
    for (Tool toolItem :
        llmRequest.config().flatMap(GenerateContentConfig::tools).orElse(ImmutableList.of())) {
      toolItem.functionDeclarations().ifPresent(allFunctionDeclarations::addAll);
    }

    if (!allFunctionDeclarations.isEmpty()) {
      String systemInstruction = buildGemmaFunctionSystemInstruction(allFunctionDeclarations);
      llmRequest =
          llmRequest.toBuilder().appendInstructions(ImmutableList.of(systemInstruction)).build();
    }

    llmRequest =
        llmRequest.toBuilder()
            .config(
                llmRequest
                    .config()
                    .map(cfg -> cfg.toBuilder().tools(ImmutableList.of()).build())
                    .orElse(null))
            .build();
    return llmRequest;
  }

  private LlmResponse extractFunctionCallsFromResponse(LlmResponse llmResponse) {
    if (llmResponse.partial().orElse(false) || llmResponse.turnComplete().orElse(false)) {
      return llmResponse;
    }

    Optional<Content> contentOptional = llmResponse.content();
    if (contentOptional.isEmpty()
        || contentOptional.get().parts().orElse(ImmutableList.of()).isEmpty()) {
      return llmResponse;
    }

    Content content = contentOptional.get();
    if (content.parts().orElse(ImmutableList.of()).size() > 1) {
      return llmResponse;
    }

    Optional<String> responseTextOptional =
        content.parts().orElse(ImmutableList.of()).get(0).text();
    if (responseTextOptional.isEmpty()) {
      return llmResponse;
    }

    String responseText = responseTextOptional.get();
    String jsonCandidate = null;

    Pattern markdownCodeBlockPattern =
        Pattern.compile("```(?:(json|tool_code))?\s*(.*?)\s*```", Pattern.DOTALL);
    Matcher blockMatcher = markdownCodeBlockPattern.matcher(responseText);

    if (blockMatcher.find()) {
      jsonCandidate = blockMatcher.group(2).trim();
    } else {
      Optional<String> lastJson = getLastValidJsonSubstring(responseText);
      if (lastJson.isPresent()) {
        jsonCandidate = lastJson.get();
      }
    }

    if (jsonCandidate == null) {
      return llmResponse;
    }

    try {
      GemmaFunctionCallModel functionCallParsed =
          objectMapper.readValue(jsonCandidate, GemmaFunctionCallModel.class);
      FunctionCall functionCall =
          FunctionCall.builder()
              .name(functionCallParsed.getName())
              .args(functionCallParsed.getParameters())
              .build();
      Part functionCallPart =
          Part.fromFunctionCall(functionCall.name().get(), functionCall.args().get());
      content = content.toBuilder().parts(ImmutableList.of(functionCallPart)).build();
      llmResponse = llmResponse.toBuilder().content(content).build();
    } catch (JsonProcessingException e) {
      logger.debug(
          "Error attempting to parse JSON into function call. Leaving as text response.", e);
    } catch (Exception e) {
      logger.warn("Error processing Gemma function call response: ", e);
    }

    return llmResponse;
  }

  private ContentPartsConversionResult convertContentPartsForGemma(Content contentItem) {
    List<Part> newParts = new ArrayList<>();
    boolean hasFunctionResponsePart = false;
    boolean hasFunctionCallPart = false;

    for (Part part : contentItem.parts().orElse(ImmutableList.of())) {
      if (part.functionResponse().isPresent()) {
        hasFunctionResponsePart = true;
        String responseJson;
        try {
          responseJson = objectMapper.writeValueAsString(part.functionResponse().get().response());
        } catch (JsonProcessingException e) {
          logger.warn("Error serializing function response to json", e);
          responseJson = "{}";
        }
        String responseText =
            String.format(
                "Invoking tool `%s` produced: `%s`.",
                part.functionResponse().get().name(), responseJson);
        newParts.add(Part.fromText(responseText));
      } else if (part.functionCall().isPresent()) {
        hasFunctionCallPart = true;
        try {
          newParts.add(Part.fromText(objectMapper.writeValueAsString(part.functionCall().get())));
        } catch (JsonProcessingException e) {
          logger.warn("Error serializing function call to json", e);
        }
      } else {
        newParts.add(part);
      }
    }
    return new ContentPartsConversionResult(newParts, hasFunctionResponsePart, hasFunctionCallPart);
  }

  private String buildGemmaFunctionSystemInstruction(
      List<FunctionDeclaration> functionDeclarations) {
    if (functionDeclarations.isEmpty()) {
      return "";
    }

    StringBuilder systemInstruction =
        new StringBuilder("You have access to the following functions:\n[");
    for (int i = 0; i < functionDeclarations.size(); i++) {
      try {
        systemInstruction.append(objectMapper.writeValueAsString(functionDeclarations.get(i)));
        if (i < functionDeclarations.size() - 1) {
          systemInstruction.append(",\n");
        }
      } catch (JsonProcessingException e) {
        logger.warn("Error serializing function declaration to json", e);
      }
    }
    systemInstruction.append("\n]\n");
    systemInstruction.append("When you call a function, you MUST respond in the format of: ");
    systemInstruction.append(
        "{\"name\": function name, \"parameters\": dictionary of argument name and its value}\n");
    systemInstruction.append(
        "When you call a function, you MUST NOT include any other text in the response.\n");

    return systemInstruction.toString();
  }

  private Optional<String> getLastValidJsonSubstring(String text) {
    String lastJsonStr = null;
    int startPos = 0;
    while (startPos < text.length()) {
      try {
        int firstBraceIndex = text.indexOf('{', startPos);
        if (firstBraceIndex == -1) {
          break;
        }
        // This is a simplification. A robust implementation would need a proper JSON parser
        // that can find the end of a JSON object. The Python version uses raw_decode.
        // For now, we'll assume the JSON is well-formed and find the matching brace.
        int braceCount = 1;
        int endIndex = -1;
        for (int i = firstBraceIndex + 1; i < text.length(); i++) {
          if (text.charAt(i) == '{') {
            braceCount++;
          } else if (text.charAt(i) == '}') {
            braceCount--;
          }
          if (braceCount == 0) {
            endIndex = i;
            break;
          }
        }

        if (endIndex != -1) {
          lastJsonStr = text.substring(firstBraceIndex, endIndex + 1);
          startPos = endIndex + 1;
        } else {
          startPos = firstBraceIndex + 1;
        }
      } catch (Exception e) {
        break;
      }
    }
    return Optional.ofNullable(lastJsonStr);
  }

  private static class ContentPartsConversionResult {
    final List<Part> newParts;
    final boolean hasFunctionResponsePart;
    final boolean hasFunctionCallPart;

    ContentPartsConversionResult(
        List<Part> newParts, boolean hasFunctionResponsePart, boolean hasFunctionCallPart) {
      this.newParts = newParts;
      this.hasFunctionResponsePart = hasFunctionResponsePart;
      this.hasFunctionCallPart = hasFunctionCallPart;
    }
  }
}
