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

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.IntentClassifier;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.VoiceAgent;
import com.google.adk.agents.VoiceConfig;
import com.google.adk.agents.VoiceMode;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import com.google.adk.web.AdkWebServer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Combined Live Audio + TTS Voice Agent demo.
 *
 * <p>This example registers TWO agents with the ADK Dev UI:
 *
 * <ol>
 *   <li><b>live_voice_agent</b> — Uses Gemini Live model for real-time bidirectional audio
 *       (mic → Gemini → speaker directly). This is the native live streaming approach.
 *   <li><b>tts_voice_agent</b> — Uses the VoiceAgent with standard Gemini + TTS/STT routing.
 *       Navigation commands are handled instantly; complex queries go to Gemini and the text
 *       response is sent back (with TTS audio if a TTS server is configured).
 * </ol>
 *
 * <h2>How to run:</h2>
 *
 * <pre>
 * export GOOGLE_API_KEY=your-gemini-api-key
 * cd adk-java
 * ./mvnw install -pl core,dev -DskipTests -q
 * ./mvnw compile exec:java -pl contrib/samples/voice-agent-demo -Dexec.mainClass=com.example.voiceagentdemo.LiveAndTtsVoiceAgent
 * </pre>
 *
 * <p>Then open http://localhost:8080 in your browser. You'll see both agents in the Dev UI dropdown.
 *
 * <ul>
 *   <li><b>live_voice_agent</b>: Click the microphone button → speak → hear Gemini respond in
 *       real-time audio
 *   <li><b>tts_voice_agent</b>: Type text → navigation commands handled instantly, complex queries
 *       go to Gemini. If TTS endpoint is configured, audio comes back too.
 * </ul>
 *
 * <h2>Optional: Add local TTS/STT servers</h2>
 *
 * <pre>
 * # STT server (Whisper)
 * docker run -p 8000:8000 fedirz/faster-whisper-server
 * export ADK_STT_ENDPOINT=http://localhost:8000
 *
 * # TTS server (Piper/AllTalk)
 * docker run -p 8001:8001 rhasspy/piper-http
 * export ADK_TTS_ENDPOINT=http://localhost:8001
 * </pre>
 *
 * @author Sandeep Belgavi
 * @since 2026-08-04
 */
public final class LiveAndTtsVoiceAgent {

  // ============================================================
  // Agent 1: LIVE VOICE (Gemini Live model — real-time bidi audio)
  // ============================================================

  /**
   * Live agent using gemini-2.5-flash-live-001 for real-time bidirectional audio streaming. The Dev
   * UI mic button sends audio → Gemini processes → audio response streams back directly.
   */
  public static final BaseAgent LIVE_VOICE_AGENT =
      LlmAgent.builder()
          .name("live_voice_agent")
          .model("gemini-2.5-flash-live-001")
          .description(
              "Real-time voice agent using Gemini Live. Click the mic button to talk and"
                  + " hear responses in real-time.")
          .instruction(
              """
              You are a friendly, conversational voice assistant. You speak naturally and concisely.

              You can help with:
              - Answering questions on any topic
              - Weather information (use the getWeather tool)
              - Current time (use the getCurrentTime tool)
              - General knowledge, trivia, and explanations
              - Creative tasks like stories, jokes, and poems

              Keep responses SHORT (1-3 sentences) since this is voice conversation.
              Be warm, natural, and engaging. Use contractions like "I'm", "it's", "don't".
              If you don't know something, say so honestly.
              """)
          .tools(
              FunctionTool.create(LiveAndTtsVoiceAgent.class, "getWeather"),
              FunctionTool.create(LiveAndTtsVoiceAgent.class, "getCurrentTime"))
          .build();

  // ============================================================
  // Agent 2: TTS VOICE (Standard Gemini + VoiceAgent routing)
  // ============================================================

  /**
   * TTS-routed agent using standard Gemini model with the VoiceAgent orchestrator. Navigation
   * commands are handled instantly. Complex queries go to Gemini and responses can optionally be
   * synthesized to audio via a local TTS server.
   */
  public static final BaseAgent TTS_VOICE_AGENT = buildTtsVoiceAgent();

  private static BaseAgent buildTtsVoiceAgent() {
    // Inner reasoning agent (standard Gemini, not live)
    LlmAgent reasoningAgent =
        LlmAgent.builder()
            .name("gemini_reasoner")
            .description("Gemini-powered reasoning agent for complex queries.")
            .model("gemini-2.5-flash")
            .instruction(
                """
                You are a helpful voice assistant. Keep responses concise and conversational
                (1-3 sentences) since they may be spoken aloud via TTS.

                You can help with:
                - Answering questions on any topic
                - Weather information (use the getWeather tool)
                - Current time (use the getCurrentTime tool)
                - General knowledge and explanations
                - Creative tasks

                Respond naturally as if speaking to someone.
                """)
            .tools(
                FunctionTool.create(LiveAndTtsVoiceAgent.class, "getWeather"),
                FunctionTool.create(LiveAndTtsVoiceAgent.class, "getCurrentTime"))
            .build();

    // Navigation commands (handled without LLM call)
    Map<String, String> navCommands = new HashMap<>();
    navCommands.put("help",
        "I can answer questions, check weather, tell the time, or just chat. "
            + "Say 'next' to continue, 'back' to go back, or 'stop' to end.");
    navCommands.put("next", "Moving forward. What would you like to know?");
    navCommands.put("back", "Going back. What would you like to revisit?");
    navCommands.put("stop", "Stopping. Goodbye!");
    navCommands.put("repeat", "Let me repeat that for you.");
    navCommands.put("pause", "Paused. Say something when you're ready.");
    navCommands.put("hello", "Hello! How can I help you today?");
    navCommands.put("hi", "Hi there! What can I do for you?");
    navCommands.put("thanks", "You're welcome! Anything else I can help with?");
    navCommands.put("thank you", "Happy to help! Need anything else?");

    // Voice configuration
    String sttEndpoint = System.getenv("ADK_STT_ENDPOINT");
    String ttsEndpoint = System.getenv("ADK_TTS_ENDPOINT");

    VoiceConfig voiceConfig =
        VoiceConfig.builder()
            .voiceMode(VoiceMode.AUTO)
            .language("en")
            .sttEndpoint(sttEndpoint != null ? sttEndpoint : "")
            .ttsEndpoint(ttsEndpoint != null ? ttsEndpoint : "")
            .ttsVoice("alloy")
            .ttsModel("tts-1")
            .llmModel("gemini-2.5-flash")
            .navigationCommands(
                List.of("help", "next", "back", "stop", "repeat", "pause",
                    "hello", "hi", "thanks", "thank you"))
            .build();

    // Build VoiceAgent
    return VoiceAgent.builder()
        .name("tts_voice_agent")
        .description(
            "Voice agent with TTS routing. Navigation commands (help, next, back, stop) "
                + "are instant. Complex queries go to Gemini. "
                + "Set ADK_TTS_ENDPOINT for audio output.")
        .voiceConfig(voiceConfig)
        .delegate(reasoningAgent)
        .intentClassifier(IntentClassifier.keyword(voiceConfig.getNavigationCommands()))
        .navigationCommands(navCommands)
        .build();
  }

  // ============================================================
  // Shared Tools (used by both agents)
  // ============================================================

  /** Gets weather for a location (mock data for demo). */
  public static Map<String, String> getWeather(
      @Schema(name = "location", description = "City name to get weather for") String location) {

    Map<String, Map<String, String>> weatherData =
        Map.of(
            "new york", Map.of("temp", "72°F (22°C)", "condition", "Partly cloudy",
                "summary", "New York is partly cloudy at 72°F."),
            "london", Map.of("temp", "59°F (15°C)", "condition", "Rainy",
                "summary", "London is rainy at 59°F. Bring an umbrella!"),
            "tokyo", Map.of("temp", "68°F (20°C)", "condition", "Clear",
                "summary", "Tokyo is clear and pleasant at 68°F."),
            "mumbai", Map.of("temp", "88°F (31°C)", "condition", "Humid",
                "summary", "Mumbai is hot and humid at 88°F."),
            "bangalore", Map.of("temp", "75°F (24°C)", "condition", "Partly cloudy",
                "summary", "Bangalore is mild at 75°F with some clouds."),
            "san francisco", Map.of("temp", "65°F (18°C)", "condition", "Foggy",
                "summary", "San Francisco is foggy at 65°F. Classic!"),
            "paris", Map.of("temp", "70°F (21°C)", "condition", "Sunny",
                "summary", "Paris is sunny and beautiful at 70°F."),
            "sydney", Map.of("temp", "77°F (25°C)", "condition", "Sunny",
                "summary", "Sydney is sunny at 77°F. Great day!"));

    String key = location.toLowerCase().trim();
    Map<String, String> data = weatherData.get(key);

    if (data != null) {
      return data;
    }
    return Map.of(
        "temp", "N/A",
        "condition", "Unknown",
        "summary", "I don't have weather data for " + location
            + ". Try: New York, London, Tokyo, Mumbai, Bangalore, San Francisco, Paris, or Sydney.");
  }

  /** Gets the current time. */
  public static Map<String, String> getCurrentTime() {
    LocalDateTime now = LocalDateTime.now();
    return Map.of(
        "time", now.format(DateTimeFormatter.ofPattern("h:mm a")),
        "date", now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")),
        "summary",
            "It's " + now.format(DateTimeFormatter.ofPattern("h:mm a"))
                + " on " + now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")));
  }

  // ============================================================
  // Main — starts the ADK Dev UI with both agents
  // ============================================================

  public static void main(String[] args) {
    System.out.println("╔══════════════════════════════════════════════════════════════╗");
    System.out.println("║      Live Audio + TTS Voice Agent Demo (ADK-Java)           ║");
    System.out.println("╠══════════════════════════════════════════════════════════════╣");
    System.out.println("║  Starting Dev UI at http://localhost:8080                    ║");
    System.out.println("║                                                              ║");
    System.out.println("║  Agents available:                                           ║");
    System.out.println("║  1. live_voice_agent  → Gemini Live bidi audio (use mic)     ║");
    System.out.println("║  2. tts_voice_agent   → Gemini + TTS routing (type/speak)    ║");
    System.out.println("║                                                              ║");
    System.out.println("║  For full TTS, also set:                                     ║");
    System.out.println("║    export ADK_TTS_ENDPOINT=http://localhost:8001              ║");
    System.out.println("║    export ADK_STT_ENDPOINT=http://localhost:8000              ║");
    System.out.println("╚══════════════════════════════════════════════════════════════╝");

    // Start the Dev UI with both agents
    // The Dev UI will show them in a dropdown — user can switch between them
    AdkWebServer.start(LIVE_VOICE_AGENT, TTS_VOICE_AGENT);
  }
}
