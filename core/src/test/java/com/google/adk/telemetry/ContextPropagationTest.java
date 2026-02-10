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

package com.google.adk.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for OpenTelemetry context propagation in ADK.
 *
 * <p>Verifies that spans created by ADK properly link to parent contexts when available, enabling
 * proper distributed tracing across async boundaries.
 */
@RunWith(JUnit4.class)
public class ContextPropagationTest {
  @Rule public final OpenTelemetryRule openTelemetryRule = OpenTelemetryRule.create();

  private Tracer tracer;
  private Tracer originalTracer;

  @Before
  public void setup() {
    this.originalTracer = Tracing.getTracer();
    Tracing.setTracerForTesting(
        openTelemetryRule.getOpenTelemetry().getTracer("ContextPropagationTest"));
    tracer = openTelemetryRule.getOpenTelemetry().getTracer("test");
  }

  @After
  public void tearDown() {
    Tracing.setTracerForTesting(originalTracer);
  }

  @Test
  public void testToolCallSpanLinksToParent() {
    // Given: Parent span is active
    Span parentSpan = tracer.spanBuilder("parent").startSpan();

    try (Scope scope = parentSpan.makeCurrent()) {
      // When: ADK creates tool_call span with setParent(Context.current())
      Span toolCallSpan =
          tracer.spanBuilder("tool_call [testTool]").setParent(Context.current()).startSpan();

      try (Scope toolScope = toolCallSpan.makeCurrent()) {
        // Simulate tool execution
      } finally {
        toolCallSpan.end();
      }
    } finally {
      parentSpan.end();
    }

    // Then: tool_call should be child of parent
    List<SpanData> spans = openTelemetryRule.getSpans();
    assertEquals("Should have 2 spans: parent and tool_call", 2, spans.size());

    SpanData parentSpanData =
        spans.stream()
            .filter(s -> s.getName().equals("parent"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Parent span not found"));

    SpanData toolCallSpanData =
        spans.stream()
            .filter(s -> s.getName().equals("tool_call [testTool]"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Tool call span not found"));

    // Verify parent-child relationship
    assertEquals(
        "Tool call should have same trace ID as parent",
        parentSpanData.getSpanContext().getTraceId(),
        toolCallSpanData.getSpanContext().getTraceId());

    assertEquals(
        "Tool call's parent should be the parent span",
        parentSpanData.getSpanContext().getSpanId(),
        toolCallSpanData.getParentSpanContext().getSpanId());
  }

  @Test
  public void testToolCallWithoutParentCreatesRootSpan() {
    // Given: No parent span active
    // When: ADK creates tool_call span with setParent(Context.current())
    try (Scope s = Context.root().makeCurrent()) {
      Span toolCallSpan =
          tracer.spanBuilder("tool_call [testTool]").setParent(Context.current()).startSpan();

      try (Scope scope = toolCallSpan.makeCurrent()) {
        // Work
      } finally {
        toolCallSpan.end();
      }
    }

    // Then: Should create root span (backward compatible)
    List<SpanData> spans = openTelemetryRule.getSpans();
    assertEquals("Should have exactly 1 span", 1, spans.size());

    SpanData toolCallSpanData = spans.get(0);
    assertFalse(
        "Tool call should be root span when no parent exists",
        toolCallSpanData.getParentSpanContext().isValid());
  }

  @Test
  public void testNestedSpanHierarchy() {
    // Test: parent → invocation → tool_call → tool_response hierarchy

    Span parentSpan = tracer.spanBuilder("parent").startSpan();

    try (Scope parentScope = parentSpan.makeCurrent()) {

      Span invocationSpan =
          tracer.spanBuilder("invocation").setParent(Context.current()).startSpan();

      try (Scope invocationScope = invocationSpan.makeCurrent()) {

        Span toolCallSpan =
            tracer.spanBuilder("tool_call [testTool]").setParent(Context.current()).startSpan();

        try (Scope toolScope = toolCallSpan.makeCurrent()) {

          Span toolResponseSpan =
              tracer
                  .spanBuilder("tool_response [testTool]")
                  .setParent(Context.current())
                  .startSpan();

          toolResponseSpan.end();
        } finally {
          toolCallSpan.end();
        }
      } finally {
        invocationSpan.end();
      }
    } finally {
      parentSpan.end();
    }

    // Verify complete hierarchy
    List<SpanData> spans = openTelemetryRule.getSpans();
    assertEquals("Should have 4 spans in the hierarchy", 4, spans.size());

    String parentTraceId =
        spans.stream()
            .filter(s -> s.getName().equals("parent"))
            .findFirst()
            .map(s -> s.getSpanContext().getTraceId())
            .orElseThrow(() -> new AssertionError("Parent span not found"));

    // All spans should have same trace ID
    spans.forEach(
        span ->
            assertEquals(
                "All spans should be in same trace",
                parentTraceId,
                span.getSpanContext().getTraceId()));

    // Verify parent-child relationships
    SpanData parentSpanData = findSpanByName(spans, "parent");
    SpanData invocationSpanData = findSpanByName(spans, "invocation");
    SpanData toolCallSpanData = findSpanByName(spans, "tool_call [testTool]");
    SpanData toolResponseSpanData = findSpanByName(spans, "tool_response [testTool]");

    // invocation should be child of parent
    assertEquals(
        "Invocation should be child of parent",
        parentSpanData.getSpanContext().getSpanId(),
        invocationSpanData.getParentSpanContext().getSpanId());

    // tool_call should be child of invocation
    assertEquals(
        "Tool call should be child of invocation",
        invocationSpanData.getSpanContext().getSpanId(),
        toolCallSpanData.getParentSpanContext().getSpanId());

    // tool_response should be child of tool_call
    assertEquals(
        "Tool response should be child of tool call",
        toolCallSpanData.getSpanContext().getSpanId(),
        toolResponseSpanData.getParentSpanContext().getSpanId());
  }

  @Test
  public void testMultipleSpansInParallel() {
    // Test: Multiple tool calls in parallel should all link to same parent

    Span parentSpan = tracer.spanBuilder("parent").startSpan();

    try (Scope parentScope = parentSpan.makeCurrent()) {
      // Simulate parallel tool calls
      Span toolCall1 =
          tracer.spanBuilder("tool_call [tool1]").setParent(Context.current()).startSpan();
      Span toolCall2 =
          tracer.spanBuilder("tool_call [tool2]").setParent(Context.current()).startSpan();
      Span toolCall3 =
          tracer.spanBuilder("tool_call [tool3]").setParent(Context.current()).startSpan();

      toolCall1.end();
      toolCall2.end();
      toolCall3.end();
    } finally {
      parentSpan.end();
    }

    // Verify all tool calls link to same parent
    List<SpanData> spans = openTelemetryRule.getSpans();
    assertEquals("Should have 4 spans: 1 parent + 3 tool calls", 4, spans.size());

    SpanData parentSpanData = findSpanByName(spans, "parent");
    String parentTraceId = parentSpanData.getSpanContext().getTraceId();
    String parentSpanId = parentSpanData.getSpanContext().getSpanId();

    // All tool calls should have same trace ID and parent span ID
    List<SpanData> toolCallSpans =
        spans.stream().filter(s -> s.getName().startsWith("tool_call")).toList();

    assertEquals("Should have 3 tool call spans", 3, toolCallSpans.size());

    toolCallSpans.forEach(
        span -> {
          assertEquals(
              "Tool call should have same trace ID as parent",
              parentTraceId,
              span.getSpanContext().getTraceId());
          assertEquals(
              "Tool call should have parent as parent span",
              parentSpanId,
              span.getParentSpanContext().getSpanId());
        });
  }

  @Test
  public void testInvokeAgentSpanLinksToInvocation() {
    // Test: invoke_agent span should link to invocation span

    Span invocationSpan = tracer.spanBuilder("invocation").startSpan();

    try (Scope invocationScope = invocationSpan.makeCurrent()) {
      Span invokeAgentSpan =
          tracer.spanBuilder("invoke_agent test-agent").setParent(Context.current()).startSpan();

      try (Scope agentScope = invokeAgentSpan.makeCurrent()) {
        // Simulate agent work
      } finally {
        invokeAgentSpan.end();
      }
    } finally {
      invocationSpan.end();
    }

    List<SpanData> spans = openTelemetryRule.getSpans();
    assertEquals("Should have 2 spans: invocation and invoke_agent", 2, spans.size());

    SpanData invocationSpanData = findSpanByName(spans, "invocation");
    SpanData invokeAgentSpanData = findSpanByName(spans, "invoke_agent test-agent");

    assertEquals(
        "Agent run should be child of invocation",
        invocationSpanData.getSpanContext().getSpanId(),
        invokeAgentSpanData.getParentSpanContext().getSpanId());
  }

  @Test
  public void testCallLlmSpanLinksToAgentRun() {
    // Test: call_llm span should link to agent_run span

    Span invokeAgentSpan = tracer.spanBuilder("invoke_agent test-agent").startSpan();

    try (Scope agentScope = invokeAgentSpan.makeCurrent()) {
      Span callLlmSpan = tracer.spanBuilder("call_llm").setParent(Context.current()).startSpan();

      try (Scope llmScope = callLlmSpan.makeCurrent()) {
        // Simulate LLM call
      } finally {
        callLlmSpan.end();
      }
    } finally {
      invokeAgentSpan.end();
    }

    List<SpanData> spans = openTelemetryRule.getSpans();
    assertEquals("Should have 2 spans: invoke_agent and call_llm", 2, spans.size());

    SpanData invokeAgentSpanData = findSpanByName(spans, "invoke_agent test-agent");
    SpanData callLlmSpanData = findSpanByName(spans, "call_llm");

    assertEquals(
        "Call LLM should be child of agent run",
        invokeAgentSpanData.getSpanContext().getSpanId(),
        callLlmSpanData.getParentSpanContext().getSpanId());
  }

  @Test
  public void testSpanCreatedWithinParentScopeIsCorrectlyParented() {
    // Test: Simulates creating a span within the scope of a parent

    Span parentSpan = tracer.spanBuilder("invocation").startSpan();
    try (Scope scope = parentSpan.makeCurrent()) {
      Span agentSpan = tracer.spanBuilder("invoke_agent").setParent(Context.current()).startSpan();
      agentSpan.end();
    } finally {
      parentSpan.end();
    }

    List<SpanData> spans = openTelemetryRule.getSpans();
    assertEquals("Should have 2 spans", 2, spans.size());

    SpanData parentSpanData = findSpanByName(spans, "invocation");
    SpanData agentSpanData = findSpanByName(spans, "invoke_agent");

    assertEquals(
        "Agent span should be a child of the invocation span",
        parentSpanData.getSpanContext().getSpanId(),
        agentSpanData.getParentSpanContext().getSpanId());
  }

  @Test
  public void testTraceFlowable() throws InterruptedException {
    Span parentSpan = tracer.spanBuilder("parent").startSpan();
    try (Scope s = parentSpan.makeCurrent()) {
      Span flowableSpan = tracer.spanBuilder("flowable").setParent(Context.current()).startSpan();
      Flowable<Integer> flowable =
          Tracing.traceFlowable(
              Context.current().with(flowableSpan),
              flowableSpan,
              () ->
                  Flowable.just(1, 2, 3)
                      .map(
                          i -> {
                            assertEquals(
                                flowableSpan.getSpanContext().getSpanId(),
                                Span.current().getSpanContext().getSpanId());
                            return i * 2;
                          }));
      flowable.test().await().assertComplete();
    } finally {
      parentSpan.end();
    }

    List<SpanData> spans = openTelemetryRule.getSpans();
    assertEquals(2, spans.size());
    SpanData parentSpanData = findSpanByName(spans, "parent");
    SpanData flowableSpanData = findSpanByName(spans, "flowable");
    assertEquals(
        parentSpanData.getSpanContext().getSpanId(),
        flowableSpanData.getParentSpanContext().getSpanId());
    assertTrue(flowableSpanData.hasEnded());
  }

  private SpanData findSpanByName(List<SpanData> spans, String name) {
    return spans.stream()
        .filter(s -> s.getName().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Span not found: " + name));
  }

  @Test
  public void testTraceAgentInvocation() {
    Span span = tracer.spanBuilder("test").startSpan();
    try (Scope scope = span.makeCurrent()) {
      Tracing.traceAgentInvocation(
          span,
          "test-agent",
          "test-description",
          InvocationContext.builder()
              .invocationId("inv-1")
              .session(Session.builder("session-1").build())
              .build());
    } finally {
      span.end();
    }
    List<SpanData> spans = openTelemetryRule.getSpans();
    assertEquals(1, spans.size());
    SpanData spanData = spans.get(0);
    Attributes attrs = spanData.getAttributes();
    assertEquals("invoke_agent", attrs.get(AttributeKey.stringKey("gen_ai.operation.name")));
    assertEquals("test-agent", attrs.get(AttributeKey.stringKey("gen_ai.agent.name")));
    assertEquals("test-description", attrs.get(AttributeKey.stringKey("gen_ai.agent.description")));
    assertEquals("session-1", attrs.get(AttributeKey.stringKey("gen_ai.conversation.id")));
  }

  @Test
  public void testTraceToolCall() {
    Span span = tracer.spanBuilder("test").startSpan();
    try (Scope scope = span.makeCurrent()) {
      Tracing.traceToolCall(
          "tool-name", "tool-description", "tool-type", ImmutableMap.of("arg1", "value1"));
    } finally {
      span.end();
    }
    List<SpanData> spans = openTelemetryRule.getSpans();
    assertEquals(1, spans.size());
    SpanData spanData = spans.get(0);
    Attributes attrs = spanData.getAttributes();
    assertEquals("execute_tool", attrs.get(AttributeKey.stringKey("gen_ai.operation.name")));
    assertEquals("tool-name", attrs.get(AttributeKey.stringKey("gen_ai.tool.name")));
    assertEquals("tool-description", attrs.get(AttributeKey.stringKey("gen_ai.tool.description")));
    assertEquals("tool-type", attrs.get(AttributeKey.stringKey("gen_ai.tool.type")));
    assertEquals(
        "{\"arg1\":\"value1\"}",
        attrs.get(AttributeKey.stringKey("gcp.vertex.agent.tool_call_args")));
    assertEquals("{}", attrs.get(AttributeKey.stringKey("gcp.vertex.agent.llm_request")));
    assertEquals("{}", attrs.get(AttributeKey.stringKey("gcp.vertex.agent.llm_response")));
  }

  @Test
  public void testTraceToolResponse() {
    Span span = tracer.spanBuilder("test").startSpan();
    try (Scope scope = span.makeCurrent()) {
      Event functionResponseEvent =
          Event.builder()
              .id("event-1")
              .content(
                  Content.fromParts(
                      Part.builder()
                          .functionResponse(
                              FunctionResponse.builder()
                                  .name("tool-name")
                                  .id("tool-call-id")
                                  .response(ImmutableMap.of("result", "tool-result"))
                                  .build())
                          .build()))
              .build();
      Tracing.traceToolResponse("event-1", functionResponseEvent);
    } finally {
      span.end();
    }
    List<SpanData> spans = openTelemetryRule.getSpans();
    assertEquals(1, spans.size());
    SpanData spanData = spans.get(0);
    Attributes attrs = spanData.getAttributes();
    assertEquals("execute_tool", attrs.get(AttributeKey.stringKey("gen_ai.operation.name")));
    assertEquals("event-1", attrs.get(AttributeKey.stringKey("gcp.vertex.agent.event_id")));
    assertEquals("tool-call-id", attrs.get(AttributeKey.stringKey("gen_ai.tool_call.id")));
    assertEquals(
        "{\"result\":\"tool-result\"}",
        attrs.get(AttributeKey.stringKey("gcp.vertex.agent.tool_response")));
  }

  @Test
  public void testTraceCallLlm() {
    Span span = tracer.spanBuilder("test").startSpan();
    try (Scope scope = span.makeCurrent()) {
      LlmRequest llmRequest =
          LlmRequest.builder()
              .model("gemini-pro")
              .contents(ImmutableList.of(Content.fromParts(Part.fromText("hello"))))
              .config(GenerateContentConfig.builder().topP(0.9f).maxOutputTokens(100).build())
              .build();
      LlmResponse llmResponse =
          LlmResponse.builder()
              .content(Content.builder().parts(Part.fromText("world")).build())
              .finishReason(new FinishReason(FinishReason.Known.STOP))
              .usageMetadata(
                  GenerateContentResponseUsageMetadata.builder()
                      .promptTokenCount(10)
                      .candidatesTokenCount(20)
                      .totalTokenCount(30)
                      .build())
              .build();
      Tracing.traceCallLlm(
          InvocationContext.builder()
              .invocationId("inv-1")
              .session(Session.builder("session-1").build())
              .build(),
          "event-1",
          llmRequest,
          llmResponse);
    } finally {
      span.end();
    }
    List<SpanData> spans = openTelemetryRule.getSpans();
    assertEquals(1, spans.size());
    SpanData spanData = spans.get(0);
    Attributes attrs = spanData.getAttributes();
    assertEquals("gcp.vertex.agent", attrs.get(AttributeKey.stringKey("gen_ai.system")));
    assertEquals("gemini-pro", attrs.get(AttributeKey.stringKey("gen_ai.request.model")));
    assertEquals("inv-1", attrs.get(AttributeKey.stringKey("gcp.vertex.agent.invocation_id")));
    assertEquals("event-1", attrs.get(AttributeKey.stringKey("gcp.vertex.agent.event_id")));
    assertEquals("session-1", attrs.get(AttributeKey.stringKey("gcp.vertex.agent.session_id")));
    assertEquals(0.9d, attrs.get(AttributeKey.doubleKey("gen_ai.request.top_p")), 0.01);
    assertEquals(100L, (long) attrs.get(AttributeKey.longKey("gen_ai.request.max_tokens")));
    assertEquals(10L, (long) attrs.get(AttributeKey.longKey("gen_ai.usage.input_tokens")));
    assertEquals(20L, (long) attrs.get(AttributeKey.longKey("gen_ai.usage.output_tokens")));
    assertEquals(
        ImmutableList.of("stop"),
        attrs.get(AttributeKey.stringArrayKey("gen_ai.response.finish_reasons")));
    assertTrue(
        attrs.get(AttributeKey.stringKey("gcp.vertex.agent.llm_request")).contains("gemini-pro"));
    assertTrue(attrs.get(AttributeKey.stringKey("gcp.vertex.agent.llm_response")).contains("STOP"));
  }

  @Test
  public void testTraceSendData() {
    Span span = tracer.spanBuilder("test").startSpan();
    try (Scope scope = span.makeCurrent()) {
      Tracing.traceSendData(
          InvocationContext.builder()
              .invocationId("inv-1")
              .session(Session.builder("session-1").build())
              .build(),
          "event-1",
          ImmutableList.of(Content.builder().role("user").parts(Part.fromText("hello")).build()));
    } finally {
      span.end();
    }
    List<SpanData> spans = openTelemetryRule.getSpans();
    assertEquals(1, spans.size());
    SpanData spanData = spans.get(0);
    Attributes attrs = spanData.getAttributes();
    assertEquals("inv-1", attrs.get(AttributeKey.stringKey("gcp.vertex.agent.invocation_id")));
    assertEquals("event-1", attrs.get(AttributeKey.stringKey("gcp.vertex.agent.event_id")));
    assertEquals("session-1", attrs.get(AttributeKey.stringKey("gcp.vertex.agent.session_id")));
    assertTrue(attrs.get(AttributeKey.stringKey("gcp.vertex.agent.data")).contains("hello"));
  }
}
