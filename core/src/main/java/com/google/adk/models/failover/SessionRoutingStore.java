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

import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.Striped;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;

/** Application-owned session assignments shared by recreated agents and model wrappers. */
public final class SessionRoutingStore {
  /**
   * ADK session identity scoped to an application, agent and ordered model chain. Session IDs must
   * be unique within the application; user identity is not part of routing.
   */
  public record SessionRoutingKey(
      String appName, String sessionId, String agentName, String chainId) {
    public SessionRoutingKey {
      Objects.requireNonNull(appName, "appName");
      Objects.requireNonNull(sessionId, "sessionId");
      Objects.requireNonNull(agentName, "agentName");
      Objects.requireNonNull(chainId, "chainId");
    }
  }

  /** Generation fences late completions after an assignment has expired and been replaced. */
  public record Assignment(int offset, String generation) {}

  private final Cache<SessionRoutingKey, Assignment> defaultOffsetSessions;
  private final Cache<SessionRoutingKey, Assignment> experimentalOffsetSessions;
  private final Striped<Lock> locks = Striped.lock(256);
  private final DoubleSupplier random;
  private final double healthyPrimaryProbability;
  private final double primaryProbeProbability;
  private final Double primaryTrafficProbability;

  public SessionRoutingStore() {
    this(0.05);
  }

  /**
   * Probability of probing the primary when unhealthy; healthy primaries receive all new sessions.
   */
  public SessionRoutingStore(double primaryProbeProbability) {
    this(
        () -> ThreadLocalRandom.current().nextDouble(),
        Ticker.systemTicker(),
        primaryProbeProbability);
  }

  /** Healthy primaries receive all new sessions; unhealthy primaries receive the probe share. */
  public static SessionRoutingStore withPrimaryProbeProbability(double primaryProbeProbability) {
    return new SessionRoutingStore(primaryProbeProbability);
  }

  /**
   * Configures primary selection for new sessions separately for each circuit health state. Both
   * probabilities must be finite and between 0 and 1, inclusive. The first fallback receives the
   * remaining share. Existing assignments remain sticky until expiry or successful failover.
   * Without a circuit breaker, the primary is considered healthy.
   *
   * <p>For example, {@code (1.0, 0.05)} routes all new sessions to a healthy primary and probes an
   * unhealthy primary with five percent of new sessions. Applications own configuration loading.
   */
  public static SessionRoutingStore withHealthBasedPrimaryProbabilities(
      double healthyPrimaryProbability, double unhealthyPrimaryProbability) {
    return withHealthBasedPrimaryProbabilities(
        healthyPrimaryProbability,
        unhealthyPrimaryProbability,
        () -> ThreadLocalRandom.current().nextDouble(),
        Ticker.systemTicker());
  }

  static SessionRoutingStore withHealthBasedPrimaryProbabilities(
      double healthyPrimaryProbability,
      double unhealthyPrimaryProbability,
      DoubleSupplier random,
      Ticker ticker) {
    return new SessionRoutingStore(
        random, ticker, healthyPrimaryProbability, unhealthyPrimaryProbability, null);
  }

  SessionRoutingStore(DoubleSupplier random, Ticker ticker) {
    this(random, ticker, 0.05);
  }

  /**
   * Selects the initial model independently of circuit health: 1 means primary, 0 means first
   * fallback. Assignments remain session-sticky and failures still advance through the chain.
   */
  public static SessionRoutingStore withPrimaryTrafficProbability(
      double primaryTrafficProbability) {
    return withPrimaryTrafficProbability(
        primaryTrafficProbability,
        () -> ThreadLocalRandom.current().nextDouble(),
        Ticker.systemTicker());
  }

  static SessionRoutingStore withPrimaryTrafficProbability(
      double primaryTrafficProbability, DoubleSupplier random, Ticker ticker) {
    if (!Double.isFinite(primaryTrafficProbability)
        || primaryTrafficProbability < 0
        || primaryTrafficProbability > 1) {
      throw new IllegalArgumentException("Primary traffic probability must be between 0 and 1");
    }
    return new SessionRoutingStore(random, ticker, 1.0, 0.05, primaryTrafficProbability);
  }

  SessionRoutingStore(DoubleSupplier random, Ticker ticker, double primaryProbeProbability) {
    this(random, ticker, 1.0, primaryProbeProbability, null);
  }

  private SessionRoutingStore(
      DoubleSupplier random,
      Ticker ticker,
      double healthyPrimaryProbability,
      double primaryProbeProbability,
      Double primaryTrafficProbability) {
    if (!Double.isFinite(healthyPrimaryProbability)
        || healthyPrimaryProbability < 0
        || healthyPrimaryProbability > 1) {
      throw new IllegalArgumentException("Healthy primary probability must be between 0 and 1");
    }
    if (!Double.isFinite(primaryProbeProbability)
        || primaryProbeProbability < 0
        || primaryProbeProbability > 1) {
      throw new IllegalArgumentException("Primary probe probability must be between 0 and 1");
    }
    this.healthyPrimaryProbability = healthyPrimaryProbability;
    this.primaryProbeProbability = primaryProbeProbability;
    this.primaryTrafficProbability = primaryTrafficProbability;
    this.random = Objects.requireNonNull(random, "random");
    this.defaultOffsetSessions =
        CacheBuilder.newBuilder().expireAfterWrite(10, TimeUnit.MINUTES).ticker(ticker).build();
    this.experimentalOffsetSessions =
        CacheBuilder.newBuilder().expireAfterWrite(10, TimeUnit.MINUTES).ticker(ticker).build();
  }

  /** Samples once for a missing key; cached reads do not extend the ten-minute TTL. */
  public Assignment getOrAssign(SessionRoutingKey key, BooleanSupplier primaryHealthy) {
    Lock lock = locks.get(key);
    lock.lock();
    try {
      Assignment existing = cachedAssignment(key);
      if (existing != null) return existing;
      int offset;
      if (primaryTrafficProbability != null) {
        offset = random.getAsDouble() < primaryTrafficProbability ? 0 : 1;
      } else {
        boolean healthy = primaryHealthy.getAsBoolean();
        double sample = random.getAsDouble();
        double probability = healthy ? healthyPrimaryProbability : primaryProbeProbability;
        offset = sample < probability ? 0 : 1;
      }
      Assignment assignment = new Assignment(offset, UUID.randomUUID().toString());
      (offset == 0 ? defaultOffsetSessions : experimentalOffsetSessions).put(key, assignment);
      return assignment;
    } finally {
      lock.unlock();
    }
  }

  /** Pins a successfully completed fallback without overwriting a newer assignment. */
  public void rememberSuccessfulFallback(SessionRoutingKey key, Assignment expected, int offset) {
    if (offset <= 0) return;
    Lock lock = locks.get(key);
    lock.lock();
    try {
      Assignment current = cachedAssignment(key);
      if (current == null
          || !current.generation().equals(expected.generation())
          || current.offset() >= offset) return;
      defaultOffsetSessions.invalidate(key);
      experimentalOffsetSessions.put(key, new Assignment(offset, current.generation()));
    } finally {
      lock.unlock();
    }
  }

  private Assignment cachedAssignment(SessionRoutingKey key) {
    Assignment assignment = defaultOffsetSessions.getIfPresent(key);
    return assignment == null ? experimentalOffsetSessions.getIfPresent(key) : assignment;
  }

  /** Stable ordered configuration identity; length framing prevents ambiguous concatenations. */
  public static String chainId(List<String> modelSpecs) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String spec : modelSpecs) {
        byte[] bytes = spec.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }
}
