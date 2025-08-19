# Custom Tools Example

This example demonstrates how to create and use custom tools with a ComponentRegistry in the Google ADK Maven plugin. The custom registry allows you to inject your own tools and components into the ADK environment.

## Overview

The example includes:
- **CustomComponentRegistry**: A custom registry that extends ComponentRegistry
- **GetWeatherTool**: A custom tool for retrieving weather information using @Schema annotation
- **weather_agent**: A configuration-based agent that uses the custom tool

## Project Structure

```
├── src/main/java/com/example/
│   ├── CustomComponentRegistry.java    # Custom registry implementation
│   └── GetWeatherTool.java            # Custom weather tool
├── config_agents/
│   └── weather_agent/
│       └── root_agent.yaml           # YAML agent configuration
├── pom.xml                           # Maven configuration
└── README.md                         # This file
```

## How to Use

### 1. Build and Run

Compile and start the ADK web server with the custom registry:

```bash
# Navigate to the example directory
cd maven_plugin/examples/custom_tools

# Clean, compile, and start the server
mvn clean compile google-adk:web \
  -Dagents=config_agents \
  -Dregistry=com.example.CustomComponentRegistry
```

### 2. Access the Web UI

Open your browser and navigate to:
```
http://localhost:8000
```

You should see the weather_agent available in the UI, and it will have access to the custom get_weather tool.

## Key Components

### CustomComponentRegistry

The `CustomComponentRegistry` extends the base `ComponentRegistry` and registers custom tools:

```java
public class CustomComponentRegistry extends ComponentRegistry {
    public static final CustomComponentRegistry INSTANCE = new CustomComponentRegistry();

    private CustomComponentRegistry() {
        super(); // Initialize base ADK components

        // Register custom tools
        register("tools.get_weather", GetWeatherTool.INSTANCE);
    }
}
```

### GetWeatherTool

A simple tool implementation using the `@Schema` annotation approach:

```java
public class GetWeatherTool {
    public static final FunctionTool INSTANCE =
        FunctionTool.create(GetWeatherTool.class, "getWeather");

    @Schema(name = "get_weather", description = "Get current weather information for a city")
    public static Map<String, Object> getWeather(
        @Schema(name = "city", description = "The city to fetch weather for.") String city) {
        if (isNullOrEmpty(city)) {
            return Map.of("error", "City parameter is required");
        }

        return Map.of(
            "city", city,
            "temperature", getSimulatedTemperature(city),
            "condition", getSimulatedCondition(city)
        );
    }
}
```

### Configuration-Based Agent

The weather agent is defined in `config_agents/weather_agent/root_agent.yaml`:

```yaml
model: "gemini-2.5-flash"
name: "weather_agent"
description: "A weather assistant that can fetch weather information for cities"
instruction: |
  You are a helpful weather assistant.

  - Use the get_weather tool to fetch current weather information for any city.
  - Always provide clear and informative responses about weather conditions.
  - If a user doesn't ask about weather, steer the topic back to weather.
tools:
  - name: "tools.get_weather"
```

## Sample Queries

Once the server is running, try these sample queries with the weather_agent:

### Basic Weather Queries

- "What's the weather like in New York?"
- "Tell me the current weather in Tokyo"
- "How's the weather in London today?"

### Follow-up Questions

- "What about Paris?"
- "And San Francisco?"
- "How about the weather in my hometown of Chicago?"

### Non-weather Topics (Agent will redirect)

- "What's the capital of France?" → Agent will steer back to weather
- "Tell me a joke" → Agent will suggest asking about weather instead

## Best Practices

- Use `@Schema` annotation for simple tool implementations
- Return structured data (Maps) from tools for better integration
- Register tools with descriptive, namespaced names (e.g., "tools.get_weather")
- Test tool functionality through the web UI