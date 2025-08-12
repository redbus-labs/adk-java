# Google ADK Maven Plugin

This Maven plugin provides a convenient way to start the ADK Web Server with your custom agents for development and testing.

## Usage

### Basic Usage

```bash
mvn google-adk:web -Dagents=com.example.MyAgentProvider.INSTANCE
```

### Parameters

- **`agents`** (required): Full class path to your AgentProvider implementation
- **`port`** (optional, default: 8000): Port for the web server
- **`host`** (optional, default: localhost): Host address to bind to
- **`hotReloading`** (optional, default: true): Whether to enable hot reloading

### Example with Custom Parameters

```bash
mvn google-adk:web \
  -Dagents=com.example.MyAgentProvider.INSTANCE \
  -Dhost=0.0.0.0 \
  -Dport=9090 \
  -DhotReloading=false
```

## Creating an AgentProvider

### 1. Implement the AgentProvider Interface

Create a class that implements `com.google.adk.maven.AgentProvider`:

```java
package com.example;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.maven.AgentProvider;
import java.util.Map;

public class MyAgentProvider implements AgentProvider {

    public static final MyAgentProvider INSTANCE = new MyAgentProvider();

    @Override
    public Map<String, BaseAgent> getAgents() {
        return Map.of(
            "chat_bot", createChatBot(),
            "code_assistant", createCodeAssistant()
        );
    }

    private BaseAgent createChatBot() {
        return LlmAgent.builder()
            .name("chat_bot")
            .description("A helpful chat bot")
            .model("gemini-2.0-flash")
            .instruction("You are a helpful assistant.")
            .build();
    }

    private BaseAgent createCodeAssistant() {
        return LlmAgent.builder()
            .name("code_assistant")
            .description("A code assistance agent")
            .model("gemini-2.0-flash")
            .instruction("You are a coding assistant. Help users with programming questions.")
            .build();
    }
}
```

### 2. Add the Plugin to Your Project

Add the plugin to your `pom.xml`:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>com.google.adk</groupId>
            <artifactId>google-adk-maven-plugin</artifactId>
            <version>0.2.1-SNAPSHOT</version>
        </plugin>
    </plugins>
</build>
```

### 3. Run the Web Server

```bash
mvn google-adk:web -Dagents=com.example.MyAgentProvider.INSTANCE
```

## Alternative AgentProvider Patterns

### Using Default Constructor

```java
public class SimpleAgentProvider implements AgentProvider {
    // No static field needed, uses default constructor
    @Override
    public Map<String, BaseAgent> getAgents() {
        return Map.of("simple_agent", createSimpleAgent());
    }

    private BaseAgent createSimpleAgent() {
        return LlmAgent.builder()
            .name("simple_agent")
            .description("A simple agent")
            .model("gemini-2.0-flash")
            .instruction("You are a helpful assistant.")
            .build();
    }
}
```

Usage:
```bash
mvn google-adk:web -Dagents=com.example.SimpleAgentProvider
```

### Using Other Static Fields

```java
public class MultipleProviders implements AgentProvider {
    public static final MultipleProviders DEFAULT = new MultipleProviders();
    public static final MultipleProviders ADVANCED = new MultipleProviders(true);

    private final boolean advanced;

    public MultipleProviders() { this(false); }
    public MultipleProviders(boolean advanced) { this.advanced = advanced; }

    @Override
    public Map<String, BaseAgent> getAgents() {
        if (advanced) {
            return Map.of("advanced_agent", createAdvancedAgent());
        }
        return Map.of("basic_agent", createBasicAgent());
    }

    private BaseAgent createBasicAgent() {
        return LlmAgent.builder()
            .name("basic_agent")
            .description("A basic agent")
            .model("gemini-2.0-flash")
            .instruction("You are a basic helpful assistant.")
            .build();
    }

    private BaseAgent createAdvancedAgent() {
        return LlmAgent.builder()
            .name("advanced_agent")
            .description("An advanced agent with more capabilities")
            .model("gemini-2.0-flash")
            .instruction("You are an advanced assistant with enhanced capabilities.")
            .build();
    }
}
```

Usage:
```bash
mvn google-adk:web -Dagents=com.example.MultipleProviders.ADVANCED
```

## Web UI

Once the server starts, open your browser to:
- Default: http://localhost:8000
- Custom port: http://localhost:{port}
- External access: http://0.0.0.0:{port} (if using -Dhost=0.0.0.0)

The web UI provides:
- Interactive chat interface with your agents
- Agent selection dropdown
- Real-time streaming responses
- Session management
- Debug information

## Dependencies

Make sure your project has the necessary ADK dependencies:

```xml
<dependencies>
    <dependency>
        <groupId>com.google.adk</groupId>
        <artifactId>google-adk</artifactId>
        <version>0.2.1-SNAPSHOT</version>
    </dependency>

    <!-- Maven plugin dependency for AgentProvider interface -->
    <dependency>
        <groupId>com.google.adk</groupId>
        <artifactId>google-adk-maven-plugin</artifactId>
        <version>0.2.1-SNAPSHOT</version>
        <scope>compile</scope>
    </dependency>
</dependencies>
```

### Plugin Usage Options

**Option 1: With plugin declaration (shorter commands)**

Add the plugin to your `pom.xml` for convenience:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>com.google.adk</groupId>
            <artifactId>google-adk-maven-plugin</artifactId>
            <version>0.2.1-SNAPSHOT</version>
        </plugin>
    </plugins>
</build>
```

Then use the short command:
```bash
mvn google-adk:web -Dagents=com.example.MyAgentProvider.INSTANCE
```

**Option 2: Without plugin declaration (no pom.xml changes)**

Use the full coordinates directly:
```bash
mvn com.google.adk:google-adk-maven-plugin:web -Dagents=com.example.MyAgentProvider.INSTANCE
```

## Stopping the Server

Press `Ctrl+C` in the terminal to stop the server.