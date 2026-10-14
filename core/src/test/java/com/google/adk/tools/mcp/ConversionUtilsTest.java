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

package com.google.adk.tools.mcp;

import static com.google.common.truth.Truth.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.adk.tools.BaseTool;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ConversionUtilsTest {

  /** Minimal {@link BaseTool} whose declaration is supplied by the test. */
  private static final class FakeTool extends BaseTool {
    private final Optional<FunctionDeclaration> declaration;

    FakeTool(String name, String description, Optional<FunctionDeclaration> declaration) {
      super(name, description);
      this.declaration = declaration;
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
      return declaration;
    }
  }

  private static Map<String, Object> inputSchemaFor(Schema parameters) {
    FunctionDeclaration declaration =
        FunctionDeclaration.builder().name("tool").parameters(parameters).build();
    return ConversionUtils.adkToMcpToolType(
            new FakeTool("tool", "description", Optional.of(declaration)))
        .inputSchema();
  }

  /** Runs the checks an MCP SDK server applies: the schema itself, then a call's arguments. */
  private static void assertMcpServerAccepts(
      Map<String, Object> inputSchema, Map<String, Object> arguments) {
    assertThat(McpJsonDefaults.getSchemaValidator().validateSchema(inputSchema).errorMessage())
        .isNull();
    assertThat(McpJsonDefaults.getSchemaValidator().validate(inputSchema, arguments).errorMessage())
        .isNull();
  }

  @Test
  public void adkToMcpToolType_declarationWithParameters_setsInputSchema() {
    FunctionDeclaration declaration =
        FunctionDeclaration.builder()
            .name("withParams")
            .parameters(
                Schema.builder()
                    .type("OBJECT")
                    .properties(ImmutableMap.of("city", Schema.builder().type("STRING").build()))
                    .build())
            .build();
    BaseTool tool = new FakeTool("withParams", "has params", Optional.of(declaration));

    McpSchema.Tool result = ConversionUtils.adkToMcpToolType(tool);

    assertThat(result.name()).isEqualTo("withParams");
    assertThat(result.description()).isEqualTo("has params");
    assertThat(result.inputSchema())
        .containsExactly(
            "type",
            "object",
            "properties",
            ImmutableMap.of("city", ImmutableMap.of("type", "string")));
  }

  @Test
  public void adkToMcpToolType_parametersAndParametersJsonSchema_prefersParametersJsonSchema() {
    ImmutableMap<String, Object> jsonSchema =
        ImmutableMap.of(
            "type",
            "object",
            "properties",
            ImmutableMap.of("jsonParam", ImmutableMap.of("type", "string")));
    FunctionDeclaration declaration =
        FunctionDeclaration.builder()
            .name("both")
            .parameters(
                Schema.builder()
                    .type(Type.Known.OBJECT)
                    .properties(
                        ImmutableMap.of(
                            "schemaParam", Schema.builder().type(Type.Known.STRING).build()))
                    .build())
            .parametersJsonSchema(jsonSchema)
            .build();

    McpSchema.Tool result =
        ConversionUtils.adkToMcpToolType(new FakeTool("both", "both", Optional.of(declaration)));

    assertThat(result.inputSchema()).isEqualTo(jsonSchema);
  }

  @Test
  public void adkToMcpToolType_jsonNodeParametersJsonSchema_convertsToMap() {
    ObjectNode jsonSchema = JsonNodeFactory.instance.objectNode().put("type", "object");
    jsonSchema.putObject("properties").putObject("query").put("type", "string");
    FunctionDeclaration declaration =
        FunctionDeclaration.builder().name("node").parametersJsonSchema(jsonSchema).build();

    McpSchema.Tool result =
        ConversionUtils.adkToMcpToolType(new FakeTool("node", "node", Optional.of(declaration)));

    assertThat(result.inputSchema())
        .containsExactly(
            "type",
            "object",
            "properties",
            ImmutableMap.of("query", ImmutableMap.of("type", "string")));
  }

  @Test
  public void adkToMcpToolType_emptyParametersJsonSchema_usesParameters() {
    FunctionDeclaration declaration =
        FunctionDeclaration.builder()
            .name("empty")
            .parameters(
                Schema.builder()
                    .type(Type.Known.OBJECT)
                    .properties(
                        ImmutableMap.of("city", Schema.builder().type(Type.Known.STRING).build()))
                    .build())
            .parametersJsonSchema(ImmutableMap.of())
            .build();

    McpSchema.Tool result =
        ConversionUtils.adkToMcpToolType(new FakeTool("empty", "empty", Optional.of(declaration)));

    assertThat(result.inputSchema())
        .containsExactly(
            "type",
            "object",
            "properties",
            ImmutableMap.of("city", ImmutableMap.of("type", "string")));
  }

  @Test
  public void adkToMcpToolType_genaiSchema_convertsToValidJsonSchema() {
    Schema schema =
        Schema.builder()
            .type(Type.Known.OBJECT)
            .title("Request")
            .propertyOrdering(ImmutableList.of("tags", "id"))
            .properties(
                ImmutableMap.of(
                    "code",
                    Schema.builder()
                        .type(Type.Known.STRING)
                        .pattern("^[a-z]+$")
                        .maxLength(5L)
                        .default_("abc")
                        .example("xyz")
                        .build(),
                    "tags",
                    Schema.builder()
                        .type(Type.Known.ARRAY)
                        .items(Schema.builder().type(Type.Known.STRING).build())
                        .minItems(1L)
                        // Keywords on a type they do not apply to pass through and stay valid.
                        .minLength(2L)
                        .build(),
                    "id",
                    Schema.builder()
                        .anyOf(
                            ImmutableList.of(
                                Schema.builder().type(Type.Known.STRING).build(),
                                Schema.builder().type(Type.Known.INTEGER).minimum(0.0).build()))
                        .build(),
                    "unspecified",
                    Schema.builder().type(Type.Known.TYPE_UNSPECIFIED).description("Any.").build(),
                    "custom",
                    Schema.builder().type("custom").build()))
            .build();

    Map<String, Object> inputSchema = inputSchemaFor(schema);

    assertThat(inputSchema)
        .containsExactly(
            "type",
            "object",
            "title",
            "Request",
            "propertyOrdering",
            ImmutableList.of("tags", "id"),
            "properties",
            ImmutableMap.of(
                "code",
                ImmutableMap.of(
                    "type",
                    "string",
                    "pattern",
                    "^[a-z]+$",
                    "maxLength",
                    5L,
                    "default",
                    "abc",
                    "example",
                    "xyz"),
                "tags",
                ImmutableMap.of(
                    "type",
                    "array",
                    "items",
                    ImmutableMap.of("type", "string"),
                    "minItems",
                    1L,
                    "minLength",
                    2L),
                "id",
                ImmutableMap.of(
                    "anyOf",
                    ImmutableList.of(
                        ImmutableMap.of("type", "string"),
                        ImmutableMap.of("type", "integer", "minimum", 0.0))),
                "unspecified",
                ImmutableMap.of("description", "Any."),
                "custom",
                ImmutableMap.of()));
    assertMcpServerAccepts(
        inputSchema, ImmutableMap.of("code", "ab", "tags", ImmutableList.of("a"), "id", 7));
  }

  @Test
  public void adkToMcpToolType_nullableGenaiSchema_mapsNullableToJsonSchema() {
    Schema schema =
        Schema.builder()
            .type(Type.Known.OBJECT)
            // MCP requires the input schema itself to be an object, so this nullable is dropped.
            .nullable(true)
            .required(ImmutableList.of("name"))
            .properties(
                ImmutableMap.of(
                    "name",
                    Schema.builder().type(Type.Known.STRING).nullable(true).build(),
                    "level",
                    Schema.builder()
                        .type(Type.Known.STRING)
                        .enum_(ImmutableList.of("LOW", "HIGH"))
                        .nullable(true)
                        .build(),
                    "id",
                    Schema.builder()
                        .anyOf(
                            ImmutableList.of(
                                Schema.builder().type(Type.Known.STRING).build(),
                                Schema.builder().type(Type.Known.INTEGER).build()))
                        .nullable(true)
                        .build(),
                    "maybe",
                    Schema.builder()
                        .anyOf(
                            ImmutableList.of(
                                Schema.builder().type(Type.Known.STRING).build(),
                                Schema.builder().type(Type.Known.NULL).build()))
                        .nullable(true)
                        .build(),
                    "nothing",
                    Schema.builder().type(Type.Known.NULL).nullable(true).build(),
                    "preset",
                    Schema.builder()
                        .type(Type.Known.STRING)
                        .enum_(Arrays.asList("A", null))
                        .nullable(true)
                        .build(),
                    "loose",
                    Schema.builder().nullable(true).build(),
                    "plain",
                    Schema.builder().type(Type.Known.STRING).nullable(false).build()))
            .build();

    Map<String, Object> inputSchema = inputSchemaFor(schema);

    assertThat(inputSchema)
        .containsExactly(
            "type",
            "object",
            "required",
            ImmutableList.of("name"),
            "properties",
            ImmutableMap.of(
                "name",
                ImmutableMap.of("type", ImmutableList.of("string", "null")),
                "level",
                ImmutableMap.of(
                    "type",
                    ImmutableList.of("string", "null"),
                    "enum",
                    Arrays.asList("LOW", "HIGH", null)),
                "id",
                ImmutableMap.of(
                    "anyOf",
                    ImmutableList.of(
                        ImmutableMap.of("type", "string"),
                        ImmutableMap.of("type", "integer"),
                        ImmutableMap.of("type", "null"))),
                "maybe",
                ImmutableMap.of(
                    "anyOf",
                    ImmutableList.of(
                        ImmutableMap.of("type", "string"), ImmutableMap.of("type", "null"))),
                "nothing",
                ImmutableMap.of("type", "null"),
                "preset",
                ImmutableMap.of(
                    "type", ImmutableList.of("string", "null"), "enum", Arrays.asList("A", null)),
                "loose",
                ImmutableMap.of(),
                "plain",
                ImmutableMap.of("type", "string")));
    Map<String, Object> arguments = new HashMap<>();
    arguments.put("name", null);
    arguments.put("level", null);
    arguments.put("id", null);
    arguments.put("maybe", null);
    arguments.put("nothing", null);
    arguments.put("preset", null);
    arguments.put("loose", null);
    assertMcpServerAccepts(inputSchema, arguments);
  }

  @Test
  public void adkToMcpToolType_declarationWithoutParameters_defaultsInputSchema() {
    // A declaration with no parameters is a valid no-argument tool.
    FunctionDeclaration declaration = FunctionDeclaration.builder().name("noParams").build();
    BaseTool tool = new FakeTool("noParams", "no params", Optional.of(declaration));

    McpSchema.Tool result = ConversionUtils.adkToMcpToolType(tool);

    assertThat(result.name()).isEqualTo("noParams");
    assertThat(result.description()).isEqualTo("no params");
    assertThat(result.inputSchema()).containsExactly("type", "object");
  }

  @Test
  public void adkToMcpToolType_noDeclaration_defaultsInputSchema() {
    BaseTool tool = new FakeTool("bare", "no declaration", Optional.empty());

    McpSchema.Tool result = ConversionUtils.adkToMcpToolType(tool);

    assertThat(result.name()).isEqualTo("bare");
    assertThat(result.description()).isEqualTo("no declaration");
    assertThat(result.inputSchema()).containsExactly("type", "object");
  }
}
