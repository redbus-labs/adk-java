package com.google.adk.plugins.agentanalytics;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.retrying.RetrySettings;
import com.google.cloud.bigquery.storage.v1.BigQueryWriteClient;
import com.google.cloud.bigquery.storage.v1.BigQueryWriteSettings;
import com.google.cloud.bigquery.storage.v1.StreamWriter;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.VerifyException;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import org.threeten.bp.Duration;

/** Manages state for the BigQueryAgentAnalyticsPlugin. */
class PluginState {
  private static final Logger logger = Logger.getLogger(PluginState.class.getName());
  private final BigQueryLoggerConfig config;
  private final ScheduledExecutorService executor;
  private final BigQueryWriteClient writeClient;
  private static final AtomicLong threadCounter = new AtomicLong(0);
  // Map of invocation ID to BatchProcessor.
  private final ConcurrentHashMap<String, BatchProcessor> batchProcessors =
      new ConcurrentHashMap<>();
  // Map of invocation ID to TraceManager.
  private final ConcurrentHashMap<String, TraceManager> traceManagers = new ConcurrentHashMap<>();
  // Cache of invocation ID to Boolean indicating invocation ID has been processed.
  private final Cache<String, Boolean> processedInvocations;

  PluginState(BigQueryLoggerConfig config) throws IOException {
    this.config = config;
    ThreadFactory threadFactory =
        r -> new Thread(r, "bq-analytics-plugin-" + threadCounter.getAndIncrement());
    this.executor = Executors.newScheduledThreadPool(1, threadFactory);
    // One write client per plugin instance, shared by all invocations.
    this.writeClient = createWriteClient(config);
    this.processedInvocations =
        CacheBuilder.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(java.time.Duration.ofMinutes(10))
            .build();
  }

  ScheduledExecutorService getExecutor() {
    return executor;
  }

  boolean isProcessed(String invocationId) {
    boolean isProcessed = processedInvocations.getIfPresent(invocationId) != null;
    if (isProcessed) {
      logger.info("Invocation ID: " + invocationId + "  already processed");
    }
    return isProcessed;
  }

  void markProcessed(String invocationId) {
    processedInvocations.put(invocationId, true);
  }

  protected BigQueryWriteClient createWriteClient(BigQueryLoggerConfig config) throws IOException {
    if (config.credentials() != null) {
      return BigQueryWriteClient.create(
          BigQueryWriteSettings.newBuilder()
              .setCredentialsProvider(FixedCredentialsProvider.create(config.credentials()))
              .build());
    }
    return BigQueryWriteClient.create();
  }

  protected StreamWriter createWriter() {
    BigQueryLoggerConfig.RetryConfig retryConfig = config.retryConfig();
    RetrySettings retrySettings =
        RetrySettings.newBuilder()
            .setMaxAttempts(retryConfig.maxRetries())
            .setInitialRetryDelay(Duration.ofMillis(retryConfig.initialDelay().toMillis()))
            .setRetryDelayMultiplier(retryConfig.multiplier())
            .setMaxRetryDelay(Duration.ofMillis(retryConfig.maxDelay().toMillis()))
            .build();

    String streamName = getStreamName(config);
    try {
      return StreamWriter.newBuilder(streamName, writeClient)
          .setRetrySettings(retrySettings)
          .setWriterSchema(BigQuerySchema.getArrowSchema())
          .build();
    } catch (Exception e) {
      throw new VerifyException("Failed to create StreamWriter for " + streamName, e);
    }
  }

  @VisibleForTesting
  String getStreamName(BigQueryLoggerConfig config) {
    return String.format(
        "projects/%s/datasets/%s/tables/%s/streams/_default",
        config.projectId(), config.datasetId(), config.tableName());
  }

  @VisibleForTesting
  TraceManager getTraceManager(String invocationId) {
    return traceManagers.computeIfAbsent(invocationId, id -> new TraceManager());
  }

  @VisibleForTesting
  BatchProcessor getBatchProcessor(String invocationId) {
    return batchProcessors.computeIfAbsent(
        invocationId,
        id -> {
          BatchProcessor p =
              new BatchProcessor(
                  createWriter(),
                  config.batchSize(),
                  config.batchFlushInterval(),
                  config.queueMaxSize(),
                  executor);
          p.start();
          return p;
        });
  }

  @VisibleForTesting
  Collection<TraceManager> getTraceManagers() {
    return traceManagers.values();
  }

  @VisibleForTesting
  Collection<BatchProcessor> getBatchProcessors() {
    return batchProcessors.values();
  }

  @VisibleForTesting
  TraceManager removeTraceManager(String invocationId) {
    return traceManagers.remove(invocationId);
  }

  @VisibleForTesting
  protected BatchProcessor removeProcessor(String invocationId) {
    return batchProcessors.remove(invocationId);
  }

  void clearTraceManagers() {
    traceManagers.clear();
  }

  void clearBatchProcessors() {
    batchProcessors.clear();
  }

  void close() {
    for (BatchProcessor processor : getBatchProcessors()) {
      processor.close();
    }
    for (TraceManager traceManager : getTraceManagers()) {
      traceManager.clearStack();
    }
    clearBatchProcessors();
    clearTraceManagers();

    if (writeClient != null) {
      writeClient.close();
    }
    try {
      executor.shutdown();
      if (!executor.awaitTermination(config.shutdownTimeout().toMillis(), MILLISECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
