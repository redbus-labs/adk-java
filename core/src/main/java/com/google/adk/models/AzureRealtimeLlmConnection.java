package com.google.adk.models;

import com.google.common.collect.ImmutableList;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.Part;
import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCDataChannelBuffer;
import dev.onvoid.webrtc.RTCDataChannelInit;
import dev.onvoid.webrtc.RTCDataChannelObserver;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceConnectionState;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCRtpReceiver;
import dev.onvoid.webrtc.RTCRtpTransceiver;
import dev.onvoid.webrtc.RTCRtpTransceiverDirection;
import dev.onvoid.webrtc.RTCRtpTransceiverInit;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.RTCSignalingState;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.audio.AudioOptions;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSink;
import dev.onvoid.webrtc.media.audio.AudioTrackSource;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.processors.PublishProcessor;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WebRTC-based connection to the Azure OpenAI Realtime API.
 *
 * <p>This class implements the full WebRTC lifecycle:
 *
 * <ol>
 *   <li>Procure an ephemeral token via {@code /openai/v1/realtime/client_secrets}
 *   <li>Create an {@link RTCPeerConnection} with a DataChannel and audio transceiver
 *   <li>Perform SDP offer/answer exchange via {@code /openai/v1/realtime/calls}
 *   <li>Use the DataChannel for JSON event exchange (text input/output, function calls)
 *   <li>Use the audio track for low-latency PCM audio streaming
 * </ol>
 *
 * @author Alfred Jimmy
 */
public final class AzureRealtimeLlmConnection implements BaseLlmConnection {

  private static final Logger logger = LoggerFactory.getLogger(AzureRealtimeLlmConnection.class);

  private static final int HTTP_TIMEOUT_SECONDS = 30;
  private static final int AUDIO_SAMPLE_RATE = 24000;

  private static final HttpClient httpClient =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_2)
          .connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
          .build();

  private final AzureRealtimeLM llm;
  private final LlmRequest llmRequest;
  private final PublishProcessor<LlmResponse> responseProcessor = PublishProcessor.create();
  private final Flowable<LlmResponse> responseFlowable = responseProcessor.serialize();
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final AtomicBoolean sessionConfigured = new AtomicBoolean(false);

  private PeerConnectionFactory peerConnectionFactory;
  private RTCPeerConnection peerConnection;
  private RTCDataChannel dataChannel;
  private String ephemeralToken;

  AzureRealtimeLlmConnection(AzureRealtimeLM llm, LlmRequest llmRequest) {
    this.llm = Objects.requireNonNull(llm, "llm cannot be null");
    this.llmRequest = Objects.requireNonNull(llmRequest, "llmRequest cannot be null");

    try {
      initializeConnection();
    } catch (Exception e) {
      logger.error("Failed to initialize Azure Realtime WebRTC connection", e);
      responseProcessor.onError(e);
    }
  }

  // ==================== Connection Initialization ====================

  private void initializeConnection() throws IOException, InterruptedException {
    logger.info("Initializing Azure Realtime WebRTC connection for model: {}", llm.modelName());

    ephemeralToken = procureEphemeralToken();
    logger.info("Ephemeral token acquired successfully.");

    setupWebRtcConnection();
  }

  /**
   * Calls the Azure OpenAI REST endpoint to obtain a short-lived ephemeral token and pre-configure
   * the session (model, instructions, voice).
   */
  private String procureEphemeralToken() throws IOException, InterruptedException {
    String endpoint = llm.resolveEndpoint();
    String apiKey = llm.resolveApiKey();
    String voice = llm.resolveVoice();
    String instructions = llm.extractInstructions(llmRequest);

    String url = endpoint + "/openai/v1/realtime/client_secrets";

    JSONObject sessionConfig = new JSONObject();
    JSONObject session = new JSONObject();
    session.put("type", "realtime");
    session.put("model", llm.modelName());
    if (!instructions.isEmpty()) {
      session.put("instructions", instructions);
    }

    JSONObject audio = new JSONObject();
    JSONObject inputCfg = new JSONObject();
    JSONObject transcription = new JSONObject();
    transcription.put("model", "whisper-1");
    inputCfg.put("transcription", transcription);

    JSONObject inputFormat = new JSONObject();
    inputFormat.put("type", "audio/pcm");
    inputFormat.put("rate", AUDIO_SAMPLE_RATE);
    inputCfg.put("format", inputFormat);

    JSONObject turnDetection = new JSONObject();
    turnDetection.put("type", "server_vad");
    turnDetection.put("threshold", 0.5);
    turnDetection.put("prefix_padding_ms", 300);
    turnDetection.put("silence_duration_ms", 200);
    turnDetection.put("create_response", true);
    inputCfg.put("turn_detection", turnDetection);

    JSONObject outputCfg = new JSONObject();
    outputCfg.put("voice", voice);
    JSONObject outputFormat = new JSONObject();
    outputFormat.put("type", "audio/pcm");
    outputFormat.put("rate", AUDIO_SAMPLE_RATE);
    outputCfg.put("format", outputFormat);

    audio.put("input", inputCfg);
    audio.put("output", outputCfg);
    session.put("audio", audio);
    sessionConfig.put("session", session);

    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("api-key", apiKey)
            .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    sessionConfig.toString(), StandardCharsets.UTF_8))
            .build();

    HttpResponse<String> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IOException(
          "Failed to procure ephemeral token: HTTP "
              + response.statusCode()
              + " — "
              + response.body());
    }

    JSONObject responseBody = new JSONObject(response.body());
    String token = responseBody.optString("value", "");
    if (token.isEmpty()) {
      throw new IOException("No ephemeral token in response: " + response.body());
    }
    return token;
  }

  // ==================== WebRTC Setup ====================

  private void setupWebRtcConnection() {
    peerConnectionFactory = new PeerConnectionFactory();

    RTCConfiguration rtcConfig = new RTCConfiguration();
    RTCIceServer stunServer = new RTCIceServer();
    stunServer.urls.add("stun:stun.l.google.com:19302");
    rtcConfig.iceServers.add(stunServer);

    peerConnection =
        peerConnectionFactory.createPeerConnection(rtcConfig, new RealtimePeerConnectionObserver());

    RTCDataChannelInit dcInit = new RTCDataChannelInit();
    dcInit.ordered = true;
    dataChannel = peerConnection.createDataChannel("realtime-channel", dcInit);
    dataChannel.registerObserver(new RealtimeDataChannelObserver());

    AudioOptions audioOptions = new AudioOptions();
    AudioTrackSource audioSource = peerConnectionFactory.createAudioSource(audioOptions);
    AudioTrack localAudioTrack = peerConnectionFactory.createAudioTrack("localAudio", audioSource);

    RTCRtpTransceiverInit transceiverInit = new RTCRtpTransceiverInit();
    transceiverInit.direction = RTCRtpTransceiverDirection.SEND_RECV;
    peerConnection.addTransceiver(localAudioTrack, transceiverInit);

    logger.info("WebRTC PeerConnection and DataChannel created, starting SDP negotiation.");
    performSdpExchange();
  }

  /**
   * Creates a local SDP offer, sends it to Azure's {@code /openai/v1/realtime/calls} endpoint with
   * the ephemeral token, and sets the returned SDP answer as the remote description.
   */
  private void performSdpExchange() {
    CompletableFuture<RTCSessionDescription> offerFuture = new CompletableFuture<>();

    RTCOfferOptions offerOptions = new RTCOfferOptions();
    peerConnection.createOffer(
        offerOptions,
        new CreateSessionDescriptionObserver() {
          @Override
          public void onSuccess(RTCSessionDescription description) {
            offerFuture.complete(description);
          }

          @Override
          public void onFailure(String error) {
            offerFuture.completeExceptionally(
                new IOException("Failed to create SDP offer: " + error));
          }
        });

    offerFuture.thenCompose(this::setLocalAndExchange).exceptionally(this::handleSdpError);
  }

  private CompletableFuture<Void> setLocalAndExchange(RTCSessionDescription localOffer) {
    CompletableFuture<Void> setLocalFuture = new CompletableFuture<>();

    peerConnection.setLocalDescription(
        localOffer,
        new SetSessionDescriptionObserver() {
          @Override
          public void onSuccess() {
            setLocalFuture.complete(null);
          }

          @Override
          public void onFailure(String error) {
            setLocalFuture.completeExceptionally(
                new IOException("Failed to set local description: " + error));
          }
        });

    return setLocalFuture.thenCompose(unused -> exchangeSdpWithAzure(localOffer.sdp));
  }

  private CompletableFuture<Void> exchangeSdpWithAzure(String offerSdp) {
    return CompletableFuture.supplyAsync(
            () -> {
              try {
                String endpoint = llm.resolveEndpoint();
                String url = endpoint + "/openai/v1/realtime/calls?webrtcfilter=on";

                HttpRequest request =
                    HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + ephemeralToken)
                        .header("Content-Type", "application/sdp")
                        .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                        .POST(HttpRequest.BodyPublishers.ofString(offerSdp, StandardCharsets.UTF_8))
                        .build();

                HttpResponse<String> response =
                    httpClient.send(
                        request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

                int status = response.statusCode();
                if (status != 200 && status != 201) {
                  throw new IOException(
                      "SDP negotiation failed: HTTP " + status + " — " + response.body());
                }

                String answerSdp = response.body();
                logger.info(
                    "Received SDP answer from Azure ({} chars), setting remote description.",
                    answerSdp.length());

                return answerSdp;
              } catch (IOException | InterruptedException e) {
                throw new RuntimeException("SDP exchange failed", e);
              }
            })
        .thenCompose(this::setRemoteDescription);
  }

  private CompletableFuture<Void> setRemoteDescription(String answerSdp) {
    CompletableFuture<Void> future = new CompletableFuture<>();

    RTCSessionDescription answer = new RTCSessionDescription(RTCSdpType.ANSWER, answerSdp);

    peerConnection.setRemoteDescription(
        answer,
        new SetSessionDescriptionObserver() {
          @Override
          public void onSuccess() {
            logger.info("Remote SDP description set. WebRTC connection establishing...");
            future.complete(null);
          }

          @Override
          public void onFailure(String error) {
            future.completeExceptionally(
                new IOException("Failed to set remote description: " + error));
          }
        });

    return future;
  }

  private Void handleSdpError(Throwable throwable) {
    logger.error("SDP negotiation failed", throwable);
    if (!closed.get()) {
      responseProcessor.onError(throwable);
    }
    return null;
  }

  // ==================== DataChannel Event Handling ====================

  private void handleDataChannelMessage(String json) {
    if (closed.get()) return;

    try {
      JSONObject event = new JSONObject(json);
      String eventType = event.optString("type", "");

      logger.debug("Realtime DataChannel event: {}", eventType);

      switch (eventType) {
        case "session.created":
          logger.info(
              "Realtime session created: {}",
              event.optJSONObject("session") != null
                  ? event.optJSONObject("session").optString("id", "unknown")
                  : "unknown");
          sessionConfigured.set(true);
          break;

        case "session.updated":
          logger.info("Realtime session updated.");
          break;

        case "response.output_text.delta":
          handleTextDelta(event);
          break;

        case "response.output_text.done":
          handleTextDone(event);
          break;

        case "response.output_audio_transcript.delta":
          handleTranscriptDelta(event);
          break;

        case "response.output_audio_transcript.done":
          handleTranscriptDone(event);
          break;

        case "response.output_audio.delta":
          handleAudioDelta(event);
          break;

        case "response.function_call_arguments.done":
          handleFunctionCallDone(event);
          break;

        case "response.done":
          handleResponseDone(event);
          break;

        case "input_audio_buffer.speech_started":
          logger.debug("User speech started.");
          break;

        case "input_audio_buffer.speech_stopped":
          logger.debug("User speech stopped.");
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
      logger.warn("Failed to parse DataChannel message: {}", json, e);
    }
  }

  private void handleTextDelta(JSONObject event) {
    String delta = event.optString("delta", "");
    if (!delta.isEmpty()) {
      responseProcessor.onNext(
          LlmResponse.builder()
              .content(Content.builder().role("model").parts(Part.fromText(delta)).build())
              .partial(true)
              .build());
    }
  }

  private void handleTextDone(JSONObject event) {
    String text = event.optString("text", "");
    responseProcessor.onNext(
        LlmResponse.builder()
            .content(Content.builder().role("model").parts(Part.fromText(text)).build())
            .partial(false)
            .turnComplete(true)
            .build());
  }

  private void handleTranscriptDelta(JSONObject event) {
    String delta = event.optString("delta", "");
    if (!delta.isEmpty()) {
      responseProcessor.onNext(
          LlmResponse.builder()
              .content(Content.builder().role("model").parts(Part.fromText(delta)).build())
              .partial(true)
              .build());
    }
  }

  private void handleTranscriptDone(JSONObject event) {
    String transcript = event.optString("transcript", "");
    if (!transcript.isEmpty()) {
      responseProcessor.onNext(
          LlmResponse.builder()
              .content(Content.builder().role("model").parts(Part.fromText(transcript)).build())
              .partial(false)
              .turnComplete(true)
              .build());
    }
  }

  private void handleAudioDelta(JSONObject event) {
    String base64Audio = event.optString("delta", "");
    if (!base64Audio.isEmpty()) {
      try {
        byte[] audioBytes = Base64.getDecoder().decode(base64Audio);
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

  private void handleFunctionCallDone(JSONObject event) {
    String name = event.optString("name", "");
    String argsStr = event.optString("arguments", "{}");

    if (!name.isEmpty()) {
      Map<String, Object> args;
      try {
        args = new JSONObject(argsStr).toMap();
      } catch (JSONException e) {
        logger.warn("Failed to parse function call arguments: {}", argsStr);
        args = Map.of();
      }

      FunctionCall fc = FunctionCall.builder().name(name).args(args).build();
      responseProcessor.onNext(
          LlmResponse.builder()
              .content(
                  Content.builder()
                      .role("model")
                      .parts(ImmutableList.of(Part.builder().functionCall(fc).build()))
                      .build())
              .partial(false)
              .build());
    }
  }

  private void handleResponseDone(JSONObject event) {
    logger.info("Realtime response completed.");
    JSONObject resp = event.optJSONObject("response");
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
    String transcript = event.optString("transcript", "");
    if (!transcript.isEmpty()) {
      responseProcessor.onNext(
          LlmResponse.builder()
              .content(Content.builder().role("user").parts(Part.fromText(transcript)).build())
              .partial(false)
              .build());
    }
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
            sendContentOverDataChannel(content);
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
            sendFunctionResponseOverDataChannel(content);
          } else {
            sendContentOverDataChannel(content);
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
          sendOverDataChannel(event.toString());
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

  private void sendContentOverDataChannel(Content content) {
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
    sendOverDataChannel(event.toString());
  }

  private void sendFunctionResponseOverDataChannel(Content content) {
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
                                  item.put("call_id", "call_" + fr.name().orElse("unknown"));
                                  item.put(
                                      "output",
                                      new JSONObject(fr.response().orElse(Map.of())).toString());

                                  event.put("item", item);
                                  sendOverDataChannel(event.toString());
                                })));

    sendResponseCreate();
  }

  private void sendResponseCreate() {
    JSONObject event = new JSONObject();
    event.put("type", "response.create");
    sendOverDataChannel(event.toString());
  }

  private void sendOverDataChannel(String json) {
    if (dataChannel == null) {
      logger.warn("DataChannel is null, cannot send message.");
      return;
    }
    try {
      byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      RTCDataChannelBuffer dcBuffer = new RTCDataChannelBuffer(buffer, false);
      dataChannel.send(dcBuffer);
      logger.debug("Sent over DataChannel: {} bytes", bytes.length);
    } catch (Exception e) {
      logger.error("Failed to send over DataChannel", e);
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
        if (dataChannel != null) {
          dataChannel.close();
          dataChannel = null;
        }
      } catch (Exception e) {
        logger.warn("Error closing DataChannel", e);
      }

      try {
        if (peerConnection != null) {
          peerConnection.close();
          peerConnection = null;
        }
      } catch (Exception e) {
        logger.warn("Error closing PeerConnection", e);
      }

      try {
        if (peerConnectionFactory != null) {
          peerConnectionFactory.dispose();
          peerConnectionFactory = null;
        }
      } catch (Exception e) {
        logger.warn("Error disposing PeerConnectionFactory", e);
      }
    }
  }

  // ==================== WebRTC Observers ====================

  private class RealtimePeerConnectionObserver implements PeerConnectionObserver {

    @Override
    public void onIceCandidate(RTCIceCandidate candidate) {
      logger.debug("ICE candidate: {}", candidate.sdp);
    }

    @Override
    public void onTrack(RTCRtpTransceiver transceiver) {
      MediaStreamTrack track = transceiver.getReceiver().getTrack();
      if (track instanceof AudioTrack audioTrack) {
        logger.info("Remote audio track received via onTrack.");
        audioTrack.addSink(new RealtimeAudioTrackSink());
      }
    }

    @Override
    public void onDataChannel(RTCDataChannel dc) {
      logger.info("Remote DataChannel opened: {}", dc.getLabel());
      dc.registerObserver(new RealtimeDataChannelObserver());
    }

    @Override
    public void onIceConnectionChange(RTCIceConnectionState state) {
      logger.info("ICE connection state: {}", state);
      if (state == RTCIceConnectionState.FAILED || state == RTCIceConnectionState.DISCONNECTED) {
        logger.warn("ICE connection lost: {}", state);
      }
    }

    @Override
    public void onConnectionChange(RTCPeerConnectionState state) {
      logger.info("PeerConnection state: {}", state);
      if (state == RTCPeerConnectionState.FAILED) {
        closeInternal(new IOException("WebRTC PeerConnection entered FAILED state."));
      }
    }

    @Override
    public void onSignalingChange(RTCSignalingState state) {
      logger.debug("Signaling state: {}", state);
    }

    @Override
    public void onRenegotiationNeeded() {
      logger.debug("Renegotiation needed.");
    }

    @Override
    public void onRemoveTrack(RTCRtpReceiver receiver) {
      logger.debug("Track removed.");
    }
  }

  private class RealtimeDataChannelObserver implements RTCDataChannelObserver {

    @Override
    public void onBufferedAmountChange(long previousAmount) {
      // no-op
    }

    @Override
    public void onStateChange() {
      if (dataChannel != null) {
        logger.info("DataChannel state: {}", dataChannel.getState());
      }
    }

    @Override
    public void onMessage(RTCDataChannelBuffer buffer) {
      try {
        ByteBuffer data = buffer.data;
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        String json = new String(bytes, StandardCharsets.UTF_8);
        handleDataChannelMessage(json);
      } catch (Exception e) {
        logger.error("Error processing DataChannel message", e);
      }
    }
  }

  /**
   * Receives remote audio from the WebRTC peer and emits it as {@link LlmResponse} containing PCM
   * audio blobs.
   */
  private class RealtimeAudioTrackSink implements AudioTrackSink {

    @Override
    public void onData(
        byte[] audioData,
        int bitsPerSample,
        int sampleRate,
        int numberOfChannels,
        int numberOfFrames) {
      if (closed.get() || audioData == null || audioData.length == 0) {
        return;
      }

      Blob audioBlob =
          Blob.builder().mimeType("audio/pcm;rate=" + sampleRate).data(audioData).build();

      responseProcessor.onNext(
          LlmResponse.builder()
              .content(
                  Content.builder()
                      .role("model")
                      .parts(ImmutableList.of(Part.builder().inlineData(audioBlob).build()))
                      .build())
              .partial(true)
              .build());
    }
  }
}
