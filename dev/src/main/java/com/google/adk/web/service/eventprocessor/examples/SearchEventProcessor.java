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

package com.google.adk.web.service.eventprocessor.examples;

import com.google.adk.events.Event;
import com.google.adk.web.controller.examples.dto.SearchRequest;
import com.google.adk.web.service.eventprocessor.EventProcessor;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Example domain-specific event processor for search functionality.
 *
 * <p>This processor demonstrates how to:
 *
 * <ul>
 *   <li>Filter and transform events based on domain logic
 *   <li>Accumulate events for consolidation
 *   <li>Format responses in domain-specific JSON structures
 *   <li>Send custom event types (connected, message, done, error)
 * </ul>
 *
 * <p><b>Event Processing Strategy:</b>
 *
 * <ol>
 *   <li>Send "connected" event when stream starts
 *   <li>Filter intermediate events (only process final results)
 *   <li>Transform final results into domain-specific format
 *   <li>Send "message" event with formatted results
 *   <li>Send "done" event when stream completes
 * </ol>
 *
 * <p><b>Usage:</b> This processor is used by {@link
 * com.google.adk.web.controller.examples.SearchSseController} to handle search-specific event
 * processing.
 *
 * @author Sandeep Belgavi
 * @since January 24, 2026
 * @see EventProcessor
 * @see com.google.adk.web.controller.examples.SearchSseController
 */
public class SearchEventProcessor implements EventProcessor {

  private static final Logger log = LoggerFactory.getLogger(SearchEventProcessor.class);

  private final String mriClientId;
  private final String mriSessionId;
  private final SearchRequest.PageContext pageContext;
  private final AtomicReference<String> finalResponse = new AtomicReference<>("");

  /**
   * Creates a new SearchEventProcessor.
   *
   * @param mriClientId the client ID
   * @param mriSessionId the session ID
   * @param pageContext the page context (can be null)
   */
  public SearchEventProcessor(
      String mriClientId, String mriSessionId, SearchRequest.PageContext pageContext) {
    this.mriClientId = mriClientId;
    this.mriSessionId = mriSessionId;
    this.pageContext = pageContext;
  }

  @Override
  public void onStreamStart(SseEmitter emitter, Map<String, Object> context) {
    try {
      // Send initial connection event
      JsonObject connectedEvent = new JsonObject();
      connectedEvent.addProperty("status", "connected");
      connectedEvent.addProperty("mriClientId", mriClientId);
      connectedEvent.addProperty("mriSessionId", mriSessionId);
      connectedEvent.addProperty("timestamp", System.currentTimeMillis());

      emitter.send(SseEmitter.event().name("connected").data(connectedEvent.toString()));
      log.debug("Sent connected event for session: {}", mriSessionId);
    } catch (Exception e) {
      log.error(
          "Error sending connected event for session {}: {}", mriSessionId, e.getMessage(), e);
    }
  }

  @Override
  public Optional<String> processEvent(Event event, Map<String, Object> context) {
    try {
      // Only process events with final results
      if (event.actions().stateDelta().containsKey("finalResult")) {
        String finalResult = (String) event.actions().stateDelta().get("finalResult");
        String formattedResponse = formatSearchResponse(finalResult);
        finalResponse.set(formattedResponse);
        return Optional.of(formattedResponse);
      }

      // Also check for finalResultWithReviews
      if (event.actions().stateDelta().containsKey("finalResultWithReviews")) {
        String finalResult = (String) event.actions().stateDelta().get("finalResult");
        String formattedResponse = formatSearchResponse(finalResult);
        finalResponse.set(formattedResponse);
        return Optional.of(formattedResponse);
      }

      // Filter out intermediate events (don't send to client)
      return Optional.empty();

    } catch (Exception e) {
      log.error("Error processing event for session {}: {}", mriSessionId, e.getMessage(), e);
      return Optional.empty();
    }
  }

  @Override
  public void onStreamComplete(SseEmitter emitter, Map<String, Object> context) {
    try {
      // Send final message if we have one
      if (!finalResponse.get().isEmpty()) {
        emitter.send(SseEmitter.event().name("message").data(finalResponse.get()));
        log.debug("Sent final message event for session: {}", mriSessionId);
      }

      // Send done event
      JsonObject doneEvent = new JsonObject();
      doneEvent.addProperty("status", "complete");
      doneEvent.addProperty("timestamp", System.currentTimeMillis());

      emitter.send(SseEmitter.event().name("done").data(doneEvent.toString()));
      log.debug("Sent done event for session: {}", mriSessionId);
    } catch (Exception e) {
      log.error(
          "Error sending completion events for session {}: {}", mriSessionId, e.getMessage(), e);
    }
  }

  @Override
  public void onStreamError(SseEmitter emitter, Throwable error, Map<String, Object> context) {
    try {
      JsonObject errorEvent = new JsonObject();
      errorEvent.addProperty("error", error.getClass().getSimpleName());
      errorEvent.addProperty(
          "message", error.getMessage() != null ? error.getMessage() : "Unknown error");
      errorEvent.addProperty("timestamp", System.currentTimeMillis());

      emitter.send(SseEmitter.event().name("error").data(errorEvent.toString()));
      log.error("Sent error event for session {}: {}", mriSessionId, error.getMessage());
    } catch (Exception e) {
      log.error("Error sending error event for session {}: {}", mriSessionId, e.getMessage(), e);
    }
  }

  /**
   * Formats the search result into domain-specific JSON structure.
   *
   * @param finalResult the raw final result from the agent
   * @return formatted JSON string
   */
  private String formatSearchResponse(String finalResult) {
    try {
      JsonObject rootObject = new JsonObject();
      rootObject.addProperty("eventType", "SERVER_RESPONSE");
      rootObject.addProperty("mriClientId", mriClientId);
      rootObject.addProperty("mriSessionId", mriSessionId);

      JsonObject payloadObject = new JsonObject();
      payloadObject.addProperty("responseType", "INVENTORY_LIST");
      payloadObject.addProperty("displayText", "Here are a few buses for your journey:");

      // Parse inventories from final result
      if (finalResult != null && !finalResult.trim().isEmpty()) {
        try {
          JsonArray inventoriesArray = JsonParser.parseString(finalResult).getAsJsonArray();
          if (inventoriesArray.size() == 0) {
            payloadObject.addProperty(
                "displayText",
                "Sorry, no buses are available for your journey on the selected date. "
                    + "Please try a different date or route.");
          }
          payloadObject.add("inventories", inventoriesArray);
        } catch (Exception e) {
          log.warn("Failed to parse inventories from final result: {}", e.getMessage());
          payloadObject.addProperty("displayText", finalResult);
        }
      }

      rootObject.add("payload", payloadObject);
      rootObject.addProperty("InputStatus", "confirm");
      rootObject.addProperty("timestamp", System.currentTimeMillis());

      return rootObject.toString();
    } catch (Exception e) {
      log.error("Error formatting search response: {}", e.getMessage(), e);
      // Return a simple error response
      JsonObject errorResponse = new JsonObject();
      errorResponse.addProperty("error", "Failed to format response");
      errorResponse.addProperty("message", e.getMessage());
      return errorResponse.toString();
    }
  }
}
