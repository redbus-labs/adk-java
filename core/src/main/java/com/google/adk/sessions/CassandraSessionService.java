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

package com.google.adk.sessions;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.store.CassandraHelper;
import com.google.common.collect.ImmutableList;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Cassandra-backed implementation of {@link BaseSessionService}.
 *
 * <p>This implementation stores sessions, user state, and app state in a Cassandra database.
 *
 * @author Sandeep Belgavi
 * @since 2025-10-02
 */
public final class CassandraSessionService implements BaseSessionService {

  private static final Logger logger = LoggerFactory.getLogger(CassandraSessionService.class);
  private final CqlSession session;
  private final ObjectMapper objectMapper;

  /** Creates a new instance of the cassandra-backed session service. */
  public CassandraSessionService() {
    this.session = CassandraHelper.getSession();
    this.objectMapper = CassandraHelper.getObjectMapper();
  }

  @Override
  public Single<Session> createSession(
      String appName,
      String userId,
      @Nullable ConcurrentMap<String, Object> state,
      @Nullable String sessionId) {
    return Single.fromCallable(
        () -> {
          Objects.requireNonNull(appName, "appName cannot be null");
          Objects.requireNonNull(userId, "userId cannot be null");

          String resolvedSessionId =
              Optional.ofNullable(sessionId)
                  .map(String::trim)
                  .filter(s -> !s.isEmpty())
                  .orElseGet(() -> UUID.randomUUID().toString());

          ConcurrentMap<String, Object> initialState =
              (state == null) ? new ConcurrentHashMap<>() : new ConcurrentHashMap<>(state);
          List<Event> initialEvents = new ArrayList<>();

          Session newSession =
              Session.builder(resolvedSessionId)
                  .appName(appName)
                  .userId(userId)
                  .state(initialState)
                  .events(initialEvents)
                  .lastUpdateTime(Instant.now())
                  .build();

          String sessionData = objectMapper.writeValueAsString(newSession);

          session.execute(
              "INSERT INTO sessions (app_name, user_id, session_id, session_data) VALUES (?, ?, ?, ?)",
              appName,
              userId,
              resolvedSessionId,
              sessionData);

          return mergeWithGlobalState(appName, userId, newSession);
        });
  }

  @Override
  public Maybe<Session> getSession(
      String appName, String userId, String sessionId, Optional<GetSessionConfig> configOpt) {
    return Maybe.fromCallable(
        () -> {
          Objects.requireNonNull(appName, "appName cannot be null");
          Objects.requireNonNull(userId, "userId cannot be null");
          Objects.requireNonNull(sessionId, "sessionId cannot be null");
          Objects.requireNonNull(configOpt, "configOpt cannot be null");

          Row row =
              session
                  .execute(
                      "SELECT session_data FROM sessions WHERE app_name = ? AND user_id = ? AND session_id = ?",
                      appName,
                      userId,
                      sessionId)
                  .one();

          if (row == null) {
            return null;
          }

          Session storedSession =
              objectMapper.readValue(row.getString("session_data"), Session.class);

          GetSessionConfig config = configOpt.orElse(GetSessionConfig.builder().build());
          List<Event> events = new ArrayList<>(storedSession.events());

          if (config.numRecentEvents().isPresent()) {
            int num = config.numRecentEvents().get();
            if (events.size() > num) {
              events = events.subList(events.size() - num, events.size());
            }
          } else if (config.afterTimestamp().isPresent()) {
            Instant threshold = config.afterTimestamp().get();
            events.removeIf(e -> getInstantFromEvent(e).isBefore(threshold));
          }

          Session updatedSession =
              Session.builder(storedSession.id())
                  .appName(storedSession.appName())
                  .userId(storedSession.userId())
                  .state(storedSession.state())
                  .events(events)
                  .lastUpdateTime(storedSession.lastUpdateTime())
                  .build();

          return mergeWithGlobalState(appName, userId, updatedSession);
        });
  }

  @Override
  public Single<ListSessionsResponse> listSessions(String appName, String userId) {
    return Single.fromCallable(
        () -> {
          Objects.requireNonNull(appName, "appName cannot be null");
          Objects.requireNonNull(userId, "userId cannot be null");

          ResultSet rs =
              session.execute(
                  "SELECT session_data FROM sessions WHERE app_name = ? AND user_id = ?",
                  appName,
                  userId);

          List<Session> sessions = new ArrayList<>();
          for (Row row : rs) {
            Session session = objectMapper.readValue(row.getString("session_data"), Session.class);
            sessions.add(
                Session.builder(session.id())
                    .appName(session.appName())
                    .userId(session.userId())
                    .lastUpdateTime(session.lastUpdateTime())
                    .build());
          }

          return ListSessionsResponse.builder().sessions(sessions).build();
        });
  }

  @Override
  public Completable deleteSession(String appName, String userId, String sessionId) {
    return Completable.fromAction(
        () -> {
          Objects.requireNonNull(appName, "appName cannot be null");
          Objects.requireNonNull(userId, "userId cannot be null");
          Objects.requireNonNull(sessionId, "sessionId cannot be null");

          session.execute(
              "DELETE FROM sessions WHERE app_name = ? AND user_id = ? AND session_id = ?",
              appName,
              userId,
              sessionId);
        });
  }

  @Override
  public Single<ListEventsResponse> listEvents(String appName, String userId, String sessionId) {
    return getSession(appName, userId, sessionId, Optional.empty())
        .map(
            session ->
                ListEventsResponse.builder().events(ImmutableList.copyOf(session.events())).build())
        .switchIfEmpty(Single.just(ListEventsResponse.builder().build()));
  }

  @Override
  public Single<Event> appendEvent(Session session, Event event) {
    return Single.fromCallable(
        () -> {
          Objects.requireNonNull(session, "session cannot be null");
          Objects.requireNonNull(event, "event cannot be null");

          EventActions actions = event.actions();
          if (actions != null) {
            Map<String, Object> stateDelta = actions.stateDelta();
            if (stateDelta != null && !stateDelta.isEmpty()) {
              stateDelta.forEach(
                  (key, value) -> {
                    try {
                      String valueStr = objectMapper.writeValueAsString(value);
                      if (key.startsWith(State.APP_PREFIX)) {
                        String appStateKey = key.substring(State.APP_PREFIX.length());
                        this.session.execute(
                            "INSERT INTO app_state (app_name, state_key, state_value) VALUES (?, ?, ?)",
                            session.appName(),
                            appStateKey,
                            valueStr);
                      } else if (key.startsWith(State.USER_PREFIX)) {
                        String userStateKey = key.substring(State.USER_PREFIX.length());
                        this.session.execute(
                            "INSERT INTO user_state (app_name, user_id, state_key, state_value) VALUES (?, ?, ?, ?)",
                            session.appName(),
                            session.userId(),
                            userStateKey,
                            valueStr);
                      }
                    } catch (JsonProcessingException e) {
                      logger.error("Error serializing state value for key: " + key, e);
                    }
                  });
            }
          }

          BaseSessionService.super.appendEvent(session, event);
          session.lastUpdateTime(getInstantFromEvent(event));

          String sessionData = objectMapper.writeValueAsString(session);
          this.session.execute(
              "INSERT INTO sessions (app_name, user_id, session_id, session_data) VALUES (?, ?, ?, ?)",
              session.appName(),
              session.userId(),
              session.id(),
              sessionData);

          return event;
        });
  }

  private Instant getInstantFromEvent(Event event) {
    return Instant.ofEpochMilli(event.timestamp());
  }

  private Session mergeWithGlobalState(String appName, String userId, Session session)
      throws IOException {
    Map<String, Object> sessionState = session.state();

    ResultSet appStateRs =
        this.session.execute(
            "SELECT state_key, state_value FROM app_state WHERE app_name = ?", appName);
    for (Row row : appStateRs) {
      sessionState.put(
          State.APP_PREFIX + row.getString("state_key"),
          objectMapper.readValue(row.getString("state_value"), Object.class));
    }

    ResultSet userStateRs =
        this.session.execute(
            "SELECT state_key, state_value FROM user_state WHERE app_name = ? AND user_id = ?",
            appName,
            userId);
    for (Row row : userStateRs) {
      sessionState.put(
          State.USER_PREFIX + row.getString("state_key"),
          objectMapper.readValue(row.getString("state_value"), Object.class));
    }

    return session;
  }

  public static class CassandraSessionServiceExample {
    public static void main(String[] args) {
      CqlSessionBuilder sessionBuilder =
          CqlSession.builder()
              .addContactPoint(new java.net.InetSocketAddress("127.0.0.1", 9042))
              .withLocalDatacenter("datacenter1");
      CassandraHelper.initialize(sessionBuilder);

      CassandraSessionService sessionService = new CassandraSessionService();

      String appName = "myApp";
      String userId = "user123";

      // Create a session
      Session createdSession =
          sessionService.createSession(appName, userId, null, null).blockingGet();
      System.out.println("Created session: " + createdSession.id());

      // Get the session
      Session retrievedSession =
          sessionService
              .getSession(appName, userId, createdSession.id(), Optional.empty())
              .blockingGet();
      System.out.println("Retrieved session: " + retrievedSession.id());

      CassandraHelper.close();
    }
  }
}
