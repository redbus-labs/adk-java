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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.events.Event;
import com.google.adk.store.CassandraHelper;
import java.io.IOException;
import java.util.Collections;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Unit tests for {@link CassandraSessionService}.
 *
 * @author Sandeep Belgavi
 * @since 2025-10-21
 */
public class CassandraSessionServiceTest {

  private CqlSession mockCqlSession;
  private ObjectMapper mockObjectMapper;
  private CassandraSessionService sessionService;
  private MockedStatic<CassandraHelper> cassandraHelper;

  @BeforeEach
  public void setUp() throws IOException {
    mockCqlSession = mock(CqlSession.class);
    mockObjectMapper = mock(ObjectMapper.class);
    cassandraHelper = Mockito.mockStatic(CassandraHelper.class);

    // Mock CassandraHelper to return our mock objects
    cassandraHelper.when(CassandraHelper::getSession).thenReturn(mockCqlSession);
    cassandraHelper.when(CassandraHelper::getObjectMapper).thenReturn(mockObjectMapper);
    sessionService = new CassandraSessionService();
  }

  @AfterEach
  public void tearDown() {
    cassandraHelper.close();
  }

  @Test
  public void testCreateSession() throws Exception {
    String appName = "testApp";
    String userId = "testUser";
    String sessionId = "testSession";

    Session session = Session.builder(sessionId).appName(appName).userId(userId).build();
    when(mockObjectMapper.writeValueAsString(any(Session.class))).thenReturn("session_json");
    ResultSet mockResultSet = mock(ResultSet.class);
    when(mockResultSet.iterator()).thenReturn(Collections.emptyIterator());
    when(mockCqlSession.execute(any(String.class), any(String.class))).thenReturn(mockResultSet);
    when(mockCqlSession.execute(any(String.class), any(String.class), any(String.class)))
        .thenReturn(mockResultSet);

    sessionService.createSession(appName, userId, null, sessionId).blockingGet();

    verify(mockCqlSession, Mockito.times(1))
        .execute(
            "INSERT INTO sessions (app_name, user_id, session_id, session_data) VALUES (?, ?, ?, ?)",
            appName,
            userId,
            sessionId,
            "session_json");
  }

  @Test
  public void testGetSession() throws Exception {
    String appName = "testApp";
    String userId = "testUser";
    String sessionId = "testSession";

    Row mockRow = mock(Row.class);
    ResultSet mockResultSet = mock(ResultSet.class);
    when(mockResultSet.one()).thenReturn(mockRow);
    when(mockCqlSession.execute(
            "SELECT session_data FROM sessions WHERE app_name = ? AND user_id = ? AND session_id = ?",
            appName,
            userId,
            sessionId))
        .thenReturn(mockResultSet);
    when(mockRow.getString("session_data")).thenReturn("session_json");
    Session expectedSession = Session.builder(sessionId).appName(appName).userId(userId).build();
    when(mockObjectMapper.readValue("session_json", Session.class)).thenReturn(expectedSession);
    ResultSet mockStateResultSet = mock(ResultSet.class);
    when(mockStateResultSet.iterator()).thenReturn(Collections.emptyIterator());
    when(mockCqlSession.execute(
            "SELECT state_key, state_value FROM app_state WHERE app_name = ?", appName))
        .thenReturn(mockStateResultSet);
    when(mockCqlSession.execute(
            "SELECT state_key, state_value FROM user_state WHERE app_name = ? AND user_id = ?",
            appName,
            userId))
        .thenReturn(mockStateResultSet);

    Session actualSession =
        sessionService.getSession(appName, userId, sessionId, Optional.empty()).blockingGet();

    assertThat(actualSession.id()).isEqualTo(expectedSession.id());
  }

  @Test
  public void testDeleteSession() {
    String appName = "testApp";
    String userId = "testUser";
    String sessionId = "testSession";

    sessionService.deleteSession(appName, userId, sessionId).blockingAwait();

    verify(mockCqlSession)
        .execute(
            "DELETE FROM sessions WHERE app_name = ? AND user_id = ? AND session_id = ?",
            appName,
            userId,
            sessionId);
  }

  @Test
  public void testListSessions() throws Exception {
    String appName = "testApp";
    String userId = "testUser";

    Row mockRow = mock(Row.class);
    ResultSet mockResultSet = mock(ResultSet.class);
    when(mockResultSet.iterator()).thenReturn(Collections.singletonList(mockRow).iterator());
    when(mockCqlSession.execute(
            "SELECT session_data FROM sessions WHERE app_name = ? AND user_id = ?",
            appName,
            userId))
        .thenReturn(mockResultSet);
    when(mockRow.getString("session_data")).thenReturn("session_json");
    Session expectedSession = Session.builder("s1").appName(appName).userId(userId).build();
    when(mockObjectMapper.readValue("session_json", Session.class)).thenReturn(expectedSession);

    ListSessionsResponse response = sessionService.listSessions(appName, userId).blockingGet();

    assertThat(response.sessions()).hasSize(1);
    assertThat(response.sessions().get(0).id()).isEqualTo("s1");
  }

  @Test
  public void testAppendEvent() throws Exception {
    String appName = "testApp";
    String userId = "testUser";
    String sessionId = "testSession";

    Session session = Session.builder(sessionId).appName(appName).userId(userId).build();
    Event event = Event.builder().timestamp(12345L).author("user").build();
    when(mockObjectMapper.writeValueAsString(any(Session.class))).thenReturn("session_json");

    sessionService.appendEvent(session, event).blockingGet();

    verify(mockCqlSession, Mockito.times(1))
        .execute(
            "INSERT INTO sessions (app_name, user_id, session_id, session_data) VALUES (?, ?, ?, ?)",
            appName,
            userId,
            sessionId,
            "session_json");
  }
}
