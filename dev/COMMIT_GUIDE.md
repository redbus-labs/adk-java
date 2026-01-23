# Commit Guide for SSE Testing Files

## Where to Commit Test Files

### 1. Test Scripts and Documentation (Root of `dev/` module)

These files should be committed to the repository as they are useful for developers:

```
adk-java/dev/
├── TEST_SSE_ENDPOINT.md          ✅ Commit - Comprehensive testing guide
├── QUICK_START_SSE.md             ✅ Commit - Quick start guide
├── test_sse.sh                    ✅ Commit - Automated test script
└── test_request.json              ✅ Commit - Sample request file
```

**Reason**: These are developer tools and documentation that help with testing and understanding the SSE implementation.

### 2. Test Code (Already in `src/test/`)

The unit and integration tests are already in the correct location:

```
adk-java/dev/src/test/java/com/google/adk/web/
├── controller/httpserver/
│   ├── HttpServerSseControllerTest.java              ✅ Already committed
│   └── HttpServerSseControllerIntegrationTest.java   ✅ Already committed
└── service/
    ├── SseEventStreamServiceTest.java                ✅ Already committed
    └── SseEventStreamServiceIntegrationTest.java      ✅ Already committed
```

### 3. Implementation Code (Already in `src/main/`)

All implementation files are in the correct location:

```
adk-java/dev/src/main/java/com/google/adk/web/
├── config/
│   └── HttpServerSseConfig.java                      ✅ Already committed
├── controller/
│   ├── ExecutionController.java                     ✅ Already committed
│   └── httpserver/
│       └── HttpServerSseController.java              ✅ Already committed
└── service/
    ├── SseEventStreamService.java                    ✅ Already committed
    └── eventprocessor/
        ├── EventProcessor.java                       ✅ Already committed
        └── PassThroughEventProcessor.java             ✅ Already committed
```

## Git Commit Structure

### Recommended Commit Messages

```bash
# For test scripts and documentation
git add dev/TEST_SSE_ENDPOINT.md dev/QUICK_START_SSE.md dev/test_sse.sh dev/test_request.json
git commit -m "docs: Add SSE endpoint testing documentation and scripts

- Add comprehensive testing guide (TEST_SSE_ENDPOINT.md)
- Add quick start guide (QUICK_START_SSE.md)
- Add automated test script (test_sse.sh)
- Add sample request JSON (test_request.json)

Author: Sandeep Belgavi
Date: January 24, 2026"

# For implementation changes (if not already committed)
git add dev/src/main/java/com/google/adk/web/config/HttpServerSseConfig.java
git add dev/src/main/java/com/google/adk/web/controller/httpserver/HttpServerSseController.java
git add dev/src/main/java/com/google/adk/web/controller/ExecutionController.java
git commit -m "feat: Make HttpServer SSE default endpoint on port 9085

- Change default SSE endpoint from Spring to HttpServer
- Update /run_sse to use HttpServer (port 9085)
- Rename Spring endpoint to /run_sse_spring (port 8080)
- Update HttpServerSseConfig to enable by default
- Fix Runner.runAsync() method signature calls

Author: Sandeep Belgavi
Date: January 24, 2026"

# For test code (if not already committed)
git add dev/src/test/java/com/google/adk/web/controller/httpserver/
git add dev/src/test/java/com/google/adk/web/service/SseEventStreamServiceTest.java
git commit -m "test: Add unit and integration tests for SSE endpoints

- Add HttpServerSseControllerTest unit tests
- Add HttpServerSseControllerIntegrationTest integration tests
- Update existing SseEventStreamService tests
- Fix test mocks and async handling

Author: Sandeep Belgavi
Date: January 24, 2026"
```

## Files to Exclude from Commit

### Build Artifacts (Already in .gitignore)
```
target/
*.class
*.jar
*.log
```

### Temporary Test Files (Don't Commit)
```
/tmp/adk_server.log          ❌ Don't commit - Temporary log file
*.dump                        ❌ Don't commit - Test dump files
```

## Directory Structure Summary

```
adk-java/dev/
├── README.md                          # Main project README
├── TEST_SSE_ENDPOINT.md               # ✅ Commit - Testing guide
├── QUICK_START_SSE.md                 # ✅ Commit - Quick start
├── COMMIT_GUIDE.md                    # ✅ Commit - This file
├── test_sse.sh                        # ✅ Commit - Test script
├── test_request.json                  # ✅ Commit - Sample request
├── pom.xml                            # Already committed
├── src/
│   ├── main/
│   │   └── java/...                   # ✅ Already committed
│   └── test/
│       └── java/...                   # ✅ Already committed
└── target/                            # ❌ Don't commit (build artifacts)
```

## Verification Before Commit

Before committing, verify:

1. ✅ All test files compile: `mvn clean compile test-compile`
2. ✅ All tests pass: `mvn test`
3. ✅ Code formatting: `mvn fmt:format`
4. ✅ No sensitive data in test files (API keys, passwords, etc.)
5. ✅ Documentation is accurate and up-to-date

## Branch Recommendation

If working on a feature branch:
```bash
git checkout -b feature/sse-httpserver-default
# ... make changes ...
git add ...
git commit -m "..."
git push origin feature/sse-httpserver-default
```

## Author and Date

All new files should include:
- Author: Sandeep Belgavi
- Date: January 24, 2026

This is already included in JavaDoc comments for code files.
