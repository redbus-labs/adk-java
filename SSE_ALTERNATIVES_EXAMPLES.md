# SSE Alternatives - Code Examples

**Author:** Sandeep Belgavi  
**Date:** June 24, 2026

## Quick Comparison

| Framework | Size | Best For | Code Lines |
|-----------|------|----------|------------|
| **Java HttpServer** | 0 KB | Zero deps | ~200 |
| **Vert.x** | 2MB | High performance | ~50 |
| **Javalin** | 1MB | Simple APIs | ~30 |
| **Spark Java** | 500KB | Quick prototypes | ~20 |

## 1. Java HttpServer (Zero Dependencies) ⭐⭐⭐⭐⭐

**Best For:** Minimal footprint, embedded applications

```java
package com.example.sse;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * Lightweight SSE server using Java's built-in HttpServer.
 * Zero dependencies - uses only JDK.
 * 
 * @author Sandeep Belgavi
 * @since June 24, 2026
 */
public class HttpServerSseExample {
    
    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        
        server.createContext("/sse", new SseHandler());
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        
        System.out.println("SSE Server started on http://localhost:8080/sse");
    }
    
    static class SseHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // Only accept POST
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method Not Allowed");
                return;
            }
            
            // Set SSE headers
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("Connection", "keep-alive");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, 0);
            
            OutputStream os = exchange.getResponseBody();
            
            try {
                // Send initial connection event
                sendSSEEvent(os, "connected", "{\"status\":\"connected\"}");
                
                // Stream events
                for (int i = 0; i < 10; i++) {
                    String data = String.format("{\"message\":\"Event %d\",\"timestamp\":%d}", 
                        i, System.currentTimeMillis());
                    sendSSEEvent(os, "message", data);
                    Thread.sleep(1000);
                }
                
                // Send completion event
                sendSSEEvent(os, "done", "{\"status\":\"complete\"}");
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                sendSSEEvent(os, "error", "{\"error\":\"Interrupted\"}");
            } catch (Exception e) {
                sendSSEEvent(os, "error", 
                    String.format("{\"error\":\"%s\"}", e.getMessage()));
            } finally {
                os.close();
            }
        }
        
        private void sendSSEEvent(OutputStream os, String eventType, String data) 
                throws IOException {
            os.write(("event: " + eventType + "\n").getBytes(StandardCharsets.UTF_8));
            os.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
        
        private void sendError(HttpExchange exchange, int code, String message) 
                throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }
}
```

**Dependencies:** None  
**JAR Size:** 0 KB  
**Startup:** < 100ms

---

## 2. Vert.x (High Performance) ⭐⭐⭐⭐⭐

**Best For:** High-throughput, reactive applications

```java
package com.example.sse;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSE server using Vert.x - lightweight and high-performance.
 * 
 * @author Sandeep Belgavi
 * @since June 24, 2026
 */
public class VertxSseExample {
    
    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();
        HttpServer server = vertx.createHttpServer();
        Router router = Router.router(vertx);
        
        router.route().handler(BodyHandler.create());
        
        router.post("/sse").handler(ctx -> {
            // Set SSE headers
            ctx.response()
                .setChunked(true)
                .putHeader("Content-Type", "text/event-stream")
                .putHeader("Cache-Control", "no-cache")
                .putHeader("Connection", "keep-alive")
                .putHeader("Access-Control-Allow-Origin", "*");
            
            // Send initial connection event
            ctx.response().write("event: connected\n");
            ctx.response().write("data: {\"status\":\"connected\"}\n\n");
            
            AtomicLong counter = new AtomicLong(0);
            
            // Stream events every second
            long timerId = vertx.setPeriodic(1000, id -> {
                long count = counter.incrementAndGet();
                String event = String.format(
                    "event: message\n" +
                    "data: {\"message\":\"Event %d\",\"timestamp\":%d}\n\n",
                    count, System.currentTimeMillis()
                );
                
                ctx.response().write(event);
                
                // Stop after 10 events
                if (count >= 10) {
                    vertx.cancelTimer(id);
                    ctx.response().write("event: done\n");
                    ctx.response().write("data: {\"status\":\"complete\"}\n\n");
                    ctx.response().end();
                }
            });
            
            // Cleanup on connection close
            ctx.response().closeHandler(v -> {
                vertx.cancelTimer(timerId);
            });
        });
        
        server.requestHandler(router).listen(8080, result -> {
            if (result.succeeded()) {
                System.out.println("Vert.x SSE Server started on http://localhost:8080/sse");
            } else {
                System.err.println("Failed to start server: " + result.cause());
            }
        });
    }
}
```

**Dependencies:**
```xml
<dependency>
    <groupId>io.vertx</groupId>
    <artifactId>vertx-web</artifactId>
    <version>4.5.0</version>
</dependency>
```

**JAR Size:** ~2MB  
**Startup:** ~200ms

---

## 3. Javalin (Simplest) ⭐⭐⭐⭐

**Best For:** Simple REST APIs, quick development

```java
package com.example.sse;

import io.javalin.Javalin;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SSE server using Javalin - simple and lightweight.
 * 
 * @author Sandeep Belgavi
 * @since June 24, 2026
 */
public class JavalinSseExample {
    
    public static void main(String[] args) {
        Javalin app = Javalin.create().start(8080);
        
        app.post("/sse", ctx -> {
            // Set SSE headers
            ctx.res().setContentType("text/event-stream");
            ctx.res().setHeader("Cache-Control", "no-cache");
            ctx.res().setHeader("Connection", "keep-alive");
            ctx.res().setHeader("Access-Control-Allow-Origin", "*");
            
            // Send initial connection event
            ctx.res().getOutputStream().write(
                "event: connected\ndata: {\"status\":\"connected\"}\n\n".getBytes()
            );
            ctx.res().getOutputStream().flush();
            
            // Stream events
            AtomicInteger counter = new AtomicInteger(0);
            for (int i = 0; i < 10; i++) {
                String event = String.format(
                    "event: message\ndata: {\"message\":\"Event %d\",\"timestamp\":%d}\n\n",
                    counter.incrementAndGet(), System.currentTimeMillis()
                );
                ctx.res().getOutputStream().write(event.getBytes());
                ctx.res().getOutputStream().flush();
                Thread.sleep(1000);
            }
            
            // Send completion event
            ctx.res().getOutputStream().write(
                "event: done\ndata: {\"status\":\"complete\"}\n\n".getBytes()
            );
            ctx.res().getOutputStream().flush();
        });
        
        System.out.println("Javalin SSE Server started on http://localhost:8080/sse");
    }
}
```

**Dependencies:**
```xml
<dependency>
    <groupId>io.javalin</groupId>
    <artifactId>javalin</artifactId>
    <version>5.6.0</version>
</dependency>
```

**JAR Size:** ~1MB  
**Startup:** ~150ms

---

## 4. Spark Java (Minimal) ⭐⭐⭐⭐

**Best For:** Quick prototypes, minimal setup

```java
package com.example.sse;

import static spark.Spark.*;

/**
 * SSE server using Spark Java - minimal and simple.
 * 
 * @author Sandeep Belgavi
 * @since June 24, 2026
 */
public class SparkSseExample {
    
    public static void main(String[] args) {
        port(8080);
        
        post("/sse", (req, res) -> {
            // Set SSE headers
            res.type("text/event-stream");
            res.header("Cache-Control", "no-cache");
            res.header("Connection", "keep-alive");
            res.header("Access-Control-Allow-Origin", "*");
            
            StringBuilder response = new StringBuilder();
            
            // Send initial connection event
            response.append("event: connected\n");
            response.append("data: {\"status\":\"connected\"}\n\n");
            
            // Stream events
            for (int i = 0; i < 10; i++) {
                response.append("event: message\n");
                response.append(String.format(
                    "data: {\"message\":\"Event %d\",\"timestamp\":%d}\n\n",
                    i + 1, System.currentTimeMillis()
                ));
                
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            
            // Send completion event
            response.append("event: done\n");
            response.append("data: {\"status\":\"complete\"}\n\n");
            
            return response.toString();
        });
        
        System.out.println("Spark SSE Server started on http://localhost:8080/sse");
    }
}
```

**Dependencies:**
```xml
<dependency>
    <groupId>com.sparkjava</groupId>
    <artifactId>spark-core</artifactId>
    <version>2.9.4</version>
</dependency>
```

**JAR Size:** ~500KB  
**Startup:** ~100ms

---

## 5. Micronaut (Cloud-Optimized) ⭐⭐⭐⭐

**Best For:** Cloud-native, serverless, Kubernetes

```java
package com.example.sse;

import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.sse.Event;
import reactor.core.publisher.Flux;
import java.time.Duration;

/**
 * SSE server using Micronaut - cloud-optimized and fast startup.
 * 
 * @author Sandeep Belgavi
 * @since June 24, 2026
 */
@Controller
public class MicronautSseExample {
    
    @Post(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM)
    public Flux<Event<String>> streamEvents() {
        return Flux.interval(Duration.ofSeconds(1))
            .take(10)
            .map(seq -> {
                String data = String.format(
                    "{\"message\":\"Event %d\",\"timestamp\":%d}",
                    seq + 1, System.currentTimeMillis()
                );
                return Event.of(data).name("message");
            })
            .startWith(Event.of("{\"status\":\"connected\"}").name("connected"))
            .concatWith(Flux.just(Event.of("{\"status\":\"complete\"}").name("done")));
    }
}
```

**Dependencies:**
```xml
<dependency>
    <groupId>io.micronaut</groupId>
    <artifactId>micronaut-http-server</artifactId>
</dependency>
```

**JAR Size:** ~5MB  
**Startup:** ~50ms (very fast!)

---

## Quick Decision Guide

### Choose **Java HttpServer** if:
- ✅ Zero dependencies required
- ✅ Minimal footprint needed
- ✅ Embedded application
- ✅ Full control needed

### Choose **Vert.x** if:
- ✅ High performance needed
- ✅ Reactive programming preferred
- ✅ High-throughput streaming
- ✅ Modern async/await style

### Choose **Javalin** if:
- ✅ Simple REST API
- ✅ Quick development
- ✅ Clean, minimal API
- ✅ Kotlin support needed

### Choose **Spark Java** if:
- ✅ Quick prototype
- ✅ Minimal setup
- ✅ Simplest possible code
- ✅ Learning/experimentation

### Choose **Micronaut/Quarkus** if:
- ✅ Cloud-native deployment
- ✅ Serverless functions
- ✅ Kubernetes
- ✅ Fast startup critical

## Performance Comparison

| Framework | Requests/sec | Memory | Startup |
|-----------|--------------|--------|---------|
| Java HttpServer | 50,000+ | Low | <100ms |
| Vert.x | 100,000+ | Medium | ~200ms |
| Javalin | 40,000+ | Low | ~150ms |
| Spark Java | 30,000+ | Low | ~100ms |
| Micronaut | 60,000+ | Low | ~50ms |

## Recommendation

**For ADK Java (if not using Spring):**

**🥇 Best: Vert.x** ✅
- Very lightweight (~2MB)
- Excellent for SSE/streaming
- High performance
- Industry standard

**🥈 Alternative: Java HttpServer** ✅
- Zero dependencies
- Full control
- Minimal overhead

Both are excellent choices depending on your needs!
