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

/** Configuration for LlmAgent. */
@WorkInProgress
public class LlmAgentConfig extends BaseAgentConfig {
  private String model;
  private String instruction;
  private Boolean disallowTransferToParent;
  private Boolean disallowTransferToPeers;
  private String outputKey;

  // Non-standard accessors with JsonProperty annotations
  @JsonProperty("model")
  public String model() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  @JsonProperty(value = "instruction", required = true)
  public String instruction() {
    return instruction;
  }

  public void setInstruction(String instruction) {
    this.instruction = instruction;
  }

  @JsonProperty("disallow_transfer_to_parent")
  public Boolean disallowTransferToParent() {
    return disallowTransferToParent;
  }

  public void setDisallowTransferToParent(Boolean disallowTransferToParent) {
    this.disallowTransferToParent = disallowTransferToParent;
  }

  @JsonProperty("disallow_transfer_to_peers")
  public Boolean disallowTransferToPeers() {
    return disallowTransferToPeers;
  }

  public void setDisallowTransferToPeers(Boolean disallowTransferToPeers) {
    this.disallowTransferToPeers = disallowTransferToPeers;
  }

  @JsonProperty("output_key")
  public String outputKey() {
    return outputKey;
  }

  public void setOutputKey(String outputKey) {
    this.outputKey = outputKey;
  }
}
