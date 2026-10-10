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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Simple handler for voice navigation commands. Matches user input against a set of known command
 * patterns and returns the corresponding response text.
 *
 * <p>Matching uses case-insensitive substring containment: if the normalized input contains a
 * registered pattern keyword, the associated response is returned.
 *
 * @author Sandeep Belgavi
 * @since 2026-08-04
 */
public class VoiceNavigationHandler {

  private final Map<String, String> commands;

  /**
   * Creates a new handler with the given command mappings.
   *
   * @param commands map of command patterns (lowercase keywords) to response text
   */
  public VoiceNavigationHandler(Map<String, String> commands) {
    this.commands = new LinkedHashMap<>();
    if (commands != null) {
      commands.forEach((pattern, response) -> this.commands.put(pattern.toLowerCase(), response));
    }
  }

  /** Creates a new empty handler. */
  public VoiceNavigationHandler() {
    this.commands = new LinkedHashMap<>();
  }

  /**
   * Attempts to match the input against registered navigation commands.
   *
   * <p>Matching is case-insensitive and uses substring containment. The first matching pattern (in
   * insertion order) wins.
   *
   * @param input the user's text input
   * @return the response text if a command matches, or empty if no match
   */
  public Optional<String> handle(String input) {
    if (input == null || input.isEmpty()) {
      return Optional.empty();
    }

    String normalizedInput = input.toLowerCase().trim();

    for (Map.Entry<String, String> entry : commands.entrySet()) {
      if (normalizedInput.contains(entry.getKey())) {
        return Optional.of(entry.getValue());
      }
    }

    return Optional.empty();
  }

  /**
   * Adds a new navigation command pattern and its response.
   *
   * @param pattern the command keyword pattern (will be lowercased)
   * @param response the response text to return when the pattern matches
   */
  public void addCommand(String pattern, String response) {
    if (pattern == null || pattern.isEmpty()) {
      throw new IllegalArgumentException("Pattern cannot be null or empty");
    }
    if (response == null || response.isEmpty()) {
      throw new IllegalArgumentException("Response cannot be null or empty");
    }
    commands.put(pattern.toLowerCase(), response);
  }

  /**
   * Returns the number of registered commands.
   *
   * @return command count
   */
  public int size() {
    return commands.size();
  }

  /**
   * Returns an unmodifiable view of the registered commands.
   *
   * @return command map
   */
  public Map<String, String> getCommands() {
    return Map.copyOf(commands);
  }
}
