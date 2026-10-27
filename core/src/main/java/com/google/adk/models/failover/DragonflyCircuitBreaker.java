package com.google.adk.models.failover;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Protocol.Command;
import redis.clients.jedis.params.ZAddParams;

/**
 * Model health in Redis; session assignment and recovery sampling belong to SessionRoutingStore.
 */
public class DragonflyCircuitBreaker {
  private static final Logger logger = LoggerFactory.getLogger(DragonflyCircuitBreaker.class);
  private final Supplier<Jedis> connector;
  private final long windowSeconds;
  private final long failureThreshold;
  private final long recoverySuccessThreshold;

  public DragonflyCircuitBreaker(
      Supplier<Jedis> connector, long windowSeconds, long failureThreshold) {
    this(connector, windowSeconds, failureThreshold, 2);
  }

  public DragonflyCircuitBreaker(
      Supplier<Jedis> connector,
      long windowSeconds,
      long failureThreshold,
      long recoverySuccessThreshold) {
    if (windowSeconds <= 0
        || windowSeconds > Long.MAX_VALUE / 2
        || failureThreshold < 0
        || recoverySuccessThreshold <= 0) {
      throw new IllegalArgumentException("Invalid failure window, threshold or recovery threshold");
    }
    this.connector = Objects.requireNonNull(connector);
    this.windowSeconds = windowSeconds;
    this.failureThreshold = failureThreshold;
    this.recoverySuccessThreshold = recoverySuccessThreshold;
  }

  /** Missing state is healthy. A Redis outage must not prevent a model call. */
  public boolean isHealthy(String modelName) {
    try (Jedis jedis = connector.get()) {
      return !"1".equals(jedis.hget(keys(modelName).get(2), "open"));
    } catch (RuntimeException error) {
      logger.warn("Could not check circuit; allowing model={}", modelName, error);
      return true;
    }
  }

  public void onSuccess(String modelName, String attemptId) {
    record(modelName, attemptId, true);
  }

  public void onFailure(String modelName, String attemptId) {
    record(modelName, attemptId, false);
  }

  /** Direct Redis commands are best-effort; concurrent outcomes may interleave. */
  private void record(String modelName, String attemptId, boolean success) {
    try (Jedis jedis = connector.get()) {
      List<String> keys = keys(modelName);
      // Dragonfly may return TIME as integers; Redis normally returns bulk strings.
      List<?> time = (List<?>) jedis.sendCommand(Command.TIME);
      double now = number(time.get(0)) + number(time.get(1)) / 1_000_000.0;
      double cutoff = now - windowSeconds;
      jedis.zremrangeByScore(keys.get(0), Double.NEGATIVE_INFINITY, cutoff);
      jedis.zremrangeByScore(keys.get(1), Double.NEGATIVE_INFINITY, cutoff);
      String history = keys.get(success ? 1 : 0);
      long added = jedis.zadd(history, now, attemptId, ZAddParams.zAddParams().nx());
      jedis.expire(keys.get(0), windowSeconds * 2);
      jedis.expire(keys.get(1), windowSeconds * 2);
      if (added == 0) return; // Do not count duplicate notifications twice in the same history.
      boolean open = "1".equals(jedis.hget(keys.get(2), "open"));
      long count = jedis.zcount(history, cutoff, now);
      if ((!success && !open && count > failureThreshold)
          || (success && open && count >= recoverySuccessThreshold)) {
        jedis.hset(keys.get(2), "open", success ? "0" : "1");
        jedis.del(keys.get(0), keys.get(1));
        logger.info(
            "Circuit transition: model={}, state={}, outcome={}, windowSeconds={}",
            modelName,
            success ? "CLOSED" : "OPEN",
            success ? "SUCCESS" : "FAILURE",
            windowSeconds);
      }
    } catch (RuntimeException error) {
      logger.warn(
          "Could not record circuit outcome: model={}, success={}", modelName, success, error);
    }
  }

  private static double number(Object value) {
    return Double.parseDouble(
        value instanceof byte[] bytes
            ? new String(bytes, StandardCharsets.UTF_8)
            : value.toString());
  }

  // Keep the existing keys and hash-tag placement compatible with deployed Redis state.
  static List<String> keys(String modelName) {
    Objects.requireNonNull(modelName, "modelName");
    if (modelName.isBlank() || modelName.indexOf('{') >= 0 || modelName.indexOf('}') >= 0) {
      throw new IllegalArgumentException(
          "Model name must be nonblank and cannot contain Redis hash-tag braces");
    }
    String base = "rae:llm:{" + modelName + "}";
    return List.of(base + "_FAILED", base + "_SUCCESS", base + "_STATE");
  }
}
