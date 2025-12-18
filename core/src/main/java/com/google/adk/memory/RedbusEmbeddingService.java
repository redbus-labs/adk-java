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

/**
 * @author Sandeep Belgavi
 * @since 2025-10-21
 */
package com.google.adk.memory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.reactivex.rxjava3.core.Single;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;

/**
 * Generates vector embeddings from text using the Redbus embedding service.
 *
 * <p>The URL for the embedding service can be configured via the `EMBEDDING_GENERATOR_URL`
 * environment variable.
 *
 * <p>Example: `export EMBEDDING_GENERATOR_URL="http://www.redbus.com/<serviceprovider>/embeddings"`
 */
public class RedbusEmbeddingService implements EmbeddingService {
  private static final String HEADER_API_KEY = "x-api-key";
  private static final HttpClient httpClient = HttpClient.newHttpClient();

  private final String username;
  private final String password;
  private final int api;
  private final String embeddingUrl;
  private final String apiKey;

  public RedbusEmbeddingService(String username, String password) {
    this(username, password, 1);
  }

  public RedbusEmbeddingService(String username, String password, int api) {
    this.username = username;
    this.password = password;
    this.api = api;
    this.embeddingUrl =
        System.getenv("EMBEDDING_GENERATOR_URL") != null
            ? System.getenv("EMBEDDING_GENERATOR_URL")
            : "<default embedding url>";
    this.apiKey =
        System.getenv("EMBEDDING_API_KEY") != null ? System.getenv("EMBEDDING_API_KEY") : "";
  }

  @Override
  public Single<double[]> generateEmbedding(String text) {
    return Single.fromCallable(
        () -> {
          JsonObject payload = new JsonObject();
          payload.addProperty("username", this.username);
          payload.addProperty("password", this.password);
          payload.addProperty("api", this.api);

          JsonObject requestBlock = new JsonObject();
          requestBlock.addProperty("input", text);
          payload.add("request", requestBlock);

          HttpRequest.Builder requestBuilder =
              HttpRequest.newBuilder()
                  .uri(URI.create(this.embeddingUrl))
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(payload.toString()));

          if (this.apiKey != null && !this.apiKey.isEmpty()) {
            requestBuilder.header(HEADER_API_KEY, this.apiKey);
          }

          HttpRequest request = requestBuilder.build();
          HttpResponse<String> response =
              httpClient.send(request, HttpResponse.BodyHandlers.ofString());

          if (response.statusCode() >= 400) {
            throw new IllegalStateException(
                String.format(
                    "Embedding API request failed with status %d and body %s",
                    response.statusCode(), response.body()));
          }

          double[] embedding = parseEmbedding(response.body());
          System.out.println("Embedding dimension: " + embedding.length);
          return embedding;
        });
  }

  /** Parse embedding vector from response */
  private double[] parseEmbedding(String responseBody) {
    JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
    validateStatus(root);

    JsonArray values = extractEmbeddingArray(root);
    if (values == null) {
      throw new IllegalStateException("Embedding vector missing in response");
    }

    double[] embedding = new double[values.size()];
    for (int i = 0; i < values.size(); i++) {
      embedding[i] = values.get(i).getAsDouble();
    }
    return embedding;
  }

  private void validateStatus(JsonObject root) {
    if (root == null || !root.has("status")) {
      return;
    }

    try {
      String status = root.get("status").getAsString();
      if (!"SUCCESS".equalsIgnoreCase(status)) {
        throw new IllegalStateException("Embedding service returned status: " + status);
      }
    } catch (UnsupportedOperationException ignored) {
      // status key exists but is not a primitive string, ignore
    }
  }

  private JsonArray extractEmbeddingArray(JsonObject root) {
    JsonArray fromOpenAiWrapper = extractFromOpenAiWrapper(root);
    if (fromOpenAiWrapper != null) {
      return fromOpenAiWrapper;
    }

    if (root.has("embedding") && root.get("embedding").isJsonArray()) {
      return root.getAsJsonArray("embedding");
    }

    if (root.has("data")) {
      JsonElement dataElement = root.get("data");
      if (dataElement.isJsonArray()) {
        JsonArray dataArray = dataElement.getAsJsonArray();
        JsonArray values = tryExtractFromArray(dataArray);
        if (values != null) {
          return values;
        }
      } else if (dataElement.isJsonObject()) {
        JsonObject dataObject = dataElement.getAsJsonObject();
        if (dataObject.has("embedding") && dataObject.get("embedding").isJsonArray()) {
          return dataObject.getAsJsonArray("embedding");
        }
        if (dataObject.has("data") && dataObject.get("data").isJsonArray()) {
          JsonArray nested = dataObject.getAsJsonArray("data");
          JsonArray values = tryExtractFromArray(nested);
          if (values != null) {
            return values;
          }
        }
      }
    }

    return null;
  }

  private JsonArray extractFromOpenAiWrapper(JsonObject root) {
    if (root == null || !root.has("response")) {
      return null;
    }

    JsonElement responseElement = root.get("response");
    if (!responseElement.isJsonObject()) {
      return null;
    }
    JsonObject responseObject = responseElement.getAsJsonObject();

    if (!responseObject.has("openAIResponse")) {
      return null;
    }
    JsonElement openAiElement = responseObject.get("openAIResponse");
    if (!openAiElement.isJsonObject()) {
      return null;
    }
    JsonObject openAiObject = openAiElement.getAsJsonObject();

    if (!openAiObject.has("data") || !openAiObject.get("data").isJsonArray()) {
      return null;
    }

    JsonArray dataArray = openAiObject.getAsJsonArray("data");
    for (JsonElement entry : dataArray) {
      if (entry.isJsonObject()) {
        JsonObject entryObj = entry.getAsJsonObject();
        if (entryObj.has("embedding") && entryObj.get("embedding").isJsonArray()) {
          return entryObj.getAsJsonArray("embedding");
        }
      } else if (entry.isJsonArray()) {
        return entry.getAsJsonArray();
      }
    }
    return null;
  }

  private JsonArray tryExtractFromArray(JsonArray array) {
    if (array == null || array.size() == 0) {
      return null;
    }

    JsonElement firstElement = array.get(0);
    if (firstElement.isJsonArray()) {
      return firstElement.getAsJsonArray();
    }

    if (firstElement.isJsonObject()) {
      JsonObject obj = firstElement.getAsJsonObject();
      if (obj.has("embedding") && obj.get("embedding").isJsonArray()) {
        return obj.getAsJsonArray("embedding");
      }
    }
    return null;
  }

  public static void main(String[] args) {
    RedbusEmbeddingService service = new RedbusEmbeddingService("", "");
    service
        .generateEmbedding("testing")
        .subscribe(
            embedding -> System.out.println(Arrays.toString(embedding)),
            error -> System.err.println("Error generating embedding: " + error.getMessage()));
  }
}
