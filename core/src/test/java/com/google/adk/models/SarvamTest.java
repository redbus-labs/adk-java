package com.google.adk.models;

import static com.google.common.truth.Truth.assertThat;

import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.io.IOException;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class SarvamTest {

  private MockWebServer mockWebServer;
  private Sarvam sarvam;

  @Before
  public void setUp() throws IOException {
    mockWebServer = new MockWebServer();
    mockWebServer.start();
    // Use the protected constructor to inject the mock server URL and client
    sarvam =
        new Sarvam("sarvam-2.0", "fake-key", mockWebServer.url("/").toString(), new OkHttpClient());
  }

  @After
  public void tearDown() throws IOException {
    mockWebServer.shutdown();
  }

  @Test
  public void generateContent_nonStreaming_success() {
    String jsonResponse = "{\"choices\": [{\"message\": {\"content\": \"Hello world\"}}]}";
    mockWebServer.enqueue(new MockResponse().setBody(jsonResponse));

    LlmRequest request =
        LlmRequest.builder()
            .contents(
                java.util.Collections.singletonList(
                    Content.builder().role("user").parts(Part.fromText("Hi")).build()))
            .build();

    TestSubscriber<LlmResponse> subscriber = sarvam.generateContent(request, false).test();

    subscriber.awaitDone(5, java.util.concurrent.TimeUnit.SECONDS);
    subscriber.assertNoErrors();
    subscriber.assertValueCount(1);

    LlmResponse response = subscriber.values().get(0);
    assertThat(response.content().flatMap(Content::parts).get().get(0).text().get())
        .isEqualTo("Hello world");
  }

  @Test
  public void generateContent_streaming_success() {
    String chunk1 = "data: {\"choices\": [{\"delta\": {\"content\": \"Hello\"}}]}\n\n";
    String chunk2 = "data: {\"choices\": [{\"delta\": {\"content\": \" world\"}}]}\n\n";
    String done = "data: [DONE]\n\n";

    mockWebServer.enqueue(new MockResponse().setBody(chunk1 + chunk2 + done));

    LlmRequest request =
        LlmRequest.builder()
            .contents(
                java.util.Collections.singletonList(
                    Content.builder().role("user").parts(Part.fromText("Hi")).build()))
            .build();

    TestSubscriber<LlmResponse> subscriber = sarvam.generateContent(request, true).test();

    subscriber.awaitDone(5, java.util.concurrent.TimeUnit.SECONDS);
    subscriber.assertNoErrors();
    subscriber.assertValueCount(2);

    assertThat(
            subscriber.values().get(0).content().flatMap(Content::parts).get().get(0).text().get())
        .isEqualTo("Hello");
    assertThat(
            subscriber.values().get(1).content().flatMap(Content::parts).get().get(0).text().get())
        .isEqualTo(" world");
  }

  @Test
  public void generateContent_error() {
    mockWebServer.enqueue(new MockResponse().setResponseCode(500).setBody("Internal Error"));

    LlmRequest request =
        LlmRequest.builder()
            .contents(
                java.util.Collections.singletonList(
                    Content.builder().role("user").parts(Part.fromText("Hi")).build()))
            .build();

    TestSubscriber<LlmResponse> subscriber = sarvam.generateContent(request, false).test();

    subscriber.awaitDone(5, java.util.concurrent.TimeUnit.SECONDS);
    subscriber.assertError(IOException.class);
  }
}
