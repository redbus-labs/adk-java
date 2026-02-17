package com.google.adk.models;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assume.assumeNotNull;

import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class SarvamIT {

  private String apiKey;

  @Before
  public void setUp() {
    apiKey = System.getenv("SARVAM_API_KEY");
    // Skip test if API key is not set
    assumeNotNull(apiKey);
  }

  @Test
  public void testGenerateContent() {
    Sarvam sarvam = new Sarvam("sarvam-2.0", apiKey);

    LlmRequest request =
        LlmRequest.builder()
            .contents(
                java.util.Collections.singletonList(
                    Content.builder()
                        .role("user")
                        .parts(Part.fromText("Hello, say hi back!"))
                        .build()))
            .build();

    TestSubscriber<LlmResponse> subscriber = sarvam.generateContent(request, false).test();

    subscriber.awaitDone(30, java.util.concurrent.TimeUnit.SECONDS);
    subscriber.assertNoErrors();
    subscriber.assertValueCount(1);

    LlmResponse response = subscriber.values().get(0);
    assertThat(response.content().flatMap(Content::parts).get().get(0).text().get()).isNotEmpty();
  }
}
