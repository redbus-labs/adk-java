# PostgreSQL Artifact Service for ADK

A production-ready PostgreSQL-backed implementation of the ADK `BaseArtifactService` interface, providing persistent storage for agent artifacts with full versioning support.

## Overview

The `PostegresArtifactService` stores agent artifacts (images, documents, binary data) in PostgreSQL using BYTEA storage with HikariCP connection pooling. It's designed for production deployments requiring persistent, reliable artifact storage across agent sessions.

## Features

- ✅ **Persistent Storage**: PostgreSQL-backed with BYTEA for binary data
- ✅ **Version Control**: Automatic versioning for artifact updates
- ✅ **Connection Pooling**: HikariCP for efficient database connections
- ✅ **Reactive API**: RxJava3 types for non-blocking operations
- ✅ **Environment Config**: Supports environment variables or explicit parameters
- ✅ **Production Ready**: Includes indexes, constraints, and connection leak detection
- ✅ **Thread Safe**: Singleton pattern with concurrent access support

## Architecture

```
PostegresArtifactService (implements BaseArtifactService)
    ↓
PostgresArtifactStore (manages connection pool & DB operations)
    ↓
HikariCP (connection pooling)
    ↓
PostgreSQL Database
```

## Quick Start

### 1. Add Dependencies

The service is included in the ADK core module. Ensure these dependencies are in your `pom.xml`:

```xml
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <version>42.7.3</version>
</dependency>
<dependency>
    <groupId>com.zaxxer</groupId>
    <artifactId>HikariCP</artifactId>
    <version>5.1.0</version>
</dependency>
```

### 2. Configure Database Connection

Set environment variables:

```bash
export DBURL="jdbc:postgresql://localhost:5432/your_database"
export DBUSER="your_username"
export DBPASSWORD="your_password"
```

### 3. Create Service Instance

```java
// Using environment variables (recommended)
PostegresArtifactService artifactService = new PostegresArtifactService();

// With custom table name
PostegresArtifactService artifactService = new PostegresArtifactService("my_artifacts");

// With explicit connection parameters
PostegresArtifactService artifactService = new PostegresArtifactService(
    "jdbc:postgresql://localhost:5432/mydb",
    "username",
    "password",
    "artifacts"
);
```

### 4. Use with Runner

```java
import com.google.adk.runner.Runner;
import com.google.adk.artifacts.PostegresArtifactService;

BaseAgent agent = MyAgent.create();
PostegresArtifactService artifactService = new PostegresArtifactService("MyApp_ART");

Runner runner = new Runner(
    agent,
    "MyApp",
    artifactService,
    sessionService,
    memoryService
);
```

## Database Schema

The service automatically creates the following table structure:

```sql
CREATE TABLE artifacts (
    id SERIAL PRIMARY KEY,
    app_name VARCHAR(255) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    session_id VARCHAR(255) NOT NULL,
    filename VARCHAR(255) NOT NULL,
    version INT NOT NULL DEFAULT 0,
    mime_type VARCHAR(100),
    data BYTEA NOT NULL,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT artifacts_unique_version UNIQUE(app_name, user_id, session_id, filename, version)
);

-- Indexes for performance
CREATE INDEX idx_artifacts_lookup ON artifacts(app_name, user_id, session_id, filename);
CREATE INDEX idx_artifacts_session ON artifacts(app_name, user_id, session_id);
```

## API Reference

### Save Artifact

```java
Single<Integer> saveArtifact(
    String appName,
    String userId,
    String sessionId,
    String filename,
    Part artifact
)
```

Saves an artifact and returns the assigned version number.

**Example:**
```java
Part imagePart = Part.fromBytes(imageBytes, "image/jpeg");

artifactService.saveArtifact(
    "MyApp",
    "user-123",
    "session-456",
    "input_image.jpg",
    imagePart
).subscribe(version -> {
    System.out.println("Saved as version: " + version);
});
```

### Load Artifact

```java
Maybe<Part> loadArtifact(
    String appName,
    String userId,
    String sessionId,
    String filename,
    Optional<Integer> version
)
```

Loads an artifact by version (or latest if version is empty).

**Example:**
```java
// Load latest version
artifactService.loadArtifact(
    "MyApp",
    "user-123",
    "session-456",
    "input_image.jpg",
    Optional.empty()
).subscribe(part -> {
    byte[] data = part.inlineData().get().data().get();
    System.out.println("Loaded " + data.length + " bytes");
});

// Load specific version
artifactService.loadArtifact(
    "MyApp",
    "user-123",
    "session-456",
    "input_image.jpg",
    Optional.of(2)
).subscribe(part -> {
    // Process version 2
});
```

### List Artifacts

```java
Single<ListArtifactsResponse> listArtifactKeys(
    String appName,
    String userId,
    String sessionId
)
```

Lists all artifact filenames for a session.

**Example:**
```java
artifactService.listArtifactKeys("MyApp", "user-123", "session-456")
    .subscribe(response -> {
        response.filenames().forEach(filename -> {
            System.out.println("Found: " + filename);
        });
    });
```

### List Versions

```java
Single<ImmutableList<Integer>> listVersions(
    String appName,
    String userId,
    String sessionId,
    String filename
)
```

Lists all versions of a specific artifact.

**Example:**
```java
artifactService.listVersions("MyApp", "user-123", "session-456", "input_image.jpg")
    .subscribe(versions -> {
        System.out.println("Available versions: " + versions);
    });
```

### Delete Artifact

```java
Completable deleteArtifact(
    String appName,
    String userId,
    String sessionId,
    String filename
)
```

Deletes all versions of an artifact.

**Example:**
```java
artifactService.deleteArtifact("MyApp", "user-123", "session-456", "input_image.jpg")
    .subscribe(() -> {
        System.out.println("Artifact deleted");
    });
```

## Usage in Agent Tools

Artifacts are automatically accessible in agent tools through the `ToolContext`:

```java
public class MyTool extends BaseTool {
    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext context) {
        // Get artifact name from state
        String artifactName = (String) context.state().get("current_image_artifact");
        
        // Load artifact
        Part imagePart = context.loadArtifact(artifactName, Optional.empty()).blockingGet();
        byte[] imageBytes = imagePart.inlineData().get().data().get();
        
        // Process image...
        
        // Save result
        Part resultPart = Part.fromBytes(resultBytes, "image/jpeg");
        context.saveArtifact("result_" + artifactName, resultPart);
        
        return Single.just(Map.of("success", true));
    }
}
```

## Connection Pool Configuration

The service uses HikariCP with optimized settings:

```java
// Default configuration
maximumPoolSize = 10
minimumIdle = 2
connectionTimeout = 30000 ms (30 seconds)
idleTimeout = 600000 ms (10 minutes)
maxLifetime = 1800000 ms (30 minutes)
leakDetectionThreshold = 60000 ms (1 minute)
```

### Custom Pool Settings

To customize, modify `PostgresArtifactStore.initializeDataSource()`:

```java
config.setMaximumPoolSize(20);
config.setMinimumIdle(5);
config.setConnectionTimeout(60000);
```

## Best Practices

### 1. Session ID Consistency

**Critical**: Use the same `sessionId` for both session creation and artifact storage:

```java
String userId = "user-123";
String sessionId = userId; // Use userId as sessionId

// Create session
runner.sessionService().createSession(APP_NAME, userId, state, sessionId);

// Save artifact with SAME sessionId
runner.artifactService().saveArtifact(APP_NAME, userId, sessionId, filename, artifact);
```

❌ **Wrong** (causes NullPointerException):
```java
String sessionId = "ImageAnalysisAgent-" + userId; // Different from session!
runner.artifactService().saveArtifact(APP_NAME, userId, sessionId, filename, artifact);
```

### 2. Table Naming Convention

Follow the ADK convention: `{AppName}_ART`

```java
PostegresArtifactService artifactService = new PostegresArtifactService("MyApp_ART");
```

### 3. Resource Cleanup

Close the service when shutting down:

```java
@PreDestroy
public void cleanup() {
    artifactService.close();
}
```

### 4. Error Handling

Always handle potential errors:

```java
artifactService.loadArtifact(appName, userId, sessionId, filename, Optional.empty())
    .subscribe(
        part -> {
            // Success
        },
        error -> {
            logger.error("Failed to load artifact", error);
        },
        () -> {
            // Empty (artifact not found)
            logger.warn("Artifact not found: {}", filename);
        }
    );
```

### 5. Version Management

Use versioning for audit trails and rollback:

```java
// Save multiple versions
artifactService.saveArtifact(app, user, session, "document.pdf", v1Part); // version 0
artifactService.saveArtifact(app, user, session, "document.pdf", v2Part); // version 1
artifactService.saveArtifact(app, user, session, "document.pdf", v3Part); // version 2

// Rollback to version 1
artifactService.loadArtifact(app, user, session, "document.pdf", Optional.of(1));
```

## Common Issues & Solutions

### Issue: NullPointerException when loading artifact

**Cause**: Session ID mismatch between session creation and artifact storage.

**Solution**: Use the same `sessionId` for both operations (see Best Practices #1).

### Issue: Connection pool exhausted

**Cause**: Too many concurrent connections or connection leaks.

**Solution**: 
- Increase pool size: `config.setMaximumPoolSize(20)`
- Check for connection leaks in logs
- Ensure proper resource cleanup

### Issue: Artifact not found

**Cause**: Incorrect `appName`, `userId`, or `sessionId` parameters.

**Solution**: Verify all parameters match exactly:
```java
logger.info("Looking for artifact: app={}, user={}, session={}, file={}", 
    appName, userId, sessionId, filename);
```

## Performance Considerations

### Storage Efficiency

- **Small files** (<1MB): Excellent performance
- **Medium files** (1-10MB): Good performance
- **Large files** (>10MB): Consider external storage (S3, GCS) with metadata in PostgreSQL

### Query Optimization

The service includes optimized indexes for common queries:
- Artifact lookup: `O(log n)` via `idx_artifacts_lookup`
- Session listing: `O(log n)` via `idx_artifacts_session`

### Caching Strategy

For frequently accessed artifacts, implement caching:

```java
private final Map<String, Part> cache = new ConcurrentHashMap<>();

public Maybe<Part> loadArtifactCached(String key, String filename) {
    if (cache.containsKey(key)) {
        return Maybe.just(cache.get(key));
    }
    
    return artifactService.loadArtifact(app, user, session, filename, Optional.empty())
        .doOnSuccess(part -> cache.put(key, part));
}
```

## Migration from In-Memory Storage

If migrating from `InMemoryArtifactService`:

```java
// Before
BaseArtifactService artifactService = new InMemoryArtifactService();

// After
BaseArtifactService artifactService = new PostegresArtifactService("MyApp_ART");
```

No code changes required - the interface is identical!

## Testing

### Unit Tests

```java
@Test
void testSaveAndLoadArtifact() {
    PostegresArtifactService service = new PostegresArtifactService("test_artifacts");
    
    Part testPart = Part.fromBytes("test data".getBytes(), "text/plain");
    
    // Save
    int version = service.saveArtifact("TestApp", "user1", "session1", "test.txt", testPart)
        .blockingGet();
    
    assertEquals(0, version);
    
    // Load
    Part loaded = service.loadArtifact("TestApp", "user1", "session1", "test.txt", Optional.empty())
        .blockingGet();
    
    assertNotNull(loaded);
}
```

### Integration Tests

Use Testcontainers for PostgreSQL:

```java
@Testcontainers
class PostgresArtifactServiceIT {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
        .withDatabaseName("test")
        .withUsername("test")
        .withPassword("test");
    
    @Test
    void testWithRealDatabase() {
        PostegresArtifactService service = new PostegresArtifactService(
            postgres.getJdbcUrl(),
            postgres.getUsername(),
            postgres.getPassword(),
            "artifacts"
        );
        
        // Test operations...
    }
}
```

## Contributing

When contributing to the PostgreSQL artifact service:

1. Maintain backward compatibility with `BaseArtifactService`
2. Add appropriate indexes for new query patterns
3. Include unit and integration tests
4. Update this README with new features
5. Follow the existing code style and patterns

## License

Copyright 2025 Google LLC

Licensed under the Apache License, Version 2.0. See LICENSE file for details.

## Support

For issues and questions:
- GitHub Issues: [google/adk-java](https://github.com/google/adk-java/issues)
- Documentation: [ADK Documentation](https://github.com/google/adk-java)

## Changelog

### Version 0.4.1-SNAPSHOT
- Initial PostgreSQL artifact service implementation
- HikariCP connection pooling
- Full versioning support
- Environment variable configuration
- Production-ready with indexes and constraints

