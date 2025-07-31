/**
 * @author Sandeep Belgavi
 * @since 2025-07-31
 */
package com.google.adk;

import static org.junit.jupiter.api.Assertions.*;

import com.datastax.oss.driver.api.core.CqlSession;
import com.google.adk.artifacts.CassandraArtifactService;
import com.google.adk.sessions.CassandraSessionService;
import java.util.UUID;
import org.junit.jupiter.api.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class CassandraServiceIntegrationTest {

  private CassandraSessionService sessionService;
  private CassandraArtifactService artifactService;
  private CqlSession
      cqlSession; // To be used for cleanup if needed, though services manage their own now

  private static final String APP_NAME = "test_app";
  private String userId;
  private String sessionId;

  @BeforeAll
  void setupEnvironment() {
    // Set Cassandra environment variables for the test
    System.setProperty("cassandra_host", "10.166.6.114");
    System.setProperty("cassandra_port", "9042");
    System.setProperty("cassandra_user", "cassandra");
    System.setProperty("cassandra_password", "cassandra");
    System.setProperty("cassandra_keyspace", "ai_bus_info");

    // Initialize services (they will pick up env vars)
    sessionService = new CassandraSessionService();
    artifactService = new CassandraArtifactService(APP_NAME, "artifacts");

    // Optional: Get the CqlSession instance if you need to perform direct CQL operations for
    // setup/teardown
    // This is a bit tricky since the services create their own sessions internally.
    // For a real integration test, you might manage the CqlSession externally and pass it in,
    // or use a test container for Cassandra. For this example, we'll rely on the services' internal
    // session.
  }

  @BeforeEach
  void generateUniqueIds() {
    userId = "user_" + UUID.randomUUID().toString();
    sessionId = "session_" + UUID.randomUUID().toString();
  }

  /*
  @Test
  void testSessionLifecycle() throws Exception {
    // 1. Create Session
    Single<Session> createSessionSingle =
        sessionService.createSession(
            APP_NAME,
            userId,
            new ConcurrentHashMap<>(Collections.singletonMap("initialKey", "initialValue")),
            sessionId);
    Session createdSession = createSessionSingle.blockingGet();
    assertNotNull(createdSession);
    assertEquals(sessionId, createdSession.id());
    assertEquals(APP_NAME, createdSession.appName());
    assertEquals(userId, createdSession.userId());
    assertTrue(createdSession.state().containsKey("initialKey"));
    assertEquals("initialValue", createdSession.state().get("initialKey"));
    assertTrue(createdSession.events().isEmpty());

    System.out.println("Created Session: " + createdSession.id());

    // 2. Get Session
    Session fetchedSession =
        sessionService.getSession(APP_NAME, userId, sessionId, Optional.empty()).blockingGet();
    assertNotNull(fetchedSession);
    assertEquals(createdSession.id(), fetchedSession.id());
    assertEquals(createdSession.appName(), fetchedSession.appName());
    assertEquals(createdSession.userId(), fetchedSession.userId());
    assertEquals(createdSession.state(), fetchedSession.state());
    assertEquals(createdSession.events(), fetchedSession.events());

    System.out.println("Fetched Session: " + fetchedSession.id());

    // 3. Append Event
    Event testEvent =
        Event.builder().id("event1").timestamp(Instant.now().getEpochSecond()).build();
    sessionService.appendEvent(fetchedSession, testEvent).blockingGet();

    Session sessionAfterEvent =
        sessionService.getSession(APP_NAME, userId, sessionId, Optional.empty()).blockingGet();
    assertNotNull(sessionAfterEvent);
    assertEquals(1, sessionAfterEvent.events().size());
    assertEquals("event1", sessionAfterEvent.events().get(0).id());

    System.out.println("Appended event to session: " + sessionAfterEvent.id());

    // 4. List Events
    assertEquals(
        1, sessionService.listEvents(APP_NAME, userId, sessionId).blockingGet().events().size());

    // 5. Delete Session
    sessionService.deleteSession(APP_NAME, userId, sessionId).blockingAwait(5, TimeUnit.SECONDS);

    // Verify session is deleted
    Maybe<Session> deletedSessionMaybe =
        sessionService.getSession(APP_NAME, userId, sessionId, Optional.empty());
    assertTrue(deletedSessionMaybe.isEmpty().blockingGet());

    System.out.println("Deleted Session: " + sessionId);
  }
  */

  /*
  @Test
  void testArtifactLifecycle() throws Exception {
    String artifactFilename = "test_artifact.txt";
    Part testPart = Part.fromText("Hello, Cassandra Artifact!");

    // 1. Save Artifact
    int version =
        artifactService
            .saveArtifact(APP_NAME, userId, sessionId, artifactFilename, testPart)
            .blockingGet();
    assertEquals(0, version); // First version is 0

    System.out.println("Saved artifact: " + artifactFilename + " version: " + version);

    // 2. Load Artifact (latest)
    Part loadedPart =
        artifactService
            .loadArtifact(APP_NAME, userId, sessionId, artifactFilename, Optional.empty())
            .blockingGet();
    assertNotNull(loadedPart);
    assertEquals("Hello, Cassandra Artifact!", loadedPart.text());

    System.out.println("Loaded latest artifact: " + artifactFilename);

    // 3. Save another version
    Part updatedPart = Part.fromText("Updated content!");
    int newVersion =
        artifactService
            .saveArtifact(APP_NAME, userId, sessionId, artifactFilename, updatedPart)
            .blockingGet();
    assertEquals(1, newVersion);

    System.out.println(
        "Saved new version of artifact: " + artifactFilename + " version: " + newVersion);

    // 4. Load specific version
    Part loadedOldVersion =
        artifactService
            .loadArtifact(APP_NAME, userId, sessionId, artifactFilename, Optional.of(0))
            .blockingGet();
    assertNotNull(loadedOldVersion);
    assertEquals("Hello, Cassandra Artifact!", loadedOldVersion.text());

    System.out.println("Loaded specific version (0) of artifact: " + artifactFilename);

    // 5. List Artifact Keys
    assertEquals(
        1,
        artifactService
            .listArtifactKeys(APP_NAME, userId, sessionId)
            .blockingGet()
            .filenames()
            .size());
    assertTrue(
        artifactService
            .listArtifactKeys(APP_NAME, userId, sessionId)
            .blockingGet()
            .filenames()
            .contains(artifactFilename));

    System.out.println("Listed artifact keys.");

    // 6. List Versions
    assertEquals(
        2,
        artifactService
            .listVersions(APP_NAME, userId, sessionId, artifactFilename)
            .blockingGet()
            .size());
    assertTrue(
        artifactService
            .listVersions(APP_NAME, userId, sessionId, artifactFilename)
            .blockingGet()
            .contains(0));
    assertTrue(
        artifactService
            .listVersions(APP_NAME, userId, sessionId, artifactFilename)
            .blockingGet()
            .contains(1));

    System.out.println("Listed artifact versions.");

    // 7. Delete Artifact
    artifactService
        .deleteArtifact(APP_NAME, userId, sessionId, artifactFilename)
        .blockingAwait(5, TimeUnit.SECONDS);

    // Verify artifact is deleted
    Maybe<Part> deletedArtifactMaybe =
        artifactService.loadArtifact(
            APP_NAME, userId, sessionId, artifactFilename, Optional.empty());
    assertTrue(deletedArtifactMaybe.isEmpty().blockingGet());

    System.out.println("Deleted artifact: " + artifactFilename);
  }
  */

  @AfterAll
  void teardown() {
    // Close the CqlSession if it was managed directly by the test.
    // Since services manage their own, we'd ideally call a close method on them.
    // For this example, the services implement AutoCloseable, so they will be closed
    // when the JVM exits or if explicitly closed.
    try {
      if (sessionService != null) {
        sessionService.close();
      }
      // Artifact service doesn't implement AutoCloseable, but its internal session will be closed
      // by the driver.
    } catch (Exception e) {
      System.err.println("Error during test teardown: " + e.getMessage());
    }

    // Clear system properties to avoid affecting other tests or subsequent runs
    System.clearProperty("cassandra_host");
    System.clearProperty("cassandra_port");
    System.clearProperty("cassandra_user");
    System.clearProperty("cassandra_password");
    System.clearProperty("cassandra_keyspace");
  }
}
