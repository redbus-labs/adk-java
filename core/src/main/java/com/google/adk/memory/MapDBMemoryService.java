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
package com.google.adk.memory;

import com.google.adk.events.Event;
import com.google.adk.sessions.Session;
import com.google.cloud.vertexai.api.EndpointName;
import com.google.cloud.vertexai.api.PredictRequest;
import com.google.cloud.vertexai.api.PredictResponse;
import com.google.cloud.vertexai.api.PredictionServiceClient;
import com.google.cloud.vertexai.api.PredictionServiceSettings;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;
import org.mapdb.BTreeMap;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.Serializer;

public final class MapDBMemoryService implements BaseMemoryService {

  private final DB db;
  private final BTreeMap<String, Vector> vectorMap;
  PredictionServiceClient predictionServiceClient;

  public MapDBMemoryService(File dbFile) throws IOException {
    dbFile.getParentFile().mkdirs();
    this.db = DBMaker.fileDB(dbFile).transactionEnable().closeOnJvmShutdown().make();
    this.vectorMap = db.treeMap("vectors", Serializer.STRING, Serializer.JAVA).createOrOpen();
    this.predictionServiceClient = createPredictionServiceClient();
  }

  private PredictionServiceClient createPredictionServiceClient() throws IOException {
    PredictionServiceSettings settings =
        PredictionServiceSettings.newBuilder()
            .setEndpoint("us-central1-aiplatform.googleapis.com:443")
            .build();
    return PredictionServiceClient.create(settings);
  }

  @Override
  public Completable addSessionToMemory(Session session) {
    return Completable.fromAction(
        () -> {
          for (Event event : session.events()) {
            if (event.content().isPresent() && event.content().get().parts().isPresent()) {
              String text = event.stringifyContent();
              double[] embedding = getEmbedding(text);
              Map<String, Object> metadata = new HashMap<>();
              metadata.put("author", event.author());
              metadata.put("timestamp", formatTimestamp(event.timestamp()));
              metadata.put("content", text);
              Vector vector = new Vector(UUID.randomUUID().toString(), embedding, metadata);
              vectorMap.put(vector.getId(), vector);
            }
          }
          db.commit();
        });
  }

  @Override
  public Single<SearchMemoryResponse> searchMemory(String appName, String userId, String query) {
    return Single.fromCallable(
        () -> {
          double[] queryEmbedding = getEmbedding(query);
          List<MemoryEntry> matchingMemories = searchTopNVectors(queryEmbedding, 0.7, 10);
          return SearchMemoryResponse.builder()
              .setMemories(ImmutableList.copyOf(matchingMemories))
              .build();
        });
  }

  private double[] getEmbedding(String text) throws IOException {
    EndpointName endpointName = EndpointName.of("us-central1", "google", "textembedding-gecko@001");
    Value.Builder instance = Value.newBuilder();
    JsonFormat.parser().merge("{\"content\": \"" + text + "\"}", instance);
    PredictRequest request =
        PredictRequest.newBuilder()
            .setEndpoint(endpointName.toString())
            .addInstances(instance)
            .build();
    PredictResponse response = predictionServiceClient.predict(request);
    Value embeddingValue = response.getPredictions(0);
    double[] embedding =
        embeddingValue
            .getStructValue()
            .getFieldsOrThrow("embedding")
            .getListValue()
            .getValuesList()
            .stream()
            .mapToDouble(Value::getNumberValue)
            .toArray();
    return embedding;
  }

  private List<MemoryEntry> searchTopNVectors(double[] queryVector, double threshold, int topN) {
    PriorityQueue<VectorWithScore> topVectors =
        new PriorityQueue<>(topN, Comparator.comparingDouble(v -> v.score));
    for (Vector vector : vectorMap.values()) {
      double similarity = cosineSimilarity(vector.getEmbedding(), queryVector);
      if (similarity >= threshold) {
        if (topVectors.size() < topN) {
          topVectors.offer(new VectorWithScore(vector, similarity));
        } else if (similarity > topVectors.peek().score) {
          topVectors.poll();
          topVectors.offer(new VectorWithScore(vector, similarity));
        }
      }
    }
    List<MemoryEntry> result = new ArrayList<>();
    for (VectorWithScore vectorWithScore : topVectors) {
      Vector vector = vectorWithScore.vector;
      result.add(
          MemoryEntry.builder()
              .content(
                  Content.builder()
                      .parts(
                          ImmutableList.of(
                              Part.fromText((String) vector.getMetadata().get("content"))))
                      .build())
              .author((String) vector.getMetadata().get("author"))
              .timestamp((String) vector.getMetadata().get("timestamp"))
              .build());
    }
    result.sort(Comparator.comparingDouble(v -> ((VectorWithScore) v).score).reversed());
    return result;
  }

  private double cosineSimilarity(double[] vectorA, double[] vectorB) {
    double dotProduct = 0.0;
    double normA = 0.0;
    double normB = 0.0;
    for (int i = 0; i < vectorA.length; i++) {
      dotProduct += vectorA[i] * vectorB[i];
      normA += Math.pow(vectorA[i], 2);
      normB += Math.pow(vectorB[i], 2);
    }
    return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
  }

  private String formatTimestamp(long timestamp) {
    return Instant.ofEpochSecond(timestamp).toString();
  }

  public void close() {
    db.close();
  }

  private static class VectorWithScore {
    final Vector vector;
    final double score;

    VectorWithScore(Vector vector, double score) {
      this.vector = vector;
      this.score = score;
    }
  }
}
