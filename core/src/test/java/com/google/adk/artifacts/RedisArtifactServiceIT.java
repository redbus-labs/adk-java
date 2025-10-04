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

import static com.google.common.truth.Truth.assertThat;

import com.google.adk.store.RedisHelper;
import com.google.genai.types.Part;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
public class RedisArtifactServiceIT {

  @Container
  private static final GenericContainer<?> redis =
      new GenericContainer<>("redis:latest").withExposedPorts(6379);

  private static RedisArtifactService artifactService;

  @BeforeAll
  public static void setUp() {
    redis.start();
    String redisUri = "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379);
    RedisHelper.initialize(redisUri);
    artifactService = new RedisArtifactService();
  }

  @AfterAll
  public static void tearDown() {
    RedisHelper.close();
    redis.stop();
  }

  @Test
  public void testSaveAndLoadArtifact() {
    String appName = "testApp";
    String userId = "testUser";
    String sessionId = "testSession";
    String filename = "test.txt";
    Part artifact = Part.fromText("hello world");

    Integer version =
        artifactService.saveArtifact(appName, userId, sessionId, filename, artifact).blockingGet();
    assertThat(version).isEqualTo(0);

    Part loadedArtifact =
        artifactService
            .loadArtifact(appName, userId, sessionId, filename, Optional.of(version))
            .blockingGet();
    assertThat(loadedArtifact.text().get()).isEqualTo("hello world");
  }
}
