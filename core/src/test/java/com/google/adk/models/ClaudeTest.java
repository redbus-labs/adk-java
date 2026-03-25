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

package com.google.adk.models;

import static com.google.common.truth.Truth.assertThat;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

@RunWith(JUnit4.class)
public final class ClaudeTest {

  private Claude claude;
  private Method partToAnthropicMessageBlockMethod;

  @Before
  public void setUp() throws Exception {
    AnthropicClient mockClient = Mockito.mock(AnthropicClient.class);
    claude = new Claude("claude-3-opus", mockClient);

    // Access private method for testing the extraction logic
    partToAnthropicMessageBlockMethod =
        Claude.class.getDeclaredMethod("partToAnthropicMessageBlock", Part.class);
    partToAnthropicMessageBlockMethod.setAccessible(true);
  }

  @Test
  public void testPartToAnthropicMessageBlock_mcpNativeFormat() throws Exception {
    Map<String, Object> responseData =
        ImmutableMap.of(
            "content",
            ImmutableList.of(ImmutableMap.of("type", "text", "text", "Extracted native MCP text")));
    FunctionResponse funcParam =
        FunctionResponse.builder().name("test_tool").response(responseData).id("call_123").build();
    Part part = Part.builder().functionResponse(funcParam).build();

    ContentBlockParam result =
        (ContentBlockParam) partToAnthropicMessageBlockMethod.invoke(claude, part);

    ToolResultBlockParam toolResult = result.asToolResult();
    assertThat(toolResult.content().get().asString()).isEqualTo("Extracted native MCP text");
  }

  @Test
  public void testPartToAnthropicMessageBlock_legacyResultKey() throws Exception {
    Map<String, Object> responseData = ImmutableMap.of("result", "Legacy result text");
    FunctionResponse funcParam =
        FunctionResponse.builder().name("test_tool").response(responseData).id("call_123").build();
    Part part = Part.builder().functionResponse(funcParam).build();

    ContentBlockParam result =
        (ContentBlockParam) partToAnthropicMessageBlockMethod.invoke(claude, part);

    ToolResultBlockParam toolResult = result.asToolResult();
    assertThat(toolResult.content().get().asString()).isEqualTo("Legacy result text");
  }

  @Test
  public void testPartToAnthropicMessageBlock_jsonFallback() throws Exception {
    Map<String, Object> responseData = ImmutableMap.of("custom_key", "custom_value");
    FunctionResponse funcParam =
        FunctionResponse.builder().name("test_tool").response(responseData).id("call_123").build();
    Part part = Part.builder().functionResponse(funcParam).build();

    ContentBlockParam result =
        (ContentBlockParam) partToAnthropicMessageBlockMethod.invoke(claude, part);

    ToolResultBlockParam toolResult = result.asToolResult();
    assertThat(toolResult.content().get().asString()).contains("\"custom_key\":\"custom_value\"");
  }
}
