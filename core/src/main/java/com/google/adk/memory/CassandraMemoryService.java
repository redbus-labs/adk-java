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

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.datastax.oss.driver.api.core.CqlSession;
import com.google.adk.sessions.Session;
import com.google.adk.tools.retrieval.CassandraRagRetrieval;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import javax.annotation.Nonnull;

/**
 * An implementation of {@link BaseMemoryService} that uses Cassandra for storage and retrieval.
 *
 * @author Sandeep Belgavi
 * @since 2025-10-19
 */
public class CassandraMemoryService implements BaseMemoryService {

  private final CassandraRagRetrieval cassandraRagRetrieval;

  public CassandraMemoryService(@Nonnull CassandraRagRetrieval cassandraRagRetrieval) {
    this.cassandraRagRetrieval = cassandraRagRetrieval;
  }

  public CassandraMemoryService(
      @Nonnull CqlSession session, @Nonnull String keyspace, @Nonnull String table) {

    this(
        new CassandraRagRetrieval(
            "cassandra_rag", "Retrieves information from Cassandra", session, keyspace, table));
  }

  public CassandraMemoryService(@Nonnull CqlSession session) {

    this(session, "rae", "rae_data");
  }

  @Override
  public Completable addSessionToMemory(Session session) {
    return Completable.fromRunnable(
        () -> {
          session
              .events()
              .forEach(
                  event -> {
                    cassandraRagRetrieval
                        .getSession()
                        .execute(
                            "INSERT INTO "
                                + cassandraRagRetrieval.getKeyspace()
                                + "."
                                + cassandraRagRetrieval.getTable()
                                + " (client_id, session_id, turn_id, data, embedding) VALUES (?, ?, now(), ?, null)",
                            session.appName(),
                            session.id(),
                            event.content().get().parts().get().get(0).text().get());
                  });
        });
  }

  @Override
  public Single<SearchMemoryResponse> searchMemory(String appName, String userId, String query) {
    return cassandraRagRetrieval
        .runAsync(ImmutableMap.of("query", query), null)
        .map(
            result -> {
              List<String> contexts = (List<String>) result.get("response");
              ImmutableList<MemoryEntry> memories =
                  contexts.stream()
                      .map(
                          context ->
                              MemoryEntry.builder()
                                  .content(
                                      Content.builder()
                                          .parts(ImmutableList.of(Part.fromText(context)))
                                          .build())
                                  .build())
                      .collect(toImmutableList());
              return SearchMemoryResponse.builder().setMemories(memories).build();
            });
  }
}
