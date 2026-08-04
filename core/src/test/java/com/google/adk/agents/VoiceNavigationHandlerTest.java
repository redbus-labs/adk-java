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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link VoiceNavigationHandler}.
 *
 * @author Sandeep Belgavi
 * @since 2026-08-04
 */
@DisplayName("VoiceNavigationHandler Tests")
class VoiceNavigationHandlerTest {

  private VoiceNavigationHandler handler;

  @BeforeEach
  void setUp() {
    Map<String, String> commands = new LinkedHashMap<>();
    commands.put("go back", "Navigating back");
    commands.put("next page", "Going to next page");
    commands.put("scroll down", "Scrolling down");
    commands.put("open menu", "Opening menu");
    handler = new VoiceNavigationHandler(commands);
  }

  @Test
  @DisplayName("handle returns response for matching command")
  void testHandleMatchingCommand() {
    Optional<String> result = handler.handle("go back");

    assertThat(result.isPresent()).isTrue();
    assertThat(result.get()).isEqualTo("Navigating back");
  }

  @Test
  @DisplayName("handle returns response for matching substring")
  void testHandleMatchingSubstring() {
    Optional<String> result = handler.handle("please go back now");

    assertThat(result.isPresent()).isTrue();
    assertThat(result.get()).isEqualTo("Navigating back");
  }

  @Test
  @DisplayName("handle returns empty for non-matching input")
  void testHandleNoMatch() {
    Optional<String> result = handler.handle("tell me a joke");

    assertThat(result.isPresent()).isFalse();
  }

  @Test
  @DisplayName("handle is case-insensitive")
  void testHandleCaseInsensitive() {
    Optional<String> result = handler.handle("GO BACK");

    assertThat(result.isPresent()).isTrue();
    assertThat(result.get()).isEqualTo("Navigating back");
  }

  @Test
  @DisplayName("handle is case-insensitive with mixed case")
  void testHandleMixedCase() {
    Optional<String> result = handler.handle("Open Menu Please");

    assertThat(result.isPresent()).isTrue();
    assertThat(result.get()).isEqualTo("Opening menu");
  }

  @Test
  @DisplayName("handle returns empty for null input")
  void testHandleNullInput() {
    Optional<String> result = handler.handle(null);

    assertThat(result.isPresent()).isFalse();
  }

  @Test
  @DisplayName("handle returns empty for empty input")
  void testHandleEmptyInput() {
    Optional<String> result = handler.handle("");

    assertThat(result.isPresent()).isFalse();
  }

  @Test
  @DisplayName("handle returns first match in insertion order")
  void testHandleFirstMatchWins() {
    Map<String, String> commands = new LinkedHashMap<>();
    commands.put("scroll", "Scrolling generic");
    commands.put("scroll down", "Scrolling down specific");
    VoiceNavigationHandler multiHandler = new VoiceNavigationHandler(commands);

    Optional<String> result = multiHandler.handle("scroll down please");

    // "scroll" matches first due to insertion order
    assertThat(result.isPresent()).isTrue();
    assertThat(result.get()).isEqualTo("Scrolling generic");
  }

  @Test
  @DisplayName("addCommand registers new command dynamically")
  void testAddCommand() {
    handler.addCommand("volume up", "Increasing volume");

    Optional<String> result = handler.handle("volume up");
    assertThat(result.isPresent()).isTrue();
    assertThat(result.get()).isEqualTo("Increasing volume");
  }

  @Test
  @DisplayName("addCommand throws on null pattern")
  void testAddCommandNullPattern() {
    assertThrows(IllegalArgumentException.class, () -> handler.addCommand(null, "response"));
  }

  @Test
  @DisplayName("addCommand throws on empty pattern")
  void testAddCommandEmptyPattern() {
    assertThrows(IllegalArgumentException.class, () -> handler.addCommand("", "response"));
  }

  @Test
  @DisplayName("addCommand throws on null response")
  void testAddCommandNullResponse() {
    assertThrows(IllegalArgumentException.class, () -> handler.addCommand("test", null));
  }

  @Test
  @DisplayName("addCommand throws on empty response")
  void testAddCommandEmptyResponse() {
    assertThrows(IllegalArgumentException.class, () -> handler.addCommand("test", ""));
  }

  @Test
  @DisplayName("size returns correct command count")
  void testSize() {
    assertThat(handler.size()).isEqualTo(4);
  }

  @Test
  @DisplayName("size updates after addCommand")
  void testSizeAfterAdd() {
    handler.addCommand("new command", "new response");
    assertThat(handler.size()).isEqualTo(5);
  }

  @Test
  @DisplayName("getCommands returns all registered commands")
  void testGetCommands() {
    Map<String, String> commands = handler.getCommands();
    assertThat(commands).hasSize(4);
    assertThat(commands).containsKey("go back");
    assertThat(commands).containsKey("next page");
  }

  @Test
  @DisplayName("empty handler returns empty for any input")
  void testEmptyHandler() {
    VoiceNavigationHandler emptyHandler = new VoiceNavigationHandler();

    Optional<String> result = emptyHandler.handle("go back");
    assertThat(result.isPresent()).isFalse();
    assertThat(emptyHandler.size()).isEqualTo(0);
  }

  @Test
  @DisplayName("constructor handles null commands map")
  void testConstructorNullMap() {
    VoiceNavigationHandler nullHandler = new VoiceNavigationHandler(null);

    assertThat(nullHandler.size()).isEqualTo(0);
    assertThat(nullHandler.handle("anything").isPresent()).isFalse();
  }
}
