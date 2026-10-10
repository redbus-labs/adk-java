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

package com.google.adk.transcription.metrics;

import static com.google.common.truth.Truth.assertThat;

import com.google.adk.agents.VoiceMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link VoiceMetrics}.
 *
 * @author Sandeep Belgavi
 * @since 2026-08-04
 */
@DisplayName("VoiceMetrics Tests")
class VoiceMetricsTest {

  private VoiceMetrics metrics;

  @BeforeEach
  void setUp() {
    metrics = VoiceMetrics.getInstance();
    metrics.reset();
  }

  @Test
  @DisplayName("recordSttCall increments total and success counters")
  void testRecordSttCallSuccess() {
    metrics.recordSttCall(100, true);
    metrics.recordSttCall(200, true);

    VoiceMetricsSnapshot snapshot = metrics.getSnapshot();
    assertThat(snapshot.sttTotalCalls()).isEqualTo(2);
    assertThat(snapshot.sttSuccessCalls()).isEqualTo(2);
    assertThat(snapshot.sttFailedCalls()).isEqualTo(0);
  }

  @Test
  @DisplayName("recordSttCall increments total and failure counters")
  void testRecordSttCallFailure() {
    metrics.recordSttCall(150, false);

    VoiceMetricsSnapshot snapshot = metrics.getSnapshot();
    assertThat(snapshot.sttTotalCalls()).isEqualTo(1);
    assertThat(snapshot.sttSuccessCalls()).isEqualTo(0);
    assertThat(snapshot.sttFailedCalls()).isEqualTo(1);
  }

  @Test
  @DisplayName("recordTtsCall increments total and success counters")
  void testRecordTtsCallSuccess() {
    metrics.recordTtsCall(50, true, 100);
    metrics.recordTtsCall(75, true, 200);

    VoiceMetricsSnapshot snapshot = metrics.getSnapshot();
    assertThat(snapshot.ttsTotalCalls()).isEqualTo(2);
    assertThat(snapshot.ttsSuccessCalls()).isEqualTo(2);
    assertThat(snapshot.ttsFailedCalls()).isEqualTo(0);
  }

  @Test
  @DisplayName("recordTtsCall increments total and failure counters")
  void testRecordTtsCallFailure() {
    metrics.recordTtsCall(30, false, 50);

    VoiceMetricsSnapshot snapshot = metrics.getSnapshot();
    assertThat(snapshot.ttsTotalCalls()).isEqualTo(1);
    assertThat(snapshot.ttsSuccessCalls()).isEqualTo(0);
    assertThat(snapshot.ttsFailedCalls()).isEqualTo(1);
  }

  @Test
  @DisplayName("getSnapshot returns correct average and max latency")
  void testGetSnapshotLatencyValues() {
    metrics.recordSttCall(100, true);
    metrics.recordSttCall(200, true);
    metrics.recordSttCall(300, true);

    VoiceMetricsSnapshot snapshot = metrics.getSnapshot();
    assertThat(snapshot.sttTotalCalls()).isEqualTo(3);
    assertThat(snapshot.sttAvgLatencyMs()).isEqualTo(200); // (100+200+300)/3
    assertThat(snapshot.sttMaxLatencyMs()).isEqualTo(300);
  }

  @Test
  @DisplayName("getSnapshot returns correct TTS latency values")
  void testGetSnapshotTtsLatencyValues() {
    metrics.recordTtsCall(50, true, 10);
    metrics.recordTtsCall(150, true, 20);

    VoiceMetricsSnapshot snapshot = metrics.getSnapshot();
    assertThat(snapshot.ttsTotalCalls()).isEqualTo(2);
    assertThat(snapshot.ttsAvgLatencyMs()).isEqualTo(100); // (50+150)/2
    assertThat(snapshot.ttsMaxLatencyMs()).isEqualTo(150);
  }

  @Test
  @DisplayName("getSnapshot returns classifier results")
  void testGetSnapshotClassifierResults() {
    metrics.recordIntentClassification(10, VoiceMode.VOICE_NAVIGATION);
    metrics.recordIntentClassification(20, VoiceMode.VOICE_NAVIGATION);
    metrics.recordIntentClassification(30, VoiceMode.VOICE_FULL);

    VoiceMetricsSnapshot snapshot = metrics.getSnapshot();
    assertThat(snapshot.classifierCalls()).isEqualTo(3);
    assertThat(snapshot.classifierResults().get(VoiceMode.VOICE_NAVIGATION)).isEqualTo(2);
    assertThat(snapshot.classifierResults().get(VoiceMode.VOICE_FULL)).isEqualTo(1);
  }

  @Test
  @DisplayName("reset clears all counters to zero")
  void testResetClearsEverything() {
    metrics.recordSttCall(100, true);
    metrics.recordSttCall(200, false);
    metrics.recordTtsCall(50, true, 100);
    metrics.recordTtsCall(75, false, 200);
    metrics.recordIntentClassification(10, VoiceMode.VOICE_NAVIGATION);

    // Reset
    metrics.reset();

    VoiceMetricsSnapshot snapshot = metrics.getSnapshot();
    assertThat(snapshot.sttTotalCalls()).isEqualTo(0);
    assertThat(snapshot.sttSuccessCalls()).isEqualTo(0);
    assertThat(snapshot.sttFailedCalls()).isEqualTo(0);
    assertThat(snapshot.sttAvgLatencyMs()).isEqualTo(0);
    assertThat(snapshot.sttMaxLatencyMs()).isEqualTo(0);
    assertThat(snapshot.ttsTotalCalls()).isEqualTo(0);
    assertThat(snapshot.ttsSuccessCalls()).isEqualTo(0);
    assertThat(snapshot.ttsFailedCalls()).isEqualTo(0);
    assertThat(snapshot.ttsAvgLatencyMs()).isEqualTo(0);
    assertThat(snapshot.ttsMaxLatencyMs()).isEqualTo(0);
    assertThat(snapshot.classifierCalls()).isEqualTo(0);
  }

  @Test
  @DisplayName("thread safety: concurrent recording does not lose counts")
  void testThreadSafety() throws Exception {
    int threadCount = 10;
    int callsPerThread = 100;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch latch = new CountDownLatch(threadCount);
    List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

    for (int t = 0; t < threadCount; t++) {
      final int threadId = t;
      executor.submit(
          () -> {
            try {
              for (int i = 0; i < callsPerThread; i++) {
                if (threadId % 2 == 0) {
                  metrics.recordSttCall(50, true);
                } else {
                  metrics.recordTtsCall(30, true, 10);
                }
              }
            } catch (Throwable e) {
              errors.add(e);
            } finally {
              latch.countDown();
            }
          });
    }

    latch.await(10, TimeUnit.SECONDS);
    executor.shutdown();

    assertThat(errors).isEmpty();

    VoiceMetricsSnapshot snapshot = metrics.getSnapshot();
    // 5 threads recording STT (threadId 0,2,4,6,8), 5 recording TTS (threadId 1,3,5,7,9)
    assertThat(snapshot.sttTotalCalls()).isEqualTo(5L * callsPerThread);
    assertThat(snapshot.ttsTotalCalls()).isEqualTo(5L * callsPerThread);
  }

  @Test
  @DisplayName("singleton instance is consistent")
  void testSingletonInstance() {
    VoiceMetrics instance1 = VoiceMetrics.getInstance();
    VoiceMetrics instance2 = VoiceMetrics.getInstance();

    assertThat(instance1).isSameInstanceAs(instance2);
  }

  @Test
  @DisplayName("max latency tracks the highest value")
  void testMaxLatencyTracking() {
    metrics.recordSttCall(100, true);
    metrics.recordSttCall(500, true);
    metrics.recordSttCall(200, true);

    VoiceMetricsSnapshot snapshot = metrics.getSnapshot();
    assertThat(snapshot.sttMaxLatencyMs()).isEqualTo(500);
  }
}
