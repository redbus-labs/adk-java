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

package com.google.adk.agents;

import com.google.adk.events.Event;
import com.google.adk.transcription.TranscriptionConfig;
import com.google.adk.transcription.metrics.VoiceMetrics;
import com.google.adk.transcription.strategy.OllamaWhisperSttService;
import com.google.adk.transcription.tts.OpenAiCompatibleTtsService;
import com.google.adk.transcription.tts.TtsConfig;
import com.google.adk.transcription.tts.TtsService;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Voice-enabled agent orchestrator that extends {@link BaseAgent} and wraps a delegate {@link
 * LlmAgent} for full reasoning capabilities.
 *
 * <p>The VoiceAgent handles the complete voice interaction pipeline:
 *
 * <ol>
 *   <li>Receives audio input (inline data) or text from the invocation context
 *   <li>Transcribes audio via STT (OllamaWhisperSttService)
 *   <li>Classifies intent using the configured {@link IntentClassifier}
 *   <li>Routes to the appropriate handler:
 *       <ul>
 *         <li>{@link VoiceMode#VOICE_NAVIGATION}: fast path through {@link VoiceNavigationHandler}
 *         <li>{@link VoiceMode#VOICE_FULL}: delegates to the inner LlmAgent, then TTS
 *         <li>{@link VoiceMode#TEXT_ONLY}: delegates to the inner LlmAgent without TTS
 *       </ul>
 *   <li>Synthesizes TTS audio for voice responses
 *   <li>Emits events with audio content (Part.fromBytes)
 * </ol>
 *
 * @author Sandeep Belgavi
 * @since 2026-08-04
 */
public class VoiceAgent extends BaseAgent {

  private static final Logger logger = LoggerFactory.getLogger(VoiceAgent.class);

  private static final String AUDIO_MIME_PREFIX = "audio/";
  private static final String DEFAULT_AUDIO_MIME_TYPE = "audio/wav";

  private final VoiceConfig voiceConfig;
  private final LlmAgent delegate;
  private final IntentClassifier intentClassifier;
  private final VoiceNavigationHandler navigationHandler;
  private final OllamaWhisperSttService sttService;
  private final TtsService ttsService;
  private final TtsConfig ttsConfig;

  /**
   * Creates a VoiceAgent via the builder.
   *
   * @param builder the builder containing all configuration
   */
  private VoiceAgent(Builder builder) {
    super(
        builder.name,
        builder.description,
        builder.subAgents,
        builder.beforeAgentCallback,
        builder.afterAgentCallback);
    this.voiceConfig = builder.voiceConfig;
    this.delegate = builder.delegate;
    this.intentClassifier = builder.intentClassifier;
    this.navigationHandler = builder.navigationHandler;

    // Initialize STT service
    String sttEndpoint = voiceConfig.getSttEndpoint();
    if (sttEndpoint != null && !sttEndpoint.isEmpty()) {
      this.sttService = new OllamaWhisperSttService(sttEndpoint, voiceConfig.getSttModel(), null);
    } else {
      this.sttService = null;
    }

    // Initialize TTS service
    String ttsEndpoint = voiceConfig.getTtsEndpoint();
    if (ttsEndpoint != null && !ttsEndpoint.isEmpty()) {
      this.ttsService = new OpenAiCompatibleTtsService(ttsEndpoint, null);
      this.ttsConfig =
          TtsConfig.builder()
              .endpoint(ttsEndpoint)
              .voice(voiceConfig.getTtsVoice())
              .model(voiceConfig.getTtsModel())
              .language(voiceConfig.getLanguage())
              .speed(voiceConfig.getTtsSpeed())
              .build();
    } else {
      this.ttsService = null;
      this.ttsConfig = null;
    }

    logger.info(
        "VoiceAgent '{}' initialized with mode={}, delegate='{}'",
        name(),
        voiceConfig.getVoiceMode(),
        delegate.name());
  }

  /**
   * Creates a new builder for VoiceAgent.
   *
   * @return a new Builder instance
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Returns the voice configuration. */
  public VoiceConfig voiceConfig() {
    return voiceConfig;
  }

  /** Returns the delegate LlmAgent. */
  public LlmAgent delegate() {
    return delegate;
  }

  /** Returns the intent classifier. */
  public IntentClassifier intentClassifier() {
    return intentClassifier;
  }

  @Override
  protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
    return Flowable.defer(() -> processInput(invocationContext));
  }

  @Override
  protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
    // Live mode delegates to the same logic as async for now
    return runAsyncImpl(invocationContext);
  }

  /**
   * Main processing pipeline: extract input → STT (if audio) → classify → route → TTS (if voice).
   */
  private Flowable<Event> processInput(InvocationContext invocationContext) {
    Optional<Content> userContentOpt = invocationContext.userContent();
    if (userContentOpt.isEmpty()) {
      logger.debug("No user content in invocation context");
      return Flowable.empty();
    }

    Content userContent = userContentOpt.get();

    // Extract text or audio from user content
    Optional<byte[]> audioData = extractAudioData(userContent);
    Optional<String> textData = extractTextData(userContent);

    if (audioData.isPresent()) {
      // Audio input path: STT → classify → route
      return transcribeAndRoute(audioData.get(), invocationContext);
    } else if (textData.isPresent()) {
      // Text input path: classify → route (no STT needed)
      return classifyAndRoute(textData.get(), invocationContext);
    } else {
      logger.debug("No audio or text content found in user content");
      return Flowable.empty();
    }
  }

  /** Transcribes audio to text, then classifies and routes. */
  private Flowable<Event> transcribeAndRoute(
      byte[] audioData, InvocationContext invocationContext) {
    if (sttService == null) {
      logger.warn("STT service not configured, cannot process audio input");
      return Flowable.empty();
    }

    TranscriptionConfig sttConfig =
        TranscriptionConfig.builder()
            .endpoint(voiceConfig.getSttEndpoint())
            .language(voiceConfig.getLanguage())
            .build();

    long sttStartNanos = System.nanoTime();

    return sttService
        .transcribeAsync(audioData, sttConfig)
        .doOnSuccess(
            result -> {
              long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sttStartNanos);
              VoiceMetrics.getInstance().recordSttCall(latencyMs, true);
            })
        .doOnError(
            error -> {
              long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sttStartNanos);
              VoiceMetrics.getInstance().recordSttCall(latencyMs, false);
            })
        .flatMapPublisher(
            result -> {
              String transcribedText = result.getText();
              logger.debug("STT transcription result: '{}'", transcribedText);

              if (transcribedText == null || transcribedText.isEmpty()) {
                logger.debug("Empty transcription, returning empty");
                return Flowable.empty();
              }

              return classifyAndRoute(transcribedText, invocationContext);
            });
  }

  /** Classifies intent and routes to the appropriate handler. */
  private Flowable<Event> classifyAndRoute(String userText, InvocationContext invocationContext) {
    VoiceMode effectiveMode = resolveVoiceMode(userText);

    logger.debug("Resolved voice mode for '{}': {}", userText, effectiveMode);

    switch (effectiveMode) {
      case VOICE_NAVIGATION:
        return handleNavigation(userText, invocationContext);
      case VOICE_FULL:
        return handleFullVoice(userText, invocationContext);
      case TEXT_ONLY:
        return handleTextOnly(invocationContext);
      default:
        // AUTO should have been resolved already, fallback to VOICE_FULL
        return handleFullVoice(userText, invocationContext);
    }
  }

  /** Resolves the effective voice mode, running intent classification for AUTO mode. */
  private VoiceMode resolveVoiceMode(String userText) {
    VoiceMode configuredMode = voiceConfig.getVoiceMode();

    if (configuredMode != VoiceMode.AUTO) {
      return configuredMode;
    }

    // AUTO mode: use the intent classifier to determine routing
    long classifierStartNanos = System.nanoTime();
    VoiceMode result = intentClassifier.classify(userText, voiceConfig);
    long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - classifierStartNanos);
    VoiceMetrics.getInstance().recordIntentClassification(latencyMs, result);
    return result;
  }

  /** Handles VOICE_NAVIGATION: lookup command → TTS → emit audio event. */
  private Flowable<Event> handleNavigation(String userText, InvocationContext invocationContext) {
    Optional<String> responseText = navigationHandler.handle(userText);

    if (responseText.isEmpty()) {
      // No matching command found, fall through to full voice
      logger.debug("No navigation match for '{}', falling through to VOICE_FULL", userText);
      return handleFullVoice(userText, invocationContext);
    }

    String response = responseText.get();
    logger.debug("Navigation response for '{}': '{}'", userText, response);

    // Synthesize TTS and emit audio event
    return synthesizeAndEmit(response, invocationContext);
  }

  /** Handles VOICE_FULL: delegate to LlmAgent → extract text → TTS → emit audio event. */
  private Flowable<Event> handleFullVoice(String userText, InvocationContext invocationContext) {
    // Delegate to the inner LlmAgent
    return delegate
        .runAsync(invocationContext)
        .toList()
        .flatMapPublisher(
            events -> {
              // Extract text from the delegate's response events
              String responseText = extractResponseText(events);

              if (responseText == null || responseText.isEmpty()) {
                // No text response, just forward the events as-is
                return Flowable.fromIterable(events);
              }

              // Synthesize TTS audio and append to the last event
              if (ttsService != null && ttsConfig != null) {
                return Flowable.fromIterable(events)
                    .concatWith(synthesizeAndEmit(responseText, invocationContext));
              } else {
                return Flowable.fromIterable(events);
              }
            });
  }

  /** Handles TEXT_ONLY: simply delegates to the inner LlmAgent without any TTS. */
  private Flowable<Event> handleTextOnly(InvocationContext invocationContext) {
    return delegate.runAsync(invocationContext);
  }

  /** Synthesizes text to audio and emits an event with the audio content. */
  private Flowable<Event> synthesizeAndEmit(String text, InvocationContext invocationContext) {
    if (ttsService == null || ttsConfig == null) {
      // No TTS available, emit text-only event
      return emitTextEvent(text, invocationContext);
    }

    long ttsStartNanos = System.nanoTime();

    return ttsService
        .synthesizeAsync(text, ttsConfig)
        .doOnSuccess(
            bytes -> {
              long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - ttsStartNanos);
              VoiceMetrics.getInstance().recordTtsCall(latencyMs, true, text.length());
            })
        .doOnError(
            error -> {
              long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - ttsStartNanos);
              VoiceMetrics.getInstance().recordTtsCall(latencyMs, false, text.length());
            })
        .flatMapPublisher(
            audioBytes -> {
              logger.debug("TTS synthesized {} bytes for text: '{}'", audioBytes.length, text);
              return emitAudioEvent(audioBytes, text, invocationContext);
            })
        .onErrorResumeNext(
            error -> {
              logger.warn(
                  "TTS synthesis failed, falling back to text event: {}", error.getMessage());
              return emitTextEvent(text, invocationContext);
            });
  }

  /** Creates and emits an event containing audio content. */
  private Flowable<Event> emitAudioEvent(
      byte[] audioBytes, String text, InvocationContext invocationContext) {
    Part audioPart = Part.fromBytes(audioBytes, DEFAULT_AUDIO_MIME_TYPE);
    Part textPart = Part.fromText(text);

    Content content =
        Content.builder().role("model").parts(ImmutableList.of(textPart, audioPart)).build();

    Event event =
        Event.builder()
            .id(Event.generateEventId())
            .invocationId(invocationContext.invocationId())
            .author(name())
            .branch(invocationContext.branch().orElse(null))
            .content(content)
            .turnComplete(true)
            .build();

    return Flowable.just(event);
  }

  /** Creates and emits a text-only event (fallback when TTS is unavailable). */
  private Flowable<Event> emitTextEvent(String text, InvocationContext invocationContext) {
    Content content =
        Content.builder().role("model").parts(ImmutableList.of(Part.fromText(text))).build();

    Event event =
        Event.builder()
            .id(Event.generateEventId())
            .invocationId(invocationContext.invocationId())
            .author(name())
            .branch(invocationContext.branch().orElse(null))
            .content(content)
            .turnComplete(true)
            .build();

    return Flowable.just(event);
  }

  // ---- Helper methods ----

  /** Extracts audio byte data from user content, if any part contains inline audio data. */
  private Optional<byte[]> extractAudioData(Content content) {
    if (content.parts().isEmpty() || content.parts().get().isEmpty()) {
      return Optional.empty();
    }

    for (Part part : content.parts().get()) {
      if (part.inlineData().isPresent()) {
        Blob blob = part.inlineData().get();
        if (blob.mimeType().isPresent()
            && blob.mimeType().get().startsWith(AUDIO_MIME_PREFIX)
            && blob.data().isPresent()) {
          return Optional.of(blob.data().get());
        }
      }
    }

    return Optional.empty();
  }

  /** Extracts text from user content. */
  private Optional<String> extractTextData(Content content) {
    if (content.parts().isEmpty() || content.parts().get().isEmpty()) {
      return Optional.empty();
    }

    StringBuilder sb = new StringBuilder();
    for (Part part : content.parts().get()) {
      part.text().ifPresent(sb::append);
    }

    String text = sb.toString().trim();
    return text.isEmpty() ? Optional.empty() : Optional.of(text);
  }

  /** Extracts the text response from a list of delegate events. */
  private String extractResponseText(List<Event> events) {
    StringBuilder sb = new StringBuilder();
    for (Event event : events) {
      event
          .content()
          .ifPresent(
              content -> {
                if (content.parts().isPresent()) {
                  for (Part part : content.parts().get()) {
                    part.text().ifPresent(sb::append);
                  }
                }
              });
    }
    return sb.toString().trim();
  }

  // ---- Builder ----

  /** Builder for {@link VoiceAgent}. */
  public static class Builder extends BaseAgent.Builder<Builder> {

    private VoiceConfig voiceConfig;
    private LlmAgent delegate;
    private IntentClassifier intentClassifier;
    private VoiceNavigationHandler navigationHandler = new VoiceNavigationHandler();
    private Map<String, String> navigationCommands;

    @Override
    protected Builder self() {
      return this;
    }

    /**
     * Sets the voice configuration.
     *
     * @param voiceConfig the voice configuration
     * @return this builder
     */
    @CanIgnoreReturnValue
    public Builder voiceConfig(VoiceConfig voiceConfig) {
      this.voiceConfig = voiceConfig;
      return this;
    }

    /**
     * Sets the delegate LlmAgent that handles full reasoning requests.
     *
     * @param delegate the LlmAgent to delegate to
     * @return this builder
     */
    @CanIgnoreReturnValue
    public Builder delegate(LlmAgent delegate) {
      this.delegate = delegate;
      return this;
    }

    /**
     * Sets the intent classifier for routing decisions.
     *
     * @param intentClassifier the classifier instance
     * @return this builder
     */
    @CanIgnoreReturnValue
    public Builder intentClassifier(IntentClassifier intentClassifier) {
      this.intentClassifier = intentClassifier;
      return this;
    }

    /**
     * Sets the navigation handler for voice navigation commands.
     *
     * @param navigationHandler the handler instance
     * @return this builder
     */
    @CanIgnoreReturnValue
    public Builder navigationHandler(VoiceNavigationHandler navigationHandler) {
      this.navigationHandler = navigationHandler;
      return this;
    }

    /**
     * Sets navigation commands as a map of patterns to responses. This is a convenience method that
     * creates a {@link VoiceNavigationHandler} from the map.
     *
     * @param commands map of command patterns to response text
     * @return this builder
     */
    @CanIgnoreReturnValue
    public Builder navigationCommands(Map<String, String> commands) {
      this.navigationCommands = commands;
      return this;
    }

    @Override
    public VoiceAgent build() {
      if (voiceConfig == null) {
        throw new IllegalArgumentException("VoiceConfig is required");
      }
      if (delegate == null) {
        throw new IllegalArgumentException("Delegate LlmAgent is required");
      }
      if (intentClassifier == null) {
        // Default to keyword classifier using config's navigation commands
        intentClassifier = IntentClassifier.keyword(voiceConfig.getNavigationCommands());
      }
      if (navigationCommands != null) {
        this.navigationHandler = new VoiceNavigationHandler(navigationCommands);
      }
      if (name == null || name.isEmpty()) {
        name = "voice_agent";
      }
      if (description == null) {
        description = "Voice-enabled agent with STT/TTS pipeline";
      }
      return new VoiceAgent(this);
    }
  }
}
