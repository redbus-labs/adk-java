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

import com.google.adk.events.Event;
import com.google.adk.sessions.Session;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.mapdb.BTreeMap;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.Serializer;
import org.mapdb.serializer.SerializerArrayTuple;

/**
 * A MapDB-based memory service for persistent storage using modern composite keys.
 *
 * <p>Uses keyword matching instead of semantic search.
 */
public final class MapDBMemoryService implements BaseMemoryService {

  // Pattern to extract words, matching the Python version.
  private static final Pattern WORD_PATTERN = Pattern.compile("[A-Za-z]+");

  private final DB db;
  // The key is Object[], representing a composite key of [userKey, sessionId]
  private final BTreeMap<Object[], List<Event>> sessionEvents;

  public MapDBMemoryService() {
    this.db =
        DBMaker.fileDB(new File("adk_memory.db")).transactionEnable().closeOnJvmShutdown().make();
    // Use the modern Tuple Key Serializer for Object[] keys
    this.sessionEvents =
        db.treeMap("sessionEvents")
            .keySerializer(new SerializerArrayTuple(Serializer.STRING, Serializer.STRING))
            .valueSerializer(Serializer.JAVA)
            .createOrOpen();
  }

  private static String userKey(String appName, String userId) {
    return appName + "/" + userId;
  }

  @Override
  public Completable addSessionToMemory(Session session) {
    return Completable.fromAction(
        () -> {
          String key = userKey(session.appName(), session.userId());
          ImmutableList<Event> nonEmptyEvents =
              session.events().stream()
                  .filter(
                      event ->
                          event.content().isPresent()
                              && event.content().get().parts().isPresent()
                              && !event.content().get().parts().get().isEmpty())
                  .collect(toImmutableList());
          // Use an Object array for the composite key
          sessionEvents.put(new Object[] {key, session.id()}, nonEmptyEvents);
          db.commit();
        });
  }

  @Override
  public Single<SearchMemoryResponse> searchMemory(String appName, String userId, String query) {
    return Single.fromCallable(
        () -> {
          String key = userKey(appName, userId);

          // Use subMap for an efficient prefix search on the composite key.
          // This gets all entries where the first part of the key matches.
          Map<Object[], List<Event>> userSessions = sessionEvents.prefixSubMap(new Object[] {key});

          if (userSessions.isEmpty()) {
            return SearchMemoryResponse.builder().build();
          }

          ImmutableSet<String> wordsInQuery =
              ImmutableSet.copyOf(query.toLowerCase(Locale.ROOT).split("\\s+"));

          List<MemoryEntry> matchingMemories = new ArrayList<>();

          for (List<Event> eventsInSession : userSessions.values()) {
            for (Event event : eventsInSession) {
              if (event.content().isEmpty() || event.content().get().parts().isEmpty()) {
                continue;
              }

              Set<String> wordsInEvent = new HashSet<>();
              for (Part part : event.content().get().parts().get()) {
                if (part.text().isPresent() && !Strings.isNullOrEmpty(part.text().get())) {
                  Matcher matcher = WORD_PATTERN.matcher(part.text().get());
                  while (matcher.find()) {
                    wordsInEvent.add(matcher.group().toLowerCase(Locale.ROOT));
                  }
                }
              }

              if (wordsInEvent.isEmpty()) {
                continue;
              }

              if (!Collections.disjoint(wordsInQuery, wordsInEvent)) {
                MemoryEntry memory =
                    MemoryEntry.builder()
                        .content(event.content().get())
                        .author(event.author())
                        .timestamp(formatTimestamp(event.timestamp()))
                        .build();
                matchingMemories.add(memory);
              }
            }
          }

          return SearchMemoryResponse.builder()
              .setMemories(ImmutableList.copyOf(matchingMemories))
              .build();
        });
  }

  private String formatTimestamp(long timestamp) {
    return Instant.ofEpochSecond(timestamp).toString();
  }
}
