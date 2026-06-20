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

import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.ModalityTokenCount;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plugin that tracks token usage emitted during a run, including bidirectional (BIDI) live sessions
 * such as Gemini Live audio.
 *
 * <p>Token usage for a live session arrives on dedicated {@code usageMetadata} events (separate
 * from the audio/content events) via {@link #onEventCallback}. Gemini Live emits one such event per
 * turn, each reporting that turn's own usage rather than a session-cumulative running total, so
 * this plugin sums the per-turn values to obtain session totals. When the run completes, {@link
 * #afterRunCallback} logs the final totals and releases the per-invocation state.
 *
 * <p>Register it on the runner like any other plugin to get per-session token accounting for both
 * live and non-live runs.
 */
public final class LiveTokenTrackingPlugin extends BasePlugin {

  private static final Logger logger = LoggerFactory.getLogger(LiveTokenTrackingPlugin.class);

  private final Map<String, Usage> usageByInvocation = new ConcurrentHashMap<>();

  public LiveTokenTrackingPlugin() {
    super("live_token_tracking_plugin");
  }

  public LiveTokenTrackingPlugin(String name) {
    super(name);
  }

  @Override
  public Maybe<Event> onEventCallback(InvocationContext invocationContext, Event event) {
    event
        .usageMetadata()
        .ifPresent(
            usageMetadata ->
                usageByInvocation
                    .computeIfAbsent(invocationContext.invocationId(), unused -> new Usage())
                    .update(usageMetadata));
    // Return empty so the original event flows through unchanged; this plugin only observes.
    return Maybe.empty();
  }

  @Override
  public Completable afterRunCallback(InvocationContext invocationContext) {
    return Completable.fromRunnable(
        () -> {
          Usage usage = usageByInvocation.remove(invocationContext.invocationId());
          if (usage == null) {
            return;
          }
          logger.info("Token usage for invocation {}: {}", invocationContext.invocationId(), usage);
        });
  }

  /**
   * Returns the accumulated token usage for the given invocation, or {@code null} if none has been
   * recorded. Intended for tests and programmatic inspection before the run completes (after which
   * {@link #afterRunCallback} releases the state).
   */
  public Usage usageFor(String invocationId) {
    return usageByInvocation.get(invocationId);
  }

  /**
   * Mutable accumulator that sums token counts across all usageMetadata events seen for a single
   * invocation. Gemini Live emits one usageMetadata event per turn, each reporting that turn's own
   * usage (not a session-cumulative running total), so per-turn values are summed to obtain the
   * session totals.
   */
  public static final class Usage {
    private Integer promptTokenCount;
    private Integer candidatesTokenCount;
    private Integer totalTokenCount;
    private Integer thoughtsTokenCount;
    private Integer cachedContentTokenCount;
    private final Map<String, Integer> promptTokensByModality = new LinkedHashMap<>();
    private final Map<String, Integer> candidatesTokensByModality = new LinkedHashMap<>();

    synchronized void update(GenerateContentResponseUsageMetadata usageMetadata) {
      usageMetadata
          .promptTokenCount()
          .ifPresent(value -> promptTokenCount = sum(promptTokenCount, value));
      usageMetadata
          .candidatesTokenCount()
          .ifPresent(value -> candidatesTokenCount = sum(candidatesTokenCount, value));
      usageMetadata
          .totalTokenCount()
          .ifPresent(value -> totalTokenCount = sum(totalTokenCount, value));
      usageMetadata
          .thoughtsTokenCount()
          .ifPresent(value -> thoughtsTokenCount = sum(thoughtsTokenCount, value));
      usageMetadata
          .cachedContentTokenCount()
          .ifPresent(value -> cachedContentTokenCount = sum(cachedContentTokenCount, value));
      usageMetadata
          .promptTokensDetails()
          .ifPresent(details -> addModalityTokens(promptTokensByModality, details));
      usageMetadata
          .candidatesTokensDetails()
          .ifPresent(details -> addModalityTokens(candidatesTokensByModality, details));
    }

    private static Integer sum(Integer existing, int addend) {
      return existing == null ? addend : existing + addend;
    }

    private static void addModalityTokens(
        Map<String, Integer> target, Iterable<ModalityTokenCount> details) {
      for (ModalityTokenCount detail : details) {
        if (detail.modality().isEmpty() || detail.tokenCount().isEmpty()) {
          continue;
        }
        target.merge(detail.modality().get().toString(), detail.tokenCount().get(), Integer::sum);
      }
    }

    public synchronized Integer promptTokenCount() {
      return promptTokenCount;
    }

    public synchronized Integer candidatesTokenCount() {
      return candidatesTokenCount;
    }

    public synchronized Integer totalTokenCount() {
      return totalTokenCount;
    }

    public synchronized Integer thoughtsTokenCount() {
      return thoughtsTokenCount;
    }

    public synchronized Integer cachedContentTokenCount() {
      return cachedContentTokenCount;
    }

    public synchronized Map<String, Integer> promptTokensByModality() {
      return new LinkedHashMap<>(promptTokensByModality);
    }

    public synchronized Map<String, Integer> candidatesTokensByModality() {
      return new LinkedHashMap<>(candidatesTokensByModality);
    }

    @Override
    public synchronized String toString() {
      return "Usage{"
          + "promptTokenCount="
          + promptTokenCount
          + ", candidatesTokenCount="
          + candidatesTokenCount
          + ", totalTokenCount="
          + totalTokenCount
          + ", thoughtsTokenCount="
          + thoughtsTokenCount
          + ", cachedContentTokenCount="
          + cachedContentTokenCount
          + ", promptTokensByModality="
          + promptTokensByModality
          + ", candidatesTokensByModality="
          + candidatesTokensByModality
          + '}';
    }
  }
}
