package com.google.adk.models;

import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BaseLlm implementation for Azure OpenAI Realtime models via the WebRTC-based Realtime API.
 *
 * <p>Unlike {@link AzureBaseLM} which uses the stateless REST Responses API, this adapter manages a
 * persistent WebRTC connection for low-latency, bidirectional audio and text streaming.
 *
 * <p>Supported models include {@code gpt-4o-realtime-preview}, {@code gpt-realtime}, {@code
 * gpt-realtime-mini}, and {@code gpt-realtime-1.5}.
 *
 * <p>Environment variables:
 *
 * <ul>
 *   <li>{@code AZURE_OPENAI_ENDPOINT} — the Azure OpenAI resource URL (e.g. {@code
 *       https://myresource.openai.azure.com})
 *   <li>{@code AZURE_OPENAI_API_KEY} — the API key for authentication
 *   <li>{@code AZURE_REALTIME_VOICE} (optional) — the output voice, defaults to {@code alloy}
 * </ul>
 *
 * @author Alfred Jimmy
 * @see <a
 *     href="https://learn.microsoft.com/en-us/azure/foundry/openai/how-to/realtime-audio-webrtc">
 *     Azure OpenAI Realtime API via WebRTC</a>
 */
public class AzureRealtimeLM extends BaseLlm {

  private static final Logger logger = LoggerFactory.getLogger(AzureRealtimeLM.class);

  public static final String ENDPOINT_ENV = "AZURE_OPENAI_ENDPOINT";
  public static final String API_KEY_ENV = "AZURE_OPENAI_API_KEY";
  public static final String VOICE_ENV = "AZURE_REALTIME_VOICE";

  private static final String DEFAULT_VOICE = "alloy";

  private final String modelName;

  /**
   * @param modelName deployment name of the realtime model (e.g. {@code gpt-4o-realtime-preview})
   */
  public AzureRealtimeLM(String modelName) {
    super(modelName);
    this.modelName = modelName;
    warnIfMissing(ENDPOINT_ENV);
    warnIfMissing(API_KEY_ENV);
  }

  private static void warnIfMissing(String envVar) {
    String val = System.getenv(envVar);
    if (val == null || val.isBlank()) {
      logger.warn("{} is not set. Azure Realtime API calls will fail.", envVar);
    }
  }

  String resolveEndpoint() {
    String ep = System.getenv(ENDPOINT_ENV);
    if (ep == null || ep.isBlank()) {
      throw new IllegalStateException(ENDPOINT_ENV + " environment variable is not set.");
    }
    return ep.replaceAll("/+$", "");
  }

  String resolveApiKey() {
    String key = System.getenv(API_KEY_ENV);
    if (key == null || key.isBlank()) {
      throw new IllegalStateException(API_KEY_ENV + " environment variable is not set.");
    }
    return key;
  }

  String resolveVoice() {
    String voice = System.getenv(VOICE_ENV);
    return (voice != null && !voice.isBlank()) ? voice : DEFAULT_VOICE;
  }

  String modelName() {
    return modelName;
  }

  /**
   * Extracts system instructions from the LlmRequest config if present.
   *
   * @return the combined system instruction text, or empty string
   */
  String extractInstructions(LlmRequest llmRequest) {
    return llmRequest
        .config()
        .flatMap(GenerateContentConfig::systemInstruction)
        .flatMap(Content::parts)
        .map(
            parts ->
                parts.stream()
                    .filter(p -> p.text().isPresent())
                    .map(p -> p.text().get())
                    .collect(Collectors.joining("\n")))
        .filter(text -> !text.isEmpty())
        .orElse("");
  }

  /**
   * For realtime models, {@code generateContent} is not the primary interaction mode. This
   * implementation provides a minimal fallback that sends text over a short-lived WebRTC session
   * and collects the text response.
   */
  @Override
  public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
    return Flowable.create(
        emitter -> {
          AzureRealtimeLlmConnection conn = null;
          try {
            conn = new AzureRealtimeLlmConnection(this, llmRequest);

            conn.receive()
                .doOnNext(emitter::onNext)
                .doOnError(emitter::onError)
                .doOnComplete(emitter::onComplete)
                .subscribe();

            Optional<Content> lastUserContent =
                llmRequest.contents().isEmpty()
                    ? Optional.empty()
                    : Optional.of(llmRequest.contents().get(llmRequest.contents().size() - 1));

            if (lastUserContent.isPresent()) {
              conn.sendContent(lastUserContent.get()).blockingAwait();
            } else {
              conn.sendContent(Content.fromParts(Part.fromText(""))).blockingAwait();
            }
          } catch (Exception e) {
            logger.error("Error in AzureRealtimeLM.generateContent", e);
            if (!emitter.isCancelled()) {
              emitter.onError(e);
            }
            if (conn != null) {
              conn.close(e);
            }
          }
        },
        io.reactivex.rxjava3.core.BackpressureStrategy.BUFFER);
  }

  @Override
  public BaseLlmConnection connect(LlmRequest llmRequest) {
    return new AzureRealtimeLlmConnection(this, llmRequest);
  }

  /** Returns true if the given model name is an Azure Realtime model. */
  public static boolean isRealtimeModel(String modelName) {
    if (modelName == null) {
      return false;
    }
    String lower = modelName.toLowerCase();
    return lower.contains("realtime");
  }
}
