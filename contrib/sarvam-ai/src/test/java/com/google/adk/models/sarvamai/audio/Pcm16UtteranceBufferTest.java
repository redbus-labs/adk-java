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

package com.google.adk.models.sarvamai.audio;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class Pcm16UtteranceBufferTest {
  private static byte[] speech() {
    return new byte[] {0, 4, 0, 4};
  }

  @Test
  void silenceBoundaryWorksWithoutMoreAudioAndFiresOnce() {
    var now = new AtomicLong(1);
    var ended = new AtomicInteger();
    Object monitor = new Object();
    try (var capture =
        new Pcm16UtteranceBuffer(
            400,
            100,
            4,
            32,
            8,
            () -> {
              assertTrue(Thread.holdsLock(monitor));
              ended.incrementAndGet();
            },
            now::get,
            monitor)) {
      capture.append(speech());
      assertTrue(capture.isSpeechActive());
      now.set(100);
      capture.checkSilence();
      assertEquals(0, ended.get());
      now.set(101);
      capture.checkSilence();
      capture.checkSilence();
      assertEquals(1, ended.get());
      assertArrayEquals(speech(), capture.drain());
      assertFalse(capture.isSpeechActive());
      assertEquals(0, capture.size());
    }
  }

  @Test
  void shortNoiseDoesNotEndTurnAndIdleCaptureIsBounded() {
    var now = new AtomicLong(1);
    var ended = new AtomicInteger();
    try (var capture =
        new Pcm16UtteranceBuffer(400, 100, 8, 32, 8, ended::incrementAndGet, now::get)) {
      for (int i = 0; i < 100; i++) capture.append(new byte[4]);
      assertTrue(capture.size() <= 12);
      capture.reset();
      capture.append(speech());
      now.set(201);
      capture.checkSilence();
      assertEquals(0, ended.get());
    }
  }

  @Test
  void sizeLimitPreservesCapturedAudioAndCloseStopsDetection() {
    var now = new AtomicLong(1);
    var ended = new AtomicInteger();
    var capture = new Pcm16UtteranceBuffer(400, 100, 4, 8, 4, ended::incrementAndGet, now::get);
    try {
      capture.append(speech());
      capture.append(speech());
      capture.append(speech());
      assertEquals(1, ended.get());
      assertEquals(8, capture.size());
      capture.close();
      capture.append(speech());
      now.set(201);
      capture.checkSilence();
      assertEquals(1, ended.get());
      assertEquals(0, capture.size());
    } finally {
      capture.close();
    }
  }
}
