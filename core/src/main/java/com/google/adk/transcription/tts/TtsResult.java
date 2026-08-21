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

import com.google.common.collect.ImmutableMap;
import java.util.Arrays;
import java.util.Map;

/**
 * Result of a text-to-speech synthesis operation containing the audio data and metadata.
 *
 * @author Sandeep Belgavi
 * @since 2026-08-04
 */
public final class TtsResult {
  private final byte[] audioData;
  private final String mimeType;
  private final int sampleRate;
  private final long durationMs;
  private final ImmutableMap<String, String> metadata;

  private TtsResult(Builder builder) {
    this.audioData =
        builder.audioData != null
            ? Arrays.copyOf(builder.audioData, builder.audioData.length)
            : new byte[0];
    this.mimeType = builder.mimeType;
    this.sampleRate = builder.sampleRate;
    this.durationMs = builder.durationMs;
    this.metadata = ImmutableMap.copyOf(builder.metadata);
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Gets the synthesized audio data.
   *
   * @return a copy of the audio byte array
   */
  public byte[] getAudioData() {
    return Arrays.copyOf(audioData, audioData.length);
  }

  public String getMimeType() {
    return mimeType;
  }

  public int getSampleRate() {
    return sampleRate;
  }

  public long getDurationMs() {
    return durationMs;
  }

  public ImmutableMap<String, String> getMetadata() {
    return metadata;
  }

  /** Builder for TtsResult. */
  public static class Builder {
    private byte[] audioData;
    private String mimeType;
    private int sampleRate;
    private long durationMs;
    private Map<String, String> metadata = Map.of();

    public Builder audioData(byte[] audioData) {
      this.audioData = audioData != null ? Arrays.copyOf(audioData, audioData.length) : null;
      return this;
    }

    public Builder mimeType(String mimeType) {
      this.mimeType = mimeType;
      return this;
    }

    public Builder sampleRate(int sampleRate) {
      this.sampleRate = sampleRate;
      return this;
    }

    public Builder durationMs(long durationMs) {
      this.durationMs = durationMs;
      return this;
    }

    public Builder metadata(Map<String, String> metadata) {
      this.metadata = Map.copyOf(metadata);
      return this;
    }

    public TtsResult build() {
      if (audioData == null) {
        throw new IllegalArgumentException("Audio data is required");
      }
      if (mimeType == null || mimeType.isEmpty()) {
        throw new IllegalArgumentException("MIME type is required");
      }
      return new TtsResult(this);
    }
  }

  @Override
  public String toString() {
    return String.format(
        "TtsResult{mimeType='%s', sampleRate=%d, durationMs=%d, audioSize=%d bytes}",
        mimeType, sampleRate, durationMs, audioData.length);
  }
}
