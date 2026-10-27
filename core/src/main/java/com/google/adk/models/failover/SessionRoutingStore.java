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
  /** Logical identity, independent of Java object or invocation identity. */
  public record SessionRoutingKey(
      String applicationName, String mriSessionId, String agentName, String chainId) {
    public SessionRoutingKey {
      Objects.requireNonNull(applicationName, "applicationName");
      Objects.requireNonNull(mriSessionId, "mriSessionId");
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
  private final double routingFlipProbability;

  public SessionRoutingStore() {
    this(0.05);
  }

  /** Probability of choosing fallback when healthy, or primary when unhealthy; range [0, 1]. */
  public SessionRoutingStore(double routingFlipProbability) {
    this(
        () -> ThreadLocalRandom.current().nextDouble(),
        Ticker.systemTicker(),
        routingFlipProbability);
  }

  SessionRoutingStore(DoubleSupplier random, Ticker ticker) {
    this(random, ticker, 0.05);
  }

  SessionRoutingStore(DoubleSupplier random, Ticker ticker, double routingFlipProbability) {
    if (!Double.isFinite(routingFlipProbability)
        || routingFlipProbability < 0
        || routingFlipProbability > 1) {
      throw new IllegalArgumentException("Routing flip probability must be between 0 and 1");
    }
    this.routingFlipProbability = routingFlipProbability;
    this.random = Objects.requireNonNull(random, "random");
    this.defaultOffsetSessions =
        CacheBuilder.newBuilder().expireAfterWrite(1, TimeUnit.MINUTES).ticker(ticker).build();
    this.experimentalOffsetSessions =
        CacheBuilder.newBuilder().expireAfterWrite(1, TimeUnit.MINUTES).ticker(ticker).build();
  }

  /** Samples once for a missing key; cached reads do not extend the one-minute TTL. */
  public Assignment getOrAssign(SessionRoutingKey key, BooleanSupplier primaryHealthy) {
    Lock lock = locks.get(key);
    lock.lock();
    try {
      Assignment existing = cachedAssignment(key);
      if (existing != null) return existing;
      int offset = primaryHealthy.getAsBoolean() ? 0 : 1;
      if (random.getAsDouble() < routingFlipProbability) offset = 1 - offset;
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
