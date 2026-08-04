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

import java.util.Optional;

/**
 * Configuration for text-to-speech synthesis services. Uses Builder Pattern for flexible
 * configuration.
 *
 * <p>All fields are immutable once built. Use the builder to create instances.
 *
 * @author Sandeep Belgavi
 * @since 2026-08-04
 */
public final class TtsConfig {
  private final String voice;
  private final String language;
  private final String model;
  private final TtsAudioFormat outputFormat;
  private final int sampleRate;
  private final double speed;
  private final String endpoint;
  private final Optional<String> apiKey;

  private TtsConfig(Builder builder) {
    this.voice = builder.voice;
    this.language = builder.language;
    this.model = builder.model;
    this.outputFormat = builder.outputFormat;
    this.sampleRate = builder.sampleRate;
    this.speed = builder.speed;
    this.endpoint = builder.endpoint;
    this.apiKey = Optional.ofNullable(builder.apiKey);
  }

  public static Builder builder() {
    return new Builder();
  }

  public String getVoice() {
    return voice;
  }

  public String getLanguage() {
    return language;
  }

  public String getModel() {
    return model;
  }

  public TtsAudioFormat getOutputFormat() {
    return outputFormat;
  }

  public int getSampleRate() {
    return sampleRate;
  }

  public double getSpeed() {
    return speed;
  }

  public String getEndpoint() {
    return endpoint;
  }

  public Optional<String> getApiKey() {
    return apiKey;
  }

  /** Builder for TtsConfig. */
  public static class Builder {
    private String voice = "default";
    private String language = "en-US";
    private String model;
    private TtsAudioFormat outputFormat = TtsAudioFormat.WAV;
    private int sampleRate = 24000;
    private double speed = 1.0;
    private String endpoint;
    private String apiKey;

    public Builder voice(String voice) {
      this.voice = voice;
      return this;
    }

    public Builder language(String language) {
      this.language = language;
      return this;
    }

    public Builder model(String model) {
      this.model = model;
      return this;
    }

    public Builder outputFormat(TtsAudioFormat outputFormat) {
      this.outputFormat = outputFormat;
      return this;
    }

    public Builder sampleRate(int sampleRate) {
      if (sampleRate <= 0) {
        throw new IllegalArgumentException("Sample rate must be > 0");
      }
      this.sampleRate = sampleRate;
      return this;
    }

    public Builder speed(double speed) {
      if (speed <= 0) {
        throw new IllegalArgumentException("Speed must be > 0");
      }
      this.speed = speed;
      return this;
    }

    public Builder endpoint(String endpoint) {
      this.endpoint = endpoint;
      return this;
    }

    public Builder apiKey(String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    public TtsConfig build() {
      if (endpoint == null || endpoint.isEmpty()) {
        throw new IllegalArgumentException("Endpoint is required");
      }
      return new TtsConfig(this);
    }
  }

  @Override
  public String toString() {
    return String.format(
        "TtsConfig{endpoint='%s', voice='%s', language='%s', model='%s', format=%s, sampleRate=%d,"
            + " speed=%.1f}",
        endpoint, voice, language, model, outputFormat, sampleRate, speed);
  }
}
