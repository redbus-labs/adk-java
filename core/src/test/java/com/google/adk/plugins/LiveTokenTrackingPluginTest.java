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
  public void onEventCallback_keepsLatestCumulativeTotals() {
    plugin
        .onEventCallback(
            mockInvocationContext,
            eventWithUsage(
                GenerateContentResponseUsageMetadata.builder()
                    .promptTokenCount(10)
                    .candidatesTokenCount(5)
                    .totalTokenCount(15)
                    .build()))
        .blockingGet();
    plugin
        .onEventCallback(
            mockInvocationContext,
            eventWithUsage(
                GenerateContentResponseUsageMetadata.builder()
                    .promptTokenCount(10)
                    .candidatesTokenCount(20)
                    .totalTokenCount(30)
                    .build()))
        .blockingGet();

    LiveTokenTrackingPlugin.Usage usage = plugin.usageFor(INVOCATION_ID);
    assertThat(usage).isNotNull();
    // Live reports running totals, so the latest values win rather than summing.
    assertThat(usage.promptTokenCount()).isEqualTo(10);
    assertThat(usage.candidatesTokenCount()).isEqualTo(20);
    assertThat(usage.totalTokenCount()).isEqualTo(30);
  }

  @Test
  public void onEventCallback_capturesAudioModalityBreakdown() {
    plugin
        .onEventCallback(
            mockInvocationContext,
            eventWithUsage(
                GenerateContentResponseUsageMetadata.builder()
                    .promptTokensDetails(
                        ImmutableList.of(
                            ModalityTokenCount.builder()
                                .modality(new MediaModality(MediaModality.Known.AUDIO))
                                .tokenCount(42)
                                .build()))
                    .build()))
        .blockingGet();

    LiveTokenTrackingPlugin.Usage usage = plugin.usageFor(INVOCATION_ID);
    assertThat(usage).isNotNull();
    assertThat(usage.promptTokensByModality()).containsEntry("AUDIO", 42);
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
