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
package com.google.adk.models;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.functions.Predicate;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class GeminiTest {

  // Test cases for processRawResponses static method
  @Test
  public void processRawResponses_withTextChunks_emitsPartialResponses() {
    Flowable<GenerateContentResponse> rawResponses =
        Flowable.just(toResponseWithText("Hello"), toResponseWithText(" world"));

    Flowable<LlmResponse> llmResponses = Gemini.processRawResponses(rawResponses);

    assertLlmResponses(
        llmResponses, isPartialTextResponse("Hello"), isPartialTextResponse(" world"));
  }

  @Test
  public void
      processRawResponses_textThenFunctionCall_emitsPartialTextThenFullTextAndFunctionCall() {
    Flowable<GenerateContentResponse> rawResponses =
        Flowable.just(
            toResponseWithText("Thinking..."),
            toResponse(Part.fromFunctionCall("test_function", ImmutableMap.of())));

    Flowable<LlmResponse> llmResponses = Gemini.processRawResponses(rawResponses);

    assertLlmResponses(
        llmResponses,
        isPartialTextResponse("Thinking..."),
        isFinalTextResponse("Thinking..."),
        isFunctionCallResponse());
  }

  // Regression test for b/513501918. gemini-3.1-flash-lite emits an extra trailing chunk after a
  // function call: `{parts:[{text:""}], finishReason:STOP}`. That chunk must not be propagated as
  // a non-partial event because BaseLlmFlow#run would treat it as the final response and
  // terminate the loop before the function response is sent back to the model. The chunk's
  // metadata (e.g. `finishReason`, `usageMetadata`) is preserved by emitting it on a content-less
  // partial response instead of dropping the chunk entirely.
  @Test
  public void
      processRawResponses_functionCallThenEmptyTextWithStop_emitsFunctionCallAndMetadataOnlyPartial() {
    Flowable<GenerateContentResponse> rawResponses =
        Flowable.just(
            toResponse(Part.fromFunctionCall("test_function", ImmutableMap.of())),
            toResponseWithText("", FinishReason.Known.STOP));

    Flowable<LlmResponse> llmResponses = Gemini.processRawResponses(rawResponses);

    assertLlmResponses(
        llmResponses,
        isFunctionCallResponse(),
        isContentlessPartialWithFinishReason(FinishReason.Known.STOP));
  }

  // Same as above but with `usageMetadata` on the trailing empty chunk: the metadata must survive
  // on the emitted content-less partial.
  @Test
  public void
      processRawResponses_functionCallThenEmptyTextWithUsageMetadata_preservesUsageMetadata() {
    GenerateContentResponseUsageMetadata metadata = createUsageMetadata(5, 10, 15);
    Flowable<GenerateContentResponse> rawResponses =
        Flowable.just(
            toResponse(Part.fromFunctionCall("test_function", ImmutableMap.of())),
            toResponseWithText("", FinishReason.Known.STOP, metadata));

    Flowable<LlmResponse> llmResponses = Gemini.processRawResponses(rawResponses);

    assertLlmResponses(
        llmResponses, isFunctionCallResponse(), isContentlessPartialWithUsageMetadata(metadata));
  }

  // Same as above but without a finishReason or usageMetadata: the trailing empty chunk carries no
  // useful payload and must be suppressed entirely.
  @Test
  public void processRawResponses_functionCallThenEmptyText_doesNotEmitExtraEmptyResponse() {
    Flowable<GenerateContentResponse> rawResponses =
        Flowable.just(
            toResponse(Part.fromFunctionCall("test_function", ImmutableMap.of())),
            toResponseWithText(""));

    Flowable<LlmResponse> llmResponses = Gemini.processRawResponses(rawResponses);

    assertLlmResponses(llmResponses, isFunctionCallResponse());
  }

  // Combined scenario: leading partial text, then a function call, then the trailing empty-text
  // chunk with STOP. Accumulated text must still be flushed, the function call must still be
  // emitted, and the trailing chunk must surface only its metadata on a content-less partial.
  @Test
  public void
      processRawResponses_textThenFunctionCallThenEmptyTextWithStop_emitsTextFunctionCallAndMetadata() {
    Flowable<GenerateContentResponse> rawResponses =
        Flowable.just(
            toResponseWithText("Thinking..."),
            toResponse(Part.fromFunctionCall("test_function", ImmutableMap.of())),
            toResponseWithText("", FinishReason.Known.STOP));

    Flowable<LlmResponse> llmResponses = Gemini.processRawResponses(rawResponses);

    assertLlmResponses(
        llmResponses,
        isPartialTextResponse("Thinking..."),
        isFinalTextResponse("Thinking..."),
        isFunctionCallResponse(),
        isContentlessPartialWithFinishReason(FinishReason.Known.STOP));
  }

  @Test
  public void processRawResponses_textAndStopReason_emitsPartialThenFinalText() {
    Flowable<GenerateContentResponse> rawResponses =
        Flowable.just(
            toResponseWithText("Hello"), toResponseWithText(" world", FinishReason.Known.STOP));

    Flowable<LlmResponse> llmResponses = Gemini.processRawResponses(rawResponses);

    assertLlmResponses(
        llmResponses,
        isPartialTextResponse("Hello"),
        isPartialTextResponse(" world"),
        isFinalTextResponse("Hello world"));
  }

  @Test
  public void processRawResponses_emptyStream_emitsNothing() {
    Flowable<GenerateContentResponse> rawResponses = Flowable.empty();

    Flowable<LlmResponse> llmResponses = Gemini.processRawResponses(rawResponses);

    assertLlmResponses(llmResponses);
  }

  @Test
  public void processRawResponses_singleEmptyResponse_emitsOneEmptyResponse() {
    Flowable<GenerateContentResponse> rawResponses =
        Flowable.just(GenerateContentResponse.builder().build());

    Flowable<LlmResponse> llmResponses = Gemini.processRawResponses(rawResponses);

    assertLlmResponses(llmResponses, isEmptyResponse());
  }

  @Test
  public void processRawResponses_finishReasonNotStop_doesNotEmitFinalAccumulatedText() {
    Flowable<GenerateContentResponse> rawResponses =
        Flowable.just(
            toResponseWithText("Hello"),
            toResponseWithText(" world", FinishReason.Known.MAX_TOKENS));

    Flowable<LlmResponse> llmResponses = Gemini.processRawResponses(rawResponses);

    assertLlmResponses(
        llmResponses, isPartialTextResponse("Hello"), isPartialTextResponse(" world"));
  }

  @Test
  public void processRawResponses_textThenEmpty_emitsPartialTextThenFullTextAndEmpty() {
    Flowable<GenerateContentResponse> rawResponses =
        Flowable.just(toResponseWithText("Thinking..."), GenerateContentResponse.builder().build());

    Flowable<LlmResponse> llmResponses = Gemini.processRawResponses(rawResponses);

    assertLlmResponses(
        llmResponses,
        isPartialTextResponse("Thinking..."),
        isFinalTextResponse("Thinking..."),
        isEmptyResponse());
  }

  @Test
  public void processRawResponses_withTextChunks_partialResponsesIncludeUsageMetadata() {
    GenerateContentResponseUsageMetadata metadata1 = createUsageMetadata(5, 10, 15);
    GenerateContentResponseUsageMetadata metadata2 = createUsageMetadata(5, 20, 25);
    Flowable<GenerateContentResponse> rawResponses =
        Flowable.just(
            toResponseWithText("Hello", metadata1), toResponseWithText(" world", metadata2));

    Flowable<LlmResponse> llmResponses = Gemini.processRawResponses(rawResponses);

    assertLlmResponses(
        llmResponses,
        isPartialTextResponseWithUsageMetadata("Hello", metadata1),
        isPartialTextResponseWithUsageMetadata(" world", metadata2));
  }

  @Test
  public void processRawResponses_textAndStopReason_finalResponseIncludesUsageMetadata() {
    GenerateContentResponseUsageMetadata metadata = createUsageMetadata(10, 20, 30);
    Flowable<GenerateContentResponse> rawResponses =
        Flowable.just(
            toResponseWithText("Hello"),
            toResponseWithText(" world", FinishReason.Known.STOP, metadata));

    Flowable<LlmResponse> llmResponses = Gemini.processRawResponses(rawResponses);

    assertLlmResponses(
        llmResponses,
        isPartialTextResponse("Hello"),
        isPartialTextResponseWithUsageMetadata(" world", metadata),
        isFinalTextResponseWithUsageMetadata("Hello world", metadata));
  }

  @Test
  public void processRawResponses_thoughtChunksAndStop_includeUsageMetadata() {
    GenerateContentResponseUsageMetadata metadata1 = createUsageMetadata(5, 10, 15);
    GenerateContentResponseUsageMetadata metadata2 = createUsageMetadata(5, 20, 25);
    Flowable<GenerateContentResponse> rawResponses =
        Flowable.just(
            toResponseWithThoughtText("Thinking", metadata1),
            toResponseWithThoughtText(" deeply", FinishReason.Known.STOP, metadata2));

    Flowable<LlmResponse> llmResponses = Gemini.processRawResponses(rawResponses);

    assertLlmResponses(
        llmResponses,
        isPartialThoughtResponseWithUsageMetadata("Thinking", metadata1),
        isPartialThoughtResponseWithUsageMetadata(" deeply", metadata2),
        isFinalThoughtResponseWithUsageMetadata("Thinking deeply", metadata2));
  }

  // Test cases for the shouldEmit filter applied by generateContent after processRawResponses.
  // shouldEmit drops chunks that are empty-text-only AND carry no useful metadata; everything else
  // is forwarded. processRawResponses normally already strips empty-text-only chunks, so shouldEmit
  // is defense-in-depth, but it must still behave correctly when fed any LlmResponse directly.

  @Test
  public void shouldEmit_emptyTextOnlyResponseWithNoMetadata_returnsFalse() {
    LlmResponse response =
        LlmResponse.builder()
            .content(Content.builder().role("model").parts(Part.fromText("")).build())
            .build();

    assertThat(Gemini.shouldEmit(response)).isFalse();
  }

  @Test
  public void shouldEmit_emptyTextOnlyResponseWithFinishReason_returnsTrue() {
    LlmResponse response =
        LlmResponse.builder()
            .content(Content.builder().role("model").parts(Part.fromText("")).build())
            .finishReason(new FinishReason(FinishReason.Known.STOP))
            .build();

    assertThat(Gemini.shouldEmit(response)).isTrue();
  }

  @Test
  public void shouldEmit_emptyTextOnlyResponseWithUsageMetadata_returnsTrue() {
    LlmResponse response =
        LlmResponse.builder()
            .content(Content.builder().role("model").parts(Part.fromText("")).build())
            .usageMetadata(createUsageMetadata(5, 10, 15))
            .build();

    assertThat(Gemini.shouldEmit(response)).isTrue();
  }

  @Test
  public void shouldEmit_nonEmptyTextResponse_returnsTrue() {
    LlmResponse response =
        LlmResponse.builder()
            .content(Content.builder().role("model").parts(Part.fromText("hello")).build())
            .build();

    assertThat(Gemini.shouldEmit(response)).isTrue();
  }

  @Test
  public void shouldEmit_functionCallResponse_returnsTrue() {
    LlmResponse response =
        LlmResponse.builder()
            .content(
                Content.builder()
                    .role("model")
                    .parts(Part.fromFunctionCall("test_function", ImmutableMap.of()))
                    .build())
            .build();

    assertThat(Gemini.shouldEmit(response)).isTrue();
  }

  @Test
  public void shouldEmit_contentlessResponse_returnsTrue() {
    // A response with no content at all is not an empty-text-only response, so it should pass
    // through regardless of metadata. This is the shape emitted by processRawResponses after it
    // strips empty-text content while preserving metadata.
    LlmResponse response = LlmResponse.builder().build();

    assertThat(Gemini.shouldEmit(response)).isTrue();
  }

  @Test
  public void shouldEmit_multiPartResponseWithEmptyTextPart_returnsTrue() {
    // Only single-part empty-text responses are considered "empty-text-only". A multi-part response
    // is treated as carrying semantic content and must always pass through.
    LlmResponse response =
        LlmResponse.builder()
            .content(
                Content.builder()
                    .role("model")
                    .parts(Part.fromText(""), Part.fromText("hello"))
                    .build())
            .build();

    assertThat(Gemini.shouldEmit(response)).isTrue();
  }

  @Test
  public void processRawResponses_thoughtAndTextWithStop_onlyFinalTextIncludesUsageMetadata() {
    GenerateContentResponseUsageMetadata metadata1 = createUsageMetadata(5, 5, 10);
    GenerateContentResponseUsageMetadata metadata2 = createUsageMetadata(10, 20, 30);
    Flowable<GenerateContentResponse> rawResponses =
        Flowable.just(
            toResponseWithThoughtText("Thinking", metadata1),
            toResponseWithText("Answer", FinishReason.Known.STOP, metadata2));

    Flowable<LlmResponse> llmResponses = Gemini.processRawResponses(rawResponses);

    assertLlmResponses(
        llmResponses,
        isPartialThoughtResponseWithUsageMetadata("Thinking", metadata1),
        isPartialTextResponseWithUsageMetadata("Answer", metadata2),
        isFinalThoughtResponseWithNoUsageMetadata("Thinking"),
        isFinalTextResponseWithUsageMetadata("Answer", metadata2));
  }

  // Helper methods for assertions

  private void assertLlmResponses(
      Flowable<LlmResponse> llmResponses, Predicate<LlmResponse>... predicates) {
    TestSubscriber<LlmResponse> testSubscriber = llmResponses.test();
    testSubscriber.assertValueCount(predicates.length);
    for (int i = 0; i < predicates.length; i++) {
      testSubscriber.assertValueAt(i, predicates[i]);
    }
    testSubscriber.assertComplete();
    testSubscriber.assertNoErrors();
  }

  private static Predicate<LlmResponse> isPartialTextResponse(String expectedText) {
    return response -> {
      assertThat(response.partial()).hasValue(true);
      assertThat(GeminiUtil.getPart0FromLlmResponse(response).flatMap(Part::text).orElse(""))
          .isEqualTo(expectedText);
      return true;
    };
  }

  private static Predicate<LlmResponse> isFinalTextResponse(String expectedText) {
    return response -> {
      assertThat(response.partial()).isEmpty();
      assertThat(GeminiUtil.getPart0FromLlmResponse(response).flatMap(Part::text).orElse(""))
          .isEqualTo(expectedText);
      return true;
    };
  }

  private static Predicate<LlmResponse> isFunctionCallResponse() {
    return response -> {
      assertThat(response.content().get().parts().get().get(0).functionCall()).isNotNull();
      return true;
    };
  }

  private static Predicate<LlmResponse> isContentlessPartialWithFinishReason(
      FinishReason.Known expectedFinishReason) {
    return response -> {
      assertThat(response.partial()).hasValue(true);
      assertThat(response.content()).isEmpty();
      assertThat(response.finishReason().map(fr -> fr.knownEnum())).hasValue(expectedFinishReason);
      return true;
    };
  }

  private static Predicate<LlmResponse> isContentlessPartialWithUsageMetadata(
      GenerateContentResponseUsageMetadata expectedMetadata) {
    return response -> {
      assertThat(response.partial()).hasValue(true);
      assertThat(response.content()).isEmpty();
      assertThat(response.usageMetadata()).hasValue(expectedMetadata);
      return true;
    };
  }

  private static Predicate<LlmResponse> isEmptyResponse() {
    return response -> {
      assertThat(response.partial()).isEmpty();
      assertThat(GeminiUtil.getPart0FromLlmResponse(response).flatMap(Part::text).orElse(""))
          .isEmpty();
      return true;
    };
  }

  private static Predicate<LlmResponse> isPartialTextResponseWithUsageMetadata(
      String expectedText, GenerateContentResponseUsageMetadata expectedMetadata) {
    return response -> {
      assertThat(response.partial()).hasValue(true);
      assertThat(GeminiUtil.getPart0FromLlmResponse(response).flatMap(Part::text).orElse(""))
          .isEqualTo(expectedText);
      assertThat(response.usageMetadata()).hasValue(expectedMetadata);
      return true;
    };
  }

  private static Predicate<LlmResponse> isPartialThoughtResponseWithUsageMetadata(
      String expectedText, GenerateContentResponseUsageMetadata expectedMetadata) {
    return response -> {
      assertThat(response.partial()).hasValue(true);
      assertThat(GeminiUtil.getPart0FromLlmResponse(response).flatMap(Part::text).orElse(""))
          .isEqualTo(expectedText);
      assertThat(GeminiUtil.getPart0FromLlmResponse(response).flatMap(Part::thought).orElse(false))
          .isTrue();
      assertThat(response.usageMetadata()).hasValue(expectedMetadata);
      return true;
    };
  }

  private static Predicate<LlmResponse> isFinalTextResponseWithUsageMetadata(
      String expectedText, GenerateContentResponseUsageMetadata expectedMetadata) {
    return response -> {
      assertThat(response.partial()).isEmpty();
      assertThat(GeminiUtil.getPart0FromLlmResponse(response).flatMap(Part::text).orElse(""))
          .isEqualTo(expectedText);
      assertThat(response.usageMetadata()).hasValue(expectedMetadata);
      return true;
    };
  }

  private static Predicate<LlmResponse> isFinalThoughtResponseWithUsageMetadata(
      String expectedText, GenerateContentResponseUsageMetadata expectedMetadata) {
    return response -> {
      assertThat(response.partial()).isEmpty();
      assertThat(GeminiUtil.getPart0FromLlmResponse(response).flatMap(Part::text).orElse(""))
          .isEqualTo(expectedText);
      assertThat(GeminiUtil.getPart0FromLlmResponse(response).flatMap(Part::thought).orElse(false))
          .isTrue();
      assertThat(response.usageMetadata()).hasValue(expectedMetadata);
      return true;
    };
  }

  private static Predicate<LlmResponse> isFinalThoughtResponseWithNoUsageMetadata(
      String expectedText) {
    return response -> {
      assertThat(response.partial()).isEmpty();
      assertThat(GeminiUtil.getPart0FromLlmResponse(response).flatMap(Part::text).orElse(""))
          .isEqualTo(expectedText);
      assertThat(GeminiUtil.getPart0FromLlmResponse(response).flatMap(Part::thought).orElse(false))
          .isTrue();
      assertThat(response.usageMetadata()).isEmpty();
      return true;
    };
  }

  // Helper methods to create responses for testing

  private GenerateContentResponse toResponseWithText(String text) {
    return toResponse(Part.fromText(text));
  }

  private GenerateContentResponse toResponseWithText(String text, FinishReason.Known finishReason) {
    return toResponse(
        Candidate.builder()
            .content(Content.builder().parts(Part.fromText(text)).build())
            .finishReason(new FinishReason(finishReason))
            .build());
  }

  private GenerateContentResponse toResponse(Part part) {
    return toResponse(Candidate.builder().content(Content.builder().parts(part).build()).build());
  }

  private GenerateContentResponse toResponse(Candidate candidate) {
    return GenerateContentResponse.builder().candidates(candidate).build();
  }

  private GenerateContentResponse toResponseWithText(
      String text, GenerateContentResponseUsageMetadata usageMetadata) {
    return GenerateContentResponse.builder()
        .candidates(
            Candidate.builder()
                .content(Content.builder().parts(Part.fromText(text)).build())
                .build())
        .usageMetadata(usageMetadata)
        .build();
  }

  private GenerateContentResponse toResponseWithText(
      String text,
      FinishReason.Known finishReason,
      GenerateContentResponseUsageMetadata usageMetadata) {
    return GenerateContentResponse.builder()
        .candidates(
            Candidate.builder()
                .content(Content.builder().parts(Part.fromText(text)).build())
                .finishReason(new FinishReason(finishReason))
                .build())
        .usageMetadata(usageMetadata)
        .build();
  }

  private GenerateContentResponse toResponseWithThoughtText(
      String text, GenerateContentResponseUsageMetadata usageMetadata) {
    Part thoughtPart = Part.fromText(text).toBuilder().thought(true).build();
    return GenerateContentResponse.builder()
        .candidates(
            Candidate.builder().content(Content.builder().parts(thoughtPart).build()).build())
        .usageMetadata(usageMetadata)
        .build();
  }

  private GenerateContentResponse toResponseWithThoughtText(
      String text,
      FinishReason.Known finishReason,
      GenerateContentResponseUsageMetadata usageMetadata) {
    Part thoughtPart = Part.fromText(text).toBuilder().thought(true).build();
    return GenerateContentResponse.builder()
        .candidates(
            Candidate.builder()
                .content(Content.builder().parts(thoughtPart).build())
                .finishReason(new FinishReason(finishReason))
                .build())
        .usageMetadata(usageMetadata)
        .build();
  }

  private static GenerateContentResponseUsageMetadata createUsageMetadata(
      int promptTokens, int candidateTokens, int totalTokens) {
    return GenerateContentResponseUsageMetadata.builder()
        .promptTokenCount(promptTokens)
        .candidatesTokenCount(candidateTokens)
        .totalTokenCount(totalTokens)
        .build();
  }
}
