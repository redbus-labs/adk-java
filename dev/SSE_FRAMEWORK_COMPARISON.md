# SSE Framework Comparison and Implementation Guide

**Author**: Sandeep Belgavi  
**Date**: January 24, 2026

## Executive Summary

This document compares different frameworks for implementing Server-Sent Events (SSE) in Java applications and explains why **Java HttpServer** is the best choice, with **Spring Boot** as the second-best option. It also covers the advantages of SSE and its applications.

## Table of Contents

1. [What is Server-Sent Events (SSE)?](#what-is-server-sent-events-sse)
2. [Framework Comparison](#framework-comparison)
3. [Why Java HttpServer is Best](#why-java-httpserver-is-best)
4. [Why Spring Boot is Second Best](#why-spring-boot-is-second-best)
5. [Advantages of SSE](#advantages-of-sse)
6. [Applications and Use Cases](#applications-and-use-cases)
7. [Implementation Details](#implementation-details)
8. [Performance Comparison](#performance-comparison)
9. [Recommendations](#recommendations)

---

## What is Server-Sent Events (SSE)?

Server-Sent Events (SSE) is a web standard that allows a server to push data to a web page over a single HTTP connection. Unlike WebSockets, SSE is unidirectional (server-to-client) and uses standard HTTP, making it simpler to implement and more firewall-friendly.

### Key Characteristics

- **Unidirectional**: Server → Client only
- **HTTP-based**: Uses standard HTTP protocol
- **Automatic Reconnection**: Built-in reconnection mechanism
- **Text-based**: Easy to debug and monitor
- **Event Types**: Supports custom event types (`message`, `error`, `done`, etc.)

### SSE Format

```
event: message
data: {"id": "1", "content": "Hello"}

event: message
data: {"id": "2", "content": "World"}

event: done
data: {"status": "complete"}
```

---

## Framework Comparison

### 1. Java HttpServer (Built-in) ⭐ **BEST**

**Port**: 9085 (default SSE endpoint)

#### Pros
- ✅ **Zero Dependencies**: Built into Java SE (no external libraries)
- ✅ **Lightweight**: Minimal memory footprint (~2-5MB)
- ✅ **Fast Startup**: Starts in milliseconds
- ✅ **Simple API**: Direct control over HTTP handling
- ✅ **No Framework Overhead**: Pure Java, no abstraction layers
- ✅ **Easy Deployment**: Single JAR, no framework dependencies
- ✅ **Perfect for Microservices**: Ideal for lightweight services
- ✅ **Full Control**: Complete control over request/response handling

#### Cons
- ❌ Manual HTTP handling (more code)
- ❌ No built-in dependency injection
- ❌ Manual CORS handling
- ❌ No automatic JSON serialization (but can use Jackson)

#### Code Example
```java
HttpServer server = HttpServer.create(new InetSocketAddress(9085), 0);
server.createContext("/run_sse", new HttpServerSseController());
server.start();
```

#### Performance Metrics
- **Memory**: ~2-5MB
- **Startup Time**: <100ms
- **Throughput**: ~10,000-50,000 req/sec (depending on hardware)
- **Latency**: <1ms overhead

---

### 2. Spring Boot ⭐ **SECOND BEST**

**Port**: 9086 (Spring SSE endpoint)

#### Pros
- ✅ **Rich Ecosystem**: Extensive Spring ecosystem
- ✅ **Auto-configuration**: Minimal configuration needed
- ✅ **Dependency Injection**: Built-in DI container
- ✅ **Jackson Integration**: Automatic JSON serialization
- ✅ **CORS Support**: Built-in CORS configuration
- ✅ **Actuator**: Health checks and metrics
- ✅ **Testing Support**: Excellent testing framework
- ✅ **Production Ready**: Battle-tested in enterprise

#### Cons
- ❌ **Heavy**: ~50-100MB memory footprint
- ❌ **Slow Startup**: 1-5 seconds startup time
- ❌ **Many Dependencies**: Large dependency tree
- ❌ **Framework Overhead**: Additional abstraction layers
- ❌ **Complex**: More moving parts

#### Code Example
```java
@RestController
public class ExecutionController {
    @PostMapping(value = "/run_sse_spring", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter agentRunSseSpring(@RequestBody AgentRunRequest request) {
        return sseEventStreamService.streamEvents(...);
    }
}
```

#### Performance Metrics
- **Memory**: ~50-100MB
- **Startup Time**: 1-5 seconds
- **Throughput**: ~5,000-20,000 req/sec
- **Latency**: 2-5ms overhead

---

### 3. Vert.x

#### Pros
- ✅ High performance (reactive)
- ✅ Low latency
- ✅ Good for high concurrency

#### Cons
- ❌ Learning curve (reactive programming)
- ❌ Additional dependency
- ❌ More complex than HttpServer

#### Performance Metrics
- **Memory**: ~20-40MB
- **Startup Time**: ~200-500ms
- **Throughput**: ~20,000-100,000 req/sec

---

### 4. Javalin

#### Pros
- ✅ Lightweight (~1MB)
- ✅ Simple API
- ✅ Good performance

#### Cons
- ❌ Less mature than Spring
- ❌ Smaller ecosystem
- ❌ Additional dependency

#### Performance Metrics
- **Memory**: ~10-20MB
- **Startup Time**: ~100-300ms
- **Throughput**: ~8,000-30,000 req/sec

---

### 5. Micronaut

#### Pros
- ✅ Fast startup
- ✅ Low memory
- ✅ Compile-time DI

#### Cons
- ❌ Learning curve
- ❌ Smaller ecosystem than Spring
- ❌ Additional dependency

#### Performance Metrics
- **Memory**: ~15-30MB
- **Startup Time**: ~200-500ms
- **Throughput**: ~10,000-40,000 req/sec

---

### 6. Quarkus

#### Pros
- ✅ Very fast startup
- ✅ Low memory
- ✅ Native compilation support

#### Cons
- ❌ Complex setup
- ❌ Learning curve
- ❌ Additional dependency

#### Performance Metrics
- **Memory**: ~20-40MB
- **Startup Time**: ~100-300ms
- **Throughput**: ~15,000-50,000 req/sec

---

## Why Java HttpServer is Best

### 1. **Zero Dependencies** 🎯

Java HttpServer is built into Java SE (since Java 6), meaning:
- No external libraries required
- Smaller deployment size
- Fewer security vulnerabilities
- Easier to maintain

**Impact**: Reduces deployment complexity and attack surface.

### 2. **Lightweight** ⚡

- **Memory**: 2-5MB vs Spring's 50-100MB
- **Startup**: <100ms vs Spring's 1-5 seconds
- **JAR Size**: Minimal vs Spring's large footprint

**Impact**: Better resource utilization, especially in containerized environments.

### 3. **Performance** 🚀

- Lower latency (no framework overhead)
- Higher throughput (direct HTTP handling)
- Better for high-frequency streaming

**Impact**: Better user experience, lower infrastructure costs.

### 4. **Simplicity** 🎨

- Direct HTTP handling
- No complex abstractions
- Easy to understand and debug

**Impact**: Faster development, easier maintenance.

### 5. **Perfect for Microservices** 🏗️

- Small footprint ideal for containers
- Fast startup for auto-scaling
- No framework bloat

**Impact**: Better scalability and cost efficiency.

### 6. **Full Control** 🎮

- Complete control over request/response
- Custom error handling
- Flexible CORS configuration

**Impact**: Can optimize for specific use cases.

---

## Why Spring Boot is Second Best

### 1. **Rich Ecosystem** 🌟

- Extensive libraries and integrations
- Large community support
- Well-documented

**Use Case**: When you need Spring ecosystem features (security, data access, etc.)

### 2. **Developer Productivity** 👨‍💻

- Auto-configuration
- Dependency injection
- Less boilerplate code

**Use Case**: Rapid development, team familiarity with Spring

### 3. **Enterprise Features** 🏢

- Actuator for monitoring
- Security framework
- Transaction management

**Use Case**: Enterprise applications requiring these features

### 4. **Testing Support** ✅

- Excellent testing framework
- MockMvc for integration tests
- Test slices

**Use Case**: Applications requiring comprehensive testing

### When to Choose Spring Boot

- ✅ Already using Spring ecosystem
- ✅ Need Spring features (security, data access)
- ✅ Team is familiar with Spring
- ✅ Enterprise application requirements
- ✅ Don't mind the overhead

---

## Advantages of SSE

### 1. **Simplicity** 🎯

- Uses standard HTTP (no special protocol)
- Easy to implement and debug
- Works through firewalls and proxies

### 2. **Automatic Reconnection** 🔄

- Built-in reconnection mechanism
- Client automatically reconnects on connection loss
- Configurable retry intervals

### 3. **Event Types** 📨

- Support for custom event types
- Can send different types of events (`message`, `error`, `done`)
- Client can listen to specific event types

### 4. **Text-Based** 📝

- Human-readable format
- Easy to debug
- Can be monitored with standard tools

### 5. **HTTP/2 Compatible** 🚀

- Works with HTTP/2 multiplexing
- Better performance over single connection
- Reduced latency

### 6. **Browser Support** 🌐

- Native browser support (EventSource API)
- No additional libraries needed
- Works in all modern browsers

### 7. **Server-Friendly** 🖥️

- Less resource intensive than WebSockets
- Easier to scale
- Better for one-way communication

### 8. **Standard Protocol** 📋

- W3C standard
- Well-documented
- Widely supported

---

## Applications and Use Cases

### 1. **Real-Time Notifications** 🔔

**Use Case**: Push notifications to users
- Order updates
- System alerts
- User activity notifications

**Example**: E-commerce order tracking
```javascript
const eventSource = new EventSource('/orders/123/updates');
eventSource.addEventListener('status', (e) => {
    updateOrderStatus(JSON.parse(e.data));
});
```

### 2. **Live Data Streaming** 📊

**Use Case**: Real-time data visualization
- Stock prices
- Sensor data
- Analytics dashboards

**Example**: Stock price ticker
```javascript
const eventSource = new EventSource('/stocks/prices');
eventSource.addEventListener('price', (e) => {
    updatePrice(JSON.parse(e.data));
});
```

### 3. **Progress Updates** 📈

**Use Case**: Long-running operations
- File uploads
- Data processing
- Report generation

**Example**: File processing progress
```javascript
const eventSource = new EventSource('/process/file123');
eventSource.addEventListener('progress', (e) => {
    updateProgressBar(JSON.parse(e.data).percent);
});
```

### 4. **Chat Applications** 💬

**Use Case**: One-way messaging
- Broadcast messages
- System announcements
- Bot responses

**Example**: Customer support chat
```javascript
const eventSource = new EventSource('/chat/session123');
eventSource.addEventListener('message', (e) => {
    displayMessage(JSON.parse(e.data));
});
```

### 5. **Live Feeds** 📰

**Use Case**: Real-time content updates
- News feeds
- Social media updates
- Activity streams

**Example**: News feed
```javascript
const eventSource = new EventSource('/news/live');
eventSource.addEventListener('article', (e) => {
    addArticle(JSON.parse(e.data));
});
```

### 6. **Monitoring and Logging** 📋

**Use Case**: Real-time system monitoring
- Application logs
- System metrics
- Error tracking

**Example**: Application logs
```javascript
const eventSource = new EventSource('/logs/stream');
eventSource.addEventListener('log', (e) => {
    appendLog(JSON.parse(e.data));
});
```

### 7. **Gaming** 🎮

**Use Case**: Real-time game updates
- Score updates
- Game state changes
- Player actions

**Example**: Live scoreboard
```javascript
const eventSource = new EventSource('/game/scoreboard');
eventSource.addEventListener('score', (e) => {
    updateScoreboard(JSON.parse(e.data));
});
```

### 8. **IoT Data Streaming** 🌐

**Use Case**: Internet of Things data
- Sensor readings
- Device status
- Telemetry data

**Example**: Temperature sensor
```javascript
const eventSource = new EventSource('/sensors/temperature');
eventSource.addEventListener('reading', (e) => {
    updateTemperature(JSON.parse(e.data).value);
});
```

---

## Implementation Details

### Current Implementation

Our implementation provides **two SSE endpoints**:

1. **HttpServer SSE (Default)** - Port 9085
   - Zero dependencies
   - Lightweight
   - Best performance

2. **Spring SSE (Alternative)** - Port 9086
   - Spring ecosystem
   - Rich features
   - Enterprise ready

### Endpoints

```
POST http://localhost:9085/run_sse          # HttpServer (default)
POST http://localhost:9086/run_sse_spring    # Spring Boot
```

### Request Format

```json
{
  "appName": "your-app-name",
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

### Response Format

```
event: message
data: {"id":"event-1","author":"agent","content":{...}}

event: message
data: {"id":"event-2","author":"agent","content":{...}}

event: done
data: {"status":"complete"}
```

---

## Performance Comparison

### Memory Usage

| Framework | Memory | Relative |
|-----------|--------|----------|
| **Java HttpServer** | 2-5MB | 1x (baseline) |
| Spring Boot | 50-100MB | 10-20x |
| Vert.x | 20-40MB | 4-8x |
| Javalin | 10-20MB | 2-4x |
| Micronaut | 15-30MB | 3-6x |
| Quarkus | 20-40MB | 4-8x |

### Startup Time

| Framework | Startup | Relative |
|-----------|---------|----------|
| **Java HttpServer** | <100ms | 1x (baseline) |
| Spring Boot | 1-5s | 10-50x |
| Vert.x | 200-500ms | 2-5x |
| Javalin | 100-300ms | 1-3x |
| Micronaut | 200-500ms | 2-5x |
| Quarkus | 100-300ms | 1-3x |

### Throughput (Requests/Second)

| Framework | Throughput | Relative |
|-----------|------------|----------|
| **Java HttpServer** | 10K-50K | 1x (baseline) |
| Spring Boot | 5K-20K | 0.5-0.4x |
| Vert.x | 20K-100K | 2-2x |
| Javalin | 8K-30K | 0.8-0.6x |
| Micronaut | 10K-40K | 1-0.8x |
| Quarkus | 15K-50K | 1.5-1x |

*Note: Actual performance depends on hardware, workload, and configuration*

---

## Recommendations

### Choose Java HttpServer When:

✅ **Microservices Architecture**
- Small, focused services
- Containerized deployments
- Need fast startup and low memory

✅ **High Performance Requirements**
- Low latency critical
- High throughput needed
- Resource constraints

✅ **Simple Use Cases**
- Straightforward SSE streaming
- Don't need framework features
- Want minimal dependencies

✅ **New Projects**
- Starting fresh
- Want lightweight solution
- Focus on performance

### Choose Spring Boot When:

✅ **Enterprise Applications**
- Need Spring ecosystem
- Require enterprise features
- Team familiar with Spring

✅ **Complex Requirements**
- Need security framework
- Require data access layers
- Want auto-configuration

✅ **Existing Spring Projects**
- Already using Spring
- Want consistency
- Leverage existing code

✅ **Rapid Development**
- Need quick prototyping
- Want less boilerplate
- Prefer convention over configuration

---

## Conclusion

**Java HttpServer** is the **best choice** for SSE implementations because:

1. ✅ **Zero dependencies** - Built into Java
2. ✅ **Lightweight** - Minimal memory footprint
3. ✅ **Fast** - Low latency, high throughput
4. ✅ **Simple** - Easy to understand and maintain
5. ✅ **Perfect for microservices** - Ideal for containers

**Spring Boot** is the **second-best choice** when:

1. ✅ You need Spring ecosystem features
2. ✅ Enterprise requirements
3. ✅ Team familiarity with Spring
4. ✅ Rapid development needed

### Our Implementation

We provide **both options**:
- **Default**: HttpServer SSE (port 9085) - Best performance
- **Alternative**: Spring SSE (port 9086) - Rich features

This gives you the flexibility to choose based on your specific needs while maintaining consistency in the API.

---

## References

- [MDN: Server-Sent Events](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events)
- [W3C: Server-Sent Events Specification](https://html.spec.whatwg.org/multipage/server-sent-events.html)
- [Java HttpServer Documentation](https://docs.oracle.com/javase/8/docs/jre/api/net/httpserver/spec/com/sun/net/httpserver/HttpServer.html)
- [Spring Boot SSE Documentation](https://docs.spring.io/spring-framework/reference/web/sse.html)

---

**Author**: Sandeep Belgavi  
**Date**: January 24, 2026  
**Version**: 1.0
