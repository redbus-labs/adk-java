
# Capability Supported

Of course. Here is the table with the 4th column for "Bedrock API" added.

| Feature | Gemini | Anthropic | AWS Bedrock API | Ollama | Azure OAI (redBus) | Bedrock+Anthropic |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Chat** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Tools/Function** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Chat Stream** | ✅ | ❌ | ✅ | ✅ | ✅ | ❌ |
| **Image (Input)** | ✅ (Multimodal models) | ❌ | ✅ (Via models like Claude 3) | ❌ | ❓ | ❌ (Claude 3 models) |
| **Image Gen (Output)** | ✅ | ❌ | ✅ (Via Titan, Stable Diffusion) | ❌ | ❓ | ❌ (Via other models like Titan Image Generator) |
| **Audio Streaming (Input)** | ✅ (Some APIs/integrations) | ❌ | ❌ (Via Amazon Transcribe) | ❌ | ❓ |❌ (Via services like Amazon Transcribe) |
| **Transcription** | ✅ (Some APIs/integrations) | ❌ | ❌ (Via Amazon Transcribe) | ❌ | ❓ | ❌ (Via Amazon Transcribe) |
| **Persistent session (MapDB)** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅  |
| **Agents as Tool/Function** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Interoperability (A2A)** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Interoperability (Tools/Functions)** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Interoperability (Agents as Tool/Function)** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Agent Workflow** | ✅ | ✅ | ✅   | ✅ | ✅ | ✅ |
| **Parallel Agents** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅   |
| **Sequential Agents** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Agent Orchestration** | ✅ | ✅ | ✅   | ✅ | ✅ | ✅ |
| **Hierarchical Task Decomposition** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅   |


# Core Differences

## Persistent session storage added, 
 
| Store | Chat | Stream | Artifact | 
| :--- | :--- | :--- | :--- | 
| **MapDB** | ✅ | ✅ | ✅ |  
| **MongoDB** | ✅ | ✅ | ❌ |  
| **Postgres** | ✅ | ✅ | ❌ |  

### MapDbSessionService("map.db")

```
    public BaseSessionService sessionService() {

        try {
            // TODO: Add logic to select service based on config (e.g., DB URL)
            log.info("Using MapDbSessionService");
            return new MapDbSessionService("map.db");
        } catch (Exception ex) {
            java.util.logging.Logger.getLogger(AdkWebServer.class.getName()).log(Level.SEVERE, null, ex);
        }

        // TODO: Add logic to select service based on config (e.g., DB URL)
        log.info("Using InMemorySessionService");
        return new InMemorySessionService();
    }
```

## Ollama API Supported, 

### OllamaBaseLM("qwen3:0.6b")
```
    LlmAgent coordinator = LlmAgent.builder()
                .name("Coordinator")
                 . model(new com.google.adk.models.OllamaBaseLM("qwen3:0.6b"))//
                .instruction("You are an assistant. Delegate requests to appropriate agent")
                .description("Main coordinator.")
                .build();
```

## Secondary Auth Over Azure API

### RedbusADG("40")

```
LlmAgent.builder()
            .name(NAME)
            .model(new com.google.adk.models.OllamaBaseLM("qwen3:0.6b"))//.model(new RedbusADG("40"))
            .description("Agent to calculate trigonometric functions (sine, cosine, tangent) for given angles.") // Updated description
            .instruction(
                "You are a helpful agent who can calculate trigonometric functions (sine, cosine, and"
                    + " tangent). Use the provided tools to perform these calculations."
                    + " When the user provides an angle, identify the value and the unit (degrees or radians)."
                    + " Call the appropriate tool based on the requested function (sin, cos, tan) and provide the angle value and unit."
                    + " Ensure the angle unit is explicitly passed to the tool as 'degrees' or 'radians'.") // Updated instruction
            .tools(
                // Register the new trigonometry tools
                FunctionTool.create(TrigonometryAgent.class, "calculateSine"),
                FunctionTool.create(TrigonometryAgent.class, "calculateCosine"),
                FunctionTool.create(TrigonometryAgent.class, "calculateTangent")
                // Removed FunctionTool.create for getCurrentTime and getWeather
                )
            .build();
```



# Agent Development Kit (ADK) for Java

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/com.google.adk/google-adk)](https://search.maven.org/artifact/com.google.adk/google-adk)
[![r/agentdevelopmentkit](https://img.shields.io/badge/Reddit-r%2Fagentdevelopmentkit-FF4500?style=flat&logo=reddit&logoColor=white)](https://www.reddit.com/r/agentdevelopmentkit/)

<html>
    <h2 align="center">
      <img src="https://raw.githubusercontent.com/google/adk-python/main/assets/agent-development-kit.png" width="256"/>
    </h2>
    <h3 align="center">
      An open-source, code-first Java toolkit for building, evaluating, and deploying sophisticated AI agents with flexibility and control.
    </h3>
    <h3 align="center">
      Important Links:
      <a href="https://google.github.io/adk-docs/">Docs</a> &
      <a href="https://github.com/google/adk-samples">Samples</a> &
      <a href="https://github.com/google/adk-python">Python ADK</a>.
    </h3>
</html>

Agent Development Kit (ADK) is designed for developers seeking fine-grained
control and flexibility when building advanced AI agents that are tightly
integrated with services in Google Cloud. It allows you to define agent
behavior, orchestration, and tool use directly in code, enabling robust
debugging, versioning, and deployment anywhere – from your laptop to the cloud.

--------------------------------------------------------------------------------

## ✨ Key Features

-   **Rich Tool Ecosystem**: Utilize pre-built tools, custom functions, OpenAPI
    specs, or integrate existing tools to give agents diverse capabilities, all
    for tight integration with the Google ecosystem.

-   **Code-First Development**: Define agent logic, tools, and orchestration
    directly in Java for ultimate flexibility, testability, and versioning.

-   **Modular Multi-Agent Systems**: Design scalable applications by composing
    multiple specialized agents into flexible hierarchies.

## 🚀 Installation

If you're using Maven, add the following to your dependencies:

<!-- {x-version-start:google-adk:released} -->

```xml
<dependency>
  <groupId>com.google.adk</groupId>
  <artifactId>google-adk</artifactId>
  <version>0.2.0</version>
</dependency>
<!-- Dev UI -->
<dependency>
    <groupId>com.google.adk</groupId>
    <artifactId>google-adk-dev</artifactId>
    <version>0.2.0</version>
</dependency>
```

<!-- {x-version-end} -->

To instead use an unreleased version, you could use <https://jitpack.io/#google/adk-java/>;
see <https://github.com/enola-dev/LearningADK#jitpack> for an example illustrating this.

## 📚 Documentation

For building, evaluating, and deploying agents by follow the Java
documentation & samples:

*   **[Documentation](https://google.github.io/adk-docs)**
*   **[Samples](https://github.com/google/adk-samples)**

## 🏁 Feature Highlight

### Same Features & Familiar Interface As Python ADK:

```java
import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.GoogleSearchTool;

LlmAgent rootAgent = LlmAgent.builder()
    .name("search_assistant")
    .description("An assistant that can search the web.")
    .model("gemini-2.0-flash") // Or your preferred models
    .instruction("You are a helpful assistant. Answer user questions using Google Search when needed.")
    .tools(new GoogleSearchTool())
    .build();
```

### Development UI

Same as the beloved Python Development UI.
A built-in development UI to help you test, evaluate, debug, and showcase your agent(s).
<img src="https://raw.githubusercontent.com/google/adk-python/main/assets/adk-web-dev-ui-function-call.png"/>

### Evaluate Agents

Coming soon...

## 🤖 A2A and ADK integration

For remote agent-to-agent communication, ADK integrates with the
[A2A protocol](https://github.com/google/A2A/).
Examples coming soon...

## 🤝 Contributing

We welcome contributions from the community! Whether it's bug reports, feature
requests, documentation improvements, or code contributions, please see our
[**Contributing Guidelines**](./CONTRIBUTING.md) to get started.

## 📄 License

This project is licensed under the Apache 2.0 License - see the
[LICENSE](LICENSE) file for details.

## Preview

This feature is subject to the "Pre-GA Offerings Terms" in the General Service
Terms section of the
[Service Specific Terms](https://cloud.google.com/terms/service-terms#1). Pre-GA
features are available "as is" and might have limited support. For more
information, see the
[launch stage descriptions](https://cloud.google.com/products?hl=en#product-launch-stages).

--------------------------------------------------------------------------------

*Happy Agent Building!*
