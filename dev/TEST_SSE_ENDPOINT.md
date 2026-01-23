# Testing SSE Endpoint Guide

This guide explains how to start the HTTP server and test the Server-Sent Events (SSE) endpoint.

## Prerequisites

- Java 17 or higher
- Maven 3.6+
- An agent application configured (appName)

## Starting the Server

### Option 1: Using Maven Spring Boot Plugin

```bash
cd /Users/sandeep.b/IdeaProjects/voice/adk-java/dev
mvn spring-boot:run
```

### Option 2: Using the Executable JAR

First, build the executable JAR:
```bash
cd /Users/sandeep.b/IdeaProjects/voice/adk-java/dev
mvn clean package
```

Then run it:
```bash
java -jar target/google-adk-dev-0.5.1-SNAPSHOT-exec.jar
```

### Option 3: Run from IDE

Run the `AdkWebServer` class as a Spring Boot application from your IDE.

## Server Endpoints

Once started, you'll have:

- **HttpServer SSE (Default)**: `http://localhost:9085/run_sse`
- **Spring SSE (Alternative)**: `http://localhost:8080/run_sse_spring`
- **Spring Boot Server**: `http://localhost:8080` (main server)

## Testing with cURL

### Basic SSE Test (HttpServer - Port 9085)

```bash
curl -N -X POST http://localhost:9085/run_sse \
  -H "Content-Type: application/json" \
  -d '{
    "appName": "your-app-name",
    "userId": "test-user",
    "sessionId": "test-session-123",
    "newMessage": {
      "role": "user",
      "parts": [{"text": "Hello, test message"}]
    },
    "streaming": true
  }'
```

### SSE Test with State Delta

```bash
curl -N -X POST http://localhost:9085/run_sse \
  -H "Content-Type: application/json" \
  -d '{
    "appName": "your-app-name",
    "userId": "test-user",
    "sessionId": "test-session-123",
    "newMessage": {
      "role": "user",
      "parts": [{"text": "Hello, test message"}]
    },
    "streaming": true,
    "stateDelta": {
      "key": "value",
      "config": {"setting": "test"}
    }
  }'
```

### Spring SSE Test (Port 8080)

```bash
curl -N -X POST http://localhost:8080/run_sse_spring \
  -H "Content-Type: application/json" \
  -d '{
    "appName": "your-app-name",
    "userId": "test-user",
    "sessionId": "test-session-123",
    "newMessage": {
      "role": "user",
      "parts": [{"text": "Hello, test message"}]
    },
    "streaming": true
  }'
```

## Understanding the cURL Flags

- `-N` or `--no-buffer`: Disables buffering, essential for streaming SSE responses
- `-X POST`: Specifies HTTP POST method
- `-H "Content-Type: application/json"`: Sets the request content type
- `-d '{...}'`: Request body with JSON payload

## Expected SSE Response Format

SSE responses follow this format:

```
event: message
data: {"id":"event-1","author":"agent","content":{...}}

event: message
data: {"id":"event-2","author":"agent","content":{...}}

event: done
data: {"status":"complete"}
```

## Testing Tips

### 1. Save Response to File

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
  }' > sse_output.txt
```

### 2. Verbose Output (See Headers)

```bash
curl -v -N -X POST http://localhost:9085/run_sse \
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

### 3. Test CORS Preflight

```bash
curl -X OPTIONS http://localhost:9085/run_sse \
  -H "Origin: http://localhost:3000" \
  -H "Access-Control-Request-Method: POST" \
  -H "Access-Control-Request-Headers: Content-Type" \
  -v
```

### 4. Test Error Cases

**Missing appName:**
```bash
curl -N -X POST http://localhost:9085/run_sse \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "test-user",
    "sessionId": "test-session-123",
    "newMessage": {
      "role": "user",
      "parts": [{"text": "Hello"}]
    }
  }'
```

**Missing sessionId:**
```bash
curl -N -X POST http://localhost:9085/run_sse \
  -H "Content-Type: application/json" \
  -d '{
    "appName": "your-app-name",
    "userId": "test-user",
    "newMessage": {
      "role": "user",
      "parts": [{"text": "Hello"}]
    }
  }'
```

## Using a Test Script

Create a file `test_sse.sh`:

```bash
#!/bin/bash

# Test SSE Endpoint
echo "Testing SSE endpoint on port 9085..."
echo "======================================"

curl -N -X POST http://localhost:9085/run_sse \
  -H "Content-Type: application/json" \
  -d '{
    "appName": "your-app-name",
    "userId": "test-user",
    "sessionId": "test-session-'$(date +%s)'",
    "newMessage": {
      "role": "user",
      "parts": [{"text": "Hello, this is a test message"}]
    },
    "streaming": true
  }'

echo ""
echo "======================================"
echo "Test completed"
```

Make it executable and run:
```bash
chmod +x test_sse.sh
./test_sse.sh
```

## Browser Testing

You can also test SSE in a browser using JavaScript:

```html
<!DOCTYPE html>
<html>
<head>
    <title>SSE Test</title>
</head>
<body>
    <h1>SSE Test</h1>
    <div id="output"></div>

    <script>
        const eventSource = new EventSource('http://localhost:9085/run_sse', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                appName: 'your-app-name',
                userId: 'test-user',
                sessionId: 'test-session-123',
                newMessage: {
                    role: 'user',
                    parts: [{text: 'Hello'}]
                },
                streaming: true
            })
        });

        eventSource.addEventListener('message', function(e) {
            const output = document.getElementById('output');
            output.innerHTML += '<p>Message: ' + e.data + '</p>';
        });

        eventSource.addEventListener('done', function(e) {
            const output = document.getElementById('output');
            output.innerHTML += '<p><strong>Done: ' + e.data + '</strong></p>';
            eventSource.close();
        });

        eventSource.addEventListener('error', function(e) {
            const output = document.getElementById('output');
            output.innerHTML += '<p style="color: red;">Error: ' + e.data + '</p>';
            eventSource.close();
        });
    </script>
</body>
</html>
```

**Note:** Browser EventSource API only supports GET requests, so for POST requests you'll need to use `fetch` with streaming:

```javascript
async function testSSE() {
    const response = await fetch('http://localhost:9085/run_sse', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify({
            appName: 'your-app-name',
            userId: 'test-user',
            sessionId: 'test-session-123',
            newMessage: {
                role: 'user',
                parts: [{text: 'Hello'}]
            },
            streaming: true
        })
    });

    const reader = response.body.getReader();
    const decoder = new TextDecoder();

    while (true) {
        const {done, value} = await reader.read();
        if (done) break;
        
        const chunk = decoder.decode(value);
        console.log('Received:', chunk);
    }
}

testSSE();
```

## Troubleshooting

### Server Not Starting

1. Check if port 9085 is already in use:
   ```bash
   lsof -i :9085
   ```

2. Check server logs for errors

3. Verify Java version:
   ```bash
   java -version
   ```

### No Events Received

1. Verify the agent/appName exists and is configured
2. Check server logs for errors
3. Ensure `streaming: true` is set in the request
4. Verify the session exists or auto-create is enabled

### Connection Refused

1. Ensure the server is running
2. Check firewall settings
3. Verify the port (9085 for HttpServer SSE, 8080 for Spring)

## Monitoring

Watch server logs while testing:
```bash
# In another terminal, tail the logs
tail -f logs/application.log
```

Or if running with Maven:
```bash
mvn spring-boot:run 2>&1 | tee server.log
```

## Author

Sandeep Belgavi
January 24, 2026
