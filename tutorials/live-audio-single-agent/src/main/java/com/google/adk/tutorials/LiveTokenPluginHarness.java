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
package com.google.adk.tutorials;

import com.google.adk.agents.LiveRequestQueue;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.plugins.LiveTokenTrackingPlugin;
import com.google.adk.runner.Runner;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Modality;
import com.google.genai.types.Part;

/**
 * Manual harness that drives a live BIDI session and verifies that {@link LiveTokenTrackingPlugin}
 * receives token usage through the plugin callbacks.
 *
 * <p>Requires GOOGLE_API_KEY in the environment. Optional system properties: {@code -Dmodel=...} to
 * pick the live model.
 */
public class LiveTokenPluginHarness {
  public static void main(String[] args) {
    String model = System.getProperty("model", "gemini-2.0-flash-live-001");

    LlmAgent agent =
        LlmAgent.builder()
            .name("audio_agent")
            .model(model)
            .instruction("You are a helpful assistant. Briefly introduce yourself.")
            .build();

    LiveTokenTrackingPlugin tokenPlugin = new LiveTokenTrackingPlugin();

    // Printer plugin registered BEFORE tokenPlugin so its afterRunCallback reads the aggregated
    // usage before tokenPlugin's own afterRunCallback clears the state. Proves the hook fires with
    // accumulated data (the tutorial's slf4j-simple binding suppresses the plugin's info log).
    com.google.adk.plugins.BasePlugin printer =
        new com.google.adk.plugins.BasePlugin("usage_printer") {
          @Override
          public io.reactivex.rxjava3.core.Completable afterRunCallback(
              com.google.adk.agents.InvocationContext invocationContext) {
            System.out.println(
                "[afterRunCallback] aggregated usage = "
                    + tokenPlugin.usageFor(invocationContext.invocationId()));
            return io.reactivex.rxjava3.core.Completable.complete();
          }
        };

    Runner runner =
        Runner.builder()
            .agent(agent)
            .appName("token_harness")
            .plugins(printer, tokenPlugin)
            .build();

    RunConfig runConfig =
        RunConfig.builder()
            .autoCreateSession(true)
            .streamingMode(RunConfig.StreamingMode.BIDI)
            .responseModalities(ImmutableList.of(new Modality(Modality.Known.AUDIO)))
            .build();

    Content userMessage =
        Content.builder()
            .role("user")
            .parts(ImmutableList.of(Part.fromText("Please introduce yourself in one sentence.")))
            .build();

    LiveRequestQueue liveRequestQueue = new LiveRequestQueue();
    liveRequestQueue.content(userMessage);

    System.out.println("Model: " + model);
    System.out.println("Starting live BIDI session...");

    runner
        .runLive("user1", "session1", liveRequestQueue, runConfig)
        .doOnNext(
            event -> {
              if (event.usageMetadata().isPresent()) {
                System.out.println("Event carried usageMetadata: " + event.usageMetadata().get());
              }
              if ("audio_agent".equals(event.author()) && event.turnComplete().orElse(false)) {
                liveRequestQueue.close();
              }
            })
        .doOnError(Throwable::printStackTrace)
        .blockingSubscribe();

    System.out.println("\n=== Plugin-captured usage (invocation lookups) ===");
    System.out.println(
        "NOTE: afterRunCallback clears state on completion; the per-event log above is the live"
            + " proof the plugin saw the data.");
    System.out.println("Done.");
    System.exit(0);
  }
}
