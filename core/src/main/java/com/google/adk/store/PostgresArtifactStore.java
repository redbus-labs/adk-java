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

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages PostgreSQL connection pool and provides database operations for artifact storage. Uses
 * HikariCP for connection pooling and supports environment variable configuration.
 *
 * @author Yashas S
 * @since 2025-12-08
 */
public class PostgresArtifactStore {

  // Environment variable keys
  private static final String DB_URL_ENV = "DBURL";
  private static final String DB_USER_ENV = "DBUSER";
  private static final String DB_PASSWORD_ENV = "DBPASSWORD";

  // Default table name
  private static final String DEFAULT_TABLE_NAME = "artifacts";

  private static volatile PostgresArtifactStore instance;
  private final HikariDataSource dataSource;
  private final String tableName;

  /**
   * Private constructor for singleton pattern. Initializes HikariCP connection pool from
   * environment variables.
   */
  private PostgresArtifactStore() {
    this(DEFAULT_TABLE_NAME);
  }

  /**
   * Private constructor with custom table name. Initializes HikariCP connection pool from
   * environment variables.
   *
   * @param tableName the table name to use for artifacts
   */
  private PostgresArtifactStore(String tableName) {
    this.tableName = tableName;
    this.dataSource = initializeDataSource();
    initializeTable();
  }

  /**
   * Constructor with explicit connection parameters.
   *
   * @param dbUrl the database URL
   * @param dbUser the database username
   * @param dbPassword the database password
   * @param tableName the table name to use for artifacts
   */
  private PostgresArtifactStore(String dbUrl, String dbUser, String dbPassword, String tableName) {
    this.tableName = tableName;
    this.dataSource = initializeDataSource(dbUrl, dbUser, dbPassword);
    initializeTable();
  }

  /**
   * Get singleton instance with default table name. Uses environment variables for database
   * connection.
   *
   * @return the PostgresArtifactStore instance
   */
  public static PostgresArtifactStore getInstance() {
    if (instance == null) {
      synchronized (PostgresArtifactStore.class) {
        if (instance == null) {
          instance = new PostgresArtifactStore();
        }
      }
    }
    return instance;
  }

  /**
   * Get singleton instance with custom table name. Uses environment variables for database
   * connection.
   *
   * @param tableName the table name to use for artifacts
   * @return the PostgresArtifactStore instance
   */
  public static PostgresArtifactStore getInstance(String tableName) {
    if (instance == null) {
      synchronized (PostgresArtifactStore.class) {
        if (instance == null) {
          instance = new PostgresArtifactStore(tableName);
        }
      }
    }
    return instance;
  }

  /**
   * Get instance with explicit connection parameters. Creates a new instance (not singleton) for
   * flexibility.
   *
   * @param dbUrl the database URL
   * @param dbUser the database username
   * @param dbPassword the database password
   * @param tableName the table name to use for artifacts
   * @return a new PostgresArtifactStore instance
   */
  public static PostgresArtifactStore createInstance(
      String dbUrl, String dbUser, String dbPassword, String tableName) {
    return new PostgresArtifactStore(dbUrl, dbUser, dbPassword, tableName);
  }

  /**
   * Initialize HikariCP data source from environment variables.
   *
   * @return the configured HikariDataSource
   */
  private HikariDataSource initializeDataSource() {
    String dbUrl = System.getenv(DB_URL_ENV);
    String dbUser = System.getenv(DB_USER_ENV);
    String dbPassword = System.getenv(DB_PASSWORD_ENV);

    if (dbUrl == null || dbUrl.isEmpty()) {
      throw new IllegalStateException(
          "Database URL not configured. Set " + DB_URL_ENV + " environment variable.");
    }

    return initializeDataSource(dbUrl, dbUser, dbPassword);
  }

  /**
   * Initialize HikariCP data source with explicit parameters.
   *
   * @param dbUrl the database URL
   * @param dbUser the database username
   * @param dbPassword the database password
   * @return the configured HikariDataSource
   */
  private HikariDataSource initializeDataSource(String dbUrl, String dbUser, String dbPassword) {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(dbUrl);
    config.setUsername(dbUser);
    config.setPassword(dbPassword);

    // Connection pool settings
    config.setMaximumPoolSize(10);
    config.setMinimumIdle(2);
    config.setConnectionTimeout(30000);
    config.setIdleTimeout(600000);
    config.setMaxLifetime(1800000);
    // Leak detection threshold increased to 2 minutes for large file handling (videos, PDFs)
    config.setLeakDetectionThreshold(120000); // 120 seconds (2 minutes)

    // Performance settings
    config.addDataSourceProperty("cachePrepStmts", "true");
    config.addDataSourceProperty("prepStmtCacheSize", "250");
    config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

    return new HikariDataSource(config);
  }

  /**
   * Get connection from pool.
   *
   * @return a database connection
   * @throws SQLException if connection fails
   */
  private Connection getConnection() throws SQLException {
    return dataSource.getConnection();
  }

  /** Initialize artifacts table if it doesn't exist. */
  public void initializeTable() {
    String createTableSQL =
        String.format(
            "CREATE TABLE IF NOT EXISTS %s ("
                + "id SERIAL PRIMARY KEY, "
                + "app_name VARCHAR(255) NOT NULL, "
                + "user_id VARCHAR(255) NOT NULL, "
                + "session_id VARCHAR(255) NOT NULL, "
                + "filename VARCHAR(255) NOT NULL, "
                + "version INT NOT NULL DEFAULT 0, "
                + "mime_type VARCHAR(100), "
                + "data BYTEA NOT NULL, "
                + "metadata JSONB, "
                + "created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP, "
                + "CONSTRAINT %s_unique_version UNIQUE(app_name, user_id, session_id, filename, version)"
                + ")",
            tableName, tableName);

    String createIndex1 =
        String.format(
            "CREATE INDEX IF NOT EXISTS idx_%s_lookup ON %s(app_name, user_id, session_id, filename)",
            tableName, tableName);

    String createIndex2 =
        String.format(
            "CREATE INDEX IF NOT EXISTS idx_%s_session ON %s(app_name, user_id, session_id)",
            tableName, tableName);

    try (Connection conn = getConnection();
        Statement stmt = conn.createStatement()) {
      stmt.execute(createTableSQL);
      stmt.execute(createIndex1);
      stmt.execute(createIndex2);
    } catch (SQLException e) {
      throw new RuntimeException("Failed to initialize artifacts table: " + tableName, e);
    }
  }

  /**
   * Save artifact to database. Returns the assigned version number.
   *
   * @param appName the application name
   * @param userId the user ID
   * @param sessionId the session ID
   * @param filename the artifact filename
   * @param data the artifact binary data
   * @param mimeType the MIME type
   * @return the version number assigned to this artifact
   * @throws SQLException if save operation fails
   */
  public int saveArtifact(
      String appName,
      String userId,
      String sessionId,
      String filename,
      byte[] data,
      String mimeType)
      throws SQLException {
    return saveArtifact(appName, userId, sessionId, filename, data, mimeType, null);
  }

  /**
   * Save artifact to database with metadata. Returns the assigned version number.
   *
   * @param appName the application name
   * @param userId the user ID
   * @param sessionId the session ID
   * @param filename the artifact filename
   * @param data the artifact binary data
   * @param mimeType the MIME type
   * @param metadata the metadata JSON string (can be null)
   * @return the version number assigned to this artifact
   * @throws SQLException if save operation fails
   */
  public int saveArtifact(
      String appName,
      String userId,
      String sessionId,
      String filename,
      byte[] data,
      String mimeType,
      String metadata)
      throws SQLException {
    // First, get the next version number
    int nextVersion = getNextVersion(appName, userId, sessionId, filename);

    String sql =
        String.format(
            "INSERT INTO %s (app_name, user_id, session_id, filename, version, mime_type, data, metadata) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
            tableName);

    try (Connection conn = getConnection();
        PreparedStatement pstmt = conn.prepareStatement(sql)) {
      pstmt.setString(1, appName);
      pstmt.setString(2, userId);
      pstmt.setString(3, sessionId);
      pstmt.setString(4, filename);
      pstmt.setInt(5, nextVersion);
      pstmt.setString(6, mimeType);
      pstmt.setBytes(7, data);
      pstmt.setString(8, metadata);

      int rowsAffected = pstmt.executeUpdate();

      if (rowsAffected > 0) {
        return nextVersion;
      } else {
        throw new SQLException("Failed to save artifact, no rows affected");
      }
    }
  }

  /**
   * Get next version number for an artifact.
   *
   * @param appName the application name
   * @param userId the user ID
   * @param sessionId the session ID
   * @param filename the artifact filename
   * @return the next version number
   * @throws SQLException if query fails
   */
  private int getNextVersion(String appName, String userId, String sessionId, String filename)
      throws SQLException {
    String sql =
        String.format(
            "SELECT COALESCE(MAX(version), -1) + 1 as next_version FROM %s "
                + "WHERE app_name = ? AND user_id = ? AND session_id = ? AND filename = ?",
            tableName);

    try (Connection conn = getConnection();
        PreparedStatement pstmt = conn.prepareStatement(sql)) {
      pstmt.setString(1, appName);
      pstmt.setString(2, userId);
      pstmt.setString(3, sessionId);
      pstmt.setString(4, filename);

      try (ResultSet rs = pstmt.executeQuery()) {
        if (rs.next()) {
          return rs.getInt("next_version");
        }
        return 0; // First version
      }
    }
  }

  /**
   * Load artifact by version or latest. Returns ArtifactData object or null if not found.
   *
   * @param appName the application name
   * @param userId the user ID
   * @param sessionId the session ID
   * @param filename the artifact filename
   * @param version the version number, or null for latest
   * @return ArtifactData object or null if not found
   * @throws SQLException if load operation fails
   */
  public ArtifactData loadArtifact(
      String appName, String userId, String sessionId, String filename, Integer version)
      throws SQLException {
    String sql;
    if (version != null) {
      // Load specific version
      sql =
          String.format(
              "SELECT data, mime_type, version, created_at, metadata FROM %s "
                  + "WHERE app_name = ? AND user_id = ? AND session_id = ? AND filename = ? AND version = ?",
              tableName);
    } else {
      // Load latest version
      sql =
          String.format(
              "SELECT data, mime_type, version, created_at, metadata FROM %s "
                  + "WHERE app_name = ? AND user_id = ? AND session_id = ? AND filename = ? "
                  + "ORDER BY version DESC LIMIT 1",
              tableName);
    }

    try (Connection conn = getConnection();
        PreparedStatement pstmt = conn.prepareStatement(sql)) {
      pstmt.setString(1, appName);
      pstmt.setString(2, userId);
      pstmt.setString(3, sessionId);
      pstmt.setString(4, filename);
      if (version != null) {
        pstmt.setInt(5, version);
      }

      try (ResultSet rs = pstmt.executeQuery()) {
        if (rs.next()) {
          byte[] data = rs.getBytes("data");
          String mimeType = rs.getString("mime_type");
          int loadedVersion = rs.getInt("version");
          Timestamp createdAt = rs.getTimestamp("created_at");
          String metadata = rs.getString("metadata");

          return new ArtifactData(data, mimeType, loadedVersion, createdAt, metadata);
        }
      }
    }

    return null; // Not found
  }

  /**
   * List all filenames for a session.
   *
   * @param appName the application name
   * @param userId the user ID
   * @param sessionId the session ID
   * @return list of artifact filenames
   * @throws SQLException if query fails
   */
  public List<String> listFilenames(String appName, String userId, String sessionId)
      throws SQLException {
    String sql =
        String.format(
            "SELECT DISTINCT filename FROM %s "
                + "WHERE app_name = ? AND user_id = ? AND session_id = ? "
                + "ORDER BY filename",
            tableName);

    List<String> filenames = new ArrayList<>();

    try (Connection conn = getConnection();
        PreparedStatement pstmt = conn.prepareStatement(sql)) {
      pstmt.setString(1, appName);
      pstmt.setString(2, userId);
      pstmt.setString(3, sessionId);

      try (ResultSet rs = pstmt.executeQuery()) {
        while (rs.next()) {
          filenames.add(rs.getString("filename"));
        }
      }
    }

    return filenames;
  }

  /**
   * Delete all versions of an artifact.
   *
   * @param appName the application name
   * @param userId the user ID
   * @param sessionId the session ID
   * @param filename the artifact filename
   * @throws SQLException if delete operation fails
   */
  public void deleteArtifact(String appName, String userId, String sessionId, String filename)
      throws SQLException {
    String sql =
        String.format(
            "DELETE FROM %s WHERE app_name = ? AND user_id = ? AND session_id = ? AND filename = ?",
            tableName);

    try (Connection conn = getConnection();
        PreparedStatement pstmt = conn.prepareStatement(sql)) {
      pstmt.setString(1, appName);
      pstmt.setString(2, userId);
      pstmt.setString(3, sessionId);
      pstmt.setString(4, filename);

      pstmt.executeUpdate();
    }
  }

  /**
   * List all versions for an artifact.
   *
   * @param appName the application name
   * @param userId the user ID
   * @param sessionId the session ID
   * @param filename the artifact filename
   * @return list of version numbers
   * @throws SQLException if query fails
   */
  public List<Integer> listVersions(
      String appName, String userId, String sessionId, String filename) throws SQLException {
    String sql =
        String.format(
            "SELECT version FROM %s "
                + "WHERE app_name = ? AND user_id = ? AND session_id = ? AND filename = ? "
                + "ORDER BY version",
            tableName);

    List<Integer> versions = new ArrayList<>();

    try (Connection conn = getConnection();
        PreparedStatement pstmt = conn.prepareStatement(sql)) {
      pstmt.setString(1, appName);
      pstmt.setString(2, userId);
      pstmt.setString(3, sessionId);
      pstmt.setString(4, filename);

      try (ResultSet rs = pstmt.executeQuery()) {
        while (rs.next()) {
          versions.add(rs.getInt("version"));
        }
      }
    }

    return versions;
  }

  /** Close the connection pool. */
  public void close() {
    if (dataSource != null && !dataSource.isClosed()) {
      dataSource.close();
    }
  }

  /** Data class for artifact results. */
  public static class ArtifactData {
    public final byte[] data;
    public final String mimeType;
    public final int version;
    public final Timestamp createdAt;
    public final String metadata;

    public ArtifactData(
        byte[] data, String mimeType, int version, Timestamp createdAt, String metadata) {
      this.data = data;
      this.mimeType = mimeType;
      this.version = version;
      this.createdAt = createdAt;
      this.metadata = metadata;
    }
  }
}
