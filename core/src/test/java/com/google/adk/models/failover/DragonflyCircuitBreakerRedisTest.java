package com.google.adk.models.failover;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import redis.clients.jedis.Jedis;

/** Run only against an explicitly supplied isolated loopback Redis, never application Redis. */
@EnabledIfSystemProperty(named = "llm.circuit.test.port", matches = "[0-9]+")
class DragonflyCircuitBreakerRedisTest {
  private final Supplier<Jedis> connector = mock(Supplier.class);
  private final String model = "test-" + UUID.randomUUID();
  private final List<String> keys = DragonflyCircuitBreaker.keys(model);
  private int port;
  private DragonflyCircuitBreaker breaker;

  private Jedis redis() {
    return new Jedis("127.0.0.1", port);
  }

  @BeforeEach
  void setup() {
    port = Integer.parseInt(System.getProperty("llm.circuit.test.port"));
    when(connector.get()).thenAnswer(invocation -> redis());
    breaker = new DragonflyCircuitBreaker(connector, 90, 10, 2);
  }

  @AfterEach
  void cleanup() {
    try (Jedis jedis = redis()) {
      jedis.del(keys.toArray(String[]::new));
    }
  }

  private void openCircuit() {
    for (int i = 0; i < 11; i++) breaker.onFailure(model, "failure-" + i);
    assertFalse(breaker.isHealthy(model));
  }

  private double now(Jedis jedis) {
    List<String> time = jedis.time();
    return Double.parseDouble(time.get(0)) + Double.parseDouble(time.get(1)) / 1_000_000;
  }

  @Test
  void opensOnEleventhFailureAndClearsBothOutcomeSets() {
    breaker.onSuccess(model, "healthy-success");
    for (int i = 0; i < 10; i++) breaker.onFailure(model, "failure-" + i);
    assertTrue(breaker.isHealthy(model));
    breaker.onFailure(model, "failure-10");
    assertFalse(breaker.isHealthy(model));
    try (Jedis jedis = redis()) {
      assertEquals(0, jedis.zcard(keys.get(0)));
      assertEquals(0, jedis.zcard(keys.get(1)));
      assertEquals("1", jedis.hget(keys.get(2), "open"));
    }
  }

  @Test
  void twoDistinctProbesCloseCircuitAndClearRecoveryHistory() {
    openCircuit();

    breaker.onSuccess(model, "probe-1");
    breaker.onSuccess(model, "probe-1");
    assertFalse(breaker.isHealthy(model));
    breaker.onFailure(model, "failed-probe");
    breaker.onSuccess(model, "probe-2");
    assertTrue(breaker.isHealthy(model));
    try (Jedis jedis = redis()) {
      assertEquals(0, jedis.zcard(keys.get(0)));
      assertEquals(0, jedis.zcard(keys.get(1)));
    }
  }

  @Test
  void successesOutsideLastNinetySecondsCannotCloseCircuit() {
    openCircuit();

    breaker.onSuccess(model, "old-probe");
    try (Jedis jedis = redis()) {
      jedis.zadd(keys.get(1), now(jedis) - 91, "old-probe");
    }
    breaker.onSuccess(model, "new-probe");
    assertFalse(breaker.isHealthy(model));
    try (Jedis jedis = redis()) {
      assertEquals(1, jedis.zcard(keys.get(1)));
    }
    breaker.onSuccess(model, "second-new-probe");
    assertTrue(breaker.isHealthy(model));
  }

  @Test
  void expiringAllHistoryDoesNotAutomaticallyCloseOpenCircuit() {
    openCircuit();
    try (Jedis jedis = redis()) {
      // Simulate the history keys expiring while the persistent state remains open.
      jedis.del(keys.get(0), keys.get(1));
    }
    assertFalse(breaker.isHealthy(model));
    try (Jedis jedis = redis()) {
      assertEquals(0, jedis.zcard(keys.get(0)));
      assertEquals(0, jedis.zcard(keys.get(1)));
      assertEquals(-1, jedis.ttl(keys.get(2)));
    }
  }

  @Test
  void expiredFailuresDoNotOpenHealthyCircuit() {
    try (Jedis jedis = redis()) {
      for (int i = 0; i < 20; i++) jedis.zadd(keys.get(0), now(jedis) - 91, "old-" + i);
    }
    breaker.onFailure(model, "new-failure");
    assertTrue(breaker.isHealthy(model));
    try (Jedis jedis = redis()) {
      assertEquals(1, jedis.zcard(keys.get(0)));
    }
  }

  @Test
  void modelsAreIsolatedAndHistoryHasBoundedRetention() {
    openCircuit();
    String other = model + "-other";
    List<String> otherKeys = DragonflyCircuitBreaker.keys(other);
    try {
      assertTrue(breaker.isHealthy(other));

      breaker.onSuccess(other, "success");
      try (Jedis jedis = redis()) {
        assertTrue(jedis.ttl(otherKeys.get(1)) > 0);
        assertTrue(jedis.ttl(otherKeys.get(1)) <= 180);
      }
      assertFalse(breaker.isHealthy(model));
    } finally {
      try (Jedis jedis = redis()) {
        jedis.del(otherKeys.toArray(String[]::new));
      }
    }
  }

  @Test
  void failoverRoutesAroundOpenPrimaryAndTwoCompletedProbesRestoreItWithoutDatabaseContext() {
    var primaryCalls = new java.util.concurrent.atomic.AtomicInteger();
    var fallbackCalls = new java.util.concurrent.atomic.AtomicInteger();
    var failPrimary = new java.util.concurrent.atomic.AtomicBoolean(true);
    BaseLlm primary =
        new BaseLlm(model) {
          @Override
          public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
            primaryCalls.incrementAndGet();
            return failPrimary.get()
                ? Flowable.error(new LlmHttpException(503, "unavailable", null))
                : Flowable.just(LlmResponse.builder().build());
          }

          @Override
          public BaseLlmConnection connect(LlmRequest request) {
            throw new UnsupportedOperationException();
          }
        };
    String fallbackName = model + "-fallback";
    BaseLlm fallback =
        new BaseLlm(fallbackName) {
          @Override
          public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
            fallbackCalls.incrementAndGet();
            return Flowable.just(LlmResponse.builder().build());
          }

          @Override
          public BaseLlmConnection connect(LlmRequest request) {
            throw new UnsupportedOperationException();
          }
        };
    var skipBreaker = new DragonflyCircuitBreaker(connector, 90, 0, 2);
    // Test double permits probes without adding a session context to this provider-only test.
    DragonflyCircuitBreaker probeBreaker =
        new DragonflyCircuitBreaker(connector, 90, 0, 2) {
          @Override
          public boolean isHealthy(String name) {
            return true;
          }
        };
    var skipAgent =
        new FailoverLlm(
            primary,
            List.of(fallback),
            FailoverConfig.builder().circuitBreaker(skipBreaker).build());
    var probeAgent =
        new FailoverLlm(
            primary,
            List.of(fallback),
            FailoverConfig.builder().circuitBreaker(probeBreaker).build());
    LlmRequest request = LlmRequest.builder().model(model).build();
    try {
      assertEquals(
          fallbackName,
          skipAgent
              .generateContent(request, false)
              .toList()
              .blockingGet()
              .get(0)
              .modelVersion()
              .orElseThrow());
      assertFalse(skipBreaker.isHealthy(model));
      skipAgent.generateContent(request, false).toList().blockingGet();
      assertEquals(1, primaryCalls.get());
      failPrimary.set(false);
      probeAgent.generateContent(request, false).toList().blockingGet();
      assertFalse(skipBreaker.isHealthy(model));
      probeAgent.generateContent(request, false).toList().blockingGet();
      assertTrue(skipBreaker.isHealthy(model));
      assertEquals(
          model,
          skipAgent
              .generateContent(request, false)
              .toList()
              .blockingGet()
              .get(0)
              .modelVersion()
              .orElseThrow());
      assertEquals(4, primaryCalls.get());
      assertEquals(2, fallbackCalls.get());
    } finally {
      try (Jedis jedis = redis()) {
        jedis.del(DragonflyCircuitBreaker.keys(fallbackName).toArray(String[]::new));
      }
    }
  }
}
