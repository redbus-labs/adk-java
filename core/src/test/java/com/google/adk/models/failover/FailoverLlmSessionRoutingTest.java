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
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.common.base.Ticker;
import io.reactivex.rxjava3.core.Flowable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import org.junit.jupiter.api.Test;

class FailoverLlmSessionRoutingTest {
  @Test
  void healthyPrimaryAssignsAllSessionsToPrimary() {
    assertSplit(true, 100, 0);
  }

  @Test
  void unhealthyPrimaryAssignsFiveSessionsToPrimaryAndNinetyFiveToFallback() {
    assertSplit(false, 5, 95);
  }

  @Test
  void applicationConfiguredSharesControlInitialModelForEachHealthState() {
    for (boolean healthy : List.of(true, false)) {
      AtomicInteger samples = new AtomicInteger();
      AtomicInteger primaryCalls = new AtomicInteger();
      AtomicInteger fallbackCalls = new AtomicInteger();
      FailoverConfig config =
          FailoverConfig.builder()
              .sessionRoutingStore(
                  SessionRoutingStore.withHealthBasedPrimaryProbabilities(
                      0.8, 0.2, () -> samples.getAndIncrement() / 100.0, Ticker.systemTicker()))
              .circuitBreaker(
                  TestCircuitBreaker.healthyWhen(name -> !name.equals("primary") || healthy))
              .build();
      FailoverLlm llm =
          new FailoverLlm(
              model("primary", primaryCalls, false),
              List.of(model("fallback", fallbackCalls, false)),
              config);
      for (int session = 0; session < 100; session++) {
        InvocationContext context = context("session-" + session);
        String selected = call(llm, context);
        assertThat(call(llm, context)).isEqualTo(selected);
      }
      assertThat(primaryCalls.get()).isEqualTo(healthy ? 160 : 40);
      assertThat(fallbackCalls.get()).isEqualTo(healthy ? 40 : 160);
      assertThat(samples.get()).isEqualTo(100);
    }
  }

  private void assertSplit(boolean healthy, int primarySessions, int fallbackSessions) {
    AtomicInteger samples = new AtomicInteger();
    AtomicInteger primaryCalls = new AtomicInteger();
    AtomicInteger fallbackCalls = new AtomicInteger();
    FailoverLlm llm =
        llm(
            new AtomicBoolean(healthy),
            () -> samples.getAndIncrement() / 100.0,
            Ticker.systemTicker(),
            model("primary", primaryCalls, false),
            model("fallback", fallbackCalls, false));
    for (int i = 0; i < 100; i++) {
      InvocationContext context = context("session-" + i);
      String selected = call(llm, context);
      assertThat(call(llm, context)).isEqualTo(selected);
    }
    assertThat(primaryCalls.get()).isEqualTo(primarySessions * 2);
    assertThat(fallbackCalls.get()).isEqualTo(fallbackSessions * 2);
    assertThat(samples.get()).isEqualTo(100);
  }

  @Test
  void cachedSessionsKeepTheirOffsetsWhenHealthChanges() {
    AtomicBoolean healthy = new AtomicBoolean(true);
    AtomicInteger samples = new AtomicInteger();
    FailoverLlm llm =
        llm(
            healthy,
            () -> {
              samples.incrementAndGet();
              return 0.5;
            },
            Ticker.systemTicker(),
            model("primary", new AtomicInteger(), false),
            model("fallback", new AtomicInteger(), false));
    assertThat(call(llm, context("default"))).isEqualTo("primary");
    healthy.set(false);
    // A new session starts on fallback once the primary is unhealthy.
    assertThat(call(llm, context("experiment"))).isEqualTo("fallback");
    assertThat(call(llm, context("default"))).isEqualTo("primary");
    assertThat(call(llm, context("experiment"))).isEqualTo("fallback");
    assertThat(samples.get()).isEqualTo(2);
  }

  @Test
  void bothCachesExpireTenMinutesAfterWriteAndReadsDoNotRefreshTtl() {
    for (boolean initiallyHealthy : List.of(true, false)) {
      AtomicBoolean healthy = new AtomicBoolean(initiallyHealthy);
      AtomicLong nanos = new AtomicLong();
      AtomicInteger samples = new AtomicInteger();
      Ticker ticker =
          new Ticker() {
            @Override
            public long read() {
              return nanos.get();
            }
          };
      FailoverLlm llm =
          llm(
              healthy,
              () -> {
                samples.incrementAndGet();
                return 0.5;
              },
              ticker,
              model("primary", new AtomicInteger(), false),
              model("fallback", new AtomicInteger(), false));
      String first = call(llm, context("adk-session"));
      healthy.set(!initiallyHealthy);
      nanos.set(TimeUnit.SECONDS.toNanos(599));
      assertThat(call(llm, context("adk-session"))).isEqualTo(first);
      assertThat(samples.get()).isEqualTo(1);
      nanos.set(TimeUnit.MINUTES.toNanos(10));
      assertThat(call(llm, context("adk-session"))).isNotEqualTo(first);
      assertThat(samples.get()).isEqualTo(2);
    }
  }

  @Test
  void adkSessionIdentitySeparatesSessionsWithTheSameState() {
    AtomicInteger samples = new AtomicInteger();
    FailoverLlm llm =
        llm(
            new AtomicBoolean(false),
            () -> samples.getAndIncrement() == 0 ? 0.5 : 0,
            Ticker.systemTicker(),
            model("primary", new AtomicInteger(), false),
            model("fallback", new AtomicInteger(), false));
    assertThat(call(llm, context("adk-1", "same-state"))).isEqualTo("fallback");
    assertThat(call(llm, context("adk-2", "same-state"))).isEqualTo("primary");
    assertThat(call(llm, context("adk-1", "changed-state"))).isEqualTo("fallback");
    assertThat(samples.get()).isEqualTo(2);
  }

  @Test
  void missingOrBlankAdkSessionIdentityStillAllowsOrdinaryFailover() {
    AtomicInteger samples = new AtomicInteger();
    FailoverLlm llm =
        llm(
            new AtomicBoolean(true),
            () -> {
              samples.incrementAndGet();
              return 0.5;
            },
            Ticker.systemTicker(),
            model("primary", new AtomicInteger(), true),
            model("fallback", new AtomicInteger(), false));
    assertThat(call(llm, null)).isEqualTo("fallback");
    assertThat(call(llm, context(""))).isEqualTo("fallback");
    assertThat(call(llm, context(" "))).isEqualTo("fallback");
    assertThat(samples.get()).isEqualTo(0);
  }

  @Test
  void eachAgentHasItsOwnSessionAssignment() {
    BaseLlm primary = model("primary", new AtomicInteger(), false);
    BaseLlm fallback = model("fallback", new AtomicInteger(), false);
    FailoverLlm first =
        llm(new AtomicBoolean(true), () -> 0.5, Ticker.systemTicker(), primary, fallback);
    FailoverLlm second =
        llm(new AtomicBoolean(false), () -> 0.5, Ticker.systemTicker(), primary, fallback);
    assertThat(call(first, context("same-state"))).isEqualTo("primary");
    assertThat(call(second, context("same-state"))).isEqualTo("fallback");
  }

  @Test
  void simultaneousRequestsForOneSessionMakeOneRandomAssignment() throws Exception {
    AtomicInteger samples = new AtomicInteger();
    FailoverLlm llm =
        llm(
            new AtomicBoolean(false),
            () -> samples.getAndIncrement() % 2 == 0 ? 0.5 : 0,
            Ticker.systemTicker(),
            model("primary", new AtomicInteger(), false),
            model("fallback", new AtomicInteger(), false));
    var executor = Executors.newFixedThreadPool(8);
    try {
      List<Future<String>> responses = new ArrayList<>();
      for (int i = 0; i < 40; i++) {
        responses.add(executor.submit(() -> call(llm, context("same-state"))));
      }
      for (Future<String> response : responses) {
        assertThat(response.get(10, TimeUnit.SECONDS)).isEqualTo("fallback");
      }
      assertThat(samples.get()).isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void selectedUnhealthyPrimaryReceivesProbeAndSuccessfulCompletionCredit() {
    AtomicInteger successes = new AtomicInteger();
    DragonflyCircuitBreaker breaker =
        new TestCircuitBreaker() {
          @Override
          public boolean isHealthy(String name) {
            return !name.equals("primary");
          }

          @Override
          public void onSuccess(String name, String id) {
            assertThat(name).isEqualTo("primary");
            successes.incrementAndGet();
          }
        };
    FailoverLlm llm =
        new FailoverLlm(
            List.of(
                () -> model("primary", new AtomicInteger(), false),
                () -> model("fallback", new AtomicInteger(), false)),
            FailoverConfig.builder()
                .circuitBreaker(breaker)
                .sessionRoutingStore(new SessionRoutingStore(() -> 0, Ticker.systemTicker()))
                .build());
    assertThat(call(llm, context("probe"))).isEqualTo("primary");
    assertThat(successes.get()).isEqualTo(1);
  }

  @Test
  void fullPrimaryShareStillFailsOverAndRemembersSuccessfulFallback() {
    AtomicInteger primaryCalls = new AtomicInteger();
    AtomicInteger fallbackCalls = new AtomicInteger();
    FailoverLlm llm =
        new FailoverLlm(
            List.of(
                () -> model("primary", primaryCalls, true),
                () -> model("fallback", fallbackCalls, false)),
            FailoverConfig.builder()
                .sessionRoutingStore(SessionRoutingStore.withPrimaryTrafficProbability(1))
                .build());
    assertThat(call(llm, context("primary-fails"))).isEqualTo("fallback");
    assertThat(call(llm, context("primary-fails"))).isEqualTo("fallback");
    assertThat(primaryCalls.get()).isEqualTo(1);
    assertThat(fallbackCalls.get()).isEqualTo(2);
  }

  @Test
  void zeroPrimaryShareStartsAtFallbackAndStillAdvancesAfterFailure() {
    AtomicInteger primaryCalls = new AtomicInteger();
    AtomicInteger fallbackCalls = new AtomicInteger();
    AtomicInteger lastCalls = new AtomicInteger();
    FailoverLlm llm =
        new FailoverLlm(
            List.of(
                () -> model("primary", primaryCalls, false),
                () -> model("fallback", fallbackCalls, true),
                () -> model("last", lastCalls, false)),
            FailoverConfig.builder()
                .sessionRoutingStore(SessionRoutingStore.withPrimaryTrafficProbability(0))
                .build());
    assertThat(call(llm, context("fallback-fails"))).isEqualTo("last");
    assertThat(primaryCalls.get()).isEqualTo(0);
    assertThat(fallbackCalls.get()).isEqualTo(1);
    assertThat(lastCalls.get()).isEqualTo(1);
  }

  @Test
  void fallbackAssignedSessionStartsAtFallbackAndContinuesInChainOnFailure() {
    AtomicInteger primaryCalls = new AtomicInteger();
    AtomicInteger fallbackCalls = new AtomicInteger();
    AtomicInteger lastCalls = new AtomicInteger();
    FailoverLlm llm =
        new FailoverLlm(
            List.of(
                () -> model("primary", primaryCalls, false),
                () -> model("fallback", fallbackCalls, true),
                () -> model("last", lastCalls, false)),
            FailoverConfig.builder()
                .sessionRoutingStore(SessionRoutingStore.withPrimaryTrafficProbability(0))
                .build());
    assertThat(call(llm, context("experiment"))).isEqualTo("last");
    assertThat(call(llm, context("experiment"))).isEqualTo("last");
    assertThat(primaryCalls.get()).isEqualTo(0);
    assertThat(fallbackCalls.get()).isEqualTo(1);
    assertThat(lastCalls.get()).isEqualTo(2);
  }

  @Test
  void successfulFailoverMovesSessionToFallbackCacheUntilExpiry() {
    AtomicInteger primaryCalls = new AtomicInteger();
    AtomicInteger fallbackCalls = new AtomicInteger();
    AtomicBoolean primaryFails = new AtomicBoolean(true);
    AtomicLong nanos = new AtomicLong();
    Ticker ticker =
        new Ticker() {
          @Override
          public long read() {
            return nanos.get();
          }
        };
    FailoverLlm llm =
        llm(
            new AtomicBoolean(true),
            () -> 0.5,
            ticker,
            model("primary", primaryCalls, primaryFails::get),
            model("fallback", fallbackCalls, false));
    assertThat(call(llm, context("adk-session"))).isEqualTo("fallback");
    primaryFails.set(false);
    nanos.set(TimeUnit.SECONDS.toNanos(599));
    assertThat(call(llm, context("adk-session"))).isEqualTo("fallback");
    assertThat(primaryCalls.get()).isEqualTo(1);
    assertThat(fallbackCalls.get()).isEqualTo(2);
    nanos.set(TimeUnit.MINUTES.toNanos(10));
    assertThat(call(llm, context("adk-session"))).isEqualTo("primary");
    assertThat(primaryCalls.get()).isEqualTo(2);
  }

  @Test
  void unsuccessfulFailoverDoesNotReplaceSessionAssignment() {
    AtomicBoolean primaryFails = new AtomicBoolean(true);
    AtomicInteger primaryCalls = new AtomicInteger();
    FailoverLlm llm =
        llm(
            new AtomicBoolean(true),
            () -> 0.5,
            Ticker.systemTicker(),
            model("primary", primaryCalls, primaryFails::get),
            model("fallback", new AtomicInteger(), true));
    assertThrows(LlmHttpException.class, () -> call(llm, context("adk-session")));
    primaryFails.set(false);
    assertThat(call(llm, context("adk-session"))).isEqualTo("primary");
    assertThat(primaryCalls.get()).isEqualTo(2);
  }

  @Test
  void assignmentIsDeferredUntilSubscription() {
    AtomicInteger samples = new AtomicInteger();
    FailoverLlm llm =
        llm(
            new AtomicBoolean(true),
            () -> {
              samples.incrementAndGet();
              return 0.5;
            },
            Ticker.systemTicker(),
            model("primary", new AtomicInteger(), false),
            model("fallback", new AtomicInteger(), false));
    Flowable<LlmResponse> response =
        llm.generateContent(
            LlmRequest.builder().model("primary").build(), false, context("adk-session"));
    assertThat(samples.get()).isEqualTo(0);
    response.toList().blockingGet();
    response.toList().blockingGet();
    assertThat(samples.get()).isEqualTo(1);
  }

  @Test
  void recreatedAgentAndWrapperReuseFallbackAssignment() {
    AtomicInteger samples = new AtomicInteger();
    SessionRoutingStore store =
        SessionRoutingStore.withPrimaryTrafficProbability(
            0.5, () -> samples.getAndIncrement() == 0 ? 0.75 : 0.25, Ticker.systemTicker());
    FailoverConfig config = FailoverConfig.builder().sessionRoutingStore(store).build();
    for (int request = 0; request < 5; request++) {
      FailoverLlm recreated =
          new FailoverLlm(
              List.of(
                  () -> model("primary", new AtomicInteger(), false),
                  () -> model("fallback", new AtomicInteger(), false)),
              config,
              "chain-A");
      assertThat(call(recreated, context("same-adk-session"))).isEqualTo("fallback");
    }
    assertThat(samples.get()).isEqualTo(1);
  }

  @Test
  void fallbackPinSurvivesRecreationAndPrimaryRecovery() {
    AtomicInteger samples = new AtomicInteger();
    AtomicInteger primaryCalls = new AtomicInteger();
    AtomicBoolean fails = new AtomicBoolean(true);
    FailoverConfig config =
        FailoverConfig.builder()
            .sessionRoutingStore(
                new SessionRoutingStore(
                    () -> {
                      samples.incrementAndGet();
                      return 0.5;
                    },
                    Ticker.systemTicker()))
            .build();
    for (int request = 0; request < 3; request++) {
      FailoverLlm recreated =
          new FailoverLlm(
              List.of(
                  () -> model("primary", primaryCalls, fails::get),
                  () -> model("fallback", new AtomicInteger(), false)),
              config,
              "chain-A");
      assertThat(call(recreated, context("same-state"))).isEqualTo("fallback");
      fails.set(false);
    }
    assertThat(primaryCalls.get()).isEqualTo(1);
    assertThat(samples.get()).isEqualTo(1);
  }

  @Test
  void differentLogicalAgentsHaveSeparateAssignmentsInSharedStore() {
    AtomicInteger samples = new AtomicInteger();
    FailoverConfig config =
        FailoverConfig.builder()
            .sessionRoutingStore(
                SessionRoutingStore.withPrimaryTrafficProbability(
                    0.5, () -> samples.getAndIncrement() == 0 ? 0.75 : 0.25, Ticker.systemTicker()))
            .build();
    for (String name : List.of("FilterGenerationAgent", "Coordinator", "FilterGenerationAgent")) {
      FailoverLlm recreated =
          new FailoverLlm(
              List.of(
                  () -> model("primary", new AtomicInteger(), false),
                  () -> model("fallback", new AtomicInteger(), false)),
              config,
              "same-chain");
      assertThat(call(recreated, context("same-state", "test", "user", name, Map.of())))
          .isEqualTo(name.equals("FilterGenerationAgent") ? "fallback" : "primary");
    }
    assertThat(samples.get()).isEqualTo(2);
  }

  @Test
  void sharedWrapperUsesContextAgentAndApplicationButDoesNotUseUserIdentity() {
    AtomicInteger samples = new AtomicInteger();
    FailoverConfig config =
        FailoverConfig.builder()
            .sessionRoutingStore(
                SessionRoutingStore.withPrimaryTrafficProbability(
                    0.5, () -> samples.getAndIncrement() == 0 ? 0.75 : 0.25, Ticker.systemTicker()))
            .build();
    FailoverLlm llm =
        FailoverLlm.builder()
            .primary(model("primary", new AtomicInteger(), false))
            .fallback(model("fallback", new AtomicInteger(), false))
            .config(config)
            .routingChainId("same-chain")
            .build();

    assertThat(call(llm, context("session", "app", "user-1", "AgentA", Map.of())))
        .isEqualTo("fallback");
    assertThat(call(llm, context("session", "app", "user-2", "AgentA", Map.of())))
        .isEqualTo("fallback");
    assertThat(call(llm, context("session", "app", null, "AgentA", Map.of())))
        .isEqualTo("fallback");
    assertThat(call(llm, context("session", "app", "user-1", "AgentB", Map.of())))
        .isEqualTo("primary");
    assertThat(call(llm, context("session", "other-app", "user-1", "AgentA", Map.of())))
        .isEqualTo("primary");
    assertThat(samples.get()).isEqualTo(3);
  }

  @Test
  void configuredAgentIdentitySurvivesMissingOrDifferentContextAgentsAndWrapperRecreation() {
    AtomicInteger samples = new AtomicInteger();
    FailoverConfig config =
        FailoverConfig.builder()
            .sessionRoutingStore(
                SessionRoutingStore.withPrimaryTrafficProbability(
                    0.5, () -> samples.getAndIncrement() == 0 ? 0.75 : 0.25, Ticker.systemTicker()))
            .build();
    InvocationContext withoutAgent = contextWithoutAgent("session");
    for (int request = 0; request < 3; request++) {
      FailoverLlm llm =
          new FailoverLlm(
              List.of(
                  () -> model("primary", new AtomicInteger(), false),
                  () -> model("fallback", new AtomicInteger(), false)),
              config,
              "ConfiguredAgent",
              "chain");
      assertThat(call(llm, withoutAgent)).isEqualTo("fallback");
      assertThat(call(llm, context("session"))).isEqualTo("fallback");
    }
    FailoverLlm otherAgent =
        FailoverLlm.builder()
            .primary(model("primary", new AtomicInteger(), false))
            .fallback(model("fallback", new AtomicInteger(), false))
            .config(config)
            .routingIdentity("OtherAgent", "chain")
            .build();
    assertThat(call(otherAgent, withoutAgent)).isEqualTo("primary");
    assertThat(samples.get()).isEqualTo(2);
  }

  @Test
  void missingAgentIdentityUsesOrdinaryFailoverWithoutCaching() {
    AtomicInteger samples = new AtomicInteger();
    FailoverLlm llm =
        llm(
            new AtomicBoolean(true),
            () -> {
              samples.incrementAndGet();
              return 0.5;
            },
            Ticker.systemTicker(),
            model("primary", new AtomicInteger(), true),
            model("fallback", new AtomicInteger(), false));
    assertThat(call(llm, contextWithoutAgent("session"))).isEqualTo("fallback");
    assertThat(samples.get()).isEqualTo(0);
  }

  @Test
  void blankConfiguredAgentIdentityIsRejected() {
    for (String name : List.of("", " ")) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new FailoverLlm(
                  List.of(() -> model("primary", new AtomicInteger(), false)),
                  FailoverConfig.defaults(),
                  name,
                  "chain"));
      assertThrows(
          IllegalArgumentException.class,
          () -> FailoverLlm.builder().routingIdentity(name, "chain"));
    }
  }

  @Test
  void inferredLogicalIdentitySurvivesNewModelObjects() {
    AtomicInteger samples = new AtomicInteger();
    FailoverConfig config =
        FailoverConfig.builder()
            .sessionRoutingStore(
                SessionRoutingStore.withPrimaryTrafficProbability(
                    0.5, () -> samples.getAndIncrement() == 0 ? 0.75 : 0.25, Ticker.systemTicker()))
            .build();
    for (int request = 0; request < 3; request++) {
      FailoverLlm recreated =
          new FailoverLlm(
              model("primary", new AtomicInteger(), false),
              List.of(model("fallback", new AtomicInteger(), false)),
              config);
      assertThat(call(recreated, context("same-state"))).isEqualTo("fallback");
    }
    assertThat(samples.get()).isEqualTo(1);
  }

  @Test
  void explicitChainIdentityPreservesLazyFallbackConstruction() {
    AtomicInteger constructions = new AtomicInteger();
    FailoverConfig config =
        FailoverConfig.builder()
            .sessionRoutingStore(new SessionRoutingStore(() -> 0.5, Ticker.systemTicker()))
            .build();
    FailoverLlm llm =
        new FailoverLlm(
            List.of(
                () -> model("primary", new AtomicInteger(), false),
                () -> {
                  constructions.incrementAndGet();
                  return model("fallback", new AtomicInteger(), false);
                }),
            config,
            "primary-then-fallback");
    assertThat(call(llm, context("adk-session"))).isEqualTo("primary");
    assertThat(constructions.get()).isEqualTo(0);
  }

  private static FailoverLlm llm(
      AtomicBoolean healthy,
      DoubleSupplier random,
      Ticker ticker,
      BaseLlm primary,
      BaseLlm fallback) {
    return new FailoverLlm(
        List.of(() -> primary, () -> fallback),
        FailoverConfig.builder()
            .circuitBreaker(
                TestCircuitBreaker.healthyWhen(name -> !name.equals("primary") || healthy.get()))
            .sessionRoutingStore(new SessionRoutingStore(random, ticker))
            .build());
  }

  private static InvocationContext contextWithoutAgent(String id) {
    // ADK's builder requires an agent; simulate a custom context omitting it for this guard.
    InvocationContext context = org.mockito.Mockito.spy(context(id));
    org.mockito.Mockito.when(context.agent()).thenReturn(null);
    return context;
  }

  private static InvocationContext context(String id) {
    return context(id, Map.of());
  }

  private static InvocationContext context(String adkId, String stateValue) {
    return context(adkId, Map.of("applicationSessionId", stateValue));
  }

  private static InvocationContext context(String adkId, Map<String, Object> state) {
    return context(adkId, "test", "user", "test_agent", state);
  }

  private static InvocationContext context(
      String adkId, String appName, String userId, String agentName, Map<String, Object> state) {
    return InvocationContext.builder()
        .agent(LlmAgent.builder().name(agentName).build())
        .sessionService(new InMemorySessionService())
        .session(Session.builder(adkId).appName(appName).userId(userId).state(state).build())
        .build();
  }

  private static String call(FailoverLlm llm, InvocationContext context) {
    return llm.generateContent(LlmRequest.builder().model("primary").build(), false, context)
        .toList()
        .blockingGet()
        .get(0)
        .modelVersion()
        .orElseThrow();
  }

  private static BaseLlm model(String name, AtomicInteger calls, boolean fails) {
    return model(name, calls, () -> fails);
  }

  private static BaseLlm model(String name, AtomicInteger calls, BooleanSupplier fails) {
    return new BaseLlm(name) {
      @Override
      public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
        assertThat(request.model().orElseThrow()).isEqualTo(name);
        calls.incrementAndGet();
        return fails.getAsBoolean()
            ? Flowable.error(new LlmHttpException(503, "unavailable", null))
            : Flowable.just(LlmResponse.builder().build());
      }

      @Override
      public BaseLlmConnection connect(LlmRequest request) {
        throw new UnsupportedOperationException();
      }
    };
  }
}
