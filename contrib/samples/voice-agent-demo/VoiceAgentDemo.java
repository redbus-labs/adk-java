/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.voiceagentdemo;

import com.google.adk.agents.IntentClassifier;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.agents.VoiceAgent;
import com.google.adk.agents.VoiceConfig;
import com.google.adk.agents.VoiceMode;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * End-to-end demo of the VoiceAgent system using Gemini as the LLM backend.
 *
 * <p>This demo runs in TEXT mode (simulating voice) so you don't need actual TTS/STT servers. It
 * demonstrates the full routing logic:
 *
 * <ul>
 *   <li>Navigation commands (next, back, help, stop, repeat) → handled instantly without LLM
 *   <li>Complex queries → routed to Gemini for full reasoning
 * </ul>
 *
 * <h2>How to run:</h2>
 *
 * <pre>
 * export GOOGLE_API_KEY=your-gemini-api-key
 * cd contrib/samples/voice-agent-demo
 * ../../../mvnw compile exec:java -pl contrib/samples/voice-agent-demo
 * </pre>
 *
 * <p>Or for interactive mode:
 *
 * <pre>
 * ../../../mvnw compile exec:java -pl contrib/samples/voice-agent-demo -Dexec.args="--interactive"
 * </pre>
 *
 * @author Sandeep Belgavi
 * @since 2026-08-04
 */
public final class VoiceAgentDemo {

  private final Runner runner;
  private final String userId;
  private final String sessionId;

  private VoiceAgentDemo() {
    String appName = "voice-agent-demo";
    this.userId = "demo-user";
    this.sessionId = UUID.randomUUID().toString();

    // 1. Build the inner Gemini-based reasoning agent
    LlmAgent reasoningAgent =
        LlmAgent.builder()
            .name("gemini_reasoner")
            .description("A Gemini-powered reasoning agent for complex queries.")
            .model("gemini-2.5-flash")
            .instruction(
                """
                You are a helpful voice assistant. Keep your responses concise and conversational
                since they will be spoken aloud. Aim for 1-3 sentences unless the user asks for
                detailed information.

                You can help with:
                - Answering questions on any topic
                - Providing explanations
                - Creative tasks (stories, poems, ideas)
                - General knowledge and trivia
                - Simple calculations and reasoning

                Always respond naturally as if you're speaking to someone.
                """)
            .build();

    // 2. Define navigation commands (handled without hitting the LLM)
    Map<String, String> navCommands = new HashMap<>();
    navCommands.put("help", "Available commands: say 'next' to continue, 'back' to go back, "
        + "'stop' to end, 'repeat' to hear the last response again, or ask me anything.");
    navCommands.put("next", "Moving to the next item. What would you like to know?");
    navCommands.put("back", "Going back. What would you like to revisit?");
    navCommands.put("stop", "Stopping. Goodbye!");
    navCommands.put("repeat", "I'll repeat my last response for you.");
    navCommands.put("pause", "Paused. Say 'next' or ask me something when you're ready.");
    navCommands.put("hello", "Hello! How can I help you today?");
    navCommands.put("hi", "Hi there! What can I do for you?");

    // 3. Configure voice settings
    //    In a real setup, you'd set sttEndpoint and ttsEndpoint to local servers.
    //    For this demo, we run without them (text-only mode with routing logic).
    VoiceConfig voiceConfig =
        VoiceConfig.builder()
            .voiceMode(VoiceMode.AUTO) // Automatically classify input
            .language("en")
            .llmModel("gemini-2.5-flash")
            .navigationCommands(List.of("help", "next", "back", "stop", "repeat", "pause"))
            .build();

    // 4. Build the VoiceAgent
    VoiceAgent voiceAgent =
        VoiceAgent.builder()
            .name("voice_assistant")
            .description("Voice-enabled assistant with navigation and full Gemini reasoning")
            .voiceConfig(voiceConfig)
            .delegate(reasoningAgent)
            .intentClassifier(IntentClassifier.keyword(voiceConfig.getNavigationCommands()))
            .navigationCommands(navCommands)
            .build();

    // 5. Create the runner
    InMemorySessionService sessionService = new InMemorySessionService();
    this.runner =
        new Runner(
            voiceAgent,
            appName,
            new InMemoryArtifactService(),
            sessionService,
            new InMemoryMemoryService());

    ConcurrentMap<String, Object> initialState = new ConcurrentHashMap<>();
    var unused =
        sessionService.createSession(appName, userId, initialState, sessionId).blockingGet();
  }

  private void run(String prompt) {
    System.out.println("\n\033[36mYou>\033[0m " + prompt);

    Content userMessage =
        Content.builder()
            .role("user")
            .parts(ImmutableList.of(Part.builder().text(prompt).build()))
            .build();

    RunConfig runConfig = RunConfig.builder().build();
    Flowable<Event> eventStream =
        this.runner.runAsync(this.userId, this.sessionId, userMessage, runConfig);
    List<Event> agentEvents = Lists.newArrayList(eventStream.blockingIterable());

    StringBuilder sb = new StringBuilder();
    for (Event event : agentEvents) {
      String content = event.stringifyContent().stripTrailing();
      if (!content.isEmpty()) {
        sb.append(content);
      }
    }

    String response = sb.toString().trim();
    if (!response.isEmpty()) {
      System.out.println("\033[33mAssistant>\033[0m " + response);
    }
  }

  private void runInteractive() {
    System.out.println("╔══════════════════════════════════════════════════════════════╗");
    System.out.println("║            Voice Agent Demo (Gemini + ADK-Java)             ║");
    System.out.println("╠══════════════════════════════════════════════════════════════╣");
    System.out.println("║  Navigation commands: help, next, back, stop, repeat, pause ║");
    System.out.println("║  Complex queries: anything else goes to Gemini              ║");
    System.out.println("║  Type 'quit' or 'exit' to end                              ║");
    System.out.println("╚══════════════════════════════════════════════════════════════╝");
    System.out.println();

    Scanner scanner = new Scanner(System.in);
    while (true) {
      System.out.print("\033[36mYou>\033[0m ");
      String input = scanner.nextLine().trim();
      if (input.isEmpty()) continue;
      if (input.equalsIgnoreCase("quit") || input.equalsIgnoreCase("exit")) {
        System.out.println("\033[33mAssistant>\033[0m Goodbye! 👋");
        break;
      }
      run(input);
    }
    scanner.close();
  }

  public static void main(String[] args) {
    System.out.println("Initializing Voice Agent Demo...");

    // Check for API key
    String apiKey = System.getenv("GOOGLE_API_KEY");
    if (apiKey == null || apiKey.isEmpty()) {
      System.err.println(
          "ERROR: GOOGLE_API_KEY environment variable is not set.\n"
              + "Get one from https://aistudio.google.com/apikey\n"
              + "Then run: export GOOGLE_API_KEY=your-key-here");
      System.exit(1);
    }

    VoiceAgentDemo demo = new VoiceAgentDemo();

    if (args.length > 0 && args[0].equals("--interactive")) {
      demo.runInteractive();
    } else {
      // Demo mode: run a few examples showing routing behavior
      System.out.println("\n--- Navigation Commands (handled instantly, no LLM call) ---");
      demo.run("help");
      demo.run("next");
      demo.run("hello");

      System.out.println("\n--- Complex Queries (routed to Gemini) ---");
      demo.run("What is the speed of light and why can nothing travel faster?");
      demo.run("Write me a haiku about Java programming");
      demo.run("What's 127 times 43?");

      System.out.println("\n--- Mixed Usage ---");
      demo.run("stop");
      demo.run("Explain quantum entanglement in simple terms");

      System.out.println("\n\nDone! Run with --interactive for a chat session.");
    }
  }
}
