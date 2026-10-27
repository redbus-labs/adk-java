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
package com.google.adk.models.failover;

import static com.google.common.truth.Truth.assertThat;

import com.google.adk.models.failover.SessionRoutingStore.Assignment;
import com.google.adk.models.failover.SessionRoutingStore.SessionRoutingKey;
import com.google.common.base.Ticker;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SessionRoutingStoreTest {
  @Test
  void configuredProbabilitySelectsFallbackWhenHealthyAndPrimaryWhenUnhealthy() {
    for (boolean healthy : List.of(true, false)) {
      AtomicInteger draws = new AtomicInteger();
      SessionRoutingStore store =
          new SessionRoutingStore(
              () -> draws.getAndIncrement() == 0 ? 0.09 : 0.10, Ticker.systemTicker(), 0.10);
      assertThat(store.getOrAssign(key("below-threshold"), () -> healthy).offset())
          .isEqualTo(healthy ? 1 : 0);
      assertThat(store.getOrAssign(key("at-threshold"), () -> healthy).offset())
          .isEqualTo(healthy ? 0 : 1);
      assertThat(store.getOrAssign(key("below-threshold"), () -> !healthy).offset())
          .isEqualTo(healthy ? 1 : 0);
      assertThat(draws.get()).isEqualTo(2);
    }
  }

  @Test
  void zeroAndFullProbabilityHaveDeterministicAssignments() {
    for (boolean healthy : List.of(true, false)) {
      var noFlip = new SessionRoutingStore(() -> 0.5, Ticker.systemTicker(), 0);
      var alwaysFlip = new SessionRoutingStore(() -> 0.5, Ticker.systemTicker(), 1);
      assertThat(noFlip.getOrAssign(key("S1"), () -> healthy).offset()).isEqualTo(healthy ? 0 : 1);
      assertThat(alwaysFlip.getOrAssign(key("S1"), () -> healthy).offset())
          .isEqualTo(healthy ? 1 : 0);
    }
  }

  @Test
  void invalidRoutingProbabilityIsRejected() {
    for (double value : new double[] {-0.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY}) {
      org.junit.jupiter.api.Assertions.assertThrows(
          IllegalArgumentException.class, () -> new SessionRoutingStore(value));
    }
  }

  @Test
  void assignmentKeySeparatesApplicationSessionAgentAndChain() {
    AtomicInteger samples = new AtomicInteger();
    SessionRoutingStore store =
        new SessionRoutingStore(
            () -> samples.getAndIncrement() == 0 ? 0 : 0.5, Ticker.systemTicker());
    SessionRoutingKey first = new SessionRoutingKey("RAY", "S1", "Filter", "chain1");
    assertThat(store.getOrAssign(first, () -> true).offset()).isEqualTo(1);
    for (SessionRoutingKey other :
        List.of(
            new SessionRoutingKey("OTHER", "S1", "Filter", "chain1"),
            new SessionRoutingKey("RAY", "S2", "Filter", "chain1"),
            new SessionRoutingKey("RAY", "S1", "Coordinator", "chain1"),
            new SessionRoutingKey("RAY", "S1", "Filter", "chain2"))) {
      assertThat(store.getOrAssign(other, () -> true).offset()).isEqualTo(0);
    }
    assertThat(
            store
                .getOrAssign(
                    first,
                    () -> {
                      throw new AssertionError("Cached health was rechecked");
                    })
                .offset())
        .isEqualTo(1);
    assertThat(samples.get()).isEqualTo(5);
  }

  @Test
  void readsDoNotRefreshEitherCacheAndExpiryResamples() {
    for (boolean healthy : List.of(true, false)) {
      AtomicLong nanos = new AtomicLong();
      SessionRoutingStore store = new SessionRoutingStore(() -> 0.5, ticker(nanos));
      SessionRoutingKey key = key("S1");
      Assignment first = store.getOrAssign(key, () -> healthy);
      nanos.set(TimeUnit.SECONDS.toNanos(59));
      assertThat(store.getOrAssign(key, () -> !healthy)).isSameInstanceAs(first);
      nanos.set(TimeUnit.MINUTES.toNanos(1));
      Assignment next = store.getOrAssign(key, () -> !healthy);
      assertThat(next.offset()).isNotEqualTo(first.offset());
      assertThat(next.generation()).isNotEqualTo(first.generation());
    }
  }

  @Test
  void successfulFallbackIsSharedAndEarlierCompletionCannotDemoteIt() {
    SessionRoutingStore store = new SessionRoutingStore(() -> 0.5, Ticker.systemTicker());
    SessionRoutingKey key = key("S1");
    Assignment first = store.getOrAssign(key, () -> true);
    store.rememberSuccessfulFallback(key, first, 1);
    assertThat(store.getOrAssign(key, () -> true).offset()).isEqualTo(1);
    store.rememberSuccessfulFallback(key, first, 2);
    store.rememberSuccessfulFallback(key, first, 1);
    Assignment current = store.getOrAssign(key, () -> true);
    assertThat(current.offset()).isEqualTo(2);
    assertThat(current.generation()).isEqualTo(first.generation());
  }

  @Test
  void staleCompletionCannotOverwriteResampledAssignment() {
    AtomicLong nanos = new AtomicLong();
    SessionRoutingStore store = new SessionRoutingStore(() -> 0.5, ticker(nanos));
    SessionRoutingKey key = key("S1");
    Assignment old = store.getOrAssign(key, () -> true);
    nanos.set(TimeUnit.MINUTES.toNanos(1));
    Assignment replacement = store.getOrAssign(key, () -> true);
    store.rememberSuccessfulFallback(key, old, 1);
    assertThat(store.getOrAssign(key, () -> true)).isSameInstanceAs(replacement);
    assertThat(replacement.offset()).isEqualTo(0);
  }

  @Test
  void completedFallbackDoesNotRefreshAnExistingFallbackTtl() {
    AtomicLong nanos = new AtomicLong();
    SessionRoutingStore store = new SessionRoutingStore(() -> 0, ticker(nanos));
    SessionRoutingKey key = key("S1");
    Assignment first = store.getOrAssign(key, () -> true);
    nanos.set(TimeUnit.SECONDS.toNanos(59));
    store.rememberSuccessfulFallback(key, first, 1);
    nanos.set(TimeUnit.MINUTES.toNanos(1));
    assertThat(store.getOrAssign(key, () -> true).generation()).isNotEqualTo(first.generation());
  }

  @Test
  void concurrentFirstRequestsSampleAndCheckHealthOnlyOnce() throws Exception {
    AtomicInteger samples = new AtomicInteger();
    AtomicInteger checks = new AtomicInteger();
    SessionRoutingStore store =
        new SessionRoutingStore(
            () -> {
              samples.incrementAndGet();
              return 0;
            },
            Ticker.systemTicker());
    CountDownLatch start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(8);
    try {
      List<Future<Assignment>> results = new ArrayList<>();
      for (int request = 0; request < 40; request++) {
        results.add(
            executor.submit(
                () -> {
                  start.await();
                  return store.getOrAssign(
                      key("S1"),
                      () -> {
                        checks.incrementAndGet();
                        return true;
                      });
                }));
      }
      start.countDown();
      Assignment first = results.get(0).get(5, TimeUnit.SECONDS);
      for (Future<Assignment> result : results)
        assertThat(result.get(5, TimeUnit.SECONDS)).isSameInstanceAs(first);
      assertThat(samples.get()).isEqualTo(1);
      assertThat(checks.get()).isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void chainIdentityIsStableOrderedAndUnambiguous() {
    assertThat(SessionRoutingStore.chainId(List.of("Gemini|p", "Bedrock|f")))
        .isEqualTo(SessionRoutingStore.chainId(List.of("Gemini|p", "Bedrock|f")));
    assertThat(SessionRoutingStore.chainId(List.of("p", "f")))
        .isNotEqualTo(SessionRoutingStore.chainId(List.of("f", "p")));
    assertThat(SessionRoutingStore.chainId(List.of("ab", "c")))
        .isNotEqualTo(SessionRoutingStore.chainId(List.of("a", "bc")));
  }

  private static SessionRoutingKey key(String session) {
    return new SessionRoutingKey("RAY", session, "FilterGenerationAgent", "chain1");
  }

  private static Ticker ticker(AtomicLong nanos) {
    return new Ticker() {
      @Override
      public long read() {
        return nanos.get();
      }
    };
  }
}
