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

package com.google.adk.plugins;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.when;

import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.MediaModality;
import com.google.genai.types.ModalityTokenCount;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class LiveTokenTrackingPluginTest {

  @Rule public MockitoRule mockitoRule = MockitoJUnit.rule();

  private static final String INVOCATION_ID = "invocation-1";

  private final LiveTokenTrackingPlugin plugin = new LiveTokenTrackingPlugin();
  @Mock private InvocationContext mockInvocationContext;

  @Before
  public void setUp() {
    when(mockInvocationContext.invocationId()).thenReturn(INVOCATION_ID);
  }

  private static Event eventWithUsage(GenerateContentResponseUsageMetadata usageMetadata) {
    return Event.builder()
        .id(Event.generateEventId())
        .author("model")
        .usageMetadata(usageMetadata)
        .build();
  }

  @Test
  public void onEventCallback_sumsPerTurnTotalsAcrossEvents() {
    // Mirrors observed Gemini Live behavior: one usageMetadata event per turn, each reporting that
    // turn's own usage. Session totals are the sum of the per-turn values.
    plugin
        .onEventCallback(
            mockInvocationContext,
            eventWithUsage(
                GenerateContentResponseUsageMetadata.builder()
                    .promptTokenCount(185)
                    .candidatesTokenCount(653)
                    .totalTokenCount(838)
                    .build()))
        .blockingGet();
    plugin
        .onEventCallback(
            mockInvocationContext,
            eventWithUsage(
                GenerateContentResponseUsageMetadata.builder()
                    .promptTokenCount(942)
                    .candidatesTokenCount(241)
                    .totalTokenCount(1183)
                    .build()))
        .blockingGet();

    LiveTokenTrackingPlugin.Usage usage = plugin.usageFor(INVOCATION_ID);
    assertThat(usage).isNotNull();
    assertThat(usage.promptTokenCount()).isEqualTo(185 + 942);
    assertThat(usage.candidatesTokenCount()).isEqualTo(653 + 241);
    assertThat(usage.totalTokenCount()).isEqualTo(838 + 1183);
  }

  @Test
  public void onEventCallback_sumsAudioModalityBreakdownAcrossEvents() {
    plugin
        .onEventCallback(
            mockInvocationContext,
            eventWithUsage(
                GenerateContentResponseUsageMetadata.builder()
                    .candidatesTokensDetails(
                        ImmutableList.of(
                            ModalityTokenCount.builder()
                                .modality(new MediaModality(MediaModality.Known.AUDIO))
                                .tokenCount(653)
                                .build()))
                    .build()))
        .blockingGet();
    plugin
        .onEventCallback(
            mockInvocationContext,
            eventWithUsage(
                GenerateContentResponseUsageMetadata.builder()
                    .candidatesTokensDetails(
                        ImmutableList.of(
                            ModalityTokenCount.builder()
                                .modality(new MediaModality(MediaModality.Known.AUDIO))
                                .tokenCount(241)
                                .build()))
                    .build()))
        .blockingGet();

    LiveTokenTrackingPlugin.Usage usage = plugin.usageFor(INVOCATION_ID);
    assertThat(usage).isNotNull();
    assertThat(usage.candidatesTokensByModality()).containsEntry("AUDIO", 653 + 241);
  }

  @Test
  public void onEventCallback_passesEventThroughUnchanged() {
    Event event =
        eventWithUsage(GenerateContentResponseUsageMetadata.builder().totalTokenCount(7).build());

    // Empty result means the original event flows through unmodified downstream.
    assertThat(plugin.onEventCallback(mockInvocationContext, event).blockingGet()).isNull();
  }

  @Test
  public void afterRunCallback_releasesInvocationState() {
    plugin
        .onEventCallback(
            mockInvocationContext,
            eventWithUsage(
                GenerateContentResponseUsageMetadata.builder().totalTokenCount(99).build()))
        .blockingGet();
    assertThat(plugin.usageFor(INVOCATION_ID)).isNotNull();

    plugin.afterRunCallback(mockInvocationContext).blockingAwait();

    assertThat(plugin.usageFor(INVOCATION_ID)).isNull();
  }
}
