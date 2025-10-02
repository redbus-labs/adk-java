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

import com.google.adk.events.Event;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class MapDBMemoryServiceTest {

  private static final String APP_NAME = "test_app";
  private static final String USER_ID = "test_user";
  private static final String SESSION_ID = "test_session_123";
  private File tempDbFile;
  private MapDBMemoryService memoryService;

  @Before
  public void setUp() throws IOException {
    tempDbFile = Files.createTempFile("test_adk_memory_", ".db").toFile();
  }

  @After
  public void tearDown() {
    if (memoryService != null) {
      memoryService.close();
    }
    if (tempDbFile != null && tempDbFile.exists()) {
      tempDbFile.delete();
    }
  }

  private Session createTestSession(String contentText) {
    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("user")
            .timestamp(System.currentTimeMillis() / 1000L)
            .content(Content.builder().parts(ImmutableList.of(Part.fromText(contentText))).build())
            .build();

    return Session.builder(SESSION_ID)
        .appName(APP_NAME)
        .userId(USER_ID)
        .events(java.util.List.of(event))
        .build();
  }

  @Test
  public void addSessionToMemory_andSearch_findsMatchingContent() {
    // Arrange
    memoryService = new MapDBMemoryService(tempDbFile);
    Session session = createTestSession("This is a test with a unique keyword: foobar");
    memoryService.addSessionToMemory(session).blockingAwait();
    memoryService.close(); // Close the service to ensure data is flushed

    // Act
    memoryService = new MapDBMemoryService(tempDbFile); // Reopen the service
    SearchMemoryResponse response =
        memoryService.searchMemory(APP_NAME, USER_ID, "foobar").blockingGet();

    // Assert
    assertThat(response.memories()).hasSize(1);
    MemoryEntry memory = response.memories().get(0);
    assertThat(memory.author()).isEqualTo("user");
    assertThat(memory.content().parts().get().get(0).text().get())
        .contains("This is a test with a unique keyword: foobar");
  }
}
