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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link VoiceConfig}.
 *
 * @author Sandeep Belgavi
 * @since 2026-08-04
 */
@DisplayName("VoiceConfig Tests")
class VoiceConfigTest {

  @Test
  @DisplayName("Builder creates config with default values")
  void testBuilderDefaults() {
    VoiceConfig config = VoiceConfig.builder().build();

    assertThat(config.getVoiceMode()).isEqualTo(VoiceMode.AUTO);
    assertThat(config.getSttModel()).isEqualTo("whisper-1");
    assertThat(config.getTtsModel()).isEqualTo("tts-1");
    assertThat(config.getTtsVoice()).isEqualTo("alloy");
    assertThat(config.getLanguage()).isEqualTo("en");
    assertThat(config.getTtsSpeed()).isEqualTo(1.0);
    assertThat(config.getNavigationCommands()).isEmpty();
  }

  @Test
  @DisplayName("Builder creates config with custom values")
  void testBuilderCustomValues() {
    VoiceConfig config =
        VoiceConfig.builder()
            .voiceMode(VoiceMode.VOICE_FULL)
            .sttEndpoint("http://localhost:9000")
            .ttsEndpoint("http://localhost:8000")
            .sttModel("whisper-large-v3")
            .ttsModel("tts-1-hd")
            .ttsVoice("nova")
            .language("fr")
            .ttsSpeed(1.25)
            .llmModel("llama3")
            .classifierModel("phi3")
            .navigationCommands(Arrays.asList("go back", "next"))
            .build();

    assertThat(config.getVoiceMode()).isEqualTo(VoiceMode.VOICE_FULL);
    assertThat(config.getSttEndpoint()).isEqualTo("http://localhost:9000");
    assertThat(config.getTtsEndpoint()).isEqualTo("http://localhost:8000");
    assertThat(config.getSttModel()).isEqualTo("whisper-large-v3");
    assertThat(config.getTtsModel()).isEqualTo("tts-1-hd");
    assertThat(config.getTtsVoice()).isEqualTo("nova");
    assertThat(config.getLanguage()).isEqualTo("fr");
    assertThat(config.getTtsSpeed()).isEqualTo(1.25);
    assertThat(config.getLlmModel()).isEqualTo("llama3");
    assertThat(config.getClassifierModel()).isEqualTo("phi3");
    assertThat(config.getNavigationCommands()).containsExactly("go back", "next");
  }

  @Test
  @DisplayName("Classifier model defaults to LLM model when not set")
  void testClassifierModelDefaultsToLlmModel() {
    VoiceConfig config = VoiceConfig.builder().llmModel("llama3").build();

    assertThat(config.getClassifierModel()).isEqualTo("llama3");
  }

  @Test
  @DisplayName("Classifier model is independent when explicitly set")
  void testClassifierModelExplicitlySet() {
    VoiceConfig config = VoiceConfig.builder().llmModel("llama3").classifierModel("phi3").build();

    assertThat(config.getLlmModel()).isEqualTo("llama3");
    assertThat(config.getClassifierModel()).isEqualTo("phi3");
  }

  @Test
  @DisplayName("Builder throws on invalid TTS speed")
  void testBuilderInvalidTtsSpeed() {
    assertThrows(IllegalArgumentException.class, () -> VoiceConfig.builder().ttsSpeed(0).build());
    assertThrows(
        IllegalArgumentException.class, () -> VoiceConfig.builder().ttsSpeed(-1.0).build());
  }

  @Test
  @DisplayName("Navigation commands are immutable")
  void testNavigationCommandsImmutable() {
    VoiceConfig config =
        VoiceConfig.builder().navigationCommands(Arrays.asList("go back", "next")).build();

    assertThrows(
        UnsupportedOperationException.class,
        () -> config.getNavigationCommands().add("new command"));
  }

  @Test
  @DisplayName("fromEnvironment returns config with defaults when no env vars set")
  void testFromEnvironmentDefaults() {
    // When no environment variables are set, fromEnvironment should return
    // defaults without throwing
    VoiceConfig config = VoiceConfig.fromEnvironment();

    assertThat(config).isNotNull();
    assertThat(config.getVoiceMode()).isEqualTo(VoiceMode.AUTO);
    assertThat(config.getSttModel()).isEqualTo("whisper-1");
    assertThat(config.getTtsModel()).isEqualTo("tts-1");
    assertThat(config.getTtsVoice()).isEqualTo("alloy");
    assertThat(config.getLanguage()).isEqualTo("en");
    assertThat(config.getTtsSpeed()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("toString includes key fields")
  void testToString() {
    VoiceConfig config =
        VoiceConfig.builder()
            .voiceMode(VoiceMode.VOICE_FULL)
            .ttsEndpoint("http://localhost:8000")
            .ttsVoice("nova")
            .build();

    String str = config.toString();
    assertThat(str).contains("VOICE_FULL");
    assertThat(str).contains("http://localhost:8000");
    assertThat(str).contains("nova");
  }

  @Test
  @DisplayName("All VoiceMode enum values are valid")
  void testVoiceModeEnumValues() {
    assertThat(VoiceMode.values()).hasLength(4);
    assertThat(VoiceMode.valueOf("TEXT_ONLY")).isEqualTo(VoiceMode.TEXT_ONLY);
    assertThat(VoiceMode.valueOf("VOICE_NAVIGATION")).isEqualTo(VoiceMode.VOICE_NAVIGATION);
    assertThat(VoiceMode.valueOf("VOICE_FULL")).isEqualTo(VoiceMode.VOICE_FULL);
    assertThat(VoiceMode.valueOf("AUTO")).isEqualTo(VoiceMode.AUTO);
  }
}
