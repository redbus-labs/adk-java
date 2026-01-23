# Both Spring and HttpServer Implementations - Complete Guide

**Author:** Sandeep Belgavi  
**Date:** June 24, 2026

## Current Status

### ✅ **Spring-Based Implementation** (Currently Active)

**Status:** Fully Implemented and Working

**Endpoints:**
- `POST http://localhost:8080/run_sse` - Generic SSE endpoint
- `POST http://localhost:8080/search/sse` - Domain-specific example

**Framework:** Spring Boot  
**Component:** Spring's `SseEmitter`  
**Dependencies:** Spring Web (already included)

**Files:**
- `SseEventStreamService.java` - Spring-based service
- `ExecutionController.java` - Spring controller
- `SearchSseController.java` - Domain-specific example

---

### 🆕 **HttpServer Implementation** (Just Added)

**Status:** ✅ Implemented and Ready to Use

**Endpoints:**
- `POST http://localhost:8081/run_sse_http` - Generic SSE endpoint (HttpServer-based)

**Framework:** Java HttpServer (JDK only)  
**Component:** Manual SSE formatting  
**Dependencies:** None (zero dependencies)

**Files:**
- `HttpServerSseController.java` - HttpServer handler
- `HttpServerSseConfig.java` - Spring configuration to start HttpServer

---

## How to Use Both

### Option 1: Enable Both (Recommended) ✅

**Configuration:** `application.properties`
```properties
# Enable HttpServer SSE endpoints (runs on separate port)
adk.httpserver.sse.enabled=true
adk.httpserver.sse.port=8081
adk.httpserver.sse.host=0.0.0.0
```

**Result:**
- **Spring endpoints:** `http://localhost:8080/run_sse`
- **HttpServer endpoints:** `http://localhost:8081/run_sse_http`

**Benefits:**
- ✅ Both available simultaneously
- ✅ Can choose per request
- ✅ Easy A/B testing
- ✅ No conflicts

### Option 2: Spring Only (Default)

**Configuration:** `application.properties`
```properties
# HttpServer SSE disabled (default)
# adk.httpserver.sse.enabled=false
```

**Result:**
- **Spring endpoints:** `http://localhost:8080/run_sse` ✅
- **HttpServer endpoints:** Disabled

### Option 3: HttpServer Only

**Configuration:** `application.properties`
```properties
# Disable Spring endpoints (if needed)
# Keep HttpServer enabled
adk.httpserver.sse.enabled=true
adk.httpserver.sse.port=8080
```

**Note:** This requires more configuration changes to disable Spring endpoints.

---

## Request Format (Same for Both)

Both implementations accept the same request format:

```json
POST /run_sse (Spring) or /run_sse_http (HttpServer)
Content-Type: application/json

{
  "appName": "my-app",
  "userId": "user123",
  "sessionId": "session456",
  "newMessage": {
    "role": "user",
    "parts": [{"text": "Hello"}]
  },
  "streaming": true,
  "stateDelta": {"key": "value"}
}
```

**Response:** Same SSE format from both endpoints

---

## Comparison

| Feature | Spring | HttpServer |
|---------|--------|------------|
| **Port** | 8080 (default) | 8081 (configurable) |
| **Endpoint** | `/run_sse` | `/run_sse_http` |
| **Dependencies** | Spring Web | None (JDK only) |
| **SSE Component** | `SseEmitter` | Manual formatting |
| **Code Lines** | ~50 | ~200 |
| **Overhead** | Spring framework | Minimal |
| **Features** | Full Spring features | Basic HTTP |

---

## Architecture

```
┌─────────────────────────────────────────┐
│         Spring Boot Server               │
│         Port: 8080                       │
│  ┌─────────────────────────────────────┐ │
│  │  POST /run_sse                      │ │
│  │  (Spring SseEmitter)                │ │
│  └─────────────────────────────────────┘ │
└─────────────────────────────────────────┘

┌─────────────────────────────────────────┐
│         HttpServer                       │
│         Port: 8081                       │
│  ┌─────────────────────────────────────┐ │
│  │  POST /run_sse_http                 │ │
│  │  (Manual SSE formatting)            │ │
│  └─────────────────────────────────────┘ │
└─────────────────────────────────────────┘

              ▲
              │ Both use
              │
┌─────────────┴─────────────────────────────┐
│      Shared Services                       │
│  ┌─────────────────────────────────────┐ │
│  │  RunnerService                      │ │
│  │  PassThroughEventProcessor          │ │
│  └─────────────────────────────────────┘ │
└───────────────────────────────────────────┘
```

---

## Usage Examples

### Using Spring Endpoint

```bash
curl -X POST http://localhost:8080/run_sse \
  -H "Content-Type: application/json" \
  -d '{
    "appName": "my-app",
    "userId": "user123",
    "sessionId": "session456",
    "newMessage": {"role": "user", "parts": [{"text": "Hello"}]},
    "streaming": true
  }'
```

### Using HttpServer Endpoint

```bash
curl -X POST http://localhost:8081/run_sse_http \
  -H "Content-Type: application/json" \
  -d '{
    "appName": "my-app",
    "userId": "user123",
    "sessionId": "session456",
    "newMessage": {"role": "user", "parts": [{"text": "Hello"}]},
    "streaming": true
  }'
```

**Both return the same SSE stream format!**

---

## When to Use Which

### Use Spring Endpoint (`/run_sse`) When:
- ✅ Already using Spring Boot
- ✅ Want framework features (dependency injection, etc.)
- ✅ Need Spring ecosystem integration
- ✅ Standard port 8080

### Use HttpServer Endpoint (`/run_sse_http`) When:
- ✅ Want zero dependencies
- ✅ Need minimal footprint
- ✅ Embedded application
- ✅ Want to avoid Spring overhead
- ✅ Different port for separation

---

## Testing Both

### Test Spring Endpoint
```bash
# Start application
# Spring endpoint available at http://localhost:8080/run_sse
curl -N -X POST http://localhost:8080/run_sse \
  -H "Content-Type: application/json" \
  -d '{"appName":"test","userId":"u1","sessionId":"s1","newMessage":{"role":"user","parts":[{"text":"test"}]},"streaming":true}'
```

### Test HttpServer Endpoint
```bash
# Enable in application.properties first:
# adk.httpserver.sse.enabled=true
# HttpServer endpoint available at http://localhost:8081/run_sse_http
curl -N -X POST http://localhost:8081/run_sse_http \
  -H "Content-Type: application/json" \
  -d '{"appName":"test","userId":"u1","sessionId":"s1","newMessage":{"role":"user","parts":[{"text":"test"}]},"streaming":true}'
```

---

## Configuration Reference

### application.properties

```properties
# HttpServer SSE Configuration
adk.httpserver.sse.enabled=true          # Enable HttpServer SSE endpoints
adk.httpserver.sse.port=8081             # Port for HttpServer (default: 8081)
adk.httpserver.sse.host=0.0.0.0          # Host to bind to (default: 0.0.0.0)
```

### application.yml

```yaml
adk:
  httpserver:
    sse:
      enabled: true
      port: 8081
      host: 0.0.0.0
```

---

## Summary

### ✅ What's Implemented

1. **Spring-Based SSE** ✅
   - Fully implemented
   - Uses Spring's SseEmitter
   - Endpoint: `/run_sse`

2. **HttpServer-Based SSE** ✅
   - Fully implemented
   - Zero dependencies
   - Endpoint: `/run_sse_http`

### ✅ How to Use

1. **Enable Both:** Set `adk.httpserver.sse.enabled=true`
2. **Use Spring:** `POST http://localhost:8080/run_sse`
3. **Use HttpServer:** `POST http://localhost:8081/run_sse_http`

### ✅ Benefits

- ✅ **Flexibility:** Choose Spring or HttpServer per use case
- ✅ **Zero Dependencies:** HttpServer option has no dependencies
- ✅ **Same API:** Both accept same request format
- ✅ **Coexistence:** Both can run simultaneously
- ✅ **Easy Testing:** Compare both implementations

---

**Status:** ✅ **Both Implementations Complete and Ready to Use!**
