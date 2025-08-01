/**
 * @author Sandeep Belgavi
 * @since 2025-07-31
 */
package com.google.adk.sessions;

import com.datastax.oss.driver.api.core.CqlSession;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.adk.events.Event;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CassandraSessionService implements BaseSessionService, AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(CassandraSessionService.class);
  private final CqlSession session;
  private final ObjectMapper objectMapper;

  private static final String CASSANDRA_HOST = "cassandra_host";
  private static final String CASSANDRA_PORT = "cassandra_port";
  private static final String CASSANDRA_USER = "cassandra_user";
  private static final String CASSANDRA_PASSWORD = "cassandra_password";
  private static final String CASSANDRA_KEYSPACE = "cassandra_keyspace";

  public CassandraSessionService() {
    this.objectMapper = new ObjectMapper();
    this.objectMapper.registerModule(new Jdk8Module());
    this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    this.session = initializeCqlSession();
  }

  private CqlSession initializeCqlSession() {
    String host = System.getProperty(CASSANDRA_HOST);
    int port = Integer.parseInt(System.getProperty(CASSANDRA_PORT));
    String username = System.getProperty(CASSANDRA_USER);
    String password = System.getProperty(CASSANDRA_PASSWORD);
    String keyspace = System.getProperty(CASSANDRA_KEYSPACE);

    if (host == null || username == null || password == null || keyspace == null) {
      throw new IllegalArgumentException("Missing Cassandra environment variables");
    }

    return CqlSession.builder()
        .addContactPoint(new InetSocketAddress(host, port))
        .withAuthCredentials(username, password)
        .withKeyspace(keyspace)
        .withLocalDatacenter("datacenter1")
        .build();
  }

  @Override
  public Single<Session> createSession(
      String appName,
      String userId,
      @Nullable ConcurrentMap<String, Object> state,
      @Nullable String sessionId) {
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
    Instant now = Instant.now();

    Session newSession =
        Session.builder(resolvedSessionId)
            .appName(appName)
            .userId(userId)
            .state(initialState)
            .events(initialEvents)
            .lastUpdateTime(now)
            .build();

    String insertCql =
        "INSERT INTO sessions (app_name, user_id, session_id, state, events, last_update_time) VALUES (?, ?, ?, ?, ?, ?)";

    try {
      session.execute(
          insertCql,
          appName,
          userId,
          resolvedSessionId,
          objectMapper.writeValueAsString(initialState),
          objectMapper.writeValueAsString(initialEvents),
          now);
      return Single.just(copySession(newSession));
    } catch (JsonProcessingException e) {
      logger.error("Error serializing session data for createSession", e);
      return Single.error(e);
    }
  }

  @Override
  public Maybe<Session> getSession(
      String appName,
      String userId,
      String sessionId,
      @Nullable Optional<GetSessionConfig> config) {
    Objects.requireNonNull(appName, "appName cannot be null");
    Objects.requireNonNull(userId, "userId cannot be null");
    Objects.requireNonNull(sessionId, "sessionId cannot be null");

    String selectCql =
        "SELECT app_name, user_id, session_id, state, events, last_update_time FROM sessions WHERE app_name = ? AND user_id = ? AND session_id = ?";
    try {
      var row = session.execute(selectCql, appName, userId, sessionId).one();
      if (row == null) {
        return Maybe.empty();
      }

      String storedAppName = row.getString("app_name");
      String storedUserId = row.getString("user_id");
      String storedSessionId = row.getString("session_id");
      String stateJson = row.getString("state");
      String eventsJson = row.getString("events");
      Instant lastUpdateTime = row.getInstant("last_update_time");

      ConcurrentMap<String, Object> state =
          objectMapper.readValue(
              stateJson,
              objectMapper
                  .getTypeFactory()
                  .constructMapType(ConcurrentMap.class, String.class, Object.class));
      List<Event> events =
          objectMapper.readValue(
              eventsJson,
              objectMapper.getTypeFactory().constructCollectionType(List.class, Event.class));

      Session retrievedSession =
          Session.builder(storedSessionId)
              .appName(storedAppName)
              .userId(storedUserId)
              .state(state)
              .events(events)
              .lastUpdateTime(lastUpdateTime)
              .build();

      // Apply filtering based on config if needed (similar to InMemorySessionService)
      // For simplicity, this example doesn't include config-based filtering.
      // You would add logic here to filter events based on numRecentEvents or afterTimestamp.

      return Maybe.just(copySession(retrievedSession));
    } catch (Exception e) {
      logger.error("Error retrieving session from Cassandra", e);
      return Maybe.error(e);
    }
  }

  /**
   * Creates a shallow copy of the session, but with deep copies of the mutable state map and events
   * list. Assumes Session provides necessary getters and a suitable constructor/setters.
   *
   * @param original The session to copy.
   * @return A new Session instance with copied data, including mutable collections.
   */
  private Session copySession(Session original) {
    return Session.builder(original.id())
        .appName(original.appName())
        .userId(original.userId())
        .state(new ConcurrentHashMap<>(original.state()))
        .events(new ArrayList<>(original.events()))
        .lastUpdateTime(original.lastUpdateTime())
        .build();
  }

  @Override
  public Single<ListSessionsResponse> listSessions(String appName, String userId) {
    Objects.requireNonNull(appName, "appName cannot be null");
    Objects.requireNonNull(userId, "userId cannot be null");

    String selectCql =
        "SELECT app_name, user_id, session_id, last_update_time FROM sessions WHERE app_name = ? AND user_id = ?";
    // Note: ALLOW FILTERING is used here for simplicity, but for production, consider creating
    // appropriate secondary indexes or adjusting the primary key for efficient querying.
    try {
      var resultSet = session.execute(selectCql, appName, userId);
      List<Session> sessionCopies = new ArrayList<>();
      for (var row : resultSet) {
        String storedAppName = row.getString("app_name");
        String storedUserId = row.getString("user_id");
        String storedSessionId = row.getString("session_id");
        Instant lastUpdateTime = row.getInstant("last_update_time");

        sessionCopies.add(
            Session.builder(storedSessionId)
                .appName(storedAppName)
                .userId(storedUserId)
                .lastUpdateTime(lastUpdateTime)
                .build());
      }
      return Single.just(ListSessionsResponse.builder().sessions(sessionCopies).build());
    } catch (Exception e) {
      logger.error("Error listing sessions from Cassandra", e);
      return Single.error(e);
    }
  }

  /**
   * Creates a copy of the session containing only metadata fields (ID, appName, userId, timestamp).
   * State and Events are explicitly *not* copied.
   *
   * @param original The session whose metadata to copy.
   * @return A new Session instance with only metadata fields populated.
   */
  private Session copySessionMetadata(Session original) {
    return Session.builder(original.id())
        .appName(original.appName())
        .userId(original.userId())
        .lastUpdateTime(original.lastUpdateTime())
        .build();
  }

  @Override
  public Completable deleteSession(String appName, String userId, String sessionId) {
    Objects.requireNonNull(appName, "appName cannot be null");
    Objects.requireNonNull(userId, "userId cannot be null");
    Objects.requireNonNull(sessionId, "sessionId cannot be null");

    String deleteCql = "DELETE FROM sessions WHERE app_name = ? AND user_id = ? AND session_id = ?";
    try {
      session.execute(deleteCql, appName, userId, sessionId);
      return Completable.complete();
    } catch (Exception e) {
      logger.error("Error deleting session from Cassandra", e);
      return Completable.error(e);
    }
  }

  @Override
  public Single<ListEventsResponse> listEvents(String appName, String userId, String sessionId) {
    Objects.requireNonNull(appName, "appName cannot be null");
    Objects.requireNonNull(userId, "userId cannot be null");
    Objects.requireNonNull(sessionId, "sessionId cannot be null");

    String selectCql =
        "SELECT events FROM sessions WHERE app_name = ? AND user_id = ? AND session_id = ?";
    try {
      var row = session.execute(selectCql, appName, userId, sessionId).one();
      if (row == null) {
        return Single.just(ListEventsResponse.builder().build());
      }

      String eventsJson = row.getString("events");
      List<Event> events =
          objectMapper.readValue(
              eventsJson,
              objectMapper.getTypeFactory().constructCollectionType(List.class, Event.class));

      return Single.just(ListEventsResponse.builder().events(ImmutableList.copyOf(events)).build());
    } catch (Exception e) {
      logger.error("Error listing events from Cassandra", e);
      return Single.error(e);
    }
  }

  @Override
  public Completable closeSession(Session session) {
    return BaseSessionService.super.closeSession(session);
  }

  @CanIgnoreReturnValue
  @Override
  public Single<Event> appendEvent(Session session, Event event) {
    Objects.requireNonNull(session, "session cannot be null");
    Objects.requireNonNull(event, "event cannot be null");
    Objects.requireNonNull(session.appName(), "session.appName cannot be null");
    Objects.requireNonNull(session.userId(), "session.userId cannot be null");
    Objects.requireNonNull(session.id(), "session.id cannot be null");

    String appName = session.appName();
    String userId = session.userId();
    String sessionId = session.id();

    // Retrieve the existing session to append the event
    return getSession(appName, userId, sessionId, Optional.empty())
        .switchIfEmpty(
            Single.error(new IllegalArgumentException("Session not found: " + sessionId)))
        .flatMap(
            existingSession -> {
              List<Event> updatedEvents = new ArrayList<>(existingSession.events());
              updatedEvents.add(event);

              Instant newLastUpdateTime = getInstantFromEvent(event);

              Session updatedSession =
                  Session.builder(existingSession.id())
                      .appName(existingSession.appName())
                      .userId(existingSession.userId())
                      .state(existingSession.state())
                      .events(updatedEvents)
                      .lastUpdateTime(newLastUpdateTime)
                      .build();

              String updateCql =
                  "UPDATE sessions SET events = ?, last_update_time = ? WHERE app_name = ? AND user_id = ? AND session_id = ?";
              try {
                this.session.execute(
                    updateCql,
                    objectMapper.writeValueAsString(updatedEvents),
                    newLastUpdateTime,
                    appName,
                    userId,
                    sessionId);
                return Single.just(event);
              } catch (JsonProcessingException e) {
                logger.error("Error serializing events for appendEvent", e);
                return Single.error(e);
              }
            });
  }

  private Instant getInstantFromEvent(Event event) {
    double epochSeconds = (double) event.timestamp();
    long seconds = (long) epochSeconds;
    long nanos = (long) ((epochSeconds - (double) seconds) * 1.0E9);
    return Instant.ofEpochSecond(seconds, nanos);
  }

  @Override
  public void close() throws Exception {
    // TODO: Close Cassandra connection
    if (session != null) {
      session.close();
    }
  }
}
