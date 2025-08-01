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

package com.google.adk.flows.llmflows;

import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.ReadonlyContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.utils.InstructionUtils;
import com.google.common.collect.ImmutableList;
import io.reactivex.rxjava3.core.Single;

/** {@link RequestProcessor} that handles instructions and global instructions for LLM flows. */
public final class Instructions implements RequestProcessor {
  public Instructions() {}

  @Override
  public Single<RequestProcessor.RequestProcessingResult> processRequest(
      InvocationContext context, LlmRequest request) {
    if (!(context.agent() instanceof LlmAgent)) {
      return Single.error(
          new IllegalArgumentException(
              "Agent in InvocationContext is not an instance of LlmAgent."));
    }
    LlmAgent agent = (LlmAgent) context.agent();
    ReadonlyContext readonlyContext = new ReadonlyContext(context);
    Single<LlmRequest.Builder> builderSingle = Single.just(request.toBuilder());
    if (agent.rootAgent() instanceof LlmAgent) {
      LlmAgent rootAgent = (LlmAgent) agent.rootAgent();
      builderSingle =
          builderSingle.flatMap(
              builder ->
                  rootAgent
                      .canonicalGlobalInstruction(readonlyContext)
                      .flatMap(
                          instructionEntry -> {
                            String globalInstr = instructionEntry.getKey();
                            boolean bypassStateInjection = instructionEntry.getValue();
                            if (!globalInstr.isEmpty()) {
                              if (bypassStateInjection) {
                                return Single.just(
                                    builder.appendInstructions(ImmutableList.of(globalInstr)));
                              }
                              return InstructionUtils.injectSessionState(context, globalInstr)
                                  .map(
                                      resolvedGlobalInstr ->
                                          builder.appendInstructions(
                                              ImmutableList.of(resolvedGlobalInstr)));
                            }
                            return Single.just(builder);
                          }));
    }

    builderSingle =
        builderSingle.flatMap(
            builder ->
                agent
                    .canonicalInstruction(readonlyContext)
                    .flatMap(
                        instructionEntry -> {
                          String agentInstr = instructionEntry.getKey();
                          boolean bypassStateInjection = instructionEntry.getValue();
                          if (!agentInstr.isEmpty()) {
                            if (bypassStateInjection) {
                              return Single.just(
                                  builder.appendInstructions(ImmutableList.of(agentInstr)));
                            }
                            return InstructionUtils.injectSessionState(context, agentInstr)
                                .map(
                                    resolvedAgentInstr ->
                                        builder.appendInstructions(
                                            ImmutableList.of(resolvedAgentInstr)));
                          }
                          return Single.just(builder);
                        }));

    return builderSingle.map(
        finalBuilder ->
            RequestProcessor.RequestProcessingResult.create(
                finalBuilder.build(), ImmutableList.of()));
  }
}
