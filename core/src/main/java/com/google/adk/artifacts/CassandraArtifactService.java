/**
 * @author Sandeep Belgavi
 * @since 2025-07-31
 */
package com.google.adk.artifacts;

import com.datastax.oss.driver.api.core.CqlSession;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CassandraArtifactService implements BaseArtifactService {
  private final String appName;
  private final String artifactTableName;
  private final CqlSession session;
  private final ObjectMapper objectMapper;
  private static final Logger logger = LoggerFactory.getLogger(CassandraArtifactService.class);
  private static final String FILENAMES_TABLE = "session_filenames_by_user";

  private static final String CASSANDRA_HOST = "cassandra_host";
  private static final String CASSANDRA_PORT = "cassandra_port";
  private static final String CASSANDRA_USER = "cassandra_user";
  private static final String CASSANDRA_PASSWORD = "cassandra_password";
  private static final String CASSANDRA_KEYSPACE = "cassandra_keyspace";

  public CassandraArtifactService(String appName, String artifactTableName) {
    this.appName = appName;
    this.artifactTableName = artifactTableName;
    this.objectMapper = new ObjectMapper();
    this.objectMapper.registerModule(new Jdk8Module());
    this.objectMapper.registerModule(new GuavaModule());
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
  public Completable deleteArtifact(
      String appName, String userId, String sessionId, String filename) {
    Objects.requireNonNull(appName, "appName cannot be null");
    Objects.requireNonNull(userId, "userId cannot be null");
    Objects.requireNonNull(sessionId, "sessionId cannot be null");
    Objects.requireNonNull(filename, "filename cannot be null");

    String deleteCql =
        "DELETE FROM artifacts WHERE app_name = ? AND user_id = ? AND session_id = ? AND filename = ?";
    try {
      session.execute(deleteCql, appName, userId, sessionId, filename);
      return Completable.complete();
    } catch (Exception e) {
      logger.error("Error deleting artifact from Cassandra", e);
      return Completable.error(e);
    }
  }

  @Override
  public Single<ListArtifactsResponse> listArtifactKeys(
      String appName, String userId, String sessionId) {
    Objects.requireNonNull(appName, "appName cannot be null");
    Objects.requireNonNull(userId, "userId cannot be null");
    Objects.requireNonNull(sessionId, "sessionId cannot be null");

    String selectCql =
        "SELECT filename FROM "
            + FILENAMES_TABLE
            + " WHERE app_name = ? AND user_id = ? AND session_id = ?";
    try {
      var resultSet = session.execute(selectCql, appName, userId, sessionId);
      List<String> filenames = new ArrayList<>();
      for (var row : resultSet) {
        filenames.add(row.getString("filename"));
      }
      return Single.just(
          ListArtifactsResponse.builder().filenames(ImmutableList.copyOf(filenames)).build());
    } catch (Exception e) {
      logger.error("Error listing artifact keys from Cassandra", e);
      return Single.error(e);
    }
  }

  @Override
  public Single<ImmutableList<Integer>> listVersions(
      String appName, String userId, String sessionId, String filename) {
    Objects.requireNonNull(appName, "appName cannot be null");
    Objects.requireNonNull(userId, "userId cannot be null");
    Objects.requireNonNull(sessionId, "sessionId cannot be null");
    Objects.requireNonNull(filename, "filename cannot be null");

    String selectCql =
        "SELECT version_number FROM artifacts WHERE app_name = ? AND user_id = ? AND session_id = ? AND filename = ?";
    try {
      var resultSet = session.execute(selectCql, appName, userId, sessionId, filename);
      List<Integer> versions = new ArrayList<>();
      for (var row : resultSet) {
        versions.add(row.getInt("version_number"));
      }
      return Single.just(ImmutableList.copyOf(versions));
    } catch (Exception e) {
      logger.error("Error listing artifact versions from Cassandra", e);
      return Single.error(e);
    }
  }

  @Override
  public Maybe<Part> loadArtifact(
      String appName, String userId, String sessionId, String filename, Optional<Integer> version) {
    Objects.requireNonNull(appName, "appName cannot be null");
    Objects.requireNonNull(userId, "userId cannot be null");
    Objects.requireNonNull(sessionId, "sessionId cannot be null");
    Objects.requireNonNull(filename, "filename cannot be null");

    String selectCql;
    if (version.isPresent()) {
      selectCql =
          "SELECT artifact_data FROM artifacts WHERE app_name = ? AND user_id = ? AND session_id = ? AND filename = ? AND version_number = ?";
      try {
        var row =
            session.execute(selectCql, appName, userId, sessionId, filename, version.get()).one();
        if (row == null) {
          return Maybe.empty();
        }
        String artifactJson = row.getString("artifact_data");
        return Maybe.just(objectMapper.readValue(artifactJson, Part.class));
      } catch (Exception e) {
        logger.error("Error loading specific artifact version from Cassandra", e);
        return Maybe.error(e);
      }
    } else {
      // Load the latest version
      selectCql =
          "SELECT artifact_data FROM artifacts WHERE app_name = ? AND user_id = ? AND session_id = ? AND filename = ? ORDER BY version_number DESC LIMIT 1";
      try {
        var row = session.execute(selectCql, appName, userId, sessionId, filename).one();
        if (row == null) {
          return Maybe.empty();
        }
        String artifactJson = row.getString("artifact_data");
        return Maybe.just(objectMapper.readValue(artifactJson, Part.class));
      } catch (Exception e) {
        logger.error("Error loading latest artifact version from Cassandra", e);
        return Maybe.error(e);
      }
    }
  }

  @Override
  public Single<Integer> saveArtifact(
      String appName, String userId, String sessionId, String filename, Part artifact) {
    Objects.requireNonNull(appName, "appName cannot be null");
    Objects.requireNonNull(userId, "userId cannot be null");
    Objects.requireNonNull(sessionId, "sessionId cannot be null");
    Objects.requireNonNull(filename, "filename cannot be null");
    Objects.requireNonNull(artifact, "artifact cannot be null");

    // Find the next version number
    String selectMaxVersionCql =
        "SELECT MAX(version_number) FROM artifacts WHERE app_name = ? AND user_id = ? AND session_id = ? AND filename = ?";
    int nextVersion = 0;
    try {
      var row = session.execute(selectMaxVersionCql, appName, userId, sessionId, filename).one();
      if (row != null && !row.isNull(0)) {
        nextVersion = row.getInt(0) + 1;
      }
    } catch (Exception e) {
      logger.error("Error getting max artifact version from Cassandra", e);
      return Single.error(e);
    }

    String insertCql =
        "INSERT INTO artifacts (app_name, user_id, session_id, filename, version_number, artifact_data) VALUES (?, ?, ?, ?, ?, ?)";

    String insertFilenamesCql =
        "INSERT INTO "
            + FILENAMES_TABLE
            + " (app_name, user_id, session_id, filename) VALUES (?, ?, ?, ?)";

    try {
      session.execute(
          insertCql,
          appName,
          userId,
          sessionId,
          filename,
          nextVersion,
          objectMapper.writeValueAsString(artifact));
      session.execute(insertFilenamesCql, appName, userId, sessionId, filename);
      return Single.just(nextVersion);
    } catch (JsonProcessingException e) {
      logger.error("Error serializing artifact data for saveArtifact", e);
      return Single.error(e);
    }
  }
}
