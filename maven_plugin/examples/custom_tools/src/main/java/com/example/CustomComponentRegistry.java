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

package com.example;

import com.google.adk.utils.ComponentRegistry;

/**
 * Example of a custom ComponentRegistry that pre-registers custom tools.
 *
 * <p>This registry extends the base ComponentRegistry and adds custom components:
 *
 * <ul>
 *   <li>GetWeatherTool - A custom tool for retrieving weather information
 * </ul>
 *
 * <p>Usage with the maven plugin:
 *
 * <pre>{@code
 * mvn google-adk:web ... -Dregistry=com.example.CustomComponentRegistry
 * }</pre>
 */
public class CustomComponentRegistry extends ComponentRegistry {

  /** Singleton instance for easy access */
  public static final CustomComponentRegistry INSTANCE = new CustomComponentRegistry();

  /** Private constructor to initialize custom components */
  public CustomComponentRegistry() {
    super(); // Initialize base ADK components
    register("tools.get_weather", GetWeatherTool.INSTANCE);
  }
}
