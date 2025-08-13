/**
 * @author Sandeep Belgavi
 * @since 2025-07-31
 */
package com.google.adk.sessions;

import com.google.adk.events.Event;
import com.google.adk.utils.CassandraDBHelper;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
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
  private final CassandraDBHelper dbHelper;

  public CassandraSessionService() {
    this.dbHelper = CassandraDBHelper.getInstance();
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

    try {
      dbHelper.saveSession(newSession);
      return Single.just(newSession);
    } catch (Exception e) {
      logger.error("Error creating session in Cassandra", e);
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

    try {
      Optional<Session> session = dbHelper.getSession(appName, userId, sessionId);
      // TODO: Apply filtering based on config if needed (similar to InMemorySessionService)
      return Maybe.fromOptional(session);
    } catch (Exception e) {
      logger.error("Error retrieving session from Cassandra", e);
      return Maybe.error(e);
    }
  }

  @Override
  public Single<ListSessionsResponse> listSessions(String appName, String userId) {
    Objects.requireNonNull(appName, "appName cannot be null");
    Objects.requireNonNull(userId, "userId cannot be null");

    try {
      List<Session> sessions = dbHelper.listSessions(appName, userId);
      return Single.just(ListSessionsResponse.builder().sessions(sessions).build());
    } catch (Exception e) {
      logger.error("Error listing sessions from Cassandra", e);
      return Single.error(e);
    }
  }

  @Override
  public Completable deleteSession(String appName, String userId, String sessionId) {
    Objects.requireNonNull(appName, "appName cannot be null");
    Objects.requireNonNull(userId, "userId cannot be null");
    Objects.requireNonNull(sessionId, "sessionId cannot be null");

    try {
      dbHelper.deleteSession(appName, userId, sessionId);
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

    try {
      List<Event> events = dbHelper.listEvents(appName, userId, sessionId);
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

    return getSession(appName, userId, sessionId, Optional.empty())
        .switchIfEmpty(
            Single.error(new IllegalArgumentException("Session not found: " + sessionId)))
        .flatMap(
            existingSession -> {
              List<Event> updatedEvents = new ArrayList<>(existingSession.events());
              updatedEvents.add(event);

              Instant newLastUpdateTime = getInstantFromEvent(event);

              try {
                dbHelper.updateSessionEvents(
                    appName, userId, sessionId, updatedEvents, newLastUpdateTime);
                return Single.just(event);
              } catch (Exception e) {
                logger.error("Error appending event to session in Cassandra", e);
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
    dbHelper.close();
  }
}
