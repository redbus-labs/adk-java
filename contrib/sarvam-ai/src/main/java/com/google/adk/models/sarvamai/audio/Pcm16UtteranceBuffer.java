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

package com.google.adk.models.sarvamai.audio;

import java.io.ByteArrayOutputStream;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Bounded PCM16 capture with energy-based speech detection and a silence watchdog.
 *
 * <p>This component detects audio boundaries only. Speech energy is not proof of a user request;
 * callers own transcription, turn scheduling and interruption policy. End callbacks run under the
 * capture monitor and may inspect or drain the buffer. Callers coordinating capture with other
 * state can supply their existing monitor. Close each instance when capture ends.
 */
public final class Pcm16UtteranceBuffer implements AutoCloseable {
  private static final ScheduledThreadPoolExecutor WATCHDOGS = createWatchdogs();
  private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
  private final double threshold;
  private final long silenceMs;
  private final int minimumSpeechBytes;
  private final int maximumBytes;
  private final int idleBytes;
  private final Runnable onEnd;
  private final LongSupplier clock;
  private final Object monitor;
  private final ScheduledFuture<?> watchdog;
  private boolean active;
  private boolean closed;
  private long lastSpeechMs;
  private int speechBytes;

  private static ScheduledThreadPoolExecutor createWatchdogs() {
    var executor =
        new ScheduledThreadPoolExecutor(
            2,
            runnable -> {
              Thread thread = new Thread(runnable, "adk-pcm-vad");
              thread.setDaemon(true);
              return thread;
            });
    executor.setRemoveOnCancelPolicy(true);
    return executor;
  }

  public Pcm16UtteranceBuffer(
      double threshold,
      long silenceMs,
      int minimumSpeechBytes,
      int maximumBytes,
      int idleBytes,
      Runnable onEnd) {
    this(
        threshold,
        silenceMs,
        minimumSpeechBytes,
        maximumBytes,
        idleBytes,
        onEnd,
        System::currentTimeMillis);
  }

  /** Constructor accepting a clock for deterministic capture tests. */
  public Pcm16UtteranceBuffer(
      double threshold,
      long silenceMs,
      int minimumSpeechBytes,
      int maximumBytes,
      int idleBytes,
      Runnable onEnd,
      LongSupplier clock) {
    this(
        threshold,
        silenceMs,
        minimumSpeechBytes,
        maximumBytes,
        idleBytes,
        onEnd,
        clock,
        new Object());
  }

  /** Shares a monitor with the caller to make capture boundaries atomic with its turn state. */
  public Pcm16UtteranceBuffer(
      double threshold,
      long silenceMs,
      int minimumSpeechBytes,
      int maximumBytes,
      int idleBytes,
      Runnable onEnd,
      LongSupplier clock,
      Object monitor) {
    if (!Double.isFinite(threshold)
        || threshold < 0
        || silenceMs <= 0
        || minimumSpeechBytes <= 0
        || maximumBytes < minimumSpeechBytes
        || idleBytes < 0) {
      throw new IllegalArgumentException("Invalid PCM capture settings");
    }
    this.threshold = threshold;
    this.silenceMs = silenceMs;
    this.minimumSpeechBytes = minimumSpeechBytes;
    this.maximumBytes = maximumBytes;
    this.idleBytes = idleBytes;
    this.onEnd = Objects.requireNonNull(onEnd);
    this.clock = Objects.requireNonNull(clock);
    this.monitor = Objects.requireNonNull(monitor);
    watchdog = WATCHDOGS.scheduleAtFixedRate(this::checkSilence, 200, 200, TimeUnit.MILLISECONDS);
  }

  public boolean isSpeechChunk(byte[] pcm) {
    return Pcm16Audio.meanAbsoluteAmplitude(pcm) >= threshold;
  }

  public void append(byte[] pcm) {
    if (pcm == null || pcm.length == 0) return;
    boolean ended;
    synchronized (monitor) {
      if (closed) return;
      boolean speech = isSpeechChunk(pcm);
      if (!active && !speech && buffer.size() > idleBytes) buffer.reset();
      if (pcm.length > maximumBytes - buffer.size()) {
        // Preserve the bounded utterance until the caller can consume it.
        ended = true;
      } else {
        buffer.write(pcm, 0, pcm.length);
        if (speech) {
          active = true;
          lastSpeechMs = clock.getAsLong();
          speechBytes += pcm.length;
        }
        ended = silenceElapsed();
        if (ended) active = false;
      }
      if (ended) onEnd.run();
    }
  }

  private boolean silenceElapsed() {
    return active
        && clock.getAsLong() - lastSpeechMs >= silenceMs
        && speechBytes >= minimumSpeechBytes;
  }

  /** Checks silence even when the audio source has stopped sending chunks. */
  public void checkSilence() {
    boolean ended;
    synchronized (monitor) {
      ended = !closed && silenceElapsed();
      if (ended) active = false;
      if (ended) onEnd.run();
    }
  }

  public int size() {
    synchronized (monitor) {
      return buffer.size();
    }
  }

  public boolean isSpeechActive() {
    synchronized (monitor) {
      return active;
    }
  }

  public byte[] drain() {
    synchronized (monitor) {
      byte[] pcm = buffer.toByteArray();
      reset();
      return pcm;
    }
  }

  public void reset() {
    synchronized (monitor) {
      buffer.reset();
      active = false;
      lastSpeechMs = 0;
      speechBytes = 0;
    }
  }

  @Override
  public void close() {
    synchronized (monitor) {
      closed = true;
      watchdog.cancel(false);
      reset();
    }
  }
}
