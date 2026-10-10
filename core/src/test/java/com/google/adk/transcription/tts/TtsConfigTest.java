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

package com.google.adk.transcription.tts;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TtsConfig}.
 *
 * @author Sandeep Belgavi
 * @since 2026-08-04
 */
@DisplayName("TtsConfig Tests")
class TtsConfigTest {

  @Test
  @DisplayName("Builder creates config with default values")
  void testBuilderDefaults() {
    TtsConfig config = TtsConfig.builder().endpoint("http://localhost:8000").build();

    assertThat(config.getEndpoint()).isEqualTo("http://localhost:8000");
    assertThat(config.getVoice()).isEqualTo("default");
    assertThat(config.getLanguage()).isEqualTo("en-US");
    assertThat(config.getModel()).isNull();
    assertThat(config.getOutputFormat()).isEqualTo(TtsAudioFormat.WAV);
    assertThat(config.getSampleRate()).isEqualTo(24000);
    assertThat(config.getSpeed()).isEqualTo(1.0);
    assertThat(config.getApiKey().isPresent()).isFalse();
  }

  @Test
  @DisplayName("Builder creates config with custom values")
  void testBuilderCustomValues() {
    TtsConfig config =
        TtsConfig.builder()
            .endpoint("https://api.openai.com")
            .voice("nova")
            .language("fr-FR")
            .model("tts-1-hd")
            .outputFormat(TtsAudioFormat.MP3)
            .sampleRate(48000)
            .speed(1.5)
            .apiKey("sk-test-key")
            .build();

    assertThat(config.getEndpoint()).isEqualTo("https://api.openai.com");
    assertThat(config.getVoice()).isEqualTo("nova");
    assertThat(config.getLanguage()).isEqualTo("fr-FR");
    assertThat(config.getModel()).isEqualTo("tts-1-hd");
    assertThat(config.getOutputFormat()).isEqualTo(TtsAudioFormat.MP3);
    assertThat(config.getSampleRate()).isEqualTo(48000);
    assertThat(config.getSpeed()).isEqualTo(1.5);
    assertThat(config.getApiKey().isPresent()).isTrue();
    assertThat(config.getApiKey().get()).isEqualTo("sk-test-key");
  }

  @Test
  @DisplayName("Builder throws exception when endpoint is null")
  void testBuilderMissingEndpoint() {
    assertThrows(IllegalArgumentException.class, () -> TtsConfig.builder().build());
  }

  @Test
  @DisplayName("Builder throws exception when endpoint is empty")
  void testBuilderEmptyEndpoint() {
    assertThrows(IllegalArgumentException.class, () -> TtsConfig.builder().endpoint("").build());
  }

  @Test
  @DisplayName("Builder throws exception for non-positive sample rate")
  void testBuilderInvalidSampleRate() {
    assertThrows(
        IllegalArgumentException.class,
        () -> TtsConfig.builder().endpoint("http://localhost:8000").sampleRate(0).build());
    assertThrows(
        IllegalArgumentException.class,
        () -> TtsConfig.builder().endpoint("http://localhost:8000").sampleRate(-1).build());
  }

  @Test
  @DisplayName("Builder throws exception for non-positive speed")
  void testBuilderInvalidSpeed() {
    assertThrows(
        IllegalArgumentException.class,
        () -> TtsConfig.builder().endpoint("http://localhost:8000").speed(0).build());
    assertThrows(
        IllegalArgumentException.class,
        () -> TtsConfig.builder().endpoint("http://localhost:8000").speed(-0.5).build());
  }

  @Test
  @DisplayName("toString includes all fields")
  void testToString() {
    TtsConfig config =
        TtsConfig.builder().endpoint("http://localhost:8000").voice("alloy").model("tts-1").build();

    String str = config.toString();
    assertThat(str).contains("endpoint='http://localhost:8000'");
    assertThat(str).contains("voice='alloy'");
    assertThat(str).contains("model='tts-1'");
  }

  @Test
  @DisplayName("ApiKey is empty Optional when not set")
  void testApiKeyAbsent() {
    TtsConfig config = TtsConfig.builder().endpoint("http://localhost:8000").build();

    assertThat(config.getApiKey()).isEqualTo(java.util.Optional.empty());
  }
}
