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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.adk.utils.FeatureDecorator.WorkInProgress;

/** Base configuration for all agents. */
@WorkInProgress
public class BaseAgentConfig {
  private String name;
  private String description = "";

  @JsonProperty(value = "name", required = true)
  public String name() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  @JsonProperty("description")
  public String description() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }
}
