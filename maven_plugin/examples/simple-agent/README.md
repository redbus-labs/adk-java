# Simple ADK Agent Example

This example demonstrates how to create and run custom ADK agents using the Maven plugin.

## What's Included

- **SimpleAgentProvider**: An implementation of `AgentProvider` that creates three example agents:
  - `chat_assistant`: A friendly general-purpose assistant
  - `search_agent`: An agent with Google Search capabilities
  - `code_helper`: A coding assistant

## How to Run

1. **Compile the project:**
   ```bash
   mvn compile
   ```

2. **Start the ADK Web Server:**
   ```bash
   mvn google-adk:web -Dagents=com.example.SimpleAgentProvider.INSTANCE
   ```

3. **Open your browser:**
   Navigate to http://localhost:8000

4. **Try the agents:**
   - Select an agent from the dropdown
   - Start a conversation
   - Test different agents to see their capabilities

## Customizing

You can modify `SimpleAgentProvider.java` to:
- Add more agents
- Change agent instructions
- Add different tools
- Use different models
- Configure agent-specific settings

Example modifications:

### Add a New Agent
```java
private BaseAgent createMathTutor() {
  return LlmAgent.builder()
      .name("math_tutor")
      .description("A mathematics tutoring agent")
      .model("gemini-2.0-flash")
      .instruction(
          "You are a patient math tutor. "
              + "Help students understand mathematical concepts step by step.")
      .build();
}
```

Then add it to the `listAgents()` method and `getAgent()` switch statement:
```java
@Override
@Nonnull
public ImmutableList<String> listAgents() {
  return ImmutableList.of("chat_assistant", "search_agent", "code_helper", "math_tutor");
}

@Override
public BaseAgent getAgent(String name) {
  switch (name) {
    case "chat_assistant":
      return createChatAssistant();
    case "search_agent":
      return createSearchAgent();
    case "code_helper":
      return createCodeHelper();
    case "math_tutor":
      return createMathTutor();  // Add the new agent
    default:
      throw new NoSuchElementException("Agent not found: " + name);
  }
}
```

### Configure Different Ports
```bash
mvn google-adk:web -Dagents=com.example.SimpleAgentProvider.INSTANCE -Dport=9090
```

## Next Steps

- Explore the ADK documentation for more advanced features
- Add custom tools to your agents
- Implement agent hierarchies with sub-agents
- Try different models (Claude, custom models, etc.)
- Add memory and state management