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

package com.google.adk.memory;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.datastax.oss.driver.api.core.CqlSession;
import com.google.adk.tools.retrieval.CassandraRagRetrieval;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link CassandraMemoryService}.
 *
 * @author Sandeep Belgavi
 * @since 2025-10-19
 */
@RunWith(JUnit4.class)
public final class CassandraMemoryServiceTest {

  @Mock private CqlSession session;
  @Mock private CassandraRagRetrieval cassandraRagRetrieval;

  private CassandraMemoryService memoryService;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
    memoryService = new CassandraMemoryService(cassandraRagRetrieval);
  }

  @Test
  public void testSearchMemory() {
    // Arrange
    String query = "test query";
    List<String> expectedContexts = ImmutableList.of("test context");
    when(cassandraRagRetrieval.runAsync(eq(ImmutableMap.of("query", query)), any()))
        .thenReturn(Single.just(ImmutableMap.of("response", expectedContexts)));

    // Act
    SearchMemoryResponse response =
        memoryService.searchMemory("test_app", "test_user", query).blockingGet();

    // Assert
    assertThat(
            response.memories().stream()
                .map(memory -> memory.content().parts().get().get(0).text().get())
                .collect(ImmutableList.toImmutableList()))
        .isEqualTo(expectedContexts);
  }
}
