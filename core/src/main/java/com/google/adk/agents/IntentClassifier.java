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

package com.google.adk.agents;

import com.google.adk.models.BaseLlm;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Classifies user input into a {@link VoiceMode} to determine how the voice agent should process
 * the request.
 *
 * <p>Three strategies are provided:
 *
 * <ul>
 *   <li><b>Keyword</b>: fast, rule-based classification using substring matching against known
 *       navigation keywords.
 *   <li><b>LLM</b>: sends the input to a small language model with a classification prompt.
 *   <li><b>Hybrid</b>: tries keyword matching first (fast path), then falls back to LLM
 *       classification if no keyword match is found.
 * </ul>
 *
 * <p>Use the static factory methods {@link #keyword(List)}, {@link #llm(BaseLlm)}, and {@link
 * #hybrid(List, BaseLlm)} to create instances.
 *
 * @author Sandeep Belgavi
 * @since 2026-08-04
 */
public abstract class IntentClassifier {

  private static final Logger logger = LoggerFactory.getLogger(IntentClassifier.class);

  /**
   * Classifies the user's text input into a {@link VoiceMode}.
   *
   * @param userText the transcribed or typed user input
   * @param config the current voice configuration
   * @return the classified voice mode (either {@link VoiceMode#VOICE_NAVIGATION} or {@link
   *     VoiceMode#VOICE_FULL})
   */
  public abstract VoiceMode classify(String userText, VoiceConfig config);

  /**
   * Asynchronously classifies the user's text input into a {@link VoiceMode}.
   *
   * @param userText the transcribed or typed user input
   * @param config the current voice configuration
   * @return a Single emitting the classified voice mode
   */
  public Single<VoiceMode> classifyAsync(String userText, VoiceConfig config) {
    return Single.fromCallable(() -> classify(userText, config)).subscribeOn(Schedulers.io());
  }

  /**
   * Creates a keyword-based intent classifier that matches input against known navigation
   * keywords/phrases.
   *
   * <p>If the user's input (lowercased) contains any of the provided navigation keywords, it is
   * classified as {@link VoiceMode#VOICE_NAVIGATION}. Otherwise, it is classified as {@link
   * VoiceMode#VOICE_FULL}.
   *
   * @param navKeywords list of navigation command keywords (e.g., "go back", "next page", "scroll
   *     down")
   * @return a keyword-based IntentClassifier
   */
  public static IntentClassifier keyword(List<String> navKeywords) {
    return new KeywordClassifier(navKeywords);
  }

  /**
   * Creates an LLM-based intent classifier that uses a language model to classify input.
   *
   * <p>Sends the user input to the model with a classification prompt and interprets the response
   * as either NAVIGATION or REASONING.
   *
   * @param model the BaseLlm model to use for classification (e.g., a small Ollama model)
   * @return an LLM-based IntentClassifier
   */
  public static IntentClassifier llm(BaseLlm model) {
    return new LlmClassifier(model);
  }

  /**
   * Creates a hybrid intent classifier that first tries keyword matching (fast path), then falls
   * back to LLM classification if no keyword match is found.
   *
   * <p>This provides the best of both worlds: instant classification for known navigation commands,
   * and intelligent LLM-based classification for ambiguous inputs.
   *
   * @param navKeywords list of navigation command keywords for fast matching
   * @param model the BaseLlm model to use as fallback for classification
   * @return a hybrid IntentClassifier
   */
  public static IntentClassifier hybrid(List<String> navKeywords, BaseLlm model) {
    return new HybridClassifier(navKeywords, model);
  }

  // ---- Keyword-based classifier implementation ----

  private static final class KeywordClassifier extends IntentClassifier {

    private final ImmutableList<String> navKeywords;

    KeywordClassifier(List<String> navKeywords) {
      this.navKeywords =
          navKeywords.stream().map(String::toLowerCase).collect(ImmutableList.toImmutableList());
    }

    @Override
    public VoiceMode classify(String userText, VoiceConfig config) {
      if (userText == null || userText.isEmpty()) {
        return VoiceMode.VOICE_FULL;
      }

      String normalizedInput = userText.toLowerCase().trim();

      // Check against provided navigation keywords
      for (String keyword : navKeywords) {
        if (normalizedInput.contains(keyword)) {
          logger.debug("Keyword match '{}' for input '{}' → VOICE_NAVIGATION", keyword, userText);
          return VoiceMode.VOICE_NAVIGATION;
        }
      }

      // Also check against VoiceConfig navigation commands if available
      ImmutableList<String> configCommands = config.getNavigationCommands();
      for (String command : configCommands) {
        if (normalizedInput.contains(command.toLowerCase())) {
          logger.debug(
              "Config command match '{}' for input '{}' → VOICE_NAVIGATION", command, userText);
          return VoiceMode.VOICE_NAVIGATION;
        }
      }

      logger.debug("No keyword match for input '{}' → VOICE_FULL", userText);
      return VoiceMode.VOICE_FULL;
    }
  }

  // ---- LLM-based classifier implementation ----

  private static final class LlmClassifier extends IntentClassifier {

    private static final String CLASSIFICATION_PROMPT =
        "Classify the following user input as either NAVIGATION (simple commands like go back, "
            + "next, stop, help, repeat) or REASONING (complex questions needing detailed "
            + "answers).\n\nUser input: '%s'\n\nRespond with ONLY one word: NAVIGATION or "
            + "REASONING";

    private final BaseLlm model;

    LlmClassifier(BaseLlm model) {
      if (model == null) {
        throw new IllegalArgumentException("Model cannot be null for LLM classifier");
      }
      this.model = model;
    }

    @Override
    public VoiceMode classify(String userText, VoiceConfig config) {
      if (userText == null || userText.isEmpty()) {
        return VoiceMode.VOICE_FULL;
      }

      try {
        String prompt = String.format(CLASSIFICATION_PROMPT, userText);
        Content promptContent =
            Content.builder().role("user").parts(ImmutableList.of(Part.fromText(prompt))).build();

        LlmRequest request =
            LlmRequest.builder()
                .model(model.model())
                .contents(ImmutableList.of(promptContent))
                .build();

        // Use blocking call for sync classification
        LlmResponse response = model.generateContent(request, false).blockingFirst();

        String responseText = extractText(response);
        if (responseText != null && responseText.trim().toUpperCase().contains("NAVIGATION")) {
          logger.debug("LLM classified '{}' → VOICE_NAVIGATION", userText);
          return VoiceMode.VOICE_NAVIGATION;
        }

        logger.debug("LLM classified '{}' → VOICE_FULL", userText);
        return VoiceMode.VOICE_FULL;
      } catch (Exception e) {
        logger.warn(
            "LLM classification failed for '{}', defaulting to VOICE_FULL: {}",
            userText,
            e.getMessage());
        return VoiceMode.VOICE_FULL;
      }
    }

    @Override
    public Single<VoiceMode> classifyAsync(String userText, VoiceConfig config) {
      if (userText == null || userText.isEmpty()) {
        return Single.just(VoiceMode.VOICE_FULL);
      }

      return Single.fromCallable(() -> classify(userText, config)).subscribeOn(Schedulers.io());
    }

    private String extractText(LlmResponse response) {
      if (response == null || response.content().isEmpty()) {
        return null;
      }
      Content content = response.content().get();
      if (content.parts().isEmpty() || content.parts().get().isEmpty()) {
        return null;
      }
      return content.parts().get().get(0).text().orElse(null);
    }
  }

  // ---- Hybrid classifier implementation ----

  private static final class HybridClassifier extends IntentClassifier {

    private final KeywordClassifier keywordClassifier;
    private final LlmClassifier llmClassifier;

    HybridClassifier(List<String> navKeywords, BaseLlm model) {
      this.keywordClassifier = new KeywordClassifier(navKeywords);
      this.llmClassifier = new LlmClassifier(model);
    }

    @Override
    public VoiceMode classify(String userText, VoiceConfig config) {
      // Fast path: try keyword matching first
      VoiceMode keywordResult = keywordClassifier.classify(userText, config);
      if (keywordResult == VoiceMode.VOICE_NAVIGATION) {
        logger.debug("Hybrid: keyword match for '{}' → VOICE_NAVIGATION", userText);
        return VoiceMode.VOICE_NAVIGATION;
      }

      // Slow path: fall back to LLM classification
      logger.debug("Hybrid: no keyword match for '{}', falling back to LLM", userText);
      return llmClassifier.classify(userText, config);
    }

    @Override
    public Single<VoiceMode> classifyAsync(String userText, VoiceConfig config) {
      if (userText == null || userText.isEmpty()) {
        return Single.just(VoiceMode.VOICE_FULL);
      }

      // Fast path: try keyword matching first (synchronous, cheap)
      VoiceMode keywordResult = keywordClassifier.classify(userText, config);
      if (keywordResult == VoiceMode.VOICE_NAVIGATION) {
        logger.debug("Hybrid async: keyword match for '{}' → VOICE_NAVIGATION", userText);
        return Single.just(VoiceMode.VOICE_NAVIGATION);
      }

      // Slow path: fall back to LLM classification on IO scheduler
      logger.debug("Hybrid async: no keyword match for '{}', falling back to LLM", userText);
      return llmClassifier.classifyAsync(userText, config);
    }
  }
}
