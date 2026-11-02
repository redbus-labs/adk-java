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

package com.google.adk.models;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.google.adk.agents.InvocationContext;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.jupiter.api.Test;

class BaseLlmContextTest {
  @Test
  void contextOverloadDelegatesToExistingProviderApi() {
    LlmRequest request = LlmRequest.builder().build();
    LlmResponse response = LlmResponse.builder().build();
    BaseLlm provider =
        new BaseLlm("legacy") {
          @Override
          public Flowable<LlmResponse> generateContent(LlmRequest actual, boolean stream) {
            assertSame(request, actual);
            assertTrue(stream);
            return Flowable.just(response);
          }

          @Override
          public BaseLlmConnection connect(LlmRequest ignored) {
            throw new UnsupportedOperationException();
          }
        };
    assertSame(
        response,
        provider.generateContent(request, true, mock(InvocationContext.class)).blockingFirst());
  }
}
