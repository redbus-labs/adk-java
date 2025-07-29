package com.google.adk.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.sessions.Session;
import java.sql.*;
import java.time.Instant;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Arun Parmar
 */
public class PostgresDBHelper {
  private static final Logger logger = LoggerFactory.getLogger(PostgresDBHelper.class);
  private static volatile PostgresDBHelper instance;
  private final ObjectMapper objectMapper = new ObjectMapper();

  private static final String DB_URL = "DBURL";
  private static final String USER = "DBUSER";
  private static final String PASSWORD = "DBPASSWORD";
  private static final String SESSIONS_TABLE = "sessions";

  private PostgresDBHelper() {
    // Initialize any non-connection resources here
    // Don't create database connections in constructor
    logger.info("PostgresDBHelper instance created.");
  }

  // Thread-safe singleton using double-checked locking
  public static PostgresDBHelper getInstance() {
    if (instance == null) {
      synchronized (PostgresDBHelper.class) {
        if (instance == null) {
          instance = new PostgresDBHelper();
        }
      }
    }
    return instance;
  }

  // Create a new connection for each operation - thread-safe approach
  private Connection getConnection() throws SQLException {
    DriverManager.setLoginTimeout(10); // timeout in seconds
    String userName = System.getenv(USER);
    String password = System.getenv(PASSWORD);
    String host = System.getenv(DB_URL);

    Connection conn = DriverManager.getConnection(host, userName, password);
    if (conn == null) {
      throw new SQLException("Failed to establish a connection to the database.");
    }
    return conn;
  }

  /**
   * Saves a session to the PostgreSQL database. This method acts as an upsert (INSERT if not
   * exists, UPDATE if exists). It parses the incoming session JSON string to extract fields and
   * handle events.
   *
   * @param sessionId The ID of the session.
   * @param session object.
   * @throws SQLException If a database access error occurs.
   * @throws JsonProcessingException If there's an error processing JSON.
   */
  public void saveSession(String sessionId, Session session)
      throws SQLException, JsonProcessingException {
    // Use a transaction for atomicity
    try (Connection conn = this.getConnection()) {
      conn.setAutoCommit(false); // Start transaction

      // Upsert the main session data
      String upsertSql =
          "INSERT INTO "
              + SESSIONS_TABLE
              + " (id, app_name, user_id, state, last_update_time, event_data) VALUES (?, ?, ?, CAST(? AS JSONB), ?,CAST(? AS JSONB)) "
              + "ON CONFLICT (id) DO UPDATE SET app_name = EXCLUDED.app_name, user_id = EXCLUDED.user_id, state = EXCLUDED.state, last_update_time = EXCLUDED.last_update_time,event_data = EXCLUDED.event_data";

      try (PreparedStatement pstmt = conn.prepareStatement(upsertSql)) {
        JSONObject eventJson = new JSONObject();
        eventJson.put("events", session.events().toString());
        System.out.println("Event JSON: " + eventJson.toString());
        pstmt.setString(1, session.id());
        pstmt.setString(2, session.appName());
        pstmt.setString(3, session.userId());
        pstmt.setString(4, objectMapper.writeValueAsString(session.state()));
        pstmt.setObject(5, Timestamp.from(session.lastUpdateTime()));
        pstmt.setString(6, eventJson.toString());
        /** Execute the upsert statement. */
        pstmt.executeUpdate();

        logger.debug("Session {} saved/updated successfully.", sessionId);
      }
      /** Commits the transaction. */
      conn.commit();
      logger.info("Session {} saved/updated successfully.", sessionId);
    } catch (SQLException e) {
      e.printStackTrace();
      logger.error("Error saving session {}. Rolling back transaction.", sessionId, e);
      throw e;
    }
  }

  /**
   * Retrieves a session from the PostgreSQL database by ID. Reconstructs the JSONObject to match
   * the format expected by
   *
   * @param id The ID of the session.
   * @return A JSONObject representing the session, or null if not found.
   * @throws SQLException If a database access error occurs.
   */
  public JSONObject getSession(String id) throws SQLException {
    String sql =
        "SELECT id, app_name, user_id, state, last_update_time,event_data FROM "
            + SESSIONS_TABLE
            + " WHERE id = ?";
    JSONObject sessionJson = null;

    try (Connection conn = getConnection();
        PreparedStatement pstmt = conn.prepareStatement(sql)) {

      pstmt.setString(1, id);
      try (ResultSet rs = pstmt.executeQuery()) {
        if (rs.next()) {
          // Extract data from ResultSet
          String sessionId = rs.getString("id");
          String appName = rs.getString("app_name");
          String userId = rs.getString("user_id");
          String stateDataJson = rs.getString("state"); // This is the JSONB string
          Instant lastUpdateTime = rs.getTimestamp("last_update_time").toInstant();
          JSONObject eventJson = new JSONObject(rs.getString("event_data"));
          JSONArray eventArr = new JSONArray(eventJson.getString("events"));

          // Create a Jackson JsonNode to reconstruct the Session structure expected by
          sessionJson = new JSONObject();
          sessionJson.put("id", sessionId);
          sessionJson.put("appName", appName);
          sessionJson.put("userId", userId);
          sessionJson.put("state", new JSONObject(stateDataJson));
          sessionJson.put("events", eventArr);
          sessionJson.put(
              "lastUpdateTime",
              (double) lastUpdateTime.getEpochSecond()
                  + lastUpdateTime.getNano() / 1_000_000_000.0);

          logger.debug("Session {} retrieved successfully.", sessionId);
        } else {
          logger.warn("Session with ID {} not found.", id);
          return null; // Return null if session not found
        }
      }
    }
    return sessionJson;
  }

  /**
   * Deletes a session and its associated events from the database.
   *
   * @param sessionId The ID of the session to delete.
   * @throws SQLException If a database access error occurs.
   */
  public void deleteSession(String sessionId) throws SQLException {
    // Due to ON DELETE CASCADE, deleting from 'sessions' table will automatically
    // delete associated events in the 'events' table.
    String sql = "DELETE FROM " + SESSIONS_TABLE + " WHERE id = ?";
    try (Connection conn = getConnection();
        PreparedStatement pstmt = conn.prepareStatement(sql)) {
      pstmt.setString(1, sessionId);
      int rowsAffected = pstmt.executeUpdate();
      if (rowsAffected > 0) {
        logger.info("Session {} and its events deleted successfully.", sessionId);
      } else {
        logger.warn("Attempted to delete session {}, but it was not found.", sessionId);
      }
    }
  }
}
