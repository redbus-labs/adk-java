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

package com.google.adk.models.failover;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.google.adk.agents.InvocationContext;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FailoverLlmTest {
  @AfterEach
  void resetRecorder() {
    LlmCallMetrics.setRecorder(null);
  }

  @Test
  void skipsPrimaryAcrossChainsOnlyAfterElevenFailures() {
    AtomicInteger primaryCalls = new AtomicInteger();
    AtomicInteger fallbackCalls = new AtomicInteger();
    BaseLlm primary =
        model(
            "primary",
            primaryCalls,
            Flowable.error(new LlmHttpException(503, "unavailable", null)));
    BaseLlm fallback = model("fallback", fallbackCalls, success());
    FailoverConfig config =
        FailoverConfig.builder()
            .circuitBreaker(
                TestCircuitBreaker.healthyWhen(
                    modelName -> !modelName.equals("primary") || primaryCalls.get() <= 10))
            .build();
    FailoverLlm firstAgent = new FailoverLlm(primary, List.of(fallback), config);
    for (int invocation = 0; invocation < 11; invocation++) {
      assertThat(
              firstAgent
                  .generateContent(LlmRequest.builder().build(), false)
                  .blockingFirst()
                  .modelVersion()
                  .get())
          .isEqualTo("fallback");
    }
    assertThat(primaryCalls.get()).isEqualTo(11);
    FailoverLlm secondAgent = new FailoverLlm(primary, List.of(fallback), config);
    secondAgent.generateContent(LlmRequest.builder().build(), false).blockingFirst();
    assertThat(primaryCalls.get()).isEqualTo(11);
    assertThat(fallbackCalls.get()).isEqualTo(12);
  }

  @Test
  void checksHealthAtSubscriptionAndCanAllowModelAgain() {
    AtomicBoolean healthy = new AtomicBoolean(true);
    AtomicInteger primaryCalls = new AtomicInteger();
    FailoverLlm failover =
        new FailoverLlm(
            model("primary", primaryCalls, success()),
            List.of(),
            FailoverConfig.builder()
                .circuitBreaker(TestCircuitBreaker.healthyWhen(modelName -> healthy.get()))
                .build());
    Flowable<LlmResponse> response = failover.generateContent(LlmRequest.builder().build(), false);
    healthy.set(false);
    assertThrows(IllegalStateException.class, response::blockingFirst);
    assertThat(primaryCalls.get()).isEqualTo(0);
    healthy.set(true);
    response.blockingFirst();
    assertThat(primaryCalls.get()).isEqualTo(1);
  }

  @Test
  void skippedModelsDoNotProduceFailureEvents() {
    AtomicInteger failures = new AtomicInteger();
    AtomicInteger unavailable = new AtomicInteger();
    LlmCallMetrics.setRecorder(
        new LlmCallMetrics.Recorder() {
          @Override
          public void onAttemptFailure(InvocationContext context, LlmError error) {
            failures.incrementAndGet();
          }

          @Override
          public void onUnavailable(InvocationContext context, Throwable error) {
            unavailable.incrementAndGet();
          }
        });
    AtomicInteger providerCalls = new AtomicInteger();
    FailoverLlm failover =
        new FailoverLlm(
            model("primary", providerCalls, success()),
            List.of(model("fallback", providerCalls, success())),
            FailoverConfig.builder()
                .circuitBreaker(TestCircuitBreaker.healthyWhen(modelName -> false))
                .build());
    assertThrows(
        IllegalStateException.class,
        () ->
            failover
                .generateContent(LlmRequest.builder().build(), false, mock(InvocationContext.class))
                .blockingFirst());
    assertThat(providerCalls.get()).isEqualTo(0);
    assertThat(failures.get()).isEqualTo(0);
    assertThat(unavailable.get()).isEqualTo(1);
  }

  @Test
  void passesInvocationIdentityAndOriginalFailureToRecorder() {
    InvocationContext invocationContext = mock(InvocationContext.class);
    List<String> attemptedModels = new ArrayList<>();
    List<InvocationContext> contexts = new ArrayList<>();
    List<LlmError> failures = new ArrayList<>();
    LlmHttpException cause = new LlmHttpException(503, "provider unavailable", null);
    LlmCallMetrics.setRecorder(
        new LlmCallMetrics.Recorder() {
          @Override
          public void onAttemptStart(InvocationContext context, String modelName) {
            contexts.add(context);
            attemptedModels.add(modelName);
          }

          @Override
          public void onAttemptFailure(InvocationContext context, LlmError error) {
            assertThat(context).isSameInstanceAs(invocationContext);
            failures.add(error);
          }
        });
    FailoverLlm failover =
        new FailoverLlm(
            model("primary", new AtomicInteger(), Flowable.error(cause)),
            model("fallback", new AtomicInteger(), success()));
    failover
        .generateContent(LlmRequest.builder().model("primary").build(), false, invocationContext)
        .blockingFirst();
    assertThat(attemptedModels).containsExactly("primary", "fallback").inOrder();
    assertThat(contexts).containsExactly(invocationContext, invocationContext);
    assertThat(failures).hasSize(1);
    assertThat(failures.get(0).cause().get()).isSameInstanceAs(cause);
  }

  @Test
  void recorderErrorsDoNotTriggerProviderFailover() {
    LlmCallMetrics.setRecorder(
        new LlmCallMetrics.Recorder() {
          @Override
          public void onAttemptStart(InvocationContext context, String modelName) {
            throw new IllegalStateException("tracking unavailable");
          }
        });
    AtomicInteger fallbackCalls = new AtomicInteger();
    FailoverLlm failover =
        new FailoverLlm(
            model("primary", new AtomicInteger(), success()),
            model("fallback", fallbackCalls, success()));
    assertThat(
            failover
                .generateContent(LlmRequest.builder().build(), false, mock(InvocationContext.class))
                .blockingFirst()
                .modelVersion()
                .get())
        .isEqualTo("primary");
    assertThat(fallbackCalls.get()).isEqualTo(0);
  }

  @Test
  void emptyCompletionRecordsFailureBeforeFallback() {
    List<String> failedModels = new ArrayList<>();
    LlmCallMetrics.setRecorder(
        new LlmCallMetrics.Recorder() {
          @Override
          public void onFailure(LlmError error, int attemptIndex, String primaryModel) {
            failedModels.add(error.modelName());
          }
        });
    FailoverLlm failover =
        new FailoverLlm(
            model("primary", new AtomicInteger(), Flowable.empty()),
            model("fallback", new AtomicInteger(), success()));
    failover.generateContent(LlmRequest.builder().build(), false).blockingFirst();
    assertThat(failedModels).containsExactly("primary");
  }

  @Test
  void onlyCompletedNonemptyAttemptsReceiveSuccessCredit() {
    AtomicInteger successes = new AtomicInteger();
    AtomicInteger failures = new AtomicInteger();
    DragonflyCircuitBreaker breaker = outcomes(successes, failures);
    FailoverConfig config = FailoverConfig.builder().circuitBreaker(breaker).build();
    new FailoverLlm(model("primary", new AtomicInteger(), success()), List.of(), config)
        .generateContent(LlmRequest.builder().build(), true)
        .toList()
        .blockingGet();
    assertThat(successes.get()).isEqualTo(1);

    Flowable<LlmResponse> partialThenError =
        success().concatWith(Flowable.error(new LlmHttpException(503, "failed after chunk", null)));
    new FailoverLlm(
            model("primary", new AtomicInteger(), partialThenError),
            List.of(model("fallback", new AtomicInteger(), success())),
            config)
        .generateContent(LlmRequest.builder().build(), true)
        .toList()
        .blockingGet();
    assertThat(successes.get()).isEqualTo(2); // Only the fallback completed.
    assertThat(failures.get()).isEqualTo(1);

    new FailoverLlm(
            model("primary", new AtomicInteger(), success().concatWith(Flowable.never())),
            List.of(),
            config)
        .generateContent(LlmRequest.builder().build(), true)
        .take(1)
        .toList()
        .blockingGet();
    assertThat(successes.get()).isEqualTo(2); // Cancellation is not recovery.
  }

  @Test
  void emptyAndInBandErrorsCannotCloseCircuit() {
    AtomicInteger successes = new AtomicInteger();
    AtomicInteger failures = new AtomicInteger();
    FailoverConfig config =
        FailoverConfig.builder().circuitBreaker(outcomes(successes, failures)).build();
    new FailoverLlm(
            model("primary", new AtomicInteger(), Flowable.empty()),
            List.of(model("fallback", new AtomicInteger(), success())),
            config)
        .generateContent(LlmRequest.builder().build(), false)
        .toList()
        .blockingGet();
    assertThat(successes.get()).isEqualTo(1);
    assertThat(failures.get()).isEqualTo(1);
    new FailoverLlm(
            model(
                "primary",
                new AtomicInteger(),
                Flowable.just(
                    LlmResponse.builder()
                        .errorCode(new com.google.genai.types.FinishReason("SAFETY"))
                        .errorMessage("blocked")
                        .build())),
            List.of(),
            config)
        .generateContent(LlmRequest.builder().build(), false)
        .toList()
        .blockingGet();
    assertThat(successes.get()).isEqualTo(1);
    assertThat(failures.get()).isEqualTo(2);
  }

  @Test
  void completionHookFailureDoesNotTurnSuccessIntoFailover() {
    AtomicInteger fallbackCalls = new AtomicInteger();
    DragonflyCircuitBreaker breaker =
        new TestCircuitBreaker() {
          @Override
          public boolean isHealthy(String name) {
            return true;
          }

          @Override
          public void onSuccess(String name, String id) {
            throw new IllegalStateException("store unavailable");
          }
        };
    new FailoverLlm(
            model("primary", new AtomicInteger(), success()),
            List.of(model("fallback", fallbackCalls, success())),
            FailoverConfig.builder().circuitBreaker(breaker).build())
        .generateContent(LlmRequest.builder().build(), false)
        .toList()
        .blockingGet();
    assertThat(fallbackCalls.get()).isEqualTo(0);
  }

  @Test
  void healthCheckAndAttemptIdentityAreLocalToEachSubscription() {
    List<String> ids = new ArrayList<>();
    AtomicInteger checks = new AtomicInteger();
    DragonflyCircuitBreaker breaker =
        new TestCircuitBreaker() {
          @Override
          public boolean isHealthy(String name) {
            checks.incrementAndGet();
            return true;
          }

          @Override
          public void onSuccess(String name, String id) {
            ids.add(id);
          }
        };
    Flowable<LlmResponse> response =
        new FailoverLlm(
                model("primary", new AtomicInteger(), success()),
                List.of(),
                FailoverConfig.builder().circuitBreaker(breaker).build())
            .generateContent(LlmRequest.builder().build(), false);
    assertThat(checks.get()).isEqualTo(0);
    response.toList().blockingGet();
    response.toList().blockingGet();
    assertThat(ids).hasSize(2);
    assertThat(checks.get()).isEqualTo(2);
    assertThat(ids.get(0)).isNotEqualTo(ids.get(1));
  }

  @Test
  void timeoutReceivesFailureCreditAndOnlyFallbackReceivesSuccessCredit() {
    AtomicInteger successes = new AtomicInteger();
    AtomicInteger failures = new AtomicInteger();
    var config =
        FailoverConfig.builder()
            .attemptTimeoutMillis(10)
            .circuitBreaker(outcomes(successes, failures))
            .build();
    new FailoverLlm(
            model("primary", new AtomicInteger(), Flowable.never()),
            List.of(model("fallback", new AtomicInteger(), success())),
            config)
        .generateContent(LlmRequest.builder().build(), true)
        .toList()
        .blockingGet();
    assertThat(failures.get()).isEqualTo(1);
    assertThat(successes.get()).isEqualTo(1);
  }

  @Test
  void primaryOnlyTimeoutAllowsFallbackToRunLongerThanPrimaryBudget() {
    var scheduler = new io.reactivex.rxjava3.schedulers.TestScheduler();
    io.reactivex.rxjava3.plugins.RxJavaPlugins.setComputationSchedulerHandler(ignored -> scheduler);
    try {
      var config = FailoverConfig.builder().primaryAttemptTimeoutMillis(100).build();
      var observer =
          new FailoverLlm(
                  model("primary", new AtomicInteger(), Flowable.never()),
                  List.of(
                      model(
                          "fallback",
                          new AtomicInteger(),
                          success()
                              .delay(200, java.util.concurrent.TimeUnit.MILLISECONDS, scheduler))),
                  config)
              .generateContent(LlmRequest.builder().build(), false)
              .test();
      scheduler.advanceTimeBy(100, java.util.concurrent.TimeUnit.MILLISECONDS);
      observer.assertNoValues().assertNotComplete();
      scheduler.advanceTimeBy(199, java.util.concurrent.TimeUnit.MILLISECONDS);
      observer.assertNoValues().assertNoErrors();
      scheduler.advanceTimeBy(1, java.util.concurrent.TimeUnit.MILLISECONDS);
      observer.assertValueCount(1).assertComplete().assertNoErrors();
      assertThat(observer.values().get(0).modelVersion()).hasValue("fallback");
    } finally {
      io.reactivex.rxjava3.plugins.RxJavaPlugins.reset();
    }
  }

  @Test
  void primaryOverrideDoesNotDisableTheGeneralFallbackTimeout() {
    var scheduler = new io.reactivex.rxjava3.schedulers.TestScheduler();
    io.reactivex.rxjava3.plugins.RxJavaPlugins.setComputationSchedulerHandler(ignored -> scheduler);
    try {
      var config =
          FailoverConfig.builder()
              .attemptTimeoutMillis(50)
              .primaryAttemptTimeoutMillis(100)
              .build();
      var observer =
          new FailoverLlm(
                  model("primary", new AtomicInteger(), Flowable.never()),
                  List.of(model("fallback", new AtomicInteger(), Flowable.never())),
                  config)
              .generateContent(LlmRequest.builder().build(), false)
              .test();
      scheduler.advanceTimeBy(149, java.util.concurrent.TimeUnit.MILLISECONDS);
      observer.assertNoValues().assertNoErrors().assertNotComplete();
      scheduler.advanceTimeBy(1, java.util.concurrent.TimeUnit.MILLISECONDS);
      observer.assertError(java.util.concurrent.TimeoutException.class);
    } finally {
      io.reactivex.rxjava3.plugins.RxJavaPlugins.reset();
    }
  }

  @Test
  void copyingConfigRetainsSharedServicesAndTimeoutInheritance() {
    var breaker = outcomes(new AtomicInteger(), new AtomicInteger());
    var original =
        FailoverConfig.builder()
            .attemptTimeoutMillis(500)
            .circuitBreaker(breaker)
            .treatEmptyResponseAsFailure(false)
            .retryableCategories(java.util.Set.of(LlmFailureCategory.TIMEOUT))
            .build();
    var overridden = original.toBuilder().primaryAttemptTimeoutMillis(100).build();
    assertThat(overridden.primaryAttemptTimeout()).isEqualTo(java.time.Duration.ofMillis(100));
    assertThat(overridden.attemptTimeout()).isEqualTo(java.time.Duration.ofMillis(500));
    assertThat(overridden.sessionRoutingStore()).isSameInstanceAs(original.sessionRoutingStore());
    assertThat(overridden.circuitBreaker().orElseThrow()).isSameInstanceAs(breaker);
    assertThat(overridden.treatEmptyResponseAsFailure()).isFalse();
    assertThat(
            overridden.shouldFailover(
                LlmError.builder("model").category(LlmFailureCategory.AUTH).build()))
        .isFalse();
    assertThat(original.primaryAttemptTimeout()).isEqualTo(java.time.Duration.ofMillis(500));
    assertThat(original.toBuilder().attemptTimeoutMillis(600).build().primaryAttemptTimeout())
        .isEqualTo(java.time.Duration.ofMillis(600));
    assertThat(
            overridden.toBuilder().primaryAttemptTimeoutMillis(0).build().primaryAttemptTimeout())
        .isEqualTo(java.time.Duration.ZERO);
    assertThrows(
        IllegalArgumentException.class,
        () -> FailoverConfig.builder().primaryAttemptTimeoutMillis(-1));
  }

  private static DragonflyCircuitBreaker outcomes(AtomicInteger successes, AtomicInteger failures) {
    return new TestCircuitBreaker() {
      @Override
      public boolean isHealthy(String name) {
        return true;
      }

      @Override
      public void onSuccess(String name, String id) {
        successes.incrementAndGet();
      }

      @Override
      public void onFailure(String name, String id) {
        failures.incrementAndGet();
      }
    };
  }

  private static Flowable<LlmResponse> success() {
    return Flowable.just(
        LlmResponse.builder().content(Content.fromParts(Part.fromText("ok"))).build());
  }

  private static BaseLlm model(String name, AtomicInteger calls, Flowable<LlmResponse> response) {
    return new BaseLlm(name) {
      @Override
      public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
        calls.incrementAndGet();
        return response;
      }

      @Override
      public BaseLlmConnection connect(LlmRequest request) {
        throw new UnsupportedOperationException();
      }
    };
  }
}
