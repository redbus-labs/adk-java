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

package com.google.adk.models.errors;

import static org.junit.jupiter.api.Assertions.*;

import com.google.adk.models.LlmResponse;
import com.google.genai.types.FinishReason;
import org.junit.jupiter.api.Test;

class LlmErrorClassifierTest {
  @Test
  void mapsTransportStatusesWithoutChangingPolicy() {
    assertCategory(401, LlmFailureCategory.AUTH);
    assertCategory(403, LlmFailureCategory.AUTH);
    assertCategory(408, LlmFailureCategory.TIMEOUT);
    assertCategory(429, LlmFailureCategory.RATE_LIMIT);
    assertCategory(503, LlmFailureCategory.UNAVAILABLE);
    assertCategory(400, LlmFailureCategory.BAD_REQUEST);
    assertCategory(404, LlmFailureCategory.BAD_REQUEST);
    assertCategory(499, LlmFailureCategory.BAD_REQUEST);
    assertCategory(500, LlmFailureCategory.SERVER_ERROR);
    assertCategory(599, LlmFailureCategory.SERVER_ERROR);
    assertCategory(-1, LlmFailureCategory.UNKNOWN);
    assertCategory(200, LlmFailureCategory.UNKNOWN);
    assertCategory(600, LlmFailureCategory.UNKNOWN);
  }

  @Test
  void inBandBlockReasonIsNotAnHttpStatus() {
    LlmError error =
        LlmErrorClassifier.classifyInBand(
                "gemini-test",
                LlmResponse.builder()
                    .errorCode(new FinishReason("SAFETY"))
                    .errorMessage("blocked")
                    .build())
            .orElseThrow();
    assertEquals(-1, error.httpStatus());
    assertEquals("SAFETY", error.providerCode().orElseThrow());
    assertEquals(LlmFailureCategory.CONTENT_BLOCKED, error.category());
    assertFalse(error.isRetryable());
  }

  @Test
  void successfulResponseIsNotClassifiedAsAnError() {
    assertTrue(LlmErrorClassifier.classifyInBand("model", LlmResponse.builder().build()).isEmpty());
  }

  private static void assertCategory(int status, LlmFailureCategory expected) {
    assertEquals(expected, LlmErrorClassifier.categoryForStatus(status));
    assertEquals(
        expected,
        LlmErrorClassifier.classify("model", new LlmHttpException(status, "failed", null))
            .category());
  }
}
