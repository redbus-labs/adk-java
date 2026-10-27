package com.google.adk.models.failover;

import java.util.function.Predicate;

/** Deterministic admission double for wrapper tests; production uses the concrete Redis breaker. */
class TestCircuitBreaker extends DragonflyCircuitBreaker {
  TestCircuitBreaker() {
    super(
        () -> {
          throw new AssertionError("Redis must not be called by this test double");
        },
        90,
        10);
  }

  @Override
  public boolean isHealthy(String name) {
    return true;
  }

  @Override
  public void onSuccess(String name, String id) {}

  @Override
  public void onFailure(String name, String id) {}

  static DragonflyCircuitBreaker healthyWhen(Predicate<String> health) {
    return new TestCircuitBreaker() {
      @Override
      public boolean isHealthy(String name) {
        return health.test(name);
      }
    };
  }
}
