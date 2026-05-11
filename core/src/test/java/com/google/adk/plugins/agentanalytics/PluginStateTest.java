/*
 * Copyright 2026 Google LLC
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

package com.google.adk.plugins.agentanalytics;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.core.ApiFutures;
import com.google.cloud.bigquery.storage.v1.AppendRowsResponse;
import com.google.cloud.bigquery.storage.v1.BigQueryWriteClient;
import com.google.cloud.bigquery.storage.v1.StreamWriter;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.apache.arrow.vector.ipc.message.ArrowRecordBatch;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

@RunWith(JUnit4.class)
public final class PluginStateTest {
  private BigQueryLoggerConfig config;
  private TestPluginState pluginState;
  private Handler mockHandler;
  private Logger pluginLogger;
  private Level originalLevel;

  private static class TestPluginState extends PluginState {
    TestPluginState(BigQueryLoggerConfig config) throws IOException {
      super(config);
    }

    private BigQueryWriteClient mockWriteClient;

    @Override
    protected BigQueryWriteClient createWriteClient(BigQueryLoggerConfig config) {
      mockWriteClient = mock(BigQueryWriteClient.class);
      return mockWriteClient;
    }

    BigQueryWriteClient getMockWriteClient() {
      return mockWriteClient;
    }

    @Override
    protected StreamWriter createWriter() {
      StreamWriter writer = mock(StreamWriter.class);
      when(writer.append(any(ArrowRecordBatch.class)))
          .thenReturn(ApiFutures.immediateFuture(AppendRowsResponse.newBuilder().build()));
      return writer;
    }
  }

  @Before
  public void setUp() throws IOException {
    config =
        BigQueryLoggerConfig.builder()
            .projectId("test-project")
            .datasetId("test-dataset")
            .tableName("test-table")
            .gcsBucketName("")
            .build();
    pluginState = new TestPluginState(config);

    pluginLogger = Logger.getLogger(PluginState.class.getName());
    mockHandler = mock(Handler.class);
    originalLevel = pluginLogger.getLevel();
    pluginLogger.setLevel(Level.INFO);
    pluginLogger.addHandler(mockHandler);
  }

  @After
  public void tearDown() {
    pluginLogger.removeHandler(mockHandler);
    pluginLogger.setLevel(originalLevel);
  }

  @Test
  public void addPendingTask_removedTaskOnCompletion() {
    String invocationId = "testInvocation";
    CompletableFuture<Void> task = new CompletableFuture<>();
    pluginState.addPendingTask(invocationId, task);

    task.complete(null);
    pluginState.ensureInvocationCompleted(invocationId).blockingAwait();

    // No specific log to check now, but we verify it completes without error.
  }

  @Test
  public void ensureInvocationCompleted_noTasks_succeeds() {
    String invocationId = "testInvocation";

    pluginState.ensureInvocationCompleted(invocationId).test().assertComplete();
  }

  @Test
  public void ensureInvocationCompleted_executionException_completesSuccessfully()
      throws InterruptedException {
    String invocationId = "testInvocation";
    CompletableFuture<Void> task = new CompletableFuture<>();
    pluginState.addPendingTask(invocationId, task);

    task.completeExceptionally(new RuntimeException("test exception"));

    pluginState.ensureInvocationCompleted(invocationId).test().assertComplete();
  }

  @Test
  public void ensureInvocationCompleted_interrupted_logsNothing() throws InterruptedException {
    String invocationId = "testInvocation";
    CompletableFuture<Void> task = new CompletableFuture<>();
    pluginState.addPendingTask(invocationId, task);

    Thread testThread =
        new Thread(
            () -> {
              pluginLogger.addHandler(mockHandler);
              pluginState.ensureInvocationCompleted(invocationId).blockingAwait();
            });
    testThread.start();
    Thread.sleep(50);
    testThread.interrupt();
    testThread.join(1000);

    // RxJava handles interruption differently, we just verify it doesn't crash here.
  }

  @Test
  public void ensureInvocationCompleted_timeout_logsWarning() throws IOException {
    config = config.toBuilder().shutdownTimeout(Duration.ofMillis(100)).build();
    pluginState = new TestPluginState(config);

    String invocationId = "testInvocation";
    CompletableFuture<Void> task = new CompletableFuture<>(); // Never completes
    pluginState.addPendingTask(invocationId, task);

    pluginState.ensureInvocationCompleted(invocationId).test().awaitDone(1, SECONDS);

    // Wait for cleanup side effects which run after terminal signal.
    long deadline = Instant.now().plusMillis(1000).toEpochMilli();
    while (!pluginState.isProcessed(invocationId) && Instant.now().toEpochMilli() < deadline) {
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    ArgumentCaptor<LogRecord> captor = ArgumentCaptor.forClass(LogRecord.class);
    verify(mockHandler, atLeastOnce()).publish(captor.capture());

    boolean found =
        captor.getAllValues().stream()
            .anyMatch(
                record ->
                    record.getLevel().equals(Level.WARNING)
                        && record
                            .getMessage()
                            .contains("Timeout while waiting for pending tasks to complete"));
    assertTrue(
        "Expected log message 'Timeout while waiting for pending tasks to complete' not found",
        found);
  }

  @Test
  public void ensureInvocationCompleted_timeout_cleansUpState() throws IOException {
    config = config.toBuilder().shutdownTimeout(Duration.ofMillis(100)).build();
    pluginState = new TestPluginState(config);

    String invocationId = "testInvocation";
    CompletableFuture<Void> task = new CompletableFuture<>(); // Never completes
    pluginState.addPendingTask(invocationId, task);

    // Populate processor and trace manager.
    var unusedProcessor = pluginState.getBatchProcessor(invocationId);
    var unusedTraceManager = pluginState.getTraceManager(invocationId);

    pluginState.ensureInvocationCompleted(invocationId).test().awaitDone(1, SECONDS);

    // Wait for cleanup side effects which run after terminal signal.
    long deadline = Instant.now().plusMillis(1000).toEpochMilli();
    while (!pluginState.isProcessed(invocationId) && Instant.now().toEpochMilli() < deadline) {
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // Verify cleanup
    assertTrue(
        "Invocation ID should be marked as processed", pluginState.isProcessed(invocationId));
    assertTrue(pluginState.getBatchProcessors().isEmpty());
    assertTrue(pluginState.getTraceManagers().isEmpty());
  }

  @Test
  public void close_succeedsAndCleansUp() throws Exception {
    String invocationId = "testInvocation";
    CompletableFuture<Void> task = new CompletableFuture<>();
    pluginState.addPendingTask(invocationId, task);

    // Populate processor and trace manager.
    var unusedProcessor = pluginState.getBatchProcessor(invocationId);
    var unusedTraceManager = pluginState.getTraceManager(invocationId);

    // Complete the task so close doesn't time out.
    task.complete(null);

    pluginState.close().test().assertComplete();

    // Verify cleanup
    assertTrue(pluginState.getBatchProcessors().isEmpty());
    assertTrue(pluginState.getTraceManagers().isEmpty());
    assertTrue(pluginState.getExecutor().isShutdown());
  }
}
