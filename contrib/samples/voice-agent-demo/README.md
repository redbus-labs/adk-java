# Voice Agent Demo (Gemini + ADK-Java)

End-to-end demo of the VoiceAgent system showing:
- **Navigation commands** handled instantly (no LLM call) — `help`, `next`, `back`, `stop`, `repeat`, `pause`
- **Complex queries** routed to Gemini for full reasoning

## Prerequisites

1. A Gemini API key from [AI Studio](https://aistudio.google.com/apikey)
2. Java 17+

## Quick Start

```bash
# 1. Set your API key
export GOOGLE_API_KEY=your-gemini-api-key

# 2. Build the core module first (since we have local changes)
cd /path/to/adk-java
./mvnw install -pl core -DskipTests -q

# 3. Run the demo (auto mode — shows routing in action)
./mvnw compile exec:java -pl contrib/samples/voice-agent-demo

# 4. Or run interactive chat mode
./mvnw compile exec:java -pl contrib/samples/voice-agent-demo -Dexec.args="--interactive"
```

## What Happens

```
--- Navigation Commands (handled instantly, no LLM call) ---
You> help
Assistant> Available commands: say 'next' to continue, 'back' to go back, ...

You> next
Assistant> Moving to the next item. What would you like to know?

--- Complex Queries (routed to Gemini) ---
You> What is the speed of light?
Assistant> The speed of light is approximately 299,792,458 meters per second...
```

## Architecture

```
User Input (text) 
    ↓
IntentClassifier (keyword matching)
    ↓
┌─────────────────────────────────────┐
│ VOICE_NAVIGATION?                   │ → VoiceNavigationHandler → instant response
│ VOICE_FULL?                         │ → Gemini LLM → response (+ TTS if configured)
└─────────────────────────────────────┘
```

## Adding TTS/STT (Optional)

To add actual voice I/O, set these environment variables:

```bash
# STT - any OpenAI-compatible /v1/audio/transcriptions endpoint
export ADK_STT_ENDPOINT=http://localhost:8000

# TTS - any OpenAI-compatible /v1/audio/speech endpoint  
export ADK_TTS_ENDPOINT=http://localhost:8001
```

Local server options:
- **STT**: `docker run -p 8000:8000 fedirz/faster-whisper-server`
- **TTS**: Piper, AllTalk, or Kokoro with OpenAI-compatible API

Then update `VoiceConfig` in the demo to include the endpoints:
```java
VoiceConfig voiceConfig = VoiceConfig.builder()
    .voiceMode(VoiceMode.AUTO)
    .sttEndpoint("http://localhost:8000")
    .ttsEndpoint("http://localhost:8001")
    .ttsVoice("alloy")
    .build();
```
