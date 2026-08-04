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

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TtsCapabilities}.
 *
 * @author Sandeep Belgavi
 * @since 2026-08-04
 */
@DisplayName("TtsCapabilities Tests")
class TtsCapabilitiesTest {

  @Test
  @DisplayName("builder defaults: all formats supported, 4096 max text, streaming true")
  void testBuilderDefaults() {
    TtsCapabilities capabilities = TtsCapabilities.builder().build();

    // All formats should be supported by default
    assertThat(capabilities.getSupportedFormats()).containsExactly(TtsAudioFormat.values());
    assertThat(capabilities.getSupportedVoices()).isEmpty();
    assertThat(capabilities.getSupportedModels()).isEmpty();
    assertThat(capabilities.getMaxTextLength()).isEqualTo(4096);
    assertThat(capabilities.isSupportsStreaming()).isTrue();
  }

  @Test
  @DisplayName("builder defaults: isFormatSupported returns true for all formats")
  void testBuilderDefaultsAllFormatsSupported() {
    TtsCapabilities capabilities = TtsCapabilities.builder().build();

    for (TtsAudioFormat format : TtsAudioFormat.values()) {
      assertThat(capabilities.isFormatSupported(format)).isTrue();
    }
  }

  @Test
  @DisplayName("custom capabilities with specific formats")
  void testCustomFormats() {
    List<TtsAudioFormat> formats = Arrays.asList(TtsAudioFormat.MP3, TtsAudioFormat.WAV);
    TtsCapabilities capabilities = TtsCapabilities.builder().supportedFormats(formats).build();

    assertThat(capabilities.getSupportedFormats())
        .containsExactly(TtsAudioFormat.MP3, TtsAudioFormat.WAV);
    assertThat(capabilities.isFormatSupported(TtsAudioFormat.MP3)).isTrue();
    assertThat(capabilities.isFormatSupported(TtsAudioFormat.WAV)).isTrue();
    assertThat(capabilities.isFormatSupported(TtsAudioFormat.OGG)).isFalse();
    assertThat(capabilities.isFormatSupported(TtsAudioFormat.PCM)).isFalse();
  }

  @Test
  @DisplayName("custom capabilities with voices and models")
  void testCustomVoicesAndModels() {
    TtsCapabilities capabilities =
        TtsCapabilities.builder()
            .supportedVoices(Arrays.asList("alloy", "echo", "nova"))
            .supportedModels(Arrays.asList("tts-1", "tts-1-hd"))
            .build();

    assertThat(capabilities.getSupportedVoices()).containsExactly("alloy", "echo", "nova");
    assertThat(capabilities.getSupportedModels()).containsExactly("tts-1", "tts-1-hd");
  }

  @Test
  @DisplayName("custom maxTextLength")
  void testCustomMaxTextLength() {
    TtsCapabilities capabilities = TtsCapabilities.builder().maxTextLength(8192).build();

    assertThat(capabilities.getMaxTextLength()).isEqualTo(8192);
  }

  @Test
  @DisplayName("custom streaming disabled")
  void testStreamingDisabled() {
    TtsCapabilities capabilities = TtsCapabilities.builder().supportsStreaming(false).build();

    assertThat(capabilities.isSupportsStreaming()).isFalse();
  }

  @Test
  @DisplayName("maxTextLength validation rejects non-positive values")
  void testMaxTextLengthValidation() {
    assertThrows(
        IllegalArgumentException.class, () -> TtsCapabilities.builder().maxTextLength(0).build());
    assertThrows(
        IllegalArgumentException.class, () -> TtsCapabilities.builder().maxTextLength(-1).build());
  }

  @Test
  @DisplayName("lists are immutable after build")
  void testImmutability() {
    TtsCapabilities capabilities =
        TtsCapabilities.builder().supportedVoices(Arrays.asList("alloy", "echo")).build();

    assertThrows(
        UnsupportedOperationException.class, () -> capabilities.getSupportedVoices().add("nova"));
    assertThrows(
        UnsupportedOperationException.class,
        () -> capabilities.getSupportedFormats().add(TtsAudioFormat.FLAC));
  }

  @Test
  @DisplayName("toString contains meaningful information")
  void testToString() {
    TtsCapabilities capabilities = TtsCapabilities.builder().build();

    String str = capabilities.toString();
    assertThat(str).contains("TtsCapabilities");
    assertThat(str).contains("4096");
  }
}
