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

package com.google.adk.store;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Manages the Cassandra connection and session.
 *
 * @author Sandeep Belgavi
 * @since 2025-10-02
 */
public class CassandraHelper {

  private static final String KEYSPACE_NAME = "adk";
  private static CqlSession session;
  private static final ObjectMapper objectMapper = createObjectMapper();

  private static ObjectMapper createObjectMapper() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.registerModule(new Jdk8Module());
    mapper.registerModule(new JavaTimeModule());
    mapper.registerModule(new GuavaModule());
    mapper.setSerializationInclusion(JsonInclude.Include.NON_ABSENT);
    mapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
    return mapper;
  }

  /**
   * Returns the shared object mapper instance.
   *
   * @return the object mapper
   */
  public static ObjectMapper getObjectMapper() {
    return objectMapper;
  }

  /**
   * Initializes the Cassandra connection and creates the keyspace and tables if they don't exist.
   *
   * @param sessionBuilder the CqlSessionBuilder to use
   */
  public static synchronized void initialize(CqlSessionBuilder sessionBuilder) {
    if (session == null) {
      session = sessionBuilder.build();
      createKeyspace();
      session.execute("USE " + KEYSPACE_NAME);
      createTables();
    }
  }

  /**
   * Returns the Cassandra session.
   *
   * @return the CqlSession
   */
  public static CqlSession getSession() {
    if (session == null) {
      throw new IllegalStateException("CassandraHelper has not been initialized.");
    }
    return session;
  }

  /** Closes the Cassandra session. */
  public static synchronized void close() {
    if (session != null) {
      session.close();
      session = null;
    }
  }

  private static void createKeyspace() {
    session.execute(
        "CREATE KEYSPACE IF NOT EXISTS "
            + KEYSPACE_NAME
            + " WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}");
  }

  private static void createTables() {
    // Session Service Tables
    session.execute(
        "CREATE TABLE IF NOT EXISTS sessions ("
            + "app_name TEXT, "
            + "user_id TEXT, "
            + "session_id TEXT, "
            + "session_data TEXT, "
            + "PRIMARY KEY ((app_name, user_id), session_id))");

    session.execute(
        "CREATE TABLE IF NOT EXISTS user_state ("
            + "app_name TEXT, "
            + "user_id TEXT, "
            + "state_key TEXT, "
            + "state_value TEXT, "
            + "PRIMARY KEY ((app_name, user_id), state_key))");

    session.execute(
        "CREATE TABLE IF NOT EXISTS app_state ("
            + "app_name TEXT, "
            + "state_key TEXT, "
            + "state_value TEXT, "
            + "PRIMARY KEY (app_name, state_key))");

    // Artifact Service Table
    session.execute(
        "CREATE TABLE IF NOT EXISTS artifacts ("
            + "app_name TEXT, "
            + "user_id TEXT, "
            + "session_id TEXT, "
            + "filename TEXT, "
            + "version INT, "
            + "artifact_data TEXT, "
            + "PRIMARY KEY ((app_name, user_id, session_id), filename, version))");

    // Memory Service Tables
    session.execute(
        "CREATE TABLE IF NOT EXISTS memory_events ("
            + "app_name TEXT, "
            + "user_id TEXT, "
            + "event_id TIMEUUID, "
            + "event_data TEXT, "
            + "PRIMARY KEY ((app_name, user_id), event_id))");

    session.execute(
        "CREATE TABLE IF NOT EXISTS memory_inverted_index ("
            + "app_name TEXT, "
            + "user_id TEXT, "
            + "word TEXT, "
            + "event_ids SET<TIMEUUID>, "
            + "PRIMARY KEY ((app_name, user_id), word))");
  }
}
