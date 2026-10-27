package com.google.adk.models.failover;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.ZAddParams;

class DragonflyCircuitBreakerTest {
  private final Supplier<Jedis> connector = mock(Supplier.class);
  private final Jedis jedis = mock(Jedis.class);
  private final List<String> keys = DragonflyCircuitBreaker.keys("primary");
  private final DragonflyCircuitBreaker breaker = new DragonflyCircuitBreaker(connector, 90, 10);

  @BeforeEach
  void setup() {
    when(connector.get()).thenReturn(jedis);
    when(jedis.sendCommand(redis.clients.jedis.Protocol.Command.TIME))
        .thenReturn(List.of(1000L, 500000L));
    when(jedis.zadd(anyString(), anyDouble(), anyString(), any(ZAddParams.class))).thenReturn(1L);
  }

  @Test
  void missingStateIsHealthyAndOpenStateIsUnhealthy() {
    assertTrue(breaker.isHealthy("primary"));
    when(jedis.hget(keys.get(2), "open")).thenReturn("1");
    assertFalse(breaker.isHealthy("primary"));
    when(jedis.hget(keys.get(2), "open")).thenReturn("0");
    assertTrue(breaker.isHealthy("primary"));
    verify(jedis, times(3)).close();
  }

  @Test
  void tenthFailureDoesNotOpenButEleventhDoes() {
    when(jedis.zcount(eq(keys.get(0)), anyDouble(), anyDouble())).thenReturn(10L, 11L);
    breaker.onFailure("primary", "failure-10");
    verify(jedis, never()).hset(anyString(), anyString(), anyString());
    breaker.onFailure("primary", "failure-11");
    verify(jedis).hset(keys.get(2), "open", "1");
    verify(jedis).del(keys.get(0), keys.get(1));
  }

  @Test
  void secondSuccessClosesOpenCircuitAndClearsHistories() {
    when(jedis.hget(keys.get(2), "open")).thenReturn("1");
    when(jedis.zcount(eq(keys.get(1)), anyDouble(), anyDouble())).thenReturn(1L, 2L);
    breaker.onSuccess("primary", "probe-1");
    verify(jedis, never()).hset(anyString(), anyString(), anyString());
    breaker.onSuccess("primary", "probe-2");
    verify(jedis).hset(keys.get(2), "open", "0");
    verify(jedis).del(keys.get(0), keys.get(1));
  }

  @Test
  void recordsRedisServerTimestampAndPrunesWindowWithoutExpiringState() {
    breaker.onFailure("primary", "attempt");
    verify(jedis).zadd(eq(keys.get(0)), eq(1000.5), eq("attempt"), any(ZAddParams.class));
    verify(jedis).zremrangeByScore(keys.get(0), Double.NEGATIVE_INFINITY, 910.5);
    verify(jedis).zremrangeByScore(keys.get(1), Double.NEGATIVE_INFINITY, 910.5);
    verify(jedis).expire(keys.get(0), 180L);
    verify(jedis).expire(keys.get(1), 180L);
    verify(jedis, never()).expire(eq(keys.get(2)), anyLong());
    verify(jedis).close();
  }

  @Test
  void acceptsRedisBulkStringTimeAsWellAsDragonflyIntegerTime() {
    when(jedis.sendCommand(redis.clients.jedis.Protocol.Command.TIME))
        .thenReturn(
            List.of(
                "1000".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "500000".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    breaker.onSuccess("primary", "attempt");
    verify(jedis).zadd(eq(keys.get(1)), eq(1000.5), eq("attempt"), any(ZAddParams.class));
  }

  @Test
  void duplicateNotificationDoesNotTriggerAnotherTransition() {
    when(jedis.zadd(anyString(), anyDouble(), anyString(), any(ZAddParams.class))).thenReturn(0L);
    breaker.onFailure("primary", "duplicate");
    verify(jedis, never()).hset(anyString(), anyString(), anyString());
    verify(jedis, never()).zcount(anyString(), anyDouble(), anyDouble());
  }

  @Test
  void outageAllowsCallsAndOutcomeFailuresDoNotEscape() {
    when(connector.get()).thenThrow(new IllegalStateException("Redis unavailable"));
    assertTrue(breaker.isHealthy("primary"));
    assertDoesNotThrow(() -> breaker.onFailure("primary", "failure"));
    assertDoesNotThrow(() -> breaker.onSuccess("primary", "success"));
  }

  @Test
  void laterLookupRecoversWhenConnectionSupplierRecovers() {
    when(connector.get())
        .thenThrow(new IllegalStateException("Redis unavailable"))
        .thenReturn(jedis);
    when(jedis.hget(keys.get(2), "open")).thenReturn("1");
    assertTrue(breaker.isHealthy("primary"));
    assertFalse(breaker.isHealthy("primary"));
  }

  @Test
  void providerFailureAndFallbackSuccessUpdateRedisWithConsoleRecorderOnly() {
    var previous = LlmCallMetrics.recorder();
    LlmCallMetrics.setRecorder(new LlmCallMetrics.LoggingRecorder());
    try {
      var primary = mock(com.google.adk.models.BaseLlm.class);
      var fallback = mock(com.google.adk.models.BaseLlm.class);
      when(primary.model()).thenReturn("primary");
      when(fallback.model()).thenReturn("fallback");
      when(primary.generateContent(any(), anyBoolean()))
          .thenReturn(
              io.reactivex.rxjava3.core.Flowable.error(
                  new LlmHttpException(503, "unavailable", null)));
      when(fallback.generateContent(any(), anyBoolean()))
          .thenReturn(
              io.reactivex.rxjava3.core.Flowable.just(
                  com.google.adk.models.LlmResponse.builder().build()));
      var llm =
          new FailoverLlm(
              primary, List.of(fallback), FailoverConfig.builder().circuitBreaker(breaker).build());
      var responses =
          llm.generateContent(
                  com.google.adk.models.LlmRequest.builder().build(),
                  false,
                  mock(com.google.adk.agents.InvocationContext.class))
              .toList()
              .blockingGet();
      assertEquals("fallback", responses.get(0).modelVersion().orElseThrow());
      verify(jedis).zadd(eq(keys.get(0)), anyDouble(), anyString(), any(ZAddParams.class));
      verify(jedis)
          .zadd(
              eq(DragonflyCircuitBreaker.keys("fallback").get(1)),
              anyDouble(),
              anyString(),
              any(ZAddParams.class));
    } finally {
      LlmCallMetrics.setRecorder(previous);
    }
  }

  @Test
  void rejectsInvalidConfigurationAndKeepsExistingModelKeys() {
    assertThrows(
        IllegalArgumentException.class, () -> new DragonflyCircuitBreaker(connector, 0, 10));
    assertThrows(
        IllegalArgumentException.class, () -> new DragonflyCircuitBreaker(connector, 90, -1));
    assertThrows(
        IllegalArgumentException.class, () -> new DragonflyCircuitBreaker(connector, 90, 10, 0));
    assertThrows(
        IllegalArgumentException.class, () -> DragonflyCircuitBreaker.keys("model{other}"));
    assertEquals(
        List.of("rae:llm:{primary}_FAILED", "rae:llm:{primary}_SUCCESS", "rae:llm:{primary}_STATE"),
        keys);
  }
}
