/** Author: Sandeep Belgavi Date: January 17, 2026 */
package com.google.adk.a2a.grpc;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.RunConfig;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.grpc.stub.StreamObserver;
import io.reactivex.rxjava3.core.Flowable;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements the A2A gRPC service, bridging requests to an ADK agent.
 *
 * <p>This service uses a Runner internally to properly handle agent execution with session
 * management, artifacts, and memory services.
 */
class A2aService extends A2AServiceGrpc.A2AServiceImplBase {

  private static final Logger logger = LoggerFactory.getLogger(A2aService.class);
  private final BaseAgent agent;
  private final Runner runner;
  private static final String DEFAULT_APP_NAME = "adk-a2a-server";

  /**
   * Constructs the service with a given ADK agent.
   *
   * <p>This constructor creates an internal Runner with in-memory services for sessions, artifacts,
   * and memory. For production use, consider using a constructor that accepts a pre-configured
   * Runner.
   *
   * @param agent The {@link BaseAgent} to handle the requests.
   */
  A2aService(BaseAgent agent) {
    this.agent = agent;
    // Create a Runner with in-memory services for simplicity
    // In production, this could be configured with persistent services
    this.runner =
        new Runner.Builder()
            .agent(agent)
            .appName(DEFAULT_APP_NAME)
            .artifactService(new InMemoryArtifactService())
            .sessionService(new InMemorySessionService())
            .memoryService(new InMemoryMemoryService())
            .build();
  }

  /**
   * Constructs the service with a pre-configured Runner.
   *
   * @param agent The {@link BaseAgent} to handle the requests.
   * @param runner The {@link Runner} instance to use for agent execution.
   */
  A2aService(BaseAgent agent, Runner runner) {
    this.agent = agent;
    this.runner = runner;
  }

  @Override
  public void sendMessage(
      SendMessageRequest request, StreamObserver<SendMessageResponse> responseObserver) {
    logger.info(
        "Received message from client: sessionId={}, query={}",
        request.getSessionId(),
        request.getUserQuery());

    try {
      // Extract session ID from request or generate a new one
      String sessionId =
          request.getSessionId() != null && !request.getSessionId().isEmpty()
              ? request.getSessionId()
              : UUID.randomUUID().toString();

      // Create user content from the request
      Content userContent = Content.fromParts(Part.fromText(request.getUserQuery()));

      // Create invocation context (sessionId is handled by Runner internally)
      InvocationContext context = InvocationContext.builder().userContent(userContent).build();

      // Configure run settings for streaming
      RunConfig runConfig =
          RunConfig.builder()
              .setStreamingMode(RunConfig.StreamingMode.SSE)
              .setMaxLlmCalls(20)
              .build();

      // Execute the agent using Runner
      Flowable<Event> eventStream =
          runner.runAsync(
              sessionId, // userId (using sessionId as userId for simplicity)
              sessionId,
              userContent,
              runConfig);

      // Process events and stream responses
      eventStream
          .doOnError(
              error -> {
                logger.error("Error executing agent", error);
                responseObserver.onError(
                    io.grpc.Status.INTERNAL
                        .withDescription("Agent execution failed: " + error.getMessage())
                        .withCause(error)
                        .asRuntimeException());
              })
          .subscribe(
              event -> {
                // Convert event to response
                String content = event.stringifyContent();
                if (content != null && !content.trim().isEmpty()) {
                  SendMessageResponse response =
                      SendMessageResponse.newBuilder().setAgentReply(content).build();
                  responseObserver.onNext(response);
                }
              },
              error -> {
                // Error already handled in doOnError, but ensure we complete
                logger.error("Error in event stream", error);
              },
              () -> {
                // Stream completed successfully
                logger.info("Agent execution completed for sessionId={}", sessionId);
                responseObserver.onCompleted();
              });

    } catch (Exception e) {
      logger.error("Unexpected error processing request", e);
      responseObserver.onError(
          io.grpc.Status.INTERNAL
              .withDescription("Unexpected error: " + e.getMessage())
              .withCause(e)
              .asRuntimeException());
    }
  }
}
