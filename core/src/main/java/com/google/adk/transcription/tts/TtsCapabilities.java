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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Represents a TTS server's capabilities including supported audio formats, voices, models, and
 * constraints. Instances are immutable once built.
 *
 * <p>Use the {@link #builder()} method to create instances via the Builder pattern.
 *
 * @author Sandeep Belgavi
 * @since 2026-08-04
 */
public final class TtsCapabilities {

  private final List<TtsAudioFormat> supportedFormats;
  private final List<String> supportedVoices;
  private final List<String> supportedModels;
  private final int maxTextLength;
  private final boolean supportsStreaming;

  private TtsCapabilities(Builder builder) {
    this.supportedFormats = Collections.unmodifiableList(new ArrayList<>(builder.supportedFormats));
    this.supportedVoices = Collections.unmodifiableList(new ArrayList<>(builder.supportedVoices));
    this.supportedModels = Collections.unmodifiableList(new ArrayList<>(builder.supportedModels));
    this.maxTextLength = builder.maxTextLength;
    this.supportsStreaming = builder.supportsStreaming;
  }

  /**
   * Creates a new builder for TtsCapabilities.
   *
   * @return a new Builder instance with default values
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Gets the list of audio formats supported by the TTS server.
   *
   * @return unmodifiable list of supported audio formats
   */
  public List<TtsAudioFormat> getSupportedFormats() {
    return supportedFormats;
  }

  /**
   * Gets the list of voices supported by the TTS server.
   *
   * @return unmodifiable list of supported voice identifiers
   */
  public List<String> getSupportedVoices() {
    return supportedVoices;
  }

  /**
   * Gets the list of models supported by the TTS server.
   *
   * @return unmodifiable list of supported model identifiers
   */
  public List<String> getSupportedModels() {
    return supportedModels;
  }

  /**
   * Gets the maximum text length the server can process in a single request.
   *
   * @return maximum text length in characters
   */
  public int getMaxTextLength() {
    return maxTextLength;
  }

  /**
   * Checks whether the server supports streaming audio output.
   *
   * @return true if streaming is supported
   */
  public boolean isSupportsStreaming() {
    return supportsStreaming;
  }

  /**
   * Checks if a given audio format is supported by the server.
   *
   * @param format the audio format to check
   * @return true if the format is supported
   */
  public boolean isFormatSupported(TtsAudioFormat format) {
    return supportedFormats.contains(format);
  }

  @Override
  public String toString() {
    return String.format(
        "TtsCapabilities{formats=%s, voices=%d, models=%d, maxTextLength=%d, streaming=%s}",
        supportedFormats,
        supportedVoices.size(),
        supportedModels.size(),
        maxTextLength,
        supportsStreaming);
  }

  /** Builder for {@link TtsCapabilities}. */
  public static class Builder {
    private List<TtsAudioFormat> supportedFormats =
        new ArrayList<>(Arrays.asList(TtsAudioFormat.values()));
    private List<String> supportedVoices = new ArrayList<>();
    private List<String> supportedModels = new ArrayList<>();
    private int maxTextLength = 4096;
    private boolean supportsStreaming = true;

    /**
     * Sets the supported audio formats.
     *
     * @param supportedFormats list of supported formats
     * @return this builder
     */
    public Builder supportedFormats(List<TtsAudioFormat> supportedFormats) {
      this.supportedFormats = new ArrayList<>(supportedFormats);
      return this;
    }

    /**
     * Sets the supported voices.
     *
     * @param supportedVoices list of supported voice identifiers
     * @return this builder
     */
    public Builder supportedVoices(List<String> supportedVoices) {
      this.supportedVoices = new ArrayList<>(supportedVoices);
      return this;
    }

    /**
     * Sets the supported models.
     *
     * @param supportedModels list of supported model identifiers
     * @return this builder
     */
    public Builder supportedModels(List<String> supportedModels) {
      this.supportedModels = new ArrayList<>(supportedModels);
      return this;
    }

    /**
     * Sets the maximum text length.
     *
     * @param maxTextLength maximum number of characters
     * @return this builder
     * @throws IllegalArgumentException if maxTextLength is not positive
     */
    public Builder maxTextLength(int maxTextLength) {
      if (maxTextLength <= 0) {
        throw new IllegalArgumentException("maxTextLength must be > 0");
      }
      this.maxTextLength = maxTextLength;
      return this;
    }

    /**
     * Sets whether the server supports streaming.
     *
     * @param supportsStreaming true if streaming is supported
     * @return this builder
     */
    public Builder supportsStreaming(boolean supportsStreaming) {
      this.supportsStreaming = supportsStreaming;
      return this;
    }

    /**
     * Builds an immutable TtsCapabilities instance.
     *
     * @return a new TtsCapabilities instance
     */
    public TtsCapabilities build() {
      return new TtsCapabilities(this);
    }
  }
}
