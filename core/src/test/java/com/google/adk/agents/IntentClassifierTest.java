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

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link IntentClassifier} keyword-based classification.
 *
 * @author Sandeep Belgavi
 * @since 2026-08-04
 */
@DisplayName("IntentClassifier Tests")
class IntentClassifierTest {

  private IntentClassifier classifier;
  private VoiceConfig config;

  @BeforeEach
  void setUp() {
    List<String> navKeywords =
        Arrays.asList("go back", "next page", "scroll down", "scroll up", "open menu");
    classifier = IntentClassifier.keyword(navKeywords);
    config = VoiceConfig.builder().build();
  }

  @Test
  @DisplayName("classify returns VOICE_NAVIGATION for matching keyword")
  void testClassifyMatchingKeyword() {
    VoiceMode result = classifier.classify("please go back", config);

    assertThat(result).isEqualTo(VoiceMode.VOICE_NAVIGATION);
  }

  @Test
  @DisplayName("classify returns VOICE_NAVIGATION for next page command")
  void testClassifyNextPageCommand() {
    VoiceMode result = classifier.classify("next page please", config);

    assertThat(result).isEqualTo(VoiceMode.VOICE_NAVIGATION);
  }

  @Test
  @DisplayName("classify returns VOICE_NAVIGATION for scroll down command")
  void testClassifyScrollDownCommand() {
    VoiceMode result = classifier.classify("can you scroll down", config);

    assertThat(result).isEqualTo(VoiceMode.VOICE_NAVIGATION);
  }

  @Test
  @DisplayName("classify returns VOICE_FULL for complex query")
  void testClassifyComplexQuery() {
    VoiceMode result = classifier.classify("What is the capital of France?", config);

    assertThat(result).isEqualTo(VoiceMode.VOICE_FULL);
  }

  @Test
  @DisplayName("classify returns VOICE_FULL for non-matching input")
  void testClassifyNonMatching() {
    VoiceMode result = classifier.classify("tell me a joke", config);

    assertThat(result).isEqualTo(VoiceMode.VOICE_FULL);
  }

  @Test
  @DisplayName("classify is case-insensitive")
  void testClassifyCaseInsensitive() {
    VoiceMode result = classifier.classify("GO BACK NOW", config);

    assertThat(result).isEqualTo(VoiceMode.VOICE_NAVIGATION);
  }

  @Test
  @DisplayName("classify handles mixed case input")
  void testClassifyMixedCase() {
    VoiceMode result = classifier.classify("Please Open Menu", config);

    assertThat(result).isEqualTo(VoiceMode.VOICE_NAVIGATION);
  }

  @Test
  @DisplayName("classify returns VOICE_FULL for null input")
  void testClassifyNullInput() {
    VoiceMode result = classifier.classify(null, config);

    assertThat(result).isEqualTo(VoiceMode.VOICE_FULL);
  }

  @Test
  @DisplayName("classify returns VOICE_FULL for empty input")
  void testClassifyEmptyInput() {
    VoiceMode result = classifier.classify("", config);

    assertThat(result).isEqualTo(VoiceMode.VOICE_FULL);
  }

  @Test
  @DisplayName("classify checks VoiceConfig navigation commands")
  void testClassifyWithConfigCommands() {
    VoiceConfig configWithCommands =
        VoiceConfig.builder().navigationCommands(Arrays.asList("volume up", "pause")).build();

    VoiceMode result = classifier.classify("volume up", configWithCommands);

    assertThat(result).isEqualTo(VoiceMode.VOICE_NAVIGATION);
  }

  @Test
  @DisplayName("classify returns VOICE_FULL when no config commands match")
  void testClassifyNoConfigCommandMatch() {
    VoiceConfig configWithCommands =
        VoiceConfig.builder().navigationCommands(Arrays.asList("volume up", "pause")).build();

    VoiceMode result = classifier.classify("explain quantum computing", configWithCommands);

    assertThat(result).isEqualTo(VoiceMode.VOICE_FULL);
  }

  @Test
  @DisplayName("classifyAsync returns correct result")
  void testClassifyAsync() {
    VoiceMode result = classifier.classifyAsync("go back", config).blockingGet();

    assertThat(result).isEqualTo(VoiceMode.VOICE_NAVIGATION);
  }

  @Test
  @DisplayName("classifyAsync returns VOICE_FULL for complex input")
  void testClassifyAsyncComplex() {
    VoiceMode result =
        classifier.classifyAsync("What are best practices for Java?", config).blockingGet();

    assertThat(result).isEqualTo(VoiceMode.VOICE_FULL);
  }

  @Test
  @DisplayName("keyword classifier with empty list classifies as VOICE_FULL")
  void testEmptyKeywordsList() {
    IntentClassifier emptyClassifier = IntentClassifier.keyword(List.of());

    VoiceMode result = emptyClassifier.classify("go back", VoiceConfig.builder().build());

    assertThat(result).isEqualTo(VoiceMode.VOICE_FULL);
  }

  @Test
  @DisplayName("keyword classifier matches substring within longer text")
  void testSubstringMatching() {
    VoiceMode result = classifier.classify("I would like you to scroll down a bit", config);

    assertThat(result).isEqualTo(VoiceMode.VOICE_NAVIGATION);
  }
}
