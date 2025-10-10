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

package com.google.adk.models;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.LiveServerContent;
import com.google.genai.types.LiveServerMessage;
import com.google.genai.types.Part;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class GeminiLlmConnectionTest {

  @Test
  public void convertToServerResponse_withInterruptedTrue_mapsInterruptedField() {
    LiveServerContent serverContent =
        LiveServerContent.builder()
            .modelTurn(
                Content.builder().parts(ImmutableList.of(Part.fromText("Model response"))).build())
            .turnComplete(false)
            .interrupted(true)
            .build();

    LiveServerMessage message = LiveServerMessage.builder().serverContent(serverContent).build();

    LlmResponse response = GeminiLlmConnection.convertToServerResponse(message).get();

    assertThat(response.content()).isPresent();
    assertThat(response.content().get().text()).isEqualTo("Model response");
    assertThat(response.partial()).hasValue(true);
    assertThat(response.turnComplete()).hasValue(false);
    assertThat(response.interrupted()).hasValue(true);
  }

  @Test
  public void convertToServerResponse_withInterruptedFalse_mapsInterruptedField() {
    LiveServerContent serverContent =
        LiveServerContent.builder()
            .modelTurn(
                Content.builder()
                    .parts(ImmutableList.of(Part.fromText("Continuing response")))
                    .build())
            .turnComplete(false)
            .interrupted(false)
            .build();

    LiveServerMessage message = LiveServerMessage.builder().serverContent(serverContent).build();

    LlmResponse response = GeminiLlmConnection.convertToServerResponse(message).get();

    assertThat(response.interrupted()).hasValue(false);
    assertThat(response.turnComplete()).hasValue(false);
  }

  @Test
  public void convertToServerResponse_withoutInterruptedField_mapsEmptyOptional() {
    LiveServerContent serverContent =
        LiveServerContent.builder()
            .modelTurn(
                Content.builder().parts(ImmutableList.of(Part.fromText("Normal response"))).build())
            .turnComplete(true)
            .build();

    LiveServerMessage message = LiveServerMessage.builder().serverContent(serverContent).build();

    LlmResponse response = GeminiLlmConnection.convertToServerResponse(message).get();

    assertThat(response.interrupted()).isEmpty();
    assertThat(response.turnComplete()).hasValue(true);
  }
}
