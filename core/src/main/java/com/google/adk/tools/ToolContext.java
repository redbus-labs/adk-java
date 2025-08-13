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

package com.google.adk.tools;

import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.EventActions;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Optional;

/** ToolContext object provides a structured context for executing tools or functions. */
public class ToolContext extends CallbackContext {
  private Optional<String> functionCallId = Optional.empty();

  private ToolContext(
      InvocationContext invocationContext,
      EventActions eventActions,
      Optional<String> functionCallId) {
    super(invocationContext, eventActions);
    this.functionCallId = functionCallId;
  }

  public EventActions actions() {
    return this.eventActions;
  }

  public void setActions(EventActions actions) {
    this.eventActions = actions;
  }

  public Optional<String> functionCallId() {
    return functionCallId;
  }

  public void functionCallId(String functionCallId) {
    this.functionCallId = Optional.ofNullable(functionCallId);
  }

  @SuppressWarnings("unused")
  private void requestCredential() {
    // TODO: b/414678311 - Implement credential request logic. Make this public.
    throw new UnsupportedOperationException("Credential request not implemented yet.");
  }

  @SuppressWarnings("unused")
  private void getAuthResponse() {
    // TODO: b/414678311 - Implement auth response retrieval logic. Make this public.
    throw new UnsupportedOperationException("Auth response retrieval not implemented yet.");
  }

  @SuppressWarnings("unused")
  private void searchMemory() {
    // TODO: b/414680316 - Implement search memory logic. Make this public.
    throw new UnsupportedOperationException("Search memory not implemented yet.");
  }

  public static Builder builder(InvocationContext invocationContext) {
    return new Builder(invocationContext);
  }

  public Builder toBuilder() {
    return new Builder(invocationContext)
        .actions(eventActions)
        .functionCallId(functionCallId.orElse(null));
  }

  /** Builder for {@link ToolContext}. */
  public static final class Builder {
    private final InvocationContext invocationContext;
    private EventActions eventActions = EventActions.builder().build(); // Default empty actions
    private Optional<String> functionCallId = Optional.empty();

    private Builder(InvocationContext invocationContext) {
      this.invocationContext = invocationContext;
    }

    @CanIgnoreReturnValue
    public Builder actions(EventActions actions) {
      this.eventActions = actions;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder functionCallId(String functionCallId) {
      this.functionCallId = Optional.ofNullable(functionCallId);
      return this;
    }

    public ToolContext build() {
      return new ToolContext(invocationContext, eventActions, functionCallId);
    }
  }
}
