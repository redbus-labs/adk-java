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

import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.common.base.Suppliers;
import io.reactivex.rxjava3.core.Flowable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * A {@link BaseLlm} decorator that wraps a primary model plus an ordered list of fallback models
 * and transparently fails over between them, in the same spirit as a Java fallback-API chain.
 *
 * <p>Key properties:
 *
 * <ul>
 *   <li><b>Zero happy-path latency.</b> The primary model's {@code Flowable} is returned with only
 *       lightweight operators attached; fallbacks are attempted lazily and only on a classified,
 *       retryable failure or timeout.
 *   <li><b>Transparent to all agents.</b> Because it implements {@link BaseLlm}, any {@code
 *       LlmAgent} (including those inside {@code SequentialAgent}, {@code ParallelAgent}, {@code
 *       LoopAgent} or custom orchestrators) gets failover simply by resolving to a {@code
 *       FailoverLlm} instance.
 *   <li><b>Uniform failure handling.</b> Three failure shapes are normalized into one path:
 *       exceptions on the {@code Flowable}, in-band {@link LlmResponse#errorCode()} emissions, and
 *       silent empty completion (Azure/Bedrock).
 * </ul>
 *
 * <p>Streaming caveat: if a failure surfaces after partial chunks were already emitted, downstream
 * consumers will have observed those partial chunks before the fallback model's output. Failover is
 * cleanest for non-streaming calls, which is the dominant path for the agents in this codebase.
 */
public class FailoverLlm extends BaseLlm {

  private final List<Supplier<BaseLlm>> chain;
  private final FailoverConfig config;

  /** Builds a failover chain from already-instantiated models. */
  public FailoverLlm(BaseLlm primary, BaseLlm... fallbacks) {
    this(primary, Arrays.asList(fallbacks), FailoverConfig.defaults());
  }

  /** Builds a failover chain from already-instantiated models with explicit config. */
  public FailoverLlm(BaseLlm primary, List<BaseLlm> fallbacks, FailoverConfig config) {
    this(toSuppliers(primary, fallbacks), config);
  }

  /**
   * Builds a failover chain from lazy suppliers. The first supplier is the primary; each supplier
   * is memoized so the underlying model (and its HTTP client) is built at most once.
   *
   * @param chain ordered suppliers, index 0 = primary; must be non-empty.
   * @param config failover tuning.
   */
  public FailoverLlm(List<Supplier<BaseLlm>> chain, FailoverConfig config) {
    super(primaryModelName(chain));
    List<Supplier<BaseLlm>> memoized = new ArrayList<>(chain.size());
    for (Supplier<BaseLlm> supplier : chain) {
      memoized.add(Suppliers.memoize(supplier::get)::get);
    }
    this.chain = memoized;
    this.config = config == null ? FailoverConfig.defaults() : config;
  }

  public static Builder builder() {
    return new Builder();
  }

  @Override
  public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
    return attempt(0, llmRequest, stream);
  }

  private Flowable<LlmResponse> attempt(int index, LlmRequest llmRequest, boolean stream) {
    final int attemptIndex = index;
    final BaseLlm attemptLlm = resolve(attemptIndex);

    // IMPORTANT: Ensure the per-attempt request's model name matches the model being invoked.
    //
    // Many call sites set LlmRequest.model() (e.g. to the current model's name). Without rewriting
    // this field, a cross-provider failover chain can accidentally call the fallback provider with
    // the primary provider's model identifier (e.g. Azure deployment name leaking into Gemini),
    // leading to confusing "model not found" errors on the fallback attempt.
    final LlmRequest attemptRequest =
        llmRequest.model().isPresent()
            ? llmRequest.toBuilder().model(attemptLlm.model()).build()
            : llmRequest;

    // Defer so the underlying call (and any eager work it does on subscription) is only triggered
    // when this attempt is actually reached.
    Flowable<LlmResponse> flow =
        Flowable.defer(() -> attemptLlm.generateContent(attemptRequest, stream));

    final String attemptModel = safeModelName(attemptIndex);

    // 1) Convert in-band error responses (errorCode set) into exceptions so they share the failover
    // path with thrown errors.
    flow =
        flow.map(
            response -> {
              LlmErrorClassifier.classifyInBand(attemptModel, response)
                  .filter(config::shouldFailover)
                  .ifPresent(
                      error -> {
                        throw new InBandFailureException(error);
                      });
              return response;
            });

    // 2) Treat silent empty completion (e.g. Azure/Bedrock non-2xx) as a failure.
    if (config.treatEmptyResponseAsFailure()) {
      flow =
          flow.switchIfEmpty(
              Flowable.error(
                  () ->
                      new LlmHttpException(
                          -1, "Model returned an empty response: " + attemptModel, null)));
    }

    // 3) Latency budget -> TimeoutException -> classified as TIMEOUT.
    if (config.isTimeoutEnabled()) {
      flow = flow.timeout(config.attemptTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    // 4) Record fallback success exactly once for non-primary attempts (attached before failover so
    // it only fires for this model's own emissions).
    if (attemptIndex > 0) {
      AtomicBoolean recorded = new AtomicBoolean(false);
      flow =
          flow.doOnNext(
              response -> {
                if (recorded.compareAndSet(false, true)) {
                  LlmCallMetrics.recordFallbackSuccess(attemptModel, model(), attemptIndex);
                }
              });
    }

    // 5) Failover.
    return flow.onErrorResumeNext(
        throwable -> {
          LlmError error =
              throwable instanceof InBandFailureException
                  ? ((InBandFailureException) throwable).error()
                  : LlmErrorClassifier.classify(attemptModel, throwable);

          LlmCallMetrics.recordFailure(error, attemptIndex, model());

          boolean hasNext = attemptIndex + 1 < chain.size();
          if (hasNext && config.shouldFailover(error)) {
            return attempt(attemptIndex + 1, llmRequest, stream);
          }

          if (!hasNext) {
            LlmCallMetrics.recordExhausted(error, model(), chain.size());
          }
          // Non-retryable, or chain exhausted: propagate the original cause.
          return Flowable.error(unwrapForPropagation(throwable));
        });
  }

  @Override
  public BaseLlmConnection connect(LlmRequest llmRequest) {
    // Live/bidi failover is out of scope; delegate to the primary model.
    return resolve(0).connect(llmRequest);
  }

  /**
   * Returns the resolved primary model instance (attempt index 0).
   *
   * <p>This is primarily intended for application-level wiring code that needs to unwrap a
   * previously-wrapped {@link FailoverLlm} without losing provider-specific identity (e.g. Azure
   * deployment names that are not valid {@link com.google.adk.models.LlmRegistry} keys by
   * themselves).
   */
  public BaseLlm primary() {
    return resolve(0);
  }

  /** The ordered model names in the failover chain (primary first). For diagnostics. */
  public List<String> chainModelNames() {
    List<String> names = new ArrayList<>(chain.size());
    for (int i = 0; i < chain.size(); i++) {
      names.add(safeModelName(i));
    }
    return names;
  }

  private BaseLlm resolve(int index) {
    return Objects.requireNonNull(
        chain.get(index).get(), "Failover chain supplier returned null at index " + index);
  }

  private String safeModelName(int index) {
    try {
      return resolve(index).model();
    } catch (RuntimeException e) {
      return "<unresolved-" + index + ">";
    }
  }

  private static Throwable unwrapForPropagation(Throwable throwable) {
    if (throwable instanceof InBandFailureException) {
      LlmError error = ((InBandFailureException) throwable).error();
      return error
          .cause()
          .orElseGet(() -> new IllegalStateException("LLM in-band error: " + error.message()));
    }
    return throwable;
  }

  private static List<Supplier<BaseLlm>> toSuppliers(BaseLlm primary, List<BaseLlm> fallbacks) {
    List<Supplier<BaseLlm>> suppliers = new ArrayList<>();
    suppliers.add(() -> primary);
    if (fallbacks != null) {
      for (BaseLlm fallback : fallbacks) {
        suppliers.add(() -> fallback);
      }
    }
    return suppliers;
  }

  private static String primaryModelName(List<Supplier<BaseLlm>> chain) {
    if (chain == null || chain.isEmpty()) {
      throw new IllegalArgumentException("FailoverLlm requires at least a primary model.");
    }
    try {
      BaseLlm primary = chain.get(0).get();
      return primary == null ? "failover" : primary.model();
    } catch (RuntimeException e) {
      // Primary not yet resolvable (lazy); name is informational only.
      return "failover";
    }
  }

  /** Carries a normalized in-band {@link LlmError} through the RxJava error channel. */
  private static final class InBandFailureException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final transient LlmError error;

    InBandFailureException(LlmError error) {
      super(error.message());
      this.error = error;
    }

    LlmError error() {
      return error;
    }
  }

  /** Fluent builder for {@link FailoverLlm}. */
  public static final class Builder {
    private final List<Supplier<BaseLlm>> chain = new ArrayList<>();
    private FailoverConfig config = FailoverConfig.defaults();

    private Builder() {}

    public Builder primary(BaseLlm primary) {
      Objects.requireNonNull(primary, "primary");
      add(() -> primary);
      return this;
    }

    public Builder primary(Supplier<BaseLlm> primary) {
      add(primary);
      return this;
    }

    public Builder fallback(BaseLlm fallback) {
      Objects.requireNonNull(fallback, "fallback");
      add(() -> fallback);
      return this;
    }

    public Builder fallback(Supplier<BaseLlm> fallback) {
      add(fallback);
      return this;
    }

    public Builder config(FailoverConfig config) {
      this.config = config == null ? FailoverConfig.defaults() : config;
      return this;
    }

    private void add(Supplier<BaseLlm> supplier) {
      chain.add(Objects.requireNonNull(supplier, "model supplier"));
    }

    public FailoverLlm build() {
      if (chain.isEmpty()) {
        throw new IllegalStateException("FailoverLlm.Builder requires at least a primary model.");
      }
      return new FailoverLlm(chain, config);
    }
  }
}
