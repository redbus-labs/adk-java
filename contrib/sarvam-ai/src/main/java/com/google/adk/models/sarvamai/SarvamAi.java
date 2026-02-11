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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.io.BufferedReader;
import java.io.IOException;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * This class is the main entry point for the Sarvam AI API.
 *
 * @author Sandeep Belgavi
 * @since 2026-02-11
 */
public class SarvamAi extends BaseLlm {

  private static final String API_ENDPOINT = "https://api.sarvam.ai/v1/chat/completions";
  private final OkHttpClient httpClient;
  private final String apiKey;
  private final ObjectMapper objectMapper;

  public SarvamAi(String modelName, SarvamAiConfig config) {
    super(modelName);
    this.httpClient = new OkHttpClient();
    this.apiKey = config.getApiKey();
    this.objectMapper = new ObjectMapper();
  }

  @Override
  public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
    if (stream) {
      return stream(llmRequest);
    } else {
      return Flowable.fromCallable(
          () -> {
            String requestBody =
                objectMapper.writeValueAsString(new SarvamAiRequest(this.model(), llmRequest));
            Request request =
                new Request.Builder()
                    .url(API_ENDPOINT)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .post(RequestBody.create(requestBody, MediaType.get("application/json")))
                    .build();
            Response response = httpClient.newCall(request).execute();
            if (!response.isSuccessful()) {
              throw new IOException("Unexpected code " + response);
            }
            ResponseBody responseBody = response.body();
            if (responseBody != null) {
              String responseBodyString = responseBody.string();
              SarvamAiResponse sarvamAiResponse =
                  objectMapper.readValue(responseBodyString, SarvamAiResponse.class);
              return toLlmResponse(sarvamAiResponse);
            } else {
              throw new IOException("Response body is null");
            }
          });
    }
  }

  private Flowable<LlmResponse> stream(LlmRequest llmRequest) {
    return Flowable.create(
        emitter -> {
          try {
            String requestBody =
                objectMapper.writeValueAsString(new SarvamAiRequest(this.model(), llmRequest));
            Request request =
                new Request.Builder()
                    .url(API_ENDPOINT)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .post(RequestBody.create(requestBody, MediaType.get("application/json")))
                    .build();
            httpClient
                .newCall(request)
                .enqueue(
                    new Callback() {
                      @Override
                      public void onFailure(Call call, IOException e) {
                        emitter.onError(e);
                      }

                      @Override
                      public void onResponse(Call call, Response response) throws IOException {
                        if (!response.isSuccessful()) {
                          emitter.onError(new IOException("Unexpected code " + response));
                          return;
                        }
                        ResponseBody responseBody = response.body();
                        if (responseBody != null) {
                          try (var reader = new BufferedReader(responseBody.charStream())) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                              if (line.startsWith("data: ")) {
                                String data = line.substring(6);
                                if (data.equals("[DONE]")) {
                                  emitter.onComplete();
                                  return;
                                }
                                SarvamAiResponse sarvamAiResponse =
                                    objectMapper.readValue(data, SarvamAiResponse.class);
                                emitter.onNext(toLlmResponse(sarvamAiResponse));
                              }
                            }
                            emitter.onComplete();
                          }
                        } else {
                          emitter.onError(new IOException("Response body is null"));
                        }
                      }
                    });
          } catch (IOException e) {
            emitter.onError(e);
          }
        },
        io.reactivex.rxjava3.core.BackpressureStrategy.BUFFER);
  }

  private LlmResponse toLlmResponse(SarvamAiResponse sarvamAiResponse) {
    Content content =
        Content.builder()
            .role("model")
            .parts(
                java.util.Collections.singletonList(
                    Part.fromText(sarvamAiResponse.getChoices().get(0).getMessage().getContent())))
            .build();
    return LlmResponse.builder().content(content).build();
  }

  @Override
  public BaseLlmConnection connect(LlmRequest llmRequest) {
    // TODO: Implement this method
    throw new UnsupportedOperationException(
        "Live connection is not supported for Sarvam AI models.");
  }
}
