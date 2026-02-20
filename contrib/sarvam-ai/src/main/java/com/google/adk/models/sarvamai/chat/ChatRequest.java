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

package com.google.adk.models.sarvamai.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.sarvamai.SarvamAiConfig;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.ArrayList;
import java.util.List;

/**
 * Request body for the Sarvam AI chat completions endpoint. Constructed from the ADK {@link
 * LlmRequest} and {@link SarvamAiConfig}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ChatRequest {

  @JsonProperty("model")
  private String model;

  @JsonProperty("messages")
  private List<ChatMessage> messages;

  @JsonProperty("stream")
  private Boolean stream;

  @JsonProperty("temperature")
  private Double temperature;

  @JsonProperty("top_p")
  private Double topP;

  @JsonProperty("max_tokens")
  private Integer maxTokens;

  @JsonProperty("reasoning_effort")
  private String reasoningEffort;

  @JsonProperty("wiki_grounding")
  private Boolean wikiGrounding;

  @JsonProperty("frequency_penalty")
  private Double frequencyPenalty;

  @JsonProperty("presence_penalty")
  private Double presencePenalty;

  @JsonProperty("n")
  private Integer n;

  @JsonProperty("seed")
  private Integer seed;

  @JsonProperty("stop")
  private Object stop;

  public ChatRequest() {}

  /**
   * Converts an ADK {@link LlmRequest} into a Sarvam-native {@link ChatRequest}, applying config
   * defaults and mapping ADK roles to OpenAI-compatible roles.
   */
  public static ChatRequest fromLlmRequest(
      String modelName, LlmRequest llmRequest, SarvamAiConfig config, boolean stream) {
    ChatRequest request = new ChatRequest();
    request.model = modelName;
    request.stream = stream ? true : null;
    request.messages = new ArrayList<>();

    for (String instruction : llmRequest.getSystemInstructions()) {
      request.messages.add(new ChatMessage("system", instruction));
    }

    for (Content content : llmRequest.contents()) {
      String role = content.role().orElse("user");
      if ("model".equals(role)) {
        role = "assistant";
      }
      StringBuilder textBuilder = new StringBuilder();
      content
          .parts()
          .ifPresent(
              parts -> {
                for (Part part : parts) {
                  part.text().ifPresent(textBuilder::append);
                }
              });
      if (textBuilder.length() > 0) {
        request.messages.add(new ChatMessage(role, textBuilder.toString()));
      }
    }

    config.temperature().ifPresent(v -> request.temperature = v);
    config.topP().ifPresent(v -> request.topP = v);
    config.maxTokens().ifPresent(v -> request.maxTokens = v);
    config.reasoningEffort().ifPresent(v -> request.reasoningEffort = v);
    config.wikiGrounding().ifPresent(v -> request.wikiGrounding = v);
    config.frequencyPenalty().ifPresent(v -> request.frequencyPenalty = v);
    config.presencePenalty().ifPresent(v -> request.presencePenalty = v);

    return request;
  }

  public String getModel() {
    return model;
  }

  public List<ChatMessage> getMessages() {
    return messages;
  }

  public Boolean getStream() {
    return stream;
  }

  public Double getTemperature() {
    return temperature;
  }

  public Double getTopP() {
    return topP;
  }

  public Integer getMaxTokens() {
    return maxTokens;
  }

  public String getReasoningEffort() {
    return reasoningEffort;
  }

  public Boolean getWikiGrounding() {
    return wikiGrounding;
  }
}
