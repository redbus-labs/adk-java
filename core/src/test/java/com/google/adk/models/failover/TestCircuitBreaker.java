package com.google.adk.models.failover;

import java.util.function.Predicate;

/**
 * Deterministic admission double for wrapper tests; production supplies a CircuitBreaker
 * implementation.
 */
class TestCircuitBreaker implements CircuitBreaker {
  @Override
  public boolean isHealthy(String name) {
    return true;
  }

  @Override
  public void onSuccess(String name, String id) {}

  @Override
  public void onFailure(String name, String id) {}

  static CircuitBreaker healthyWhen(Predicate<String> health) {
    return new TestCircuitBreaker() {
      @Override
      public boolean isHealthy(String name) {
        return health.test(name);
      }
    };
  }
}
