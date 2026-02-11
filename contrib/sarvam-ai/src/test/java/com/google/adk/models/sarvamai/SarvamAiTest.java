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

// MODIFIED BY Sandeep Belgavi, 2026-02-11
package com.google.adk.models.sarvamai;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.io.IOException;
import java.util.List;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Before;
import org.junit.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class SarvamAiTest {

  private static final String API_KEY = "test-api-key";
  private static final String MODEL_NAME = "test-model";
  private static final String COMPLETION_TEXT = "Hello, world!";
  private static final String STREAMING_CHUNK_1 =
      "data: {\"choices\": [{\"message\": {\"content\": \"Hello,\"}}]}";
  private static final String STREAMING_CHUNK_2 =
      "data: {\"choices\": [{\"message\": {\"content\": \" world!\"}}]}";
  private static final String STREAMING_DONE = "data: [DONE]";

  @Mock private OkHttpClient mockHttpClient;
  @Mock private Call mockCall;
  @Mock private SarvamAiConfig mockConfig;

  @Captor private ArgumentCaptor<Request> requestCaptor;
  @Captor private ArgumentCaptor<Callback> callbackCaptor;

  private SarvamAi sarvamAi;
  private ObjectMapper objectMapper;

  @Before
  public void setUp() {
    when(mockConfig.getApiKey()).thenReturn(API_KEY);
    sarvamAi = new SarvamAi(MODEL_NAME, mockConfig);
    objectMapper = new ObjectMapper();

    when(mockHttpClient.newCall(requestCaptor.capture())).thenReturn(mockCall);
  }

  @Test
  public void generateContent_blockingCall_returnsLlmResponse() throws IOException {
    String mockResponseBody = createMockSarvamAiResponseBody(COMPLETION_TEXT);
    Response mockResponse =
        new Response.Builder()
            .request(new Request.Builder().url("http://localhost").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(ResponseBody.create(mockResponseBody, MediaType.get("application/json")))
            .build();

    when(mockCall.execute()).thenReturn(mockResponse);

    LlmRequest llmRequest =
        LlmRequest.builder()
            .contents(
                List.of(Content.builder().parts(List.of(Part.fromText("test query"))).build()))
            .build();
    Flowable<LlmResponse> responseFlowable = sarvamAi.generateContent(llmRequest, false);

    LlmResponse llmResponse = responseFlowable.blockingFirst();

    assertThat(llmResponse.content().get().parts().get().get(0).text()).isEqualTo(COMPLETION_TEXT);
  }

  @Test
  public void generateContent_streamingCall_returnsLlmResponses() throws IOException {
    ResponseBody mockStreamingBody =
        createMockStreamingResponseBody(
            List.of(STREAMING_CHUNK_1, STREAMING_CHUNK_2, STREAMING_DONE));

    Response mockResponse =
        new Response.Builder()
            .request(new Request.Builder().url("http://localhost").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(mockStreamingBody)
            .build();

    when(mockCall.execute())
        .thenThrow(new IllegalStateException("Should not be called for streaming"));

    LlmRequest llmRequest =
        LlmRequest.builder()
            .contents(
                List.of(Content.builder().parts(List.of(Part.fromText("test query"))).build()))
            .build();
    Flowable<LlmResponse> responseFlowable = sarvamAi.generateContent(llmRequest, true);

    // Simulate the asynchronous callback
    Callback capturedCallback = callbackCaptor.getValue();
    capturedCallback.onResponse(mockCall, mockResponse);

    List<LlmResponse> responses = responseFlowable.toList().blockingGet();

    assertThat(responses).hasSize(2);
    assertThat(responses.get(0).content().get().parts().get().get(0).text()).isEqualTo("Hello,");
    assertThat(responses.get(1).content().get().parts().get().get(0).text()).isEqualTo(" world!");
  }

  // Helper method to create a mock SarvamAi response body
  private String createMockSarvamAiResponseBody(String text) {
    return String.format("{\"choices\": [{\"message\": {\"content\": \"%s\"}}]}", text);
  }

  // Helper method to create a mock streaming response body
  private ResponseBody createMockStreamingResponseBody(List<String> chunks) {
    StringBuilder bodyBuilder = new StringBuilder();
    for (String chunk : chunks) {
      bodyBuilder.append(chunk).append("\n\n");
    }
    return ResponseBody.create(bodyBuilder.toString(), MediaType.get("text/event-stream"));
  }
}
