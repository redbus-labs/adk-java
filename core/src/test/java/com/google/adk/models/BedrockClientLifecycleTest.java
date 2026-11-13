package com.google.adk.models;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.json.JSONArray;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClientBuilder;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamResponseHandler;

class BedrockClientLifecycleTest {
  @Test
  void recreatedModelInstancesReuseClientAcrossModelIds() {
    AtomicInteger constructions = new AtomicInteger();
    var client = mock(BedrockRuntimeAsyncClient.class);
    String endpoint = "https://" + UUID.randomUUID() + ".example.invalid";
    class Model extends BedrockBaseLM {
      Model(String name) {
        super(name, endpoint);
      }

      @Override
      BedrockRuntimeAsyncClient createBedrockRuntimeAsyncClient(String token) {
        constructions.incrementAndGet();
        return client;
      }
    }
    assertThat(new Model("model-a").createBedrockRuntimeAsyncClient()).isSameInstanceAs(client);
    assertThat(new Model("model-b").createBedrockRuntimeAsyncClient()).isSameInstanceAs(client);
    assertThat(constructions.get()).isEqualTo(1);
    verify(client, never()).close();
  }

  @Test
  void concurrentClientLookupBuildsOnceAndShutdownClosesOnce() throws Exception {
    var registry = new BedrockBaseLM.AsyncClients();
    var client = mock(BedrockRuntimeAsyncClient.class);
    AtomicInteger constructions = new AtomicInteger();
    Supplier<BedrockRuntimeAsyncClient> factory =
        () -> {
          constructions.incrementAndGet();
          return client;
        };
    var executor = Executors.newFixedThreadPool(8);
    try {
      var jobs = new ArrayList<java.util.concurrent.Callable<BedrockRuntimeAsyncClient>>();
      for (int i = 0; i < 32; i++)
        jobs.add(() -> registry.get(Region.US_EAST_1, null, null, factory));
      for (var result : executor.invokeAll(jobs)) assertThat(result.get()).isSameInstanceAs(client);
    } finally {
      executor.shutdownNow();
    }
    assertThat(constructions.get()).isEqualTo(1);
    registry.close();
    registry.close();
    verify(client).close();
    assertThrows(
        IllegalStateException.class, () -> registry.get(Region.US_EAST_1, null, null, factory));
  }

  @Test
  void differentRegionsEndpointsAndAuthenticationDoNotShareClients() {
    try (var registry = new BedrockBaseLM.AsyncClients()) {
      AtomicInteger constructions = new AtomicInteger();
      Supplier<BedrockRuntimeAsyncClient> factory =
          () -> {
            constructions.incrementAndGet();
            return mock(BedrockRuntimeAsyncClient.class);
          };
      var iam = registry.get(Region.US_EAST_1, null, null, factory);
      assertThat(registry.get(Region.US_EAST_1, null, "", factory)).isSameInstanceAs(iam);
      registry.get(Region.US_WEST_2, null, null, factory);
      registry.get(Region.US_EAST_1, URI.create("https://example.invalid"), null, factory);
      var bearer = registry.get(Region.US_EAST_1, null, "test-token-a", factory);
      assertThat(registry.get(Region.US_EAST_1, null, "test-token-a", factory))
          .isSameInstanceAs(bearer);
      registry.get(Region.US_EAST_1, null, "test-token-b", factory);
      assertThat(constructions.get()).isEqualTo(5);
    }
  }

  @Test
  void cancellingOneRequestDoesNotCancelAnotherOrCloseSharedClient() {
    var client = mock(BedrockRuntimeAsyncClient.class);
    var first = new CompletableFuture<Void>();
    var second = new CompletableFuture<Void>();
    List<ConverseStreamResponseHandler> handlers = new ArrayList<>();
    when(client.converseStream(
            any(ConverseStreamRequest.class), any(ConverseStreamResponseHandler.class)))
        .thenAnswer(
            call -> {
              handlers.add(call.getArgument(1));
              return handlers.size() == 1 ? first : second;
            });
    var model = model(client);
    var cancelled = stream(model).test();
    var continuing = stream(model).test();
    var subscription = mock(org.reactivestreams.Subscription.class);
    handlers.get(0).onEventStream(subscriber -> subscriber.onSubscribe(subscription));
    cancelled.cancel();
    verify(subscription).cancel();
    assertThat(first.isCancelled()).isTrue();
    assertThat(second.isCancelled()).isFalse();
    handlers.get(1).complete();
    second.complete(null);
    continuing.awaitDone(5, TimeUnit.SECONDS).assertComplete().assertNoErrors();
    verify(client, never()).close();
  }

  @Test
  void cancellationBeforeSdkReturnsFutureStillCancelsThatRequest() {
    var client = mock(BedrockRuntimeAsyncClient.class);
    var pending = new CompletableFuture<Void>();
    var subscriber = new io.reactivex.rxjava3.subscribers.TestSubscriber<LlmResponse>();
    when(client.converseStream(
            any(ConverseStreamRequest.class), any(ConverseStreamResponseHandler.class)))
        .thenAnswer(
            call -> {
              subscriber.cancel();
              return pending;
            });
    stream(model(client)).subscribe(subscriber);
    assertThat(pending.isCancelled()).isTrue();
    verify(client, never()).close();
  }

  @Test
  void futureFailureWithoutHandlerCallbackPropagatesWithoutClosingClient() {
    var client = mock(BedrockRuntimeAsyncClient.class);
    var pending = new CompletableFuture<Void>();
    when(client.converseStream(
            any(ConverseStreamRequest.class), any(ConverseStreamResponseHandler.class)))
        .thenReturn(pending);
    var result = stream(model(client)).test();
    pending.completeExceptionally(
        SdkClientException.create("Acquire operation took longer than 10000 milliseconds"));
    result
        .awaitDone(5, TimeUnit.SECONDS)
        .assertError(
            error ->
                error.getMessage().contains("Acquire operation")
                    && !error.getMessage().contains("default credential chain"));
    verify(client, never()).close();
  }

  @Test
  void noBearerTokenSelectsDefaultCredentialsWhileBearerSelectsTokenAuth() {
    var builder = mock(BedrockRuntimeAsyncClientBuilder.class, RETURNS_SELF);
    var client = mock(BedrockRuntimeAsyncClient.class);
    when(builder.build()).thenReturn(client);
    var model = new BedrockBaseLM("model");
    model.createBedrockRuntimeAsyncClient(null, builder);
    var credentials =
        org.mockito.ArgumentCaptor.forClass(
            software.amazon.awssdk.auth.credentials.AwsCredentialsProvider.class);
    verify(builder).credentialsProvider(credentials.capture());
    assertThat(credentials.getValue())
        .isInstanceOf(software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider.class);
    ((software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider) credentials.getValue())
        .close();
    verify(builder, never()).tokenProvider(any());
    clearInvocations(builder);
    model.createBedrockRuntimeAsyncClient("test-token", builder);
    verify(builder).tokenProvider(any());
    verify(builder).authSchemeProvider(any());
    verify(builder, never()).credentialsProvider(any());
  }

  private static BedrockBaseLM model(BedrockRuntimeAsyncClient client) {
    return new BedrockBaseLM("model") {
      @Override
      BedrockRuntimeAsyncClient createBedrockRuntimeAsyncClient() {
        return client;
      }
    };
  }

  private static io.reactivex.rxjava3.core.Flowable<LlmResponse> stream(BedrockBaseLM model) {
    return model.createAwsSdkStreamingResponse("model", new JSONArray(), new JSONArray(), null);
  }
}
