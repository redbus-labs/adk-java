package com.google.adk.tutorials;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.runner.Runner;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Modality;
import com.google.genai.types.ModalityTokenCount;
import com.google.genai.types.Part;

public class LiveAudioTokensTest {
  public static void main(String[] args) {
    LlmAgent agent =
        LlmAgent.builder()
            .name("audio_agent")
            .model("gemini-2.5-pro") // using the requested model
            .instruction(
                "You are a helpful assistant. Please say 'Hello, how can I help you today?'")
            .build();

    Runner runner = Runner.builder().agent(agent).appName("audio_test").build();

    RunConfig runConfig =
        RunConfig.builder()
            .autoCreateSession(true)
            .responseModalities(ImmutableList.of(new Modality(Modality.Known.AUDIO)))
            .build();

    Content userMessage =
        Content.builder()
            .role("user")
            .parts(ImmutableList.of(Part.fromText("Please introduce yourself.")))
            .build();

    System.out.println("Sending request to model...");

    runner
        .runAsync("user1", "session1", userMessage, runConfig)
        .doOnNext(
            event -> {
              if (event.author() != null && event.author().equals("model")) {
                if (event.content().isPresent()) {
                  Content c = event.content().get();
                  for (Part p : c.parts().get()) {
                    if (p.text().isPresent()) {
                      System.out.println("Text: " + p.text().get());
                    }
                  }
                }
                if (event.usageMetadata().isPresent()) {
                  GenerateContentResponseUsageMetadata usage = event.usageMetadata().get();
                  System.out.println("Total Tokens: " + usage.totalTokenCount().orElse(0));

                  if (usage.promptTokensDetails().isPresent()) {
                    for (ModalityTokenCount mtc : usage.promptTokensDetails().get()) {
                      System.out.println(
                          "Prompt Modality: "
                              + mtc.modality().get()
                              + " Tokens: "
                              + mtc.tokenCount().get());
                    }
                  }
                  if (usage.candidatesTokensDetails().isPresent()) {
                    for (ModalityTokenCount mtc : usage.candidatesTokensDetails().get()) {
                      System.out.println(
                          "Completion Modality: "
                              + mtc.modality().get()
                              + " Tokens: "
                              + mtc.tokenCount().get());
                    }
                  }
                }
              }
            })
        .doOnError(Throwable::printStackTrace)
        .blockingSubscribe();

    System.out.println("Done.");
    System.exit(0);
  }
}
