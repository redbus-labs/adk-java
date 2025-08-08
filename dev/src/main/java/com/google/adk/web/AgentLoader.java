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

package com.google.adk.web;

import com.google.adk.agents.BaseAgent;
import java.io.IOException;
import java.util.Map;

/**
 * Interface for loading agents into a registry. Implementations can handle different types of agent
 * sources (compiled Java classes, YAML configurations, etc.) and loading strategies (one-time
 * loading, hot-reloading, etc.).
 */
public interface AgentLoader {

  /**
   * Loads agents and returns them as a map. This method performs a one-time loading operation and
   * returns all discovered agents.
   *
   * @return A map of agent names to their BaseAgent instances
   * @throws IOException if there's an error during loading
   */
  Map<String, BaseAgent> loadAgents() throws IOException;

  /**
   * Starts any ongoing monitoring or hot-reloading functionality. For loaders that support
   * continuous monitoring (like hot-reloading), this method should be called to activate the
   * monitoring process.
   *
   * @throws IOException if there's an error starting the monitoring
   */
  default void start() throws IOException {
    // Default implementation does nothing - for loaders that don't support continuous monitoring
  }

  /**
   * Stops any ongoing monitoring or hot-reloading functionality and cleans up resources. Should be
   * called when the loader is no longer needed.
   */
  default void stop() {
    // Default implementation does nothing - for loaders that don't need cleanup
  }

  /**
   * Returns whether this loader supports hot-reloading functionality.
   *
   * @return true if the loader supports hot-reloading, false otherwise
   */
  default boolean supportsHotReloading() {
    return false;
  }

  /**
   * Returns a description of what type of agents this loader handles.
   *
   * @return A string description of the loader type
   */
  String getLoaderType();
}
