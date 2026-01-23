# Quick Start: Testing SSE Endpoint

## Step 1: Start the Server

Open a terminal and run:

```bash
cd /Users/sandeep.b/IdeaProjects/voice/adk-java/dev
mvn spring-boot:run
```

Wait for the server to start. You should see logs indicating:
- Spring Boot server started on port 8080
- HttpServer SSE service started on port 9085

## Step 2: Test the SSE Endpoint

### Option A: Using the Test Script (Recommended)

In a new terminal:

```bash
cd /Users/sandeep.b/IdeaProjects/voice/adk-java/dev
./test_sse.sh
```

### Option B: Using cURL Directly

```bash
curl -N -X POST http://localhost:9085/run_sse \
  -H "Content-Type: application/json" \
  -d @test_request.json
```

Or inline:

```bash
curl -N -X POST http://localhost:9085/run_sse \
  -H "Content-Type: application/json" \
  -d '{
    "appName": "your-app-name",
    "userId": "test-user",
    "sessionId": "test-session-123",
    "newMessage": {
      "role": "user",
      "parts": [{"text": "Hello"}]
    },
    "streaming": true
  }'
```

## Step 3: Watch the Output

You should see SSE events streaming in the format:

```
event: message
data: {"id":"event-1","author":"agent","content":{...}}

event: message
data: {"id":"event-2","author":"agent","content":{...}}

event: done
data: {"status":"complete"}
```

## Important Notes

1. **Replace `your-app-name`**: Update the `appName` field with an actual agent application name that exists in your system.

2. **The `-N` flag is crucial**: This disables buffering in curl, which is essential for seeing SSE events as they stream.

3. **Port 9085**: This is the HttpServer SSE endpoint (default). The Spring-based endpoint is on port 8080 at `/run_sse_spring`.

4. **Session Auto-Create**: If the session doesn't exist, ensure your RunConfig has `autoCreateSession: true` or create the session first.

## Troubleshooting

- **Connection refused**: Make sure the server is running
- **No events**: Check that `streaming: true` is set and the appName exists
- **400 Bad Request**: Verify all required fields (appName, sessionId, newMessage) are present

For more detailed testing options, see `TEST_SSE_ENDPOINT.md`.
