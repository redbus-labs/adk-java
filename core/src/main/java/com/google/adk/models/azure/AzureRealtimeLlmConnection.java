package com.google.adk.models.azure;

import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.processors.PublishProcessor;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WebSocket-based connection to the Azure OpenAI Realtime API.
 *
 * <p>Implements the GA WebSocket protocol:
 *
 * <ol>
 *   <li>Open a WebSocket to {@code
 *       wss://<resource>.openai.azure.com/openai/v1/realtime?model=<deployment>}
 *   <li>Authenticate via {@code api-key} header
 *   <li>Send/receive JSON events for text, audio, and function calls
 * </ol>
 *
 * @author Alfred Jimmy
 * @see <a
 *     href="https://learn.microsoft.com/en-us/azure/foundry/openai/how-to/realtime-audio-websockets">
 *     Azure OpenAI Realtime API via WebSockets</a>
 */
public final class AzureRealtimeLlmConnection implements BaseLlmConnection {

  private static final Logger logger = LoggerFactory.getLogger(AzureRealtimeLlmConnection.class);

  private static final int CONNECT_TIMEOUT_SECONDS = 30;

  /**
   * Turn detection and VAD configuration — tuned for noisy real-world environments (crowds, street,
   * phone speakers) per the OpenAI Realtime session reference and MS Learn VAD docs.
   *
   * <p>We use {@code server_vad} with:
   *
   * <ul>
   *   <li>{@code threshold=0.7} — higher than default 0.5, ignores low-energy background chatter.
   *   <li>{@code silence_duration_ms=300} — slightly more than default 200; avoids cutting off
   *       mid-sentence pauses but still responsive.
   *   <li>{@code prefix_padding_ms=400} — captures more lead-in audio for better first-word
   *       clarity.
   *   <li>{@code interrupt_response=true} — allows barge-in (MS Learn "Response interruption").
   *   <li>{@code input_audio_noise_reduction: far_field} — server-side noise filtering for
   *       non-headset mics (laptops, phones in crowds). Improves VAD accuracy and model perception.
   * </ul>
   *
   * <p>Set {@link #useSemanticVadInstead} to {@code true} for quiet 1:1 environments where natural
   * turn-taking matters more than noise robustness.
   */
  private static final boolean useSemanticVadInstead = false;

  private static final String SEMANTIC_VAD_EAGERNESS = "medium";

  private static final double REALTIME_SERVER_VAD_THRESHOLD = 0.5;

  private static final int REALTIME_SERVER_VAD_PREFIX_PADDING_MS = 300;

  private static final int REALTIME_SERVER_VAD_SILENCE_DURATION_MS = 200;

  private static final boolean createResponseAfterTurnDetectionStop = true;

  /**
   * Critical for barge-in: when {@code true}, a VAD "speech started" signal cancels the current
   * assistant response ({@link #handleResponseDone} emits {@link LlmResponse#interrupted()} when
   * status is cancelled).
   */
  private static final boolean interruptRealtimeResponses = true;

  private final AzureConfig config;
  private final LlmRequest llmRequest;
  private final PublishProcessor<LlmResponse> responseProcessor = PublishProcessor.create();
  private final Flowable<LlmResponse> responseFlowable = responseProcessor.serialize();
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final AtomicBoolean sessionConfigured = new AtomicBoolean(false);
  private final CountDownLatch connectedLatch = new CountDownLatch(1);

  private RealtimeWebSocketClient wsClient;

  /**
   * When true, we already forwarded assistant text via {@code response.*.delta} events for this
   * response; the matching {@code *.done} carries the full string again and must not be printed
   * twice.
   */
  private final AtomicBoolean assistantOutputTextHadDelta = new AtomicBoolean(false);

  private final AtomicBoolean assistantAudioTranscriptHadDelta = new AtomicBoolean(false);

  /**
   * Tracks in-flight function calls by item_id so that {@code
   * response.function_call_arguments.done} (which may omit name/call_id on some API versions) can
   * be resolved. Populated from {@code response.output_item.added} events.
   */
  private final ConcurrentHashMap<String, FunctionCallInfo> pendingFunctionCalls =
      new ConcurrentHashMap<>();

  private static final Set<String> WHISPER_HALLUCINATIONS =
      Set.of(
          "thank you.",
          "thanks for watching.",
          "bye.",
          "you",
          "the end.",
          "thanks for watching!",
          "subscribe",
          "продолжение следует...",
          "thank you for watching.",
          ".");

  private record FunctionCallInfo(String name, String callId) {}

  AzureRealtimeLlmConnection(AzureConfig config, LlmRequest llmRequest) {
    this.config = Objects.requireNonNull(config, "config cannot be null");
    this.llmRequest = Objects.requireNonNull(llmRequest, "llmRequest cannot be null");

    try {
      initializeConnection();
    } catch (Exception e) {
      logger.error("Failed to initialize Azure Realtime WebSocket connection", e);
      responseProcessor.onError(e);
    }
  }

  // ==================== Connection Initialization ====================

  private void initializeConnection() throws Exception {
    logger.info(
        "Initializing Azure Realtime WebSocket connection for model: {}", config.modelName());

    String apiKey = config.apiKey();

    String wsUrl =
        config.endpoint().replaceFirst("^https://", "wss://").replaceFirst("^http://", "ws://");

    if (!wsUrl.contains("deployment=") && !wsUrl.contains("model=")) {
      String separator = wsUrl.contains("?") ? "&" : "?";
      wsUrl = wsUrl + separator + "deployment=" + config.modelName();
    }

    logger.info("Connecting to WebSocket: {}", wsUrl);

    URI uri = URI.create(wsUrl);
    wsClient = new RealtimeWebSocketClient(uri, apiKey);
    wsClient.connectBlocking(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

    if (!wsClient.isOpen()) {
      throw new IllegalStateException("WebSocket connection failed to open within timeout");
    }

    if (!connectedLatch.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      throw new IllegalStateException("WebSocket connected but session.created not received");
    }

    sendSessionUpdate();
    logger.info("Azure Realtime WebSocket connection established.");
  }

  private void sendSessionUpdate() {
    String voice = config.voice();
    String instructions = AzureRequestConverter.extractInstructions(llmRequest);

    JSONObject event = new JSONObject();
    event.put("type", "session.update");

    JSONObject session = new JSONObject();
    if (!instructions.isEmpty()) {
      session.put("instructions", instructions);
    }
    session.put("voice", voice);
    session.put("modalities", new JSONArray().put("text").put("audio"));

    session.put("input_audio_format", "pcm16");
    session.put("output_audio_format", "pcm16");

    JSONObject noiseReduction = new JSONObject();
    noiseReduction.put("type", "far_field");
    session.put("input_audio_noise_reduction", noiseReduction);

    JSONObject turnDetection = new JSONObject();
    if (useSemanticVadInstead) {
      turnDetection.put("type", "semantic_vad");
      turnDetection.put("eagerness", SEMANTIC_VAD_EAGERNESS);
      turnDetection.put("create_response", createResponseAfterTurnDetectionStop);
      turnDetection.put("interrupt_response", interruptRealtimeResponses);
    } else {
      turnDetection.put("type", "server_vad");
      turnDetection.put("threshold", REALTIME_SERVER_VAD_THRESHOLD);
      turnDetection.put("prefix_padding_ms", REALTIME_SERVER_VAD_PREFIX_PADDING_MS);
      turnDetection.put("silence_duration_ms", REALTIME_SERVER_VAD_SILENCE_DURATION_MS);
      turnDetection.put("create_response", createResponseAfterTurnDetectionStop);
      turnDetection.put("interrupt_response", interruptRealtimeResponses);
    }
    session.put("turn_detection", turnDetection);

    JSONObject transcription = new JSONObject();
    transcription.put("model", "whisper-1");
    session.put("input_audio_transcription", transcription);

    JSONArray toolsArray = AzureRequestConverter.buildTools(llmRequest);
    if (toolsArray.length() > 0) {
      session.put("tools", toolsArray);
      session.put("tool_choice", "auto");
    }

    event.put("session", session);
    sendMessage(event.toString());
    logger.info(
        "Sent session.update with voice={}, turn_detection={}, noise_reduction=far_field, tools={}",
        voice,
        useSemanticVadInstead
            ? "semantic_vad(eagerness=" + SEMANTIC_VAD_EAGERNESS + ")"
            : "server_vad(threshold="
                + REALTIME_SERVER_VAD_THRESHOLD
                + ",silence="
                + REALTIME_SERVER_VAD_SILENCE_DURATION_MS
                + "ms)",
        toolsArray.length());
  }

  // ==================== WebSocket Event Handling ====================

  private void handleMessage(String json) {
    if (closed.get()) return;

    try {
      JSONObject event = new JSONObject(json);
      String eventType = event.optString("type", "");

      logger.info("Realtime WS event: {}", eventType);

      switch (eventType) {
        case "session.created":
          logger.info(
              "Realtime session created: {}",
              event.optJSONObject("session") != null
                  ? event.optJSONObject("session").optString("id", "unknown")
                  : "unknown");
          sessionConfigured.set(true);
          connectedLatch.countDown();
          break;

        case "session.updated":
          JSONObject updatedSession = event.optJSONObject("session");
          logger.info(
              "Realtime session updated: {}",
              updatedSession != null
                  ? updatedSession
                      .toString()
                      .substring(0, Math.min(updatedSession.toString().length(), 500))
                  : "no session in event");
          break;

        case "response.created":
          assistantOutputTextHadDelta.set(false);
          assistantAudioTranscriptHadDelta.set(false);
          break;

        case "response.text.delta":
        case "response.output_text.delta":
          handleTextDelta(event);
          break;

        case "response.text.done":
        case "response.output_text.done":
          handleTextDone(event);
          break;

        case "response.audio_transcript.delta":
        case "response.output_audio_transcript.delta":
          handleTranscriptDelta(event);
          break;

        case "response.audio_transcript.done":
        case "response.output_audio_transcript.done":
          handleTranscriptDone(event);
          break;

        case "response.audio.delta":
        case "response.output_audio.delta":
          handleAudioDelta(event);
          break;

        case "response.output_item.added":
          handleOutputItemAdded(event);
          break;

        case "response.function_call_arguments.delta":
          break;

        case "response.function_call_arguments.done":
          handleFunctionCallDone(event);
          break;

        case "response.done":
          handleResponseDone(event);
          break;

        case "input_audio_buffer.speech_started":
          logger.info("Realtime: speech_started — user began speaking.");
          responseProcessor.onNext(LlmResponse.builder().interrupted(true).build());
          break;

        case "input_audio_buffer.speech_stopped":
          logger.debug("User speech stopped.");
          break;

        case "input_audio_buffer.committed":
        case "conversation.item.created":
        case "response.output_item.done":
        case "response.content_part.added":
        case "response.content_part.done":
          logger.debug("Lifecycle event: {}", eventType);
          break;

        case "conversation.item.input_audio_transcription.completed":
          handleInputTranscription(event);
          break;

        case "error":
          handleErrorEvent(event);
          break;

        default:
          logger.debug("Unhandled Realtime event type: {}", eventType);
          break;
      }
    } catch (JSONException e) {
      logger.warn("Failed to parse WebSocket message: {}", json, e);
    }
  }

  private void handleTextDelta(JSONObject event) {
    String delta = event.optString("delta", "");
    if (!delta.isEmpty()) {
      assistantOutputTextHadDelta.set(true);
      responseProcessor.onNext(
          LlmResponse.builder()
              .content(Content.builder().role("model").parts(Part.fromText(delta)).build())
              .partial(true)
              .build());
    }
  }

  private void handleTextDone(JSONObject event) {
    String text = event.optString("text", "");
    if (assistantOutputTextHadDelta.compareAndSet(true, false)) {
      emitAssistantTurnTerminatorOnly();
      return;
    }
    if (!text.isEmpty()) {
      responseProcessor.onNext(
          LlmResponse.builder()
              .content(Content.builder().role("model").parts(Part.fromText(text)).build())
              .partial(false)
              .turnComplete(true)
              .build());
    }
  }

  private void handleTranscriptDelta(JSONObject event) {
    String delta = event.optString("delta", "");
    if (!delta.isEmpty()) {
      assistantAudioTranscriptHadDelta.set(true);
      responseProcessor.onNext(
          LlmResponse.builder()
              .content(Content.builder().role("model").parts(Part.fromText(delta)).build())
              .partial(true)
              .build());
    }
  }

  private void handleTranscriptDone(JSONObject event) {
    String transcript = event.optString("transcript", "");
    if (assistantAudioTranscriptHadDelta.compareAndSet(true, false)) {
      emitAssistantTurnTerminatorOnly();
      return;
    }
    if (!transcript.isEmpty()) {
      responseProcessor.onNext(
          LlmResponse.builder()
              .content(Content.builder().role("model").parts(Part.fromText(transcript)).build())
              .partial(false)
              .turnComplete(true)
              .build());
    }
  }

  /** Ends the assistant line in the UI without repeating text already streamed via deltas. */
  private void emitAssistantTurnTerminatorOnly() {
    responseProcessor.onNext(
        LlmResponse.builder()
            .content(Content.builder().role("model").parts(Part.fromText("")).build())
            .partial(false)
            .turnComplete(true)
            .build());
  }

  private void handleAudioDelta(JSONObject event) {
    String base64Audio = event.optString("delta", "");
    if (!base64Audio.isEmpty()) {
      try {
        byte[] audioBytes = Base64.getDecoder().decode(base64Audio);
        logger.info("<< SPEAKER RECV: {} bytes of audio from model", audioBytes.length);
        Blob audioBlob = Blob.builder().mimeType("audio/pcm").data(audioBytes).build();

        responseProcessor.onNext(
            LlmResponse.builder()
                .content(
                    Content.builder()
                        .role("model")
                        .parts(ImmutableList.of(Part.builder().inlineData(audioBlob).build()))
                        .build())
                .partial(true)
                .build());
      } catch (IllegalArgumentException e) {
        logger.warn("Failed to decode audio delta", e);
      }
    }
  }

  /**
   * Captures function_call items from {@code response.output_item.added} so that name and call_id
   * are available when {@code response.function_call_arguments.done} arrives (some API versions
   * omit them from the latter event).
   */
  private void handleOutputItemAdded(JSONObject event) {
    JSONObject item = event.optJSONObject("item");
    if (item == null) return;
    String type = item.optString("type", "");
    if (!"function_call".equals(type)) return;

    String itemId = item.optString("id", "");
    String name = item.optString("name", "");
    String callId = item.optString("call_id", "");
    if (!itemId.isEmpty() && !name.isEmpty()) {
      pendingFunctionCalls.put(itemId, new FunctionCallInfo(name, callId));
      logger.info(
          "Tracked pending function_call: item_id={}, name={}, call_id={}", itemId, name, callId);
    }
  }

  private void handleFunctionCallDone(JSONObject event) {
    String name = event.optString("name", "");
    String callId = event.optString("call_id", "");
    String itemId = event.optString("item_id", "");
    String argsStr = event.optString("arguments", "{}");

    if (name.isEmpty() && !itemId.isEmpty()) {
      FunctionCallInfo tracked = pendingFunctionCalls.remove(itemId);
      if (tracked != null) {
        name = tracked.name();
        if (callId.isEmpty()) callId = tracked.callId();
      }
    } else if (!itemId.isEmpty()) {
      pendingFunctionCalls.remove(itemId);
    }

    if (name.isEmpty()) {
      logger.warn(
          "Dropping function_call_arguments.done with no resolvable name (item_id={})", itemId);
      return;
    }

    Map<String, Object> args;
    try {
      args = new JSONObject(argsStr).toMap();
    } catch (JSONException e) {
      logger.warn("Failed to parse function call arguments: {}", argsStr);
      args = Map.of();
    }

    FunctionCall.Builder fcBuilder = FunctionCall.builder().name(name).args(args);
    if (!callId.isEmpty()) {
      fcBuilder.id(callId);
    }
    FunctionCall fc = fcBuilder.build();
    logger.info(
        "Emitting FunctionCall: name={}, call_id={}, args_keys={}", name, callId, args.keySet());
    responseProcessor.onNext(
        LlmResponse.builder()
            .content(
                Content.builder()
                    .role("model")
                    .parts(ImmutableList.of(Part.builder().functionCall(fc).build()))
                    .build())
            .partial(false)
            .turnComplete(true)
            .build());
  }

  private void handleResponseDone(JSONObject event) {
    JSONObject resp = event.optJSONObject("response");
    String status =
        resp != null ? resp.optString("status", "").trim().toLowerCase(java.util.Locale.ROOT) : "";
    boolean interrupted =
        "cancelled".equals(status) || "canceled".equals(status) || "interrupted".equals(status);
    if (interrupted) {
      logger.info(
          "Realtime response ended with status={} — emitting interrupted playback signal.", status);
      responseProcessor.onNext(LlmResponse.builder().interrupted(true).build());
    } else {
      logger.info(
          "Realtime response completed (status={}).", status.isEmpty() ? "unknown" : status);
    }

    if (resp != null) {
      JSONObject usage = resp.optJSONObject("usage");
      if (usage != null) {
        logger.info(
            "Realtime token usage — input: {}, output: {}",
            usage.optInt("input_tokens", 0),
            usage.optInt("output_tokens", 0));
      }
    }
  }

  private void handleInputTranscription(JSONObject event) {
    String transcript = event.optString("transcript", "").trim();
    if (transcript.isEmpty()) return;

    if (transcript.length() <= 2
        || WHISPER_HALLUCINATIONS.contains(transcript.toLowerCase(java.util.Locale.ROOT))) {
      logger.debug("Filtered likely Whisper hallucination: '{}'", transcript);
      return;
    }

    responseProcessor.onNext(
        LlmResponse.builder()
            .content(Content.builder().role("user").parts(Part.fromText(transcript)).build())
            .partial(false)
            .build());
  }

  private void handleErrorEvent(JSONObject event) {
    JSONObject error = event.optJSONObject("error");
    String message = error != null ? error.optString("message", "Unknown error") : "Unknown error";
    logger.error("Realtime API error: {}", message);
    responseProcessor.onNext(LlmResponse.builder().errorMessage(message).build());
  }

  // ==================== BaseLlmConnection Methods ====================

  @Override
  public Completable sendHistory(List<Content> history) {
    return Completable.fromAction(
        () -> {
          if (closed.get()) {
            throw new IllegalStateException("Connection is closed");
          }
          for (Content content : history) {
            sendContentOverWebSocket(content);
          }
        });
  }

  @Override
  public Completable sendContent(Content content) {
    return Completable.fromAction(
        () -> {
          if (closed.get()) {
            throw new IllegalStateException("Connection is closed");
          }
          Objects.requireNonNull(content, "content cannot be null");

          boolean isFunctionResponse =
              content.parts().isPresent()
                  && !content.parts().get().isEmpty()
                  && content.parts().get().get(0).functionResponse().isPresent();

          if (isFunctionResponse) {
            sendFunctionResponseOverWebSocket(content);
          } else {
            sendContentOverWebSocket(content);
            sendResponseCreate();
          }
        });
  }

  @Override
  public Completable sendRealtime(Blob blob) {
    return Completable.fromAction(
        () -> {
          if (closed.get()) {
            throw new IllegalStateException("Connection is closed");
          }
          Objects.requireNonNull(blob, "blob cannot be null");

          byte[] audioData = blob.data().orElse(new byte[0]);
          if (audioData.length == 0) {
            return;
          }

          String base64Audio = Base64.getEncoder().encodeToString(audioData);
          JSONObject event = new JSONObject();
          event.put("type", "input_audio_buffer.append");
          event.put("audio", base64Audio);
          sendMessage(event.toString());
        });
  }

  @Override
  public Completable clearRealtimeAudioBuffer() {
    return Completable.fromAction(
        () -> {
          if (closed.get()) {
            throw new IllegalStateException("Connection is closed");
          }
          JSONObject event = new JSONObject();
          event.put("type", "input_audio_buffer.clear");
          logger.debug("Sending input_audio_buffer.clear");
          sendMessage(event.toString());
        });
  }

  @Override
  public Flowable<LlmResponse> receive() {
    return responseFlowable;
  }

  @Override
  public void close() {
    closeInternal(null);
  }

  @Override
  public void close(Throwable throwable) {
    Objects.requireNonNull(throwable, "throwable cannot be null");
    closeInternal(throwable);
  }

  // ==================== Internal Helpers ====================

  private void sendContentOverWebSocket(Content content) {
    String role = content.role().orElse("user");
    String text =
        content.parts().isPresent()
            ? content.parts().get().stream()
                .filter(p -> p.text().isPresent())
                .map(p -> p.text().get())
                .collect(Collectors.joining("\n"))
            : "";

    JSONObject event = new JSONObject();
    event.put("type", "conversation.item.create");

    JSONObject item = new JSONObject();
    item.put("type", "message");
    item.put("role", role.equals("model") ? "assistant" : role);

    JSONArray contentArr = new JSONArray();
    JSONObject contentItem = new JSONObject();
    contentItem.put("type", "input_text");
    contentItem.put("text", text);
    contentArr.put(contentItem);
    item.put("content", contentArr);

    event.put("item", item);
    sendMessage(event.toString());
  }

  private void sendFunctionResponseOverWebSocket(Content content) {
    content
        .parts()
        .ifPresent(
            parts ->
                parts.forEach(
                    part ->
                        part.functionResponse()
                            .ifPresent(
                                fr -> {
                                  JSONObject event = new JSONObject();
                                  event.put("type", "conversation.item.create");

                                  JSONObject item = new JSONObject();
                                  item.put("type", "function_call_output");
                                  String callId =
                                      fr.id().orElse("call_" + fr.name().orElse("unknown"));
                                  item.put("call_id", callId);
                                  item.put(
                                      "output",
                                      new JSONObject(fr.response().orElse(Map.of())).toString());

                                  event.put("item", item);
                                  sendMessage(event.toString());
                                })));

    sendResponseCreate();
  }

  private void sendResponseCreate() {
    JSONObject event = new JSONObject();
    event.put("type", "response.create");
    sendMessage(event.toString());
  }

  private void sendMessage(String json) {
    if (wsClient == null || !wsClient.isOpen()) {
      logger.warn("WebSocket is not open, cannot send message.");
      return;
    }
    try {
      wsClient.send(json);
      logger.debug("Sent over WebSocket: {} bytes", json.getBytes(StandardCharsets.UTF_8).length);
    } catch (Exception e) {
      logger.error("Failed to send over WebSocket", e);
    }
  }

  private void closeInternal(Throwable throwable) {
    if (closed.compareAndSet(false, true)) {
      logger.info("Closing AzureRealtimeLlmConnection.");

      if (throwable == null) {
        responseProcessor.onComplete();
      } else {
        responseProcessor.onError(throwable);
      }

      try {
        if (wsClient != null && wsClient.isOpen()) {
          wsClient.closeBlocking();
          wsClient = null;
        }
      } catch (Exception e) {
        logger.warn("Error closing WebSocket", e);
      }
    }
  }

  // ==================== WebSocket Client ====================

  private class RealtimeWebSocketClient extends WebSocketClient {

    RealtimeWebSocketClient(URI uri, String apiKey) {
      super(uri);
      addHeader("api-key", apiKey);
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
      logger.info("WebSocket connection opened (status: {})", handshake.getHttpStatus());
    }

    @Override
    public void onMessage(String message) {
      handleMessage(message);
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
      logger.info("WebSocket closed: code={}, reason={}, remote={}", code, reason, remote);
      if (!closed.get()) {
        closeInternal(
            new IllegalStateException("WebSocket closed unexpectedly: " + code + " " + reason));
      }
    }

    @Override
    public void onError(Exception ex) {
      logger.error("WebSocket error", ex);
      if (!closed.get()) {
        closeInternal(ex);
      }
    }
  }
}
