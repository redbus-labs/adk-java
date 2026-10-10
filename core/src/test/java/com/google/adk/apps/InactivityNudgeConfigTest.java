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

package com.google.adk.apps;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableMap;
import java.time.Duration;
import org.junit.Test;

public final class InactivityNudgeConfigTest {

  @Test
  public void defaultsToDisabled() {
    InactivityNudgeConfig config = InactivityNudgeConfig.disabled();

    assertThat(config.enabled()).isFalse();
    assertThat(config.inactivityTimeout()).isEqualTo(Duration.ofSeconds(5));
    assertThat(config.messageForLocale("en-IN")).isEqualTo("Hey, are you there?");
  }

  @Test
  public void resolvesExactLanguageAndDefaultLocales() {
    InactivityNudgeConfig config =
        InactivityNudgeConfig.builder()
            .defaultLocale("en-IN")
            .localizedMessages(
                ImmutableMap.of(
                    "en-IN", "Are you there?",
                    "hi", "क्या आप वहाँ हैं?",
                    "hi-IN", "क्या आप अभी भी वहाँ हैं?"))
            .build();

    assertThat(config.messageForLocale("hi_IN")).isEqualTo("क्या आप अभी भी वहाँ हैं?");
    assertThat(config.messageForLocale("hi-NP")).isEqualTo("क्या आप वहाँ हैं?");
    assertThat(config.messageForLocale("ta-IN")).isEqualTo("Are you there?");
  }

  @Test
  public void rejectsNonPositiveTimeout() {
    assertThrows(
        IllegalArgumentException.class,
        () -> InactivityNudgeConfig.builder().inactivityTimeout(Duration.ZERO).build());
  }

  @Test
  public void requiresMessageForDefaultLocale() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            InactivityNudgeConfig.builder()
                .defaultLocale("hi")
                .localizedMessages(ImmutableMap.of("en", "Hello"))
                .build());
  }
}
