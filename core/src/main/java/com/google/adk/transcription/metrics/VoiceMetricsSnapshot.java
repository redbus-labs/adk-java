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
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Immutable snapshot of voice pipeline metrics at a point in time.
 *
 * <p>Captures cumulative statistics for STT, TTS, and intent classification services including call
 * counts, latency averages, and maximum latencies.
 *
 * <p>Instances are created via the {@link Builder} pattern.
 *
 * @author Sandeep Belgavi
 * @since 2026-08-04
 */
public final class VoiceMetricsSnapshot {

  private final long sttTotalCalls;
  private final long sttSuccessCalls;
  private final long sttFailedCalls;
  private final long sttAvgLatencyMs;
  private final long sttMaxLatencyMs;

  private final long ttsTotalCalls;
  private final long ttsSuccessCalls;
  private final long ttsFailedCalls;
  private final long ttsAvgLatencyMs;
  private final long ttsMaxLatencyMs;

  private final long classifierCalls;
  private final Map<VoiceMode, Long> classifierResults;

  private VoiceMetricsSnapshot(Builder builder) {
    this.sttTotalCalls = builder.sttTotalCalls;
    this.sttSuccessCalls = builder.sttSuccessCalls;
    this.sttFailedCalls = builder.sttFailedCalls;
    this.sttAvgLatencyMs = builder.sttAvgLatencyMs;
    this.sttMaxLatencyMs = builder.sttMaxLatencyMs;
    this.ttsTotalCalls = builder.ttsTotalCalls;
    this.ttsSuccessCalls = builder.ttsSuccessCalls;
    this.ttsFailedCalls = builder.ttsFailedCalls;
    this.ttsAvgLatencyMs = builder.ttsAvgLatencyMs;
    this.ttsMaxLatencyMs = builder.ttsMaxLatencyMs;
    this.classifierCalls = builder.classifierCalls;
    this.classifierResults = Collections.unmodifiableMap(new EnumMap<>(builder.classifierResults));
  }

  /** Returns a new builder for constructing a snapshot. */
  public static Builder builder() {
    return new Builder();
  }

  public long sttTotalCalls() {
    return sttTotalCalls;
  }

  public long sttSuccessCalls() {
    return sttSuccessCalls;
  }

  public long sttFailedCalls() {
    return sttFailedCalls;
  }

  public long sttAvgLatencyMs() {
    return sttAvgLatencyMs;
  }

  public long sttMaxLatencyMs() {
    return sttMaxLatencyMs;
  }

  public long ttsTotalCalls() {
    return ttsTotalCalls;
  }

  public long ttsSuccessCalls() {
    return ttsSuccessCalls;
  }

  public long ttsFailedCalls() {
    return ttsFailedCalls;
  }

  public long ttsAvgLatencyMs() {
    return ttsAvgLatencyMs;
  }

  public long ttsMaxLatencyMs() {
    return ttsMaxLatencyMs;
  }

  public long classifierCalls() {
    return classifierCalls;
  }

  /** Returns an unmodifiable map of classifier results by VoiceMode. */
  public Map<VoiceMode, Long> classifierResults() {
    return classifierResults;
  }

  @Override
  public String toString() {
    return String.format(
        "VoiceMetricsSnapshot{%n"
            + "  STT: total=%d, success=%d, failed=%d, avgLatency=%dms, maxLatency=%dms%n"
            + "  TTS: total=%d, success=%d, failed=%d, avgLatency=%dms, maxLatency=%dms%n"
            + "  Classifier: total=%d, results=%s%n"
            + "}",
        sttTotalCalls,
        sttSuccessCalls,
        sttFailedCalls,
        sttAvgLatencyMs,
        sttMaxLatencyMs,
        ttsTotalCalls,
        ttsSuccessCalls,
        ttsFailedCalls,
        ttsAvgLatencyMs,
        ttsMaxLatencyMs,
        classifierCalls,
        classifierResults);
  }

  /** Builder for {@link VoiceMetricsSnapshot}. */
  public static final class Builder {
    private long sttTotalCalls;
    private long sttSuccessCalls;
    private long sttFailedCalls;
    private long sttAvgLatencyMs;
    private long sttMaxLatencyMs;
    private long ttsTotalCalls;
    private long ttsSuccessCalls;
    private long ttsFailedCalls;
    private long ttsAvgLatencyMs;
    private long ttsMaxLatencyMs;
    private long classifierCalls;
    private Map<VoiceMode, Long> classifierResults = new EnumMap<>(VoiceMode.class);

    private Builder() {}

    public Builder sttTotalCalls(long sttTotalCalls) {
      this.sttTotalCalls = sttTotalCalls;
      return this;
    }

    public Builder sttSuccessCalls(long sttSuccessCalls) {
      this.sttSuccessCalls = sttSuccessCalls;
      return this;
    }

    public Builder sttFailedCalls(long sttFailedCalls) {
      this.sttFailedCalls = sttFailedCalls;
      return this;
    }

    public Builder sttAvgLatencyMs(long sttAvgLatencyMs) {
      this.sttAvgLatencyMs = sttAvgLatencyMs;
      return this;
    }

    public Builder sttMaxLatencyMs(long sttMaxLatencyMs) {
      this.sttMaxLatencyMs = sttMaxLatencyMs;
      return this;
    }

    public Builder ttsTotalCalls(long ttsTotalCalls) {
      this.ttsTotalCalls = ttsTotalCalls;
      return this;
    }

    public Builder ttsSuccessCalls(long ttsSuccessCalls) {
      this.ttsSuccessCalls = ttsSuccessCalls;
      return this;
    }

    public Builder ttsFailedCalls(long ttsFailedCalls) {
      this.ttsFailedCalls = ttsFailedCalls;
      return this;
    }

    public Builder ttsAvgLatencyMs(long ttsAvgLatencyMs) {
      this.ttsAvgLatencyMs = ttsAvgLatencyMs;
      return this;
    }

    public Builder ttsMaxLatencyMs(long ttsMaxLatencyMs) {
      this.ttsMaxLatencyMs = ttsMaxLatencyMs;
      return this;
    }

    public Builder classifierCalls(long classifierCalls) {
      this.classifierCalls = classifierCalls;
      return this;
    }

    public Builder classifierResults(Map<VoiceMode, Long> classifierResults) {
      this.classifierResults = new EnumMap<>(classifierResults);
      return this;
    }

    public VoiceMetricsSnapshot build() {
      return new VoiceMetricsSnapshot(this);
    }
  }
}
