package com.google.adk.models;

import com.google.adk.models.azure.AzureConfig;
import com.google.adk.models.azure.AzureRealtimeTransport;
import com.google.adk.models.azure.AzureRestTransport;
import com.google.adk.models.azure.AzureTransport;
import io.reactivex.rxjava3.core.Flowable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unified Azure LLM adapter that delegates to the appropriate transport based on model type.
 *
 * <p>Supports all Azure-hosted models (REST Responses API, WebSocket Realtime API, and future
 * transports) through a single entry point. Transport selection is automatic based on model name.
 *
 * <p>Environment variables:
 *
 * <ul>
 *   <li>{@code AZURE_MODEL_ENDPOINT} — full Azure endpoint URL (includes api-version)
 *   <li>{@code AZURE_OPENAI_API_KEY} — API key for authentication
 *   <li>{@code AZURE_REALTIME_VOICE} — (optional) voice for realtime models, defaults to "alloy"
 * </ul>
 *
 * @author Alfred Jimmy
 */
public class AzureBaseLM extends BaseLlm {

  private static final Logger logger = LoggerFactory.getLogger(AzureBaseLM.class);

  private final AzureConfig config;
  private final AzureTransport transport;

  /**
   * Creates an AzureBaseLM for the given model/deployment name.
   *
   * @param modelName the Azure deployment name (e.g. "gpt5pro", "gpt-4o-realtime-preview")
   */
  public AzureBaseLM(String modelName) {
    super(modelName);
    this.config = AzureConfig.fromEnvironment(modelName);
    this.transport =
        isRealtimeModel(modelName) ? new AzureRealtimeTransport() : new AzureRestTransport();
    logger.info(
        "AzureBaseLM initialized: model={}, transport={}",
        modelName,
        transport.getClass().getSimpleName());
  }

  @Override
  public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
    return transport.generateContent(llmRequest, config, stream);
  }

  @Override
  public BaseLlmConnection connect(LlmRequest llmRequest) {
    return transport.connect(llmRequest, config);
  }

  /** Returns true if the given model name indicates an Azure Realtime model. */
  public static boolean isRealtimeModel(String modelName) {
    if (modelName == null) return false;
    return modelName.toLowerCase().contains("realtime");
  }
}
