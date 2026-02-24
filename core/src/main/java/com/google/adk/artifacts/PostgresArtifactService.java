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

package com.google.adk.artifacts;

import com.google.adk.store.PostgresArtifactStore;
import com.google.adk.store.PostgresArtifactStore.ArtifactData;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * A PostgreSQL-backed implementation of the {@link BaseArtifactService}.
 *
 * <p>Stores artifacts persistently in PostgreSQL database with BYTEA storage. Uses reactive RxJava3
 * types wrapping JDBC operations. Supports environment variable configuration or explicit
 * constructor parameters.
 *
 * <p><b>Single Table Per JVM:</b> This service uses a single "artifacts" table per JVM for all
 * artifact storage. Multi-tenancy is achieved through (appName, userId, sessionId) isolation,
 * eliminating the need for separate physical tables.
 *
 * <p>Example usage with environment variables:
 *
 * <pre>{@code
 * PostgresArtifactService artifactService = new PostgresArtifactService();
 * // Uses DBURL, DBUSER, DBPASSWORD environment variables
 * // Stores in "artifacts" table with appName/userId/sessionId isolation
 * }</pre>
 *
 * <p>Example usage with explicit connection parameters:
 *
 * <pre>{@code
 * PostgresArtifactService artifactService = new PostgresArtifactService(
 *     "jdbc:postgresql://localhost:5432/mydb",
 *     "username",
 *     "password"
 * );
 * }</pre>
 *
 * @author Yashas S
 * @since 2025-12-08
 */
public final class PostgresArtifactService implements BaseArtifactService {

  private static final String DEFAULT_TABLE_NAME = "artifacts";
  private final PostgresArtifactStore dbHelper;

  /**
   * Creates a new PostgresArtifactService using environment variables for database connection. Uses
   * the default "artifacts" table. Per JVM, only one table is used for all artifact operations,
   * with multi-tenancy achieved through (appName, userId, sessionId) isolation.
   *
   * <p>Required environment variables:
   *
   * <ul>
   *   <li>DBURL - PostgreSQL database URL (e.g., jdbc:postgresql://localhost:5432/mydb)
   *   <li>DBUSER - Database username
   *   <li>DBPASSWORD - Database password
   * </ul>
   */
  public PostgresArtifactService() {
    this.dbHelper = PostgresArtifactStore.getInstance(DEFAULT_TABLE_NAME);
  }

  /**
   * Creates a new PostgresArtifactService with explicit connection parameters. Uses the default
   * "artifacts" table.
   *
   * <p>This constructor is useful for testing or when environment variables are not available.
   *
   * @param dbUrl the database URL
   * @param dbUser the database username
   * @param dbPassword the database password
   */
  public PostgresArtifactService(String dbUrl, String dbUser, String dbPassword) {
    this.dbHelper =
        PostgresArtifactStore.createInstance(dbUrl, dbUser, dbPassword, DEFAULT_TABLE_NAME);
  }

  @Override
  public Single<Integer> saveArtifact(
      String appName, String userId, String sessionId, String filename, Part artifact) {
    return Single.fromCallable(
            () -> {
              try {
                // Extract data from Part
                byte[] data = extractBytesFromPart(artifact);
                String mimeType = extractMimeTypeFromPart(artifact);

                // Save to database without metadata (metadata = null)
                // Applications should use saveArtifact(..., metadata) if they need custom metadata
                return dbHelper.saveArtifact(
                    appName, userId, sessionId, filename, data, mimeType, null);
              } catch (SQLException e) {
                throw new RuntimeException("Failed to save artifact: " + e.getMessage(), e);
              }
            })
        .subscribeOn(Schedulers.io());
  }

  /**
   * Save an artifact with custom metadata.
   *
   * <p>This overloaded method allows the caller to provide custom metadata as a JSON string.
   * Metadata can contain any application-specific information (e.g., cost tracking, business
   * context, processing results).
   *
   * <p>Example usage:
   *
   * <pre>{@code
   * String metadata = "{\"projectId\":\"ABC\",\"uploadedBy\":\"user123\",\"cost\":0.005}";
   * artifactService.saveArtifact(appName, userId, sessionId, filename, part, metadata);
   * }</pre>
   *
   * @param appName the application name
   * @param userId the user ID
   * @param sessionId the session ID
   * @param filename the artifact filename
   * @param artifact the artifact as a Part object
   * @param metadata custom metadata JSON string (can be null)
   * @return a Single emitting the version number of the saved artifact
   */
  public Single<Integer> saveArtifact(
      String appName,
      String userId,
      String sessionId,
      String filename,
      Part artifact,
      String metadata) {
    return Single.fromCallable(
            () -> {
              try {
                // Extract data from Part
                byte[] data = extractBytesFromPart(artifact);
                String mimeType = extractMimeTypeFromPart(artifact);

                // Save to database with caller-provided metadata
                return dbHelper.saveArtifact(
                    appName, userId, sessionId, filename, data, mimeType, metadata);
              } catch (SQLException e) {
                throw new RuntimeException("Failed to save artifact: " + e.getMessage(), e);
              }
            })
        .subscribeOn(Schedulers.io());
  }

  @Override
  public Maybe<Part> loadArtifact(
      String appName, String userId, String sessionId, String filename, Optional<Integer> version) {
    return Maybe.fromCallable(
            () -> {
              try {
                // Load from database
                ArtifactData artifactData =
                    dbHelper.loadArtifact(
                        appName, userId, sessionId, filename, version.orElse(null));

                if (artifactData == null) {
                  return null;
                }

                // Reconstruct Part from binary data
                return Part.fromBytes(artifactData.data, artifactData.mimeType);
              } catch (SQLException e) {
                throw new RuntimeException("Failed to load artifact: " + e.getMessage(), e);
              }
            })
        .subscribeOn(Schedulers.io());
  }

  @Override
  public Single<ListArtifactsResponse> listArtifactKeys(
      String appName, String userId, String sessionId) {
    return Single.fromCallable(
            () -> {
              try {
                List<String> filenames = dbHelper.listFilenames(appName, userId, sessionId);
                return ListArtifactsResponse.builder().filenames(filenames).build();
              } catch (SQLException e) {
                throw new RuntimeException("Failed to list artifacts: " + e.getMessage(), e);
              }
            })
        .subscribeOn(Schedulers.io());
  }

  @Override
  public Completable deleteArtifact(
      String appName, String userId, String sessionId, String filename) {
    return Completable.fromAction(
            () -> {
              try {
                dbHelper.deleteArtifact(appName, userId, sessionId, filename);
              } catch (SQLException e) {
                throw new RuntimeException("Failed to delete artifact: " + e.getMessage(), e);
              }
            })
        .subscribeOn(Schedulers.io());
  }

  @Override
  public Single<ImmutableList<Integer>> listVersions(
      String appName, String userId, String sessionId, String filename) {
    return Single.fromCallable(
            () -> {
              try {
                List<Integer> versions =
                    dbHelper.listVersions(appName, userId, sessionId, filename);
                return ImmutableList.copyOf(versions);
              } catch (SQLException e) {
                throw new RuntimeException("Failed to list versions: " + e.getMessage(), e);
              }
            })
        .subscribeOn(Schedulers.io());
  }

  /**
   * Extract bytes from Part object. Handles the nested Optional structure of Part.inlineData().
   *
   * @param part the Part object to extract bytes from
   * @return the byte array
   * @throws IllegalStateException if Part does not contain inline data
   */
  private byte[] extractBytesFromPart(Part part) {
    if (part.inlineData() != null && part.inlineData().isPresent()) {
      return part.inlineData()
          .get()
          .data()
          .orElseThrow(() -> new IllegalStateException("Part does not contain data"));
    }
    throw new IllegalStateException("Part does not contain inline data");
  }

  /**
   * Extract MIME type from Part object.
   *
   * @param part the Part object to extract MIME type from
   * @return the MIME type, or "application/octet-stream" as default
   */
  private String extractMimeTypeFromPart(Part part) {
    if (part.inlineData() != null && part.inlineData().isPresent()) {
      return part.inlineData().get().mimeType().orElse("application/octet-stream");
    }
    return "application/octet-stream"; // Default fallback
  }

  /** Closes the database connection pool. Call this when shutting down the application. */
  public void close() {
    dbHelper.close();
  }
}
