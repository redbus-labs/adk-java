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

import com.google.adk.agents.VoiceMode;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Singleton class that tracks metrics for the voice pipeline.
 *
 * <p>Tracks per-service (STT/TTS) statistics including total calls, successful calls, failed calls,
 * total latency, average latency, and approximate p99 latency (using max tracking).
 *
 * <p>All operations are thread-safe using atomic variables.
 *
 * @author Sandeep Belgavi
 * @since 2026-08-04
 */
public final class VoiceMetrics {

  private static final VoiceMetrics INSTANCE = new VoiceMetrics();

  // STT metrics
  private final AtomicLong sttTotalCalls = new AtomicLong(0);
  private final AtomicLong sttSuccessCalls = new AtomicLong(0);
  private final AtomicLong sttFailedCalls = new AtomicLong(0);
  private final AtomicLong sttTotalLatencyMs = new AtomicLong(0);
  private final AtomicLong sttMaxLatencyMs = new AtomicLong(0);

  // TTS metrics
  private final AtomicLong ttsTotalCalls = new AtomicLong(0);
  private final AtomicLong ttsSuccessCalls = new AtomicLong(0);
  private final AtomicLong ttsFailedCalls = new AtomicLong(0);
  private final AtomicLong ttsTotalLatencyMs = new AtomicLong(0);
  private final AtomicLong ttsMaxLatencyMs = new AtomicLong(0);
  private final AtomicLong ttsTotalTextLength = new AtomicLong(0);

  // Intent classification metrics
  private final AtomicLong classifierCalls = new AtomicLong(0);
  private final AtomicLong classifierTotalLatencyMs = new AtomicLong(0);
  private final Map<VoiceMode, AtomicLong> classifierResults = new EnumMap<>(VoiceMode.class);

  private VoiceMetrics() {
    for (VoiceMode mode : VoiceMode.values()) {
      classifierResults.put(mode, new AtomicLong(0));
    }
  }

  /**
   * Returns the singleton instance of VoiceMetrics.
   *
   * @return the VoiceMetrics instance
   */
  public static VoiceMetrics getInstance() {
    return INSTANCE;
  }

  /**
   * Records a speech-to-text call with its latency and outcome.
   *
   * @param latencyMs the call latency in milliseconds
   * @param success true if the call succeeded, false otherwise
   */
  public void recordSttCall(long latencyMs, boolean success) {
    sttTotalCalls.incrementAndGet();
    sttTotalLatencyMs.addAndGet(latencyMs);
    updateMax(sttMaxLatencyMs, latencyMs);

    if (success) {
      sttSuccessCalls.incrementAndGet();
    } else {
      sttFailedCalls.incrementAndGet();
    }
  }

  /**
   * Records a text-to-speech call with its latency, outcome, and input text length.
   *
   * @param latencyMs the call latency in milliseconds
   * @param success true if the call succeeded, false otherwise
   * @param textLength the length of the input text in characters
   */
  public void recordTtsCall(long latencyMs, boolean success, int textLength) {
    ttsTotalCalls.incrementAndGet();
    ttsTotalLatencyMs.addAndGet(latencyMs);
    ttsTotalTextLength.addAndGet(textLength);
    updateMax(ttsMaxLatencyMs, latencyMs);

    if (success) {
      ttsSuccessCalls.incrementAndGet();
    } else {
      ttsFailedCalls.incrementAndGet();
    }
  }

  /**
   * Records an intent classification call with its latency and result.
   *
   * @param latencyMs the call latency in milliseconds
   * @param result the classified VoiceMode result
   */
  public void recordIntentClassification(long latencyMs, VoiceMode result) {
    classifierCalls.incrementAndGet();
    classifierTotalLatencyMs.addAndGet(latencyMs);
    classifierResults.get(result).incrementAndGet();
  }

  /**
   * Returns an immutable snapshot of the current metrics state.
   *
   * @return a VoiceMetricsSnapshot capturing the current metrics
   */
  public VoiceMetricsSnapshot getSnapshot() {
    long sttTotal = sttTotalCalls.get();
    long ttsTotal = ttsTotalCalls.get();

    long sttAvg = sttTotal > 0 ? sttTotalLatencyMs.get() / sttTotal : 0;
    long ttsAvg = ttsTotal > 0 ? ttsTotalLatencyMs.get() / ttsTotal : 0;

    Map<VoiceMode, Long> classifierResultsSnapshot = new EnumMap<>(VoiceMode.class);
    for (Map.Entry<VoiceMode, AtomicLong> entry : classifierResults.entrySet()) {
      classifierResultsSnapshot.put(entry.getKey(), entry.getValue().get());
    }

    return VoiceMetricsSnapshot.builder()
        .sttTotalCalls(sttTotal)
        .sttSuccessCalls(sttSuccessCalls.get())
        .sttFailedCalls(sttFailedCalls.get())
        .sttAvgLatencyMs(sttAvg)
        .sttMaxLatencyMs(sttMaxLatencyMs.get())
        .ttsTotalCalls(ttsTotal)
        .ttsSuccessCalls(ttsSuccessCalls.get())
        .ttsFailedCalls(ttsFailedCalls.get())
        .ttsAvgLatencyMs(ttsAvg)
        .ttsMaxLatencyMs(ttsMaxLatencyMs.get())
        .classifierCalls(classifierCalls.get())
        .classifierResults(classifierResultsSnapshot)
        .build();
  }

  /**
   * Resets all metrics counters to zero.
   *
   * <p>Note: this is not strictly atomic across all counters, but each individual counter reset is
   * atomic. For precise point-in-time data, use {@link #getSnapshot()} before resetting.
   */
  public void reset() {
    sttTotalCalls.set(0);
    sttSuccessCalls.set(0);
    sttFailedCalls.set(0);
    sttTotalLatencyMs.set(0);
    sttMaxLatencyMs.set(0);

    ttsTotalCalls.set(0);
    ttsSuccessCalls.set(0);
    ttsFailedCalls.set(0);
    ttsTotalLatencyMs.set(0);
    ttsMaxLatencyMs.set(0);
    ttsTotalTextLength.set(0);

    classifierCalls.set(0);
    classifierTotalLatencyMs.set(0);
    for (AtomicLong counter : classifierResults.values()) {
      counter.set(0);
    }
  }

  /**
   * Atomically updates the max value if the new value is greater.
   *
   * @param maxHolder the AtomicLong holding the current max
   * @param newValue the new value to compare
   */
  private void updateMax(AtomicLong maxHolder, long newValue) {
    long currentMax;
    do {
      currentMax = maxHolder.get();
      if (newValue <= currentMax) {
        return;
      }
    } while (!maxHolder.compareAndSet(currentMax, newValue));
  }
}
