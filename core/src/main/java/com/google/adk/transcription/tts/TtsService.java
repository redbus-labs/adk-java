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

import com.google.adk.transcription.ServiceHealth;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;

/**
 * Core interface for text-to-speech synthesis services. Implementations provide text-to-audio
 * synthesis capabilities.
 *
 * <p>This interface follows the Strategy Pattern, allowing different TTS providers (Google Cloud
 * TTS, ElevenLabs, Azure, etc.) to be used interchangeably.
 *
 * @author Sandeep Belgavi
 * @since 2026-08-04
 */
public interface TtsService {

  /**
   * Synthesizes text to audio synchronously.
   *
   * @param text the text to synthesize
   * @param config TTS configuration
   * @return synthesized audio bytes
   * @throws TtsException if synthesis fails
   */
  byte[] synthesize(String text, TtsConfig config) throws TtsException;

  /**
   * Synthesizes text to audio asynchronously using RxJava Single.
   *
   * @param text the text to synthesize
   * @param config TTS configuration
   * @return Single containing synthesized audio bytes
   */
  Single<byte[]> synthesizeAsync(String text, TtsConfig config);

  /**
   * Streams synthesized audio chunks for real-time playback. Processes the text and returns audio
   * chunks as they become available.
   *
   * @param text the text to synthesize
   * @param config TTS configuration
   * @return Flowable of audio chunks
   */
  Flowable<byte[]> synthesizeStream(String text, TtsConfig config);

  /**
   * Checks if the service is available and healthy.
   *
   * @return true if service is available
   */
  boolean isAvailable();

  /**
   * Gets service health status with details.
   *
   * @return Health status information
   */
  ServiceHealth getHealth();

  /**
   * Returns the capabilities of this TTS service, including supported formats, voices, models, and
   * constraints.
   *
   * <p>The default implementation returns a capabilities object with all formats supported, no
   * specific voices or models listed, a maximum text length of 4096, and streaming enabled.
   *
   * @return the TTS service capabilities
   */
  default TtsCapabilities capabilities() {
    return TtsCapabilities.builder().build();
  }
}
