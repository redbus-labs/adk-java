package com.google.adk.artifacts;

import com.google.common.collect.ImmutableList;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.Optional;

public class PostegresArtifactService implements BaseArtifactService {
  private final String appName;
  private final String artifactTableName;

  public PostegresArtifactService(String appName, String artifactTableName) {
    this.appName = appName;
    this.artifactTableName = artifactTableName;
  }

  @Override
  public Completable deleteArtifact(
      String appName, String userId, String sessionId, String filename) {
    // TODO Auto-generated method stub
    throw new UnsupportedOperationException("Unimplemented method 'deleteArtifact'");
  }

  @Override
  public Single<ListArtifactsResponse> listArtifactKeys(
      String appName, String userId, String sessionId) {
    // TODO Auto-generated method stub
    throw new UnsupportedOperationException("Unimplemented method 'listArtifactKeys'");
  }

  @Override
  public Single<ImmutableList<Integer>> listVersions(
      String appName, String userId, String sessionId, String filename) {
    // TODO Auto-generated method stub
    throw new UnsupportedOperationException("Unimplemented method 'listVersions'");
  }

  @Override
  public Maybe<Part> loadArtifact(
      String appName, String userId, String sessionId, String filename, Optional<Integer> version) {
    // TODO Auto-generated method stub
    throw new UnsupportedOperationException("Unimplemented method 'loadArtifact'");
  }

  @Override
  public Single<Integer> saveArtifact(
      String appName, String userId, String sessionId, String filename, Part artifact) {
    // TODO Auto-generated method stub
    throw new UnsupportedOperationException("Unimplemented method 'saveArtifact'");
  }
}
