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
package com.google.adk.a2a.converters;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.adk.agents.InvocationContext;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import io.a2a.spec.Message;
import io.a2a.spec.Part;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Converter for ADK Events to A2A Messages. */
public final class EventConverter {
  private static final Logger logger = LoggerFactory.getLogger(EventConverter.class);

  private EventConverter() {}

  /**
   * Converts an ADK InvocationContext to an A2A Message.
   *
   * <p>It combines all the events in the session, plus the user content, converted into A2A Parts,
   * into a single A2A Message.
   *
   * <p>If the context has no events, or no suitable content to build the message, an empty optional
   * is returned.
   *
   * @param context The ADK InvocationContext to convert.
   * @return The converted A2A Message.
   */
  public static Optional<Message> convertEventsToA2AMessage(InvocationContext context) {
    if (context.session().events().isEmpty()) {
      logger.warn("No events in session, cannot convert to A2A message.");
      return Optional.empty();
    }

    ImmutableList.Builder<Part<?>> partsBuilder = ImmutableList.builder();

    context
        .session()
        .events()
        .forEach(
            event ->
                partsBuilder.addAll(
                    contentToParts(event.content(), event.partial().orElse(false))));
    partsBuilder.addAll(contentToParts(context.userContent(), false));

    ImmutableList<Part<?>> parts = partsBuilder.build();

    if (parts.isEmpty()) {
      logger.warn("No suitable content found to build A2A request message.");
      return Optional.empty();
    }

    return Optional.of(
        new Message.Builder()
            .messageId(UUID.randomUUID().toString())
            .parts(parts)
            .role(Message.Role.USER)
            .build());
  }

  public static ImmutableList<Part<?>> contentToParts(
      Optional<Content> content, boolean isPartial) {
    return content.flatMap(Content::parts).stream()
        .flatMap(Collection::stream)
        .map(part -> PartConverter.fromGenaiPart(part, isPartial))
        .collect(toImmutableList());
  }
}
