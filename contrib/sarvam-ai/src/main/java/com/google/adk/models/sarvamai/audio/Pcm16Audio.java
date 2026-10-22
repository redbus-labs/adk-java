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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/** PCM16 container and amplitude operations shared by speech clients and audio artifact writers. */
public final class Pcm16Audio {
  private Pcm16Audio() {}

  public static byte[] toWav(byte[] pcm, int sampleRate, int channels) {
    Objects.requireNonNull(pcm, "pcm must not be null");
    if (sampleRate <= 0 || channels <= 0 || channels > 32767 / 2) {
      throw new IllegalArgumentException("Invalid PCM sample rate or channel count");
    }
    int blockAlign = Math.multiplyExact(channels, 2);
    if (pcm.length % blockAlign != 0) {
      throw new IllegalArgumentException("PCM must contain whole sample frames");
    }
    int byteRate = Math.multiplyExact(sampleRate, blockAlign);
    ByteBuffer wav =
        ByteBuffer.allocate(Math.addExact(44, pcm.length)).order(ByteOrder.LITTLE_ENDIAN);
    wav.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(Math.addExact(36, pcm.length));
    wav.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
    wav.putShort((short) 1).putShort((short) channels).putInt(sampleRate).putInt(byteRate);
    wav.putShort((short) blockAlign).putShort((short) 16);
    wav.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length).put(pcm);
    return wav.array();
  }

  public static boolean isWav(byte[] bytes) {
    return bytes != null
        && bytes.length >= 12
        && bytes[0] == 'R'
        && bytes[1] == 'I'
        && bytes[2] == 'F'
        && bytes[3] == 'F'
        && bytes[8] == 'W'
        && bytes[9] == 'A'
        && bytes[10] == 'V'
        && bytes[11] == 'E';
  }

  /** Returns the data chunk of a WAV container, or the input when it is already raw PCM. */
  public static byte[] extract(byte[] bytes) {
    if (bytes == null) return new byte[0];
    if (!isWav(bytes)) return bytes;
    ByteBuffer wav = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    for (long offset = 12; offset + 8 <= bytes.length; ) {
      int index = (int) offset;
      long size = Integer.toUnsignedLong(wav.getInt(index + 4));
      long end = offset + 8 + size;
      if (end > bytes.length) throw new IllegalArgumentException("Truncated WAV chunk");
      if (bytes[index] == 'd'
          && bytes[index + 1] == 'a'
          && bytes[index + 2] == 't'
          && bytes[index + 3] == 'a') {
        if ((size & 1) != 0) throw new IllegalArgumentException("Truncated PCM16 sample");
        return Arrays.copyOfRange(bytes, index + 8, (int) end);
      }
      offset = end + (size & 1);
    }
    throw new IllegalArgumentException("WAV container has no data chunk");
  }

  public static double meanAbsoluteAmplitude(byte[] pcm) {
    if (pcm == null || pcm.length < 2) return 0;
    long sum = 0;
    for (int i = 0; i + 1 < pcm.length; i += 2) {
      int sample = (short) ((pcm[i] & 255) | (pcm[i + 1] << 8));
      sum += Math.abs(sample);
    }
    return (double) sum / (pcm.length / 2);
  }
}
