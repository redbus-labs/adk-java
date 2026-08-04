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

/**
 * Defines the voice interaction mode for an agent.
 *
 * <p>Controls how audio input/output is handled relative to the LLM reasoning pipeline.
 *
 * @author Sandeep Belgavi
 * @since 2026-08-04
 */
public enum VoiceMode {

  /**
   * Normal text-based agent interaction. No speech-to-text or text-to-speech processing is applied.
   */
  TEXT_ONLY,

  /**
   * Voice navigation mode: STT → simple command matching → TTS. Bypasses heavy LLM reasoning for
   * faster response to known navigation commands and keywords.
   */
  VOICE_NAVIGATION,

  /**
   * Full voice mode: STT → full LLM reasoning → TTS response. All audio input is transcribed,
   * processed through the complete LLM pipeline, and the response is synthesized back to speech.
   */
  VOICE_FULL,

  /**
   * Automatic mode: classifies the input dynamically and picks the appropriate voice mode. Simple
   * navigation commands are routed through {@link #VOICE_NAVIGATION}, while complex queries are
   * routed through {@link #VOICE_FULL}.
   */
  AUTO
}
