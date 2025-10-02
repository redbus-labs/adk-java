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

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.uuid.Uuids;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.events.Event;
import com.google.adk.sessions.Session;
import com.google.adk.store.CassandraHelper;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A Cassandra-backed implementation of the {@link BaseMemoryService}.
 *
 * <p>This implementation stores session events in a Cassandra database and uses an inverted index
 * for keyword-based search.
 *
 * @author Sandeep Belgavi
 * @since 2025-10-02
 */
public final class CassandraMemoryService implements BaseMemoryService {

  private static final Pattern WORD_PATTERN = Pattern.compile("[A-Za-z]+");
  private final CqlSession session;
  private final ObjectMapper objectMapper;

  public CassandraMemoryService() {
    this.session = CassandraHelper.getSession();
    this.objectMapper = CassandraHelper.getObjectMapper();
  }

  @Override
  public Completable addSessionToMemory(Session session) {
    return Completable.fromAction(
        () -> {
          for (Event event : session.events()) {
            if (event.content().isEmpty()
                || event.content().get().parts().isEmpty()
                || event.content().get().parts().get().isEmpty()) {
              continue;
            }

            UUID eventId = Uuids.timeBased();
            String eventData = objectMapper.writeValueAsString(event);
            this.session.execute(
                "INSERT INTO memory_events (app_name, user_id, event_id, event_data) VALUES (?, ?, ?, ?)",
                session.appName(),
                session.userId(),
                eventId,
                eventData);

            Set<String> wordsInEvent = extractWords(event);
            for (String word : wordsInEvent) {
              this.session.execute(
                  "UPDATE memory_inverted_index SET event_ids = event_ids + ? WHERE app_name = ? AND user_id = ? AND word = ?",
                  Set.of(eventId),
                  session.appName(),
                  session.userId(),
                  word);
            }
          }
        });
  }

  @Override
  public Single<SearchMemoryResponse> searchMemory(String appName, String userId, String query) {
    return Single.fromCallable(
        () -> {
          ImmutableSet<String> wordsInQuery =
              ImmutableSet.copyOf(query.toLowerCase(Locale.ROOT).split("\\s+"));

          Set<UUID> matchingEventIds = new HashSet<>();
          for (String word : wordsInQuery) {
            ResultSet rs =
                this.session.execute(
                    "SELECT event_ids FROM memory_inverted_index WHERE app_name = ? AND user_id = ? AND word = ?",
                    appName,
                    userId,
                    word);
            Row row = rs.one();
            if (row != null) {
              matchingEventIds.addAll(row.getSet("event_ids", UUID.class));
            }
          }

          if (matchingEventIds.isEmpty()) {
            return SearchMemoryResponse.builder().build();
          }

          String cqlInClause =
              matchingEventIds.stream().map(UUID::toString).collect(Collectors.joining(","));
          ResultSet eventRs =
              this.session.execute(
                  "SELECT event_data FROM memory_events WHERE app_name = ? AND user_id = ? AND event_id IN ("
                      + cqlInClause
                      + ")",
                  appName,
                  userId);

          List<MemoryEntry> matchingMemories = new ArrayList<>();
          for (Row row : eventRs) {
            Event event = objectMapper.readValue(row.getString("event_data"), Event.class);
            MemoryEntry memory =
                MemoryEntry.builder()
                    .content(event.content().get())
                    .author(event.author())
                    .timestamp(formatTimestamp(event.timestamp()))
                    .build();
            matchingMemories.add(memory);
          }

          return SearchMemoryResponse.builder()
              .setMemories(ImmutableList.copyOf(matchingMemories))
              .build();
        });
  }

  private Set<String> extractWords(Event event) {
    Set<String> words = new HashSet<>();
    if (event.content().isPresent() && event.content().get().parts().isPresent()) {
      for (Part part : event.content().get().parts().get()) {
        if (!Strings.isNullOrEmpty(part.text().get())) {
          Matcher matcher = WORD_PATTERN.matcher(part.text().get());
          while (matcher.find()) {
            words.add(matcher.group().toLowerCase(Locale.ROOT));
          }
        }
      }
    }
    return words;
  }

  private String formatTimestamp(long timestamp) {
    return Instant.ofEpochMilli(timestamp).toString();
  }

  public static class CassandraMemoryServiceExample {
    public static void main(String[] args) {
      CqlSessionBuilder sessionBuilder =
          CqlSession.builder()
              .addContactPoint(new java.net.InetSocketAddress("127.0.0.1", 9042))
              .withLocalDatacenter("datacenter1");
      CassandraHelper.initialize(sessionBuilder);

      CassandraMemoryService memoryService = new CassandraMemoryService();

      String appName = "myApp";
      String userId = "user123";
      Session session =
          Session.builder("session789")
              .appName(appName)
              .userId(userId)
              .events(
                  List.of(
                      Event.builder()
                          .timestamp(1L)
                          .author("user")
                          .content(
                              com.google.genai.types.Content.builder()
                                  .parts(List.of(Part.fromText("hello from the past")))
                                  .build())
                          .build()))
              .build();

      // Add a session to memory
      memoryService.addSessionToMemory(session).blockingAwait();
      System.out.println("Added session to memory.");

      // Search memory
      SearchMemoryResponse response =
          memoryService.searchMemory(appName, userId, "past").blockingGet();
      System.out.println("Search results: " + response.memories());

      CassandraHelper.close();
    }
  }
}
