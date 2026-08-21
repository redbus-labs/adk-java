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

import com.google.adk.transcription.ServiceHealth;
import com.google.adk.transcription.ServiceType;
import com.google.adk.transcription.TranscriptionConfig;
import com.google.adk.transcription.TranscriptionEvent;
import com.google.adk.transcription.TranscriptionException;
import com.google.adk.transcription.TranscriptionResult;
import com.google.adk.transcription.TranscriptionService;
import com.google.adk.transcription.tts.TtsConfig;
import com.google.adk.transcription.tts.TtsException;
import com.google.adk.transcription.tts.TtsService;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.util.concurrent.TimeUnit;

/**
 * Decorator that adds metrics tracking to any {@link TtsService} or {@link TranscriptionService}.
 *
 * <p>Wraps delegate service calls to automatically record timing and success/failure metrics via
 * {@link VoiceMetrics}.
 *
 * @author Sandeep Belgavi
 * @since 2026-08-04
 */
public final class VoiceMetricsInterceptor {

  private VoiceMetricsInterceptor() {}

  /**
   * Wraps a TtsService with metrics instrumentation.
   *
   * @param delegate the TtsService to wrap
   * @return a TtsService that records metrics for every call
   */
  public static TtsService wrapTts(TtsService delegate) {
    return new InstrumentedTtsService(delegate);
  }

  /**
   * Wraps a TranscriptionService with metrics instrumentation.
   *
   * @param delegate the TranscriptionService to wrap
   * @return a TranscriptionService that records metrics for every call
   */
  public static TranscriptionService wrapStt(TranscriptionService delegate) {
    return new InstrumentedTranscriptionService(delegate);
  }

  /** TTS service wrapper that records metrics on every call. */
  private static final class InstrumentedTtsService implements TtsService {

    private final TtsService delegate;
    private final VoiceMetrics metrics = VoiceMetrics.getInstance();

    InstrumentedTtsService(TtsService delegate) {
      this.delegate = delegate;
    }

    @Override
    public byte[] synthesize(String text, TtsConfig config) throws TtsException {
      long startNanos = System.nanoTime();
      boolean success = false;
      try {
        byte[] result = delegate.synthesize(text, config);
        success = true;
        return result;
      } finally {
        long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        metrics.recordTtsCall(latencyMs, success, text != null ? text.length() : 0);
      }
    }

    @Override
    public Single<byte[]> synthesizeAsync(String text, TtsConfig config) {
      return Single.defer(
          () -> {
            long startNanos = System.nanoTime();
            return delegate
                .synthesizeAsync(text, config)
                .doOnSuccess(
                    bytes -> {
                      long latencyMs =
                          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                      metrics.recordTtsCall(latencyMs, true, text != null ? text.length() : 0);
                    })
                .doOnError(
                    error -> {
                      long latencyMs =
                          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                      metrics.recordTtsCall(latencyMs, false, text != null ? text.length() : 0);
                    });
          });
    }

    @Override
    public Flowable<byte[]> synthesizeStream(String text, TtsConfig config) {
      return Flowable.defer(
          () -> {
            long startNanos = System.nanoTime();
            return delegate
                .synthesizeStream(text, config)
                .doOnComplete(
                    () -> {
                      long latencyMs =
                          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                      metrics.recordTtsCall(latencyMs, true, text != null ? text.length() : 0);
                    })
                .doOnError(
                    error -> {
                      long latencyMs =
                          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                      metrics.recordTtsCall(latencyMs, false, text != null ? text.length() : 0);
                    });
          });
    }

    @Override
    public boolean isAvailable() {
      return delegate.isAvailable();
    }

    @Override
    public ServiceHealth getHealth() {
      return delegate.getHealth();
    }
  }

  /** Transcription service wrapper that records metrics on every call. */
  private static final class InstrumentedTranscriptionService implements TranscriptionService {

    private final TranscriptionService delegate;
    private final VoiceMetrics metrics = VoiceMetrics.getInstance();

    InstrumentedTranscriptionService(TranscriptionService delegate) {
      this.delegate = delegate;
    }

    @Override
    public TranscriptionResult transcribe(byte[] audioData, TranscriptionConfig config)
        throws TranscriptionException {
      long startNanos = System.nanoTime();
      boolean success = false;
      try {
        TranscriptionResult result = delegate.transcribe(audioData, config);
        success = true;
        return result;
      } finally {
        long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        metrics.recordSttCall(latencyMs, success);
      }
    }

    @Override
    public Single<TranscriptionResult> transcribeAsync(
        byte[] audioData, TranscriptionConfig config) {
      return Single.defer(
          () -> {
            long startNanos = System.nanoTime();
            return delegate
                .transcribeAsync(audioData, config)
                .doOnSuccess(
                    result -> {
                      long latencyMs =
                          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                      metrics.recordSttCall(latencyMs, true);
                    })
                .doOnError(
                    error -> {
                      long latencyMs =
                          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                      metrics.recordSttCall(latencyMs, false);
                    });
          });
    }

    @Override
    public Flowable<TranscriptionEvent> transcribeStream(
        Flowable<byte[]> audioStream, TranscriptionConfig config) {
      return Flowable.defer(
          () -> {
            long startNanos = System.nanoTime();
            return delegate
                .transcribeStream(audioStream, config)
                .doOnComplete(
                    () -> {
                      long latencyMs =
                          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                      metrics.recordSttCall(latencyMs, true);
                    })
                .doOnError(
                    error -> {
                      long latencyMs =
                          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                      metrics.recordSttCall(latencyMs, false);
                    });
          });
    }

    @Override
    public boolean isAvailable() {
      return delegate.isAvailable();
    }

    @Override
    public ServiceType getServiceType() {
      return delegate.getServiceType();
    }

    @Override
    public ServiceHealth getHealth() {
      return delegate.getHealth();
    }
  }
}
