/*
 * Copyright 2026 Google LLC
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

package com.google.adk.skills;

/**
 * Exception for {@link SkillSource} implementations to signal recoverable errors that will have the
 * message sending back to the LLM.
 */
public final class SkillSourceException extends Exception {

  public SkillSourceException(String message) {
    super(message);
  }

  public SkillSourceException(String message, Throwable cause) {
    super(message, cause);
  }
}
