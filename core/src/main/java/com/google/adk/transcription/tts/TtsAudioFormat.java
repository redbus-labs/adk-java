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

/**
 * Audio output format specifications for text-to-speech synthesis.
 *
 * @author Sandeep Belgavi
 * @since 2026-08-04
 */
public enum TtsAudioFormat {
  /** WAV format. */
  WAV("wav"),

  /** MP3 format. */
  MP3("mp3"),

  /** OGG Vorbis format. */
  OGG("ogg"),

  /** Raw PCM format. */
  PCM("pcm"),

  /** FLAC lossless format. */
  FLAC("flac");

  private final String value;

  TtsAudioFormat(String value) {
    this.value = value;
  }

  /**
   * Gets the string value of the audio format.
   *
   * @return format string value
   */
  public String getValue() {
    return value;
  }

  /**
   * Parses a string value into a {@link TtsAudioFormat}.
   *
   * @param value the string representation of the format
   * @return the matching {@link TtsAudioFormat}
   * @throws IllegalArgumentException if the value does not match any format
   */
  public static TtsAudioFormat fromString(String value) {
    if (value == null) {
      throw new IllegalArgumentException("Audio format value cannot be null");
    }
    for (TtsAudioFormat format : values()) {
      if (format.value.equalsIgnoreCase(value)) {
        return format;
      }
    }
    throw new IllegalArgumentException("Unknown audio format: " + value);
  }
}
