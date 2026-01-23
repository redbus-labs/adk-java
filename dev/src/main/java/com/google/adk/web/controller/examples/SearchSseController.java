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

package com.google.adk.web.controller.examples;

import com.google.adk.agents.RunConfig;
import com.google.adk.agents.RunConfig.StreamingMode;
import com.google.adk.runner.Runner;
import com.google.adk.web.controller.examples.dto.SearchRequest;
import com.google.adk.web.service.RunnerService;
import com.google.adk.web.service.SseEventStreamService;
import com.google.adk.web.service.eventprocessor.examples.SearchEventProcessor;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Example domain-specific SSE controller for search functionality.
 *
 * <p>This controller demonstrates how to create domain-specific SSE endpoints that:
 *
 * <ul>
 *   <li>Accept domain-specific request DTOs
 *   <li>Transform requests to agent format
 *   <li>Use custom event processors for domain-specific logic
 *   <li>Maintain clean separation between generic infrastructure and domain logic
 * </ul>
 *
 * <p><b>Usage Example:</b>
 *
 * <pre>{@code
 * POST /search/sse
 * Content-Type: application/json
 *
 * {
 *   "mriClientId": "client123",
 *   "mriSessionId": "session456",
 *   "userQuery": "Find buses from Mumbai to Delhi",
 *   "pageContext": {
 *     "sourceCityId": 1,
 *     "destinationCityId": 2,
 *     "dateOfJourney": "2026-06-25"
 *   }
 * }
 * }</pre>
 *
 * <p><b>Response:</b> Server-Sent Events stream with domain-specific event types:
 *
 * <ul>
 *   <li><code>connected</code> - Initial connection event
 *   <li><code>message</code> - Search results or intermediate updates
 *   <li><code>done</code> - Stream completion event
 *   <li><code>error</code> - Error event
 * </ul>
 *
 * <p><b>Note:</b> This is an example implementation. Applications should create their own
 * domain-specific controllers based on their requirements.
 *
 * @author Sandeep Belgavi
 * @since January 24, 2026
 * @see SseEventStreamService
 * @see SearchEventProcessor
 * @see SearchRequest
 */
@RestController
public class SearchSseController {

  private static final Logger log = LoggerFactory.getLogger(SearchSseController.class);

  private final RunnerService runnerService;
  private final SseEventStreamService sseEventStreamService;

  @Autowired
  public SearchSseController(
      RunnerService runnerService, SseEventStreamService sseEventStreamService) {
    this.runnerService = runnerService;
    this.sseEventStreamService = sseEventStreamService;
  }

  /**
   * Handles search queries with SSE streaming.
   *
   * <p>This endpoint accepts domain-specific search requests and streams results via SSE. It uses a
   * custom {@link SearchEventProcessor} to transform events into domain-specific formats and handle
   * business logic.
   *
   * @param request the search request containing query and context
   * @return SseEmitter that streams search results to the client
   * @throws ResponseStatusException if request validation fails
   */
  @PostMapping(value = "/search/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter searchSse(@RequestBody SearchRequest request) {
    // Validate request
    validateRequest(request);

    log.info(
        "Search SSE request received: clientId={}, sessionId={}, query={}",
        request.getMriClientId(),
        request.getMriSessionId(),
        request.getUserQuery());

    try {
      // Get runner for the app (assuming app name from request or default)
      String appName = request.getAppName() != null ? request.getAppName() : "search-app";
      Runner runner = runnerService.getRunner(appName);

      // Convert domain request to agent format
      Content userMessage = Content.fromParts(Part.fromText(request.getUserQuery()));
      String userId = request.getMriClientId();
      String sessionId = request.getMriSessionId();

      // Build state delta from page context
      Map<String, Object> stateDelta = buildStateDelta(request);

      // Build run config with SSE streaming
      RunConfig runConfig = RunConfig.builder().setStreamingMode(StreamingMode.SSE).build();

      // Create domain-specific event processor
      SearchEventProcessor eventProcessor =
          new SearchEventProcessor(
              request.getMriClientId(), request.getMriSessionId(), request.getPageContext());

      // Stream events using the service
      return sseEventStreamService.streamEvents(
          runner, appName, userId, sessionId, userMessage, runConfig, stateDelta, eventProcessor);

    } catch (ResponseStatusException e) {
      // Re-throw HTTP exceptions
      throw e;
    } catch (Exception e) {
      log.error(
          "Error setting up search SSE stream for session {}: {}",
          request.getMriSessionId(),
          e.getMessage(),
          e);
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "Failed to setup search SSE stream", e);
    }
  }

  /**
   * Validates the search request.
   *
   * @param request the request to validate
   * @throws ResponseStatusException if validation fails
   */
  private void validateRequest(SearchRequest request) {
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request cannot be null");
    }
    if (request.getMriClientId() == null || request.getMriClientId().trim().isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "mriClientId cannot be null or empty");
    }
    if (request.getMriSessionId() == null || request.getMriSessionId().trim().isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "mriSessionId cannot be null or empty");
    }
    if (request.getUserQuery() == null || request.getUserQuery().trim().isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "userQuery cannot be null or empty");
    }
  }

  /**
   * Builds state delta from search request page context.
   *
   * @param request the search request
   * @return state delta map
   */
  private Map<String, Object> buildStateDelta(SearchRequest request) {
    Map<String, Object> stateDelta = new HashMap<>();

    if (request.getPageContext() != null) {
      SearchRequest.PageContext pageContext = request.getPageContext();

      if (pageContext.getSourceCityId() != null) {
        stateDelta.put("fromCityId", pageContext.getSourceCityId().toString());
      }
      if (pageContext.getDestinationCityId() != null) {
        stateDelta.put("toCityId", pageContext.getDestinationCityId().toString());
      }
      if (pageContext.getDateOfJourney() != null) {
        stateDelta.put("dateOfJourney", pageContext.getDateOfJourney());
      }
    }

    // Add default date if not provided
    if (!stateDelta.containsKey("dateOfJourney")) {
      stateDelta.put(
          "dateOfJourney",
          java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE));
    }

    return stateDelta;
  }
}
