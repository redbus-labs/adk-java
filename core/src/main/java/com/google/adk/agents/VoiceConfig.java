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

import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import java.util.List;

/**
 * Configuration for voice-enabled agent interaction. Uses Builder Pattern for flexible
 * configuration.
 *
 * <p>All fields are immutable once built. Use the builder to create instances, or use {@link
 * #fromEnvironment()} to load configuration from environment variables.
 *
 * <p>Supported environment variables:
 *
 * <ul>
 *   <li>{@code ADK_VOICE_MODE} - Voice mode (TEXT_ONLY, VOICE_NAVIGATION, VOICE_FULL, AUTO)
 *   <li>{@code ADK_STT_ENDPOINT} - Speech-to-text service endpoint
 *   <li>{@code ADK_TTS_ENDPOINT} - Text-to-speech service endpoint
 *   <li>{@code ADK_STT_MODEL} - STT model name
 *   <li>{@code ADK_TTS_MODEL} - TTS model name
 *   <li>{@code ADK_TTS_VOICE} - TTS voice name
 *   <li>{@code ADK_VOICE_LANGUAGE} - Language code
 *   <li>{@code ADK_TTS_SPEED} - TTS playback speed
 *   <li>{@code ADK_LLM_MODEL} - LLM model for reasoning
 *   <li>{@code ADK_CLASSIFIER_MODEL} - Model for intent classification
 *   <li>{@code ADK_NAVIGATION_COMMANDS} - Comma-separated navigation commands
 * </ul>
 *
 * @author Sandeep Belgavi
 * @since 2026-08-04
 */
public final class VoiceConfig {
  private final VoiceMode voiceMode;
  private final String sttEndpoint;
  private final String ttsEndpoint;
  private final String sttModel;
  private final String ttsModel;
  private final String ttsVoice;
  private final String language;
  private final double ttsSpeed;
  private final String llmModel;
  private final String classifierModel;
  private final ImmutableList<String> navigationCommands;

  private VoiceConfig(Builder builder) {
    this.voiceMode = builder.voiceMode;
    this.sttEndpoint = builder.sttEndpoint;
    this.ttsEndpoint = builder.ttsEndpoint;
    this.sttModel = builder.sttModel;
    this.ttsModel = builder.ttsModel;
    this.ttsVoice = builder.ttsVoice;
    this.language = builder.language;
    this.ttsSpeed = builder.ttsSpeed;
    this.llmModel = builder.llmModel;
    this.classifierModel =
        builder.classifierModel != null ? builder.classifierModel : builder.llmModel;
    this.navigationCommands = ImmutableList.copyOf(builder.navigationCommands);
  }

  /**
   * Creates a new {@link Builder} instance.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Creates a {@link VoiceConfig} from environment variables.
   *
   * <p>Reads all configuration from environment variables with sensible defaults for values that
   * are not set.
   *
   * @return a VoiceConfig populated from environment variables
   */
  public static VoiceConfig fromEnvironment() {
    Builder builder = new Builder();

    String mode = System.getenv("ADK_VOICE_MODE");
    if (mode != null && !mode.isEmpty()) {
      builder.voiceMode(VoiceMode.valueOf(mode.toUpperCase()));
    }

    String sttEndpoint = System.getenv("ADK_STT_ENDPOINT");
    if (sttEndpoint != null && !sttEndpoint.isEmpty()) {
      builder.sttEndpoint(sttEndpoint);
    }

    String ttsEndpoint = System.getenv("ADK_TTS_ENDPOINT");
    if (ttsEndpoint != null && !ttsEndpoint.isEmpty()) {
      builder.ttsEndpoint(ttsEndpoint);
    }

    String sttModel = System.getenv("ADK_STT_MODEL");
    if (sttModel != null && !sttModel.isEmpty()) {
      builder.sttModel(sttModel);
    }

    String ttsModel = System.getenv("ADK_TTS_MODEL");
    if (ttsModel != null && !ttsModel.isEmpty()) {
      builder.ttsModel(ttsModel);
    }

    String ttsVoice = System.getenv("ADK_TTS_VOICE");
    if (ttsVoice != null && !ttsVoice.isEmpty()) {
      builder.ttsVoice(ttsVoice);
    }

    String language = System.getenv("ADK_VOICE_LANGUAGE");
    if (language != null && !language.isEmpty()) {
      builder.language(language);
    }

    String ttsSpeed = System.getenv("ADK_TTS_SPEED");
    if (ttsSpeed != null && !ttsSpeed.isEmpty()) {
      builder.ttsSpeed(Double.parseDouble(ttsSpeed));
    }

    String llmModel = System.getenv("ADK_LLM_MODEL");
    if (llmModel != null && !llmModel.isEmpty()) {
      builder.llmModel(llmModel);
    }

    String classifierModel = System.getenv("ADK_CLASSIFIER_MODEL");
    if (classifierModel != null && !classifierModel.isEmpty()) {
      builder.classifierModel(classifierModel);
    }

    String navCommands = System.getenv("ADK_NAVIGATION_COMMANDS");
    if (navCommands != null && !navCommands.isEmpty()) {
      builder.navigationCommands(Arrays.asList(navCommands.split(",")));
    }

    return builder.build();
  }

  /** Returns the voice interaction mode. */
  public VoiceMode getVoiceMode() {
    return voiceMode;
  }

  /** Returns the speech-to-text service endpoint. */
  public String getSttEndpoint() {
    return sttEndpoint;
  }

  /** Returns the text-to-speech service endpoint. */
  public String getTtsEndpoint() {
    return ttsEndpoint;
  }

  /** Returns the STT model name. */
  public String getSttModel() {
    return sttModel;
  }

  /** Returns the TTS model name. */
  public String getTtsModel() {
    return ttsModel;
  }

  /** Returns the TTS voice name. */
  public String getTtsVoice() {
    return ttsVoice;
  }

  /** Returns the language code. */
  public String getLanguage() {
    return language;
  }

  /** Returns the TTS playback speed multiplier. */
  public double getTtsSpeed() {
    return ttsSpeed;
  }

  /** Returns the LLM model name used for reasoning. */
  public String getLlmModel() {
    return llmModel;
  }

  /** Returns the classifier model name used for intent classification. */
  public String getClassifierModel() {
    return classifierModel;
  }

  /** Returns the list of navigation commands that trigger voice-nav mode. */
  public ImmutableList<String> getNavigationCommands() {
    return navigationCommands;
  }

  /** Builder for {@link VoiceConfig}. */
  public static class Builder {
    private VoiceMode voiceMode = VoiceMode.AUTO;
    private String sttEndpoint = System.getenv("ADK_STT_ENDPOINT");
    private String ttsEndpoint = System.getenv("ADK_TTS_ENDPOINT");
    private String sttModel = "whisper-1";
    private String ttsModel = "tts-1";
    private String ttsVoice = "alloy";
    private String language = "en";
    private double ttsSpeed = 1.0;
    private String llmModel;
    private String classifierModel;
    private List<String> navigationCommands = List.of();

    /**
     * Sets the voice interaction mode.
     *
     * @param voiceMode the voice mode
     * @return this builder
     */
    public Builder voiceMode(VoiceMode voiceMode) {
      this.voiceMode = voiceMode;
      return this;
    }

    /**
     * Sets the speech-to-text service endpoint.
     *
     * @param sttEndpoint the STT endpoint URL
     * @return this builder
     */
    public Builder sttEndpoint(String sttEndpoint) {
      this.sttEndpoint = sttEndpoint;
      return this;
    }

    /**
     * Sets the text-to-speech service endpoint.
     *
     * @param ttsEndpoint the TTS endpoint URL
     * @return this builder
     */
    public Builder ttsEndpoint(String ttsEndpoint) {
      this.ttsEndpoint = ttsEndpoint;
      return this;
    }

    /**
     * Sets the STT model name.
     *
     * @param sttModel the STT model name
     * @return this builder
     */
    public Builder sttModel(String sttModel) {
      this.sttModel = sttModel;
      return this;
    }

    /**
     * Sets the TTS model name.
     *
     * @param ttsModel the TTS model name
     * @return this builder
     */
    public Builder ttsModel(String ttsModel) {
      this.ttsModel = ttsModel;
      return this;
    }

    /**
     * Sets the TTS voice name.
     *
     * @param ttsVoice the voice name
     * @return this builder
     */
    public Builder ttsVoice(String ttsVoice) {
      this.ttsVoice = ttsVoice;
      return this;
    }

    /**
     * Sets the language code.
     *
     * @param language the language code (e.g., "en", "es", "fr")
     * @return this builder
     */
    public Builder language(String language) {
      this.language = language;
      return this;
    }

    /**
     * Sets the TTS playback speed multiplier.
     *
     * @param ttsSpeed the speed multiplier (must be greater than 0)
     * @return this builder
     * @throws IllegalArgumentException if speed is not greater than 0
     */
    public Builder ttsSpeed(double ttsSpeed) {
      if (ttsSpeed <= 0) {
        throw new IllegalArgumentException("TTS speed must be > 0");
      }
      this.ttsSpeed = ttsSpeed;
      return this;
    }

    /**
     * Sets the LLM model name for reasoning.
     *
     * @param llmModel the LLM model name (e.g., "llama3")
     * @return this builder
     */
    public Builder llmModel(String llmModel) {
      this.llmModel = llmModel;
      return this;
    }

    /**
     * Sets the classifier model name for intent classification. If not set, defaults to the LLM
     * model.
     *
     * @param classifierModel the classifier model name
     * @return this builder
     */
    public Builder classifierModel(String classifierModel) {
      this.classifierModel = classifierModel;
      return this;
    }

    /**
     * Sets the navigation commands that trigger voice-nav mode.
     *
     * @param navigationCommands list of command keywords
     * @return this builder
     */
    public Builder navigationCommands(List<String> navigationCommands) {
      this.navigationCommands = List.copyOf(navigationCommands);
      return this;
    }

    /**
     * Builds the {@link VoiceConfig} instance.
     *
     * @return the immutable VoiceConfig
     */
    public VoiceConfig build() {
      return new VoiceConfig(this);
    }
  }

  @Override
  public String toString() {
    return String.format(
        "VoiceConfig{mode=%s, sttEndpoint='%s', ttsEndpoint='%s', sttModel='%s', ttsModel='%s',"
            + " voice='%s', language='%s', speed=%.1f, llmModel='%s', classifierModel='%s',"
            + " navCommands=%s}",
        voiceMode,
        sttEndpoint,
        ttsEndpoint,
        sttModel,
        ttsModel,
        ttsVoice,
        language,
        ttsSpeed,
        llmModel,
        classifierModel,
        navigationCommands);
  }
}
