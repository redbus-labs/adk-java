/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.tools.mcp;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.JsonBaseModel;
import com.google.adk.agents.ReadonlyContext;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Connects to a MCP Server, and retrieves MCP Tools into ADK Tools.
 *
 * <p>Attributes:
 *
 * <ul>
 *   <li>{@code connectionParams}: The connection parameters to the MCP server. Can be either {@code
 *       ServerParameters} or {@code SseServerParameters}.
 *   <li>{@code session}: The MCP session being initialized with the connection.
 * </ul>
 */
public class McpToolset implements BaseToolset {
  private static final Logger logger = LoggerFactory.getLogger(McpToolset.class);
  private final McpSessionManager mcpSessionManager;
  private McpSyncClient mcpSession;
  private final ObjectMapper objectMapper;
  private final Optional<Object> toolFilter;

  private static final int MAX_RETRIES = 3;
  private static final long RETRY_DELAY_MILLIS = 100;

  /**
   * Initializes the McpToolset with SSE server parameters.
   *
   * @param connectionParams The SSE connection parameters to the MCP server.
   * @param objectMapper An ObjectMapper instance for parsing schemas.
   * @param toolFilter An Optional containing either a ToolPredicate or a List of tool names.
   */
  public McpToolset(
      SseServerParameters connectionParams,
      ObjectMapper objectMapper,
      Optional<Object> toolFilter) {
    Objects.requireNonNull(connectionParams);
    Objects.requireNonNull(objectMapper);
    this.objectMapper = objectMapper;
    this.mcpSessionManager = new McpSessionManager(connectionParams);
    this.toolFilter = toolFilter;
  }

  /**
   * Initializes the McpToolset with SSE server parameters and no tool filter.
   *
   * @param connectionParams The SSE connection parameters to the MCP server.
   * @param objectMapper An ObjectMapper instance for parsing schemas.
   */
  public McpToolset(SseServerParameters connectionParams, ObjectMapper objectMapper) {
    this(connectionParams, objectMapper, Optional.empty());
  }

  /**
   * Initializes the McpToolset with local server parameters.
   *
   * @param connectionParams The local server connection parameters to the MCP server.
   * @param objectMapper An ObjectMapper instance for parsing schemas.
   * @param toolFilter An Optional containing either a ToolPredicate or a List of tool names.
   */
  public McpToolset(
      ServerParameters connectionParams, ObjectMapper objectMapper, Optional<Object> toolFilter) {
    Objects.requireNonNull(connectionParams);
    Objects.requireNonNull(objectMapper);
    this.objectMapper = objectMapper;
    this.mcpSessionManager = new McpSessionManager(connectionParams);
    this.toolFilter = toolFilter;
  }

  /**
   * Initializes the McpToolset with local server parameters and no tool filter.
   *
   * @param connectionParams The local server connection parameters to the MCP server.
   * @param objectMapper An ObjectMapper instance for parsing schemas.
   */
  public McpToolset(ServerParameters connectionParams, ObjectMapper objectMapper) {
    this(connectionParams, objectMapper, Optional.empty());
  }

  /**
   * Initializes the McpToolset with SSE server parameters, using the ObjectMapper used across the
   * ADK.
   *
   * @param connectionParams The SSE connection parameters to the MCP server.
   * @param toolFilter An Optional containing either a ToolPredicate or a List of tool names.
   */
  public McpToolset(SseServerParameters connectionParams, Optional<Object> toolFilter) {
    this(connectionParams, JsonBaseModel.getMapper(), toolFilter);
  }

  /**
   * Initializes the McpToolset with SSE server parameters, using the ObjectMapper used across the
   * ADK and no tool filter.
   *
   * @param connectionParams The SSE connection parameters to the MCP server.
   */
  public McpToolset(SseServerParameters connectionParams) {
    this(connectionParams, JsonBaseModel.getMapper(), Optional.empty());
  }

  /**
   * Initializes the McpToolset with local server parameters, using the ObjectMapper used across the
   * ADK.
   *
   * @param connectionParams The local server connection parameters to the MCP server.
   * @param toolFilter An Optional containing either a ToolPredicate or a List of tool names.
   */
  public McpToolset(ServerParameters connectionParams, Optional<Object> toolFilter) {
    this(connectionParams, JsonBaseModel.getMapper(), toolFilter);
  }

  /**
   * Initializes the McpToolset with local server parameters, using the ObjectMapper used across the
   * ADK and no tool filter.
   *
   * @param connectionParams The local server connection parameters to the MCP server.
   */
  public McpToolset(ServerParameters connectionParams) {
    this(connectionParams, JsonBaseModel.getMapper(), Optional.empty());
  }

  /**
   * Initializes the McpToolset with an McpSessionManager.
   *
   * @param mcpSessionManager A McpSessionManager instance for testing.
   * @param objectMapper An ObjectMapper instance for parsing schemas.
   * @param toolFilter An Optional containing either a ToolPredicate or a List of tool names.
   */
  public McpToolset(
      McpSessionManager mcpSessionManager, ObjectMapper objectMapper, Optional<Object> toolFilter) {
    Objects.requireNonNull(mcpSessionManager);
    Objects.requireNonNull(objectMapper);
    this.mcpSessionManager = mcpSessionManager;
    this.objectMapper = objectMapper;
    this.toolFilter = toolFilter;
  }

  @Override
  public Flowable<BaseTool> getTools(ReadonlyContext readonlyContext) {
    return Flowable.fromCallable(
            () -> {
              for (int i = 0; i < MAX_RETRIES; i++) {
                try {
                  if (this.mcpSession == null) {
                    logger.info("MCP session is null or closed, initializing (attempt {}).", i + 1);
                    this.mcpSession = this.mcpSessionManager.createSession();
                  }

                  ListToolsResult toolsResponse = this.mcpSession.listTools();
                  return toolsResponse.tools().stream()
                      .map(
                          tool ->
                              new McpTool(
                                  tool, this.mcpSession, this.mcpSessionManager, this.objectMapper))
                      .filter(
                          tool ->
                              isToolSelected(
                                  tool, toolFilter, Optional.ofNullable(readonlyContext)))
                      .collect(toImmutableList());
                } catch (IllegalArgumentException e) {
                  // This could happen if parameters for tool loading are somehow invalid.
                  // This is likely a fatal error and should not be retried.
                  logger.error("Invalid argument encountered during tool loading.", e);
                  throw new McpToolLoadingException(
                      "Invalid argument encountered during tool loading.", e);
                } catch (RuntimeException e) { // Catch any other unexpected runtime exceptions
                  logger.error("Unexpected error during tool loading, retry attempt " + (i + 1), e);
                  if (i < MAX_RETRIES - 1) {
                    // For other general exceptions, we might still want to retry if they are
                    // potentially transient, or if we don't have more specific handling. But it's
                    // better to be specific. For now, we'll treat them as potentially retryable but
                    // log
                    // them at a higher level.
                    try {
                      logger.info(
                          "Reinitializing MCP session before next retry for unexpected error.");
                      this.mcpSession = this.mcpSessionManager.createSession();
                      Thread.sleep(RETRY_DELAY_MILLIS);
                    } catch (InterruptedException ie) {
                      Thread.currentThread().interrupt();
                      logger.error(
                          "Interrupted during retry delay for loadTools (unexpected error).", ie);
                      throw new McpToolLoadingException(
                          "Interrupted during retry delay (unexpected error)", ie);
                    } catch (RuntimeException reinitE) {
                      logger.error(
                          "Failed to reinitialize session during retry (unexpected error).",
                          reinitE);
                      throw new McpInitializationException(
                          "Failed to reinitialize session during tool loading retry (unexpected"
                              + " error).",
                          reinitE);
                    }
                  } else {
                    logger.error(
                        "Failed to load tools after multiple retries due to unexpected error.", e);
                    throw new McpToolLoadingException(
                        "Failed to load tools after multiple retries due to unexpected error.", e);
                  }
                }
              }
              // This line should ideally not be reached if retries are handled correctly or an
              // exception is always thrown.
              throw new IllegalStateException("Unexpected state in getTools retry loop");
            })
        .flatMapIterable(tools -> tools);
  }

  @Override
  public void close() {
    if (this.mcpSession != null) {
      try {
        this.mcpSession.close();
        logger.debug("MCP session closed successfully.");
      } catch (RuntimeException e) {
        logger.error("Failed to close MCP session", e);
        // We don't throw an exception here, as closing is a cleanup operation and
        // failing to close shouldn't prevent the program from continuing (or exiting).
        // However, we log the error for debugging purposes.
      } finally {
        this.mcpSession = null;
      }
    }
  }

  /** Base exception for all errors originating from {@code McpToolset}. */
  public static class McpToolsetException extends RuntimeException {
    public McpToolsetException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** Exception thrown when there's an error during MCP session initialization. */
  public static class McpInitializationException extends McpToolsetException {
    public McpInitializationException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** Exception thrown when there's an error during loading tools from the MCP server. */
  public static class McpToolLoadingException extends McpToolsetException {
    public McpToolLoadingException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
