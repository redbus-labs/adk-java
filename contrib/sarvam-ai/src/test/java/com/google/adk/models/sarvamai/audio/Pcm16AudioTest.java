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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class Pcm16AudioTest {
  @Test
  void wavRoundTripAndFrameValidation() {
    byte[] pcm = {0, 1, 2, 3};
    byte[] wav = Pcm16Audio.toWav(pcm, 16000, 1);
    assertTrue(Pcm16Audio.isWav(wav));
    assertArrayEquals(pcm, Pcm16Audio.extract(wav));
    assertEquals(16000, ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getInt(24));
    assertThrows(IllegalArgumentException.class, () -> Pcm16Audio.toWav(new byte[1], 16000, 1));
    assertThrows(IllegalArgumentException.class, () -> Pcm16Audio.toWav(new byte[2], 16000, 2));
  }

  @Test
  void extractionSkipsMetadataAndOddChunkPadding() {
    byte[] pcm = {1, 2, 3, 4};
    byte[] wav = Pcm16Audio.toWav(pcm, 16000, 1);
    var out = ByteBuffer.allocate(wav.length + 10).order(ByteOrder.LITTLE_ENDIAN);
    out.put(wav, 0, 36);
    out.putInt(4, out.capacity() - 8);
    out.put(new byte[] {'J', 'U', 'N', 'K'}).putInt(1).put((byte) 9).put((byte) 0);
    out.put(wav, 36, wav.length - 36);
    assertArrayEquals(pcm, Pcm16Audio.extract(out.array()));
    out.putInt(40, Integer.MAX_VALUE);
    assertThrows(IllegalArgumentException.class, () -> Pcm16Audio.extract(out.array()));
  }

  @Test
  void amplitudeUsesSignedSamplesIncludingMinimumValue() {
    assertEquals(32768, Pcm16Audio.meanAbsoluteAmplitude(new byte[] {0, (byte) 128}));
    assertEquals(0, Pcm16Audio.meanAbsoluteAmplitude(null));
  }
}
