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

import static com.google.common.truth.Truth.assertThat;

import com.google.adk.models.LlmResponse;
import com.google.adk.testing.TestLlm;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link IntentClassifier} LLM-based and hybrid classification.
 *
 * @author Sandeep Belgavi
 * @since 2026-08-04
 */
@DisplayName("IntentClassifier LLM Tests")
class IntentClassifierLlmTest {

  private VoiceConfig config = VoiceConfig.builder().build();

  private TestLlm createTestLlm(String response) {
    LlmResponse llmResponse =
        LlmResponse.builder()
            .content(
                Content.builder()
                    .role("model")
                    .parts(ImmutableList.of(Part.fromText(response)))
                    .build())
            .build();
    return new TestLlm(ImmutableList.of(llmResponse));
  }

  @Test
  @DisplayName("hybrid classifier uses keyword first when keyword matches")
  void testHybridUsesKeywordFirst() {
    // Create an LLM that would classify as REASONING if called
    TestLlm llm = createTestLlm("REASONING");

    List<String> keywords = Arrays.asList("go back", "next page", "stop");
    IntentClassifier classifier = IntentClassifier.hybrid(keywords, llm);

    VoiceMode result = classifier.classify("please go back", config);

    assertThat(result).isEqualTo(VoiceMode.VOICE_NAVIGATION);
    // The LLM should NOT have been called since keyword matched
    assertThat(llm.getRequests()).isEmpty();
  }

  @Test
  @DisplayName("hybrid classifier uses keyword first for multiple keywords")
  void testHybridKeywordMatchVariousCommands() {
    TestLlm llm = createTestLlm("REASONING");

    List<String> keywords = Arrays.asList("go back", "next page", "stop", "scroll down");
    IntentClassifier classifier = IntentClassifier.hybrid(keywords, llm);

    assertThat(classifier.classify("next page please", config))
        .isEqualTo(VoiceMode.VOICE_NAVIGATION);
    assertThat(classifier.classify("stop now", config)).isEqualTo(VoiceMode.VOICE_NAVIGATION);
    assertThat(classifier.classify("can you scroll down", config))
        .isEqualTo(VoiceMode.VOICE_NAVIGATION);

    // LLM should never have been called
    assertThat(llm.getRequests()).isEmpty();
  }

  @Test
  @DisplayName("hybrid falls through to LLM when no keyword match — NAVIGATION response")
  void testHybridFallsToLlmNavigation() {
    TestLlm llm = createTestLlm("NAVIGATION");

    List<String> keywords = Arrays.asList("go back", "next page");
    IntentClassifier classifier = IntentClassifier.hybrid(keywords, llm);

    // "help me" doesn't match any keyword, so LLM is called
    VoiceMode result = classifier.classify("help me", config);

    assertThat(result).isEqualTo(VoiceMode.VOICE_NAVIGATION);
    // The LLM should have been called
    assertThat(llm.getRequests()).hasSize(1);
  }

  @Test
  @DisplayName("hybrid falls through to LLM when no keyword match — REASONING response")
  void testHybridFallsToLlmReasoning() {
    TestLlm llm = createTestLlm("REASONING");

    List<String> keywords = Arrays.asList("go back", "next page");
    IntentClassifier classifier = IntentClassifier.hybrid(keywords, llm);

    VoiceMode result = classifier.classify("What is the meaning of life?", config);

    assertThat(result).isEqualTo(VoiceMode.VOICE_FULL);
    assertThat(llm.getRequests()).hasSize(1);
  }

  @Test
  @DisplayName("LLM classifier returns VOICE_NAVIGATION for NAVIGATION response")
  void testLlmClassifierNavigation() {
    TestLlm llm = createTestLlm("NAVIGATION");
    IntentClassifier classifier = IntentClassifier.llm(llm);

    VoiceMode result = classifier.classify("repeat that", config);

    assertThat(result).isEqualTo(VoiceMode.VOICE_NAVIGATION);
    assertThat(llm.getRequests()).hasSize(1);
  }

  @Test
  @DisplayName("LLM classifier returns VOICE_FULL for REASONING response")
  void testLlmClassifierReasoning() {
    TestLlm llm = createTestLlm("REASONING");
    IntentClassifier classifier = IntentClassifier.llm(llm);

    VoiceMode result = classifier.classify("explain quantum physics", config);

    assertThat(result).isEqualTo(VoiceMode.VOICE_FULL);
    assertThat(llm.getRequests()).hasSize(1);
  }

  @Test
  @DisplayName("LLM classifier defaults to VOICE_FULL on error")
  void testLlmClassifierDefaultsOnError() {
    // Create a TestLlm that throws an error
    TestLlm llm = TestLlm.create(ImmutableList.of(), new RuntimeException("LLM unavailable"));
    IntentClassifier classifier = IntentClassifier.llm(llm);

    VoiceMode result = classifier.classify("hello world", config);

    assertThat(result).isEqualTo(VoiceMode.VOICE_FULL);
  }

  @Test
  @DisplayName("LLM classifier returns VOICE_FULL for null input")
  void testLlmClassifierNullInput() {
    TestLlm llm = createTestLlm("NAVIGATION");
    IntentClassifier classifier = IntentClassifier.llm(llm);

    VoiceMode result = classifier.classify(null, config);

    assertThat(result).isEqualTo(VoiceMode.VOICE_FULL);
    assertThat(llm.getRequests()).isEmpty();
  }

  @Test
  @DisplayName("LLM classifier returns VOICE_FULL for empty input")
  void testLlmClassifierEmptyInput() {
    TestLlm llm = createTestLlm("NAVIGATION");
    IntentClassifier classifier = IntentClassifier.llm(llm);

    VoiceMode result = classifier.classify("", config);

    assertThat(result).isEqualTo(VoiceMode.VOICE_FULL);
    assertThat(llm.getRequests()).isEmpty();
  }

  @Test
  @DisplayName("hybrid classifyAsync uses keyword when matched")
  void testHybridClassifyAsyncKeyword() {
    TestLlm llm = createTestLlm("REASONING");
    List<String> keywords = Arrays.asList("go back", "next page");
    IntentClassifier classifier = IntentClassifier.hybrid(keywords, llm);

    VoiceMode result = classifier.classifyAsync("go back now", config).blockingGet();

    assertThat(result).isEqualTo(VoiceMode.VOICE_NAVIGATION);
    assertThat(llm.getRequests()).isEmpty();
  }

  @Test
  @DisplayName("hybrid classifyAsync falls to LLM when no keyword match")
  void testHybridClassifyAsyncFallsToLlm() {
    TestLlm llm = createTestLlm("REASONING");
    List<String> keywords = Arrays.asList("go back", "next page");
    IntentClassifier classifier = IntentClassifier.hybrid(keywords, llm);

    VoiceMode result =
        classifier.classifyAsync("explain the theory of relativity", config).blockingGet();

    assertThat(result).isEqualTo(VoiceMode.VOICE_FULL);
    assertThat(llm.getRequests()).hasSize(1);
  }

  @Test
  @DisplayName("LLM request contains classification prompt with user text")
  void testLlmRequestContainsPrompt() {
    TestLlm llm = createTestLlm("NAVIGATION");
    IntentClassifier classifier = IntentClassifier.llm(llm);

    classifier.classify("show me settings", config);

    assertThat(llm.getRequests()).hasSize(1);
    // Verify the prompt was sent to the LLM
    String promptText =
        llm.getRequests().get(0).contents().get(0).parts().get().get(0).text().orElse("");
    assertThat(promptText).contains("show me settings");
    assertThat(promptText).contains("NAVIGATION");
    assertThat(promptText).contains("REASONING");
  }
}
