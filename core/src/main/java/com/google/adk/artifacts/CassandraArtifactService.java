/**
 * @author Sandeep Belgavi
 * @since 2025-07-31
 */
package com.google.adk.artifacts;

import com.google.adk.utils.CassandraDBHelper;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CassandraArtifactService implements BaseArtifactService {
  private final String appName;
  private final String artifactTableName;
  private final CassandraDBHelper dbHelper;
  private static final Logger logger = LoggerFactory.getLogger(CassandraArtifactService.class);

  public CassandraArtifactService(String appName, String artifactTableName) {
    this.appName = appName;
    this.artifactTableName = artifactTableName;
    this.dbHelper = CassandraDBHelper.getInstance();
  }

  @Override
  public Completable deleteArtifact(
      String appName, String userId, String sessionId, String filename) {
    Objects.requireNonNull(appName, "appName cannot be null");
    Objects.requireNonNull(userId, "userId cannot be null");
    Objects.requireNonNull(sessionId, "sessionId cannot be null");
    Objects.requireNonNull(filename, "filename cannot be null");

    try {
      dbHelper.deleteArtifact(appName, userId, sessionId, filename);
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

    try {
      List<String> filenames = dbHelper.listArtifactKeys(appName, userId, sessionId);
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

    try {
      List<Integer> versions = dbHelper.listArtifactVersions(appName, userId, sessionId, filename);
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

    try {
      return Maybe.fromOptional(
          dbHelper.loadArtifact(appName, userId, sessionId, filename, version));
    } catch (Exception e) {
      logger.error("Error loading artifact from Cassandra", e);
      return Maybe.error(e);
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

    try {
      int nextVersion = dbHelper.saveArtifact(appName, userId, sessionId, filename, artifact);
      return Single.just(nextVersion);
    } catch (Exception e) {
      logger.error("Error saving artifact to Cassandra", e);
      return Single.error(e);
    }
  }
}
