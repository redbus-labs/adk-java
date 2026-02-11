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

// MODIFIED BY Sandeep Belgavi, 2026-02-11
package com.google.adk.models.sarvamai;

import com.google.adk.models.LlmRequest;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.ArrayList;
import java.util.List;

/**
 * This class is used to create a request to the Sarvam AI API.
 *
 * @author Sandeep Belgavi
 * @since 2026-02-11
 */
public class SarvamAiRequest {

  private String model;
  private List<SarvamAiMessage> messages;

  public SarvamAiRequest(String model, LlmRequest llmRequest) {
    this.model = model;
    this.messages = new ArrayList<>();
    for (Content content : llmRequest.contents()) {
      for (Part part : content.parts().get()) {
        this.messages.add(new SarvamAiMessage(content.role().get(), part.text().get()));
      }
    }
  }

  public String getModel() {
    return model;
  }

  public List<SarvamAiMessage> getMessages() {
    return messages;
  }
}
