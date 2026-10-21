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

package com.google.adk.tools.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.adk.JsonBaseModel;
import com.google.adk.tools.BaseTool;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Static helpers for converting ADK tools to MCP tools. */
public final class ConversionUtils {

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  /**
   * Input schema for a tool with no parameters. This is the schema the deprecated {@code
   * McpSchema.Tool.builder()} filled in when none was set, and the MCP spec accepts it for a tool
   * that takes no arguments.
   */
  private static final ImmutableMap<String, Object> NO_PARAMETERS_SCHEMA =
      ImmutableMap.of("type", "object");

  private static final ImmutableMap<String, Object> NULL_SCHEMA = ImmutableMap.of("type", "null");

  /**
   * Converts an ADK tool to an MCP tool. Uses the declaration's {@code parametersJsonSchema} when
   * present and non-empty, falls back to {@code parameters} converted to JSON Schema, and defaults
   * to {@code {"type": "object"}}.
   */
  public static McpSchema.Tool adkToMcpToolType(BaseTool tool) {
    Optional<FunctionDeclaration> declaration = tool.declaration();
    Map<String, Object> inputSchema =
        declaration
            .flatMap(FunctionDeclaration::parametersJsonSchema)
            // ADK's Jackson 2 mapper: a Jackson 3 MCP SDK mapper would read a JsonNode as a bean.
            .map(schema -> JsonBaseModel.getMapper().convertValue(schema, MAP_TYPE))
            .filter(schema -> !schema.isEmpty())
            .or(
                () ->
                    declaration
                        .flatMap(FunctionDeclaration::parameters)
                        .map(ConversionUtils::toJsonSchema))
            .orElse(NO_PARAMETERS_SCHEMA);
    return McpSchema.Tool.builder(tool.name(), inputSchema).description(tool.description()).build();
  }

  /** Converts a genai {@link Schema}, which spells types in uppercase, to JSON Schema. */
  private static Map<String, Object> toJsonSchema(Schema schema) {
    Map<String, Object> json = JsonBaseModel.getMapper().convertValue(schema, MAP_TYPE);
    // MCP requires the input schema itself to have "type": "object", so it cannot be nullable.
    json.remove("nullable");
    normalizeSchema(json);
    return json;
  }

  /**
   * Normalizes {@code node} and its nested schemas in place: lowercases {@code type}, drops a type
   * with no JSON Schema name such as {@code TYPE_UNSPECIFIED} so the schema accepts any value, and
   * replaces genai's {@code nullable}, which JSON Schema does not have.
   */
  private static void normalizeSchema(Object node) {
    if (!(node instanceof Map<?, ?> rawSchema)) {
      return;
    }
    // Safe: convertValue built the whole tree from Map<String, Object> nodes.
    @SuppressWarnings("unchecked")
    Map<String, Object> schema = (Map<String, Object>) rawSchema;
    normalizeType(schema);
    normalizeSubschemas(schema);
    if (Objects.equals(schema.remove("nullable"), true)) {
      allowNull(schema);
    }
  }

  private static void normalizeType(Map<String, Object> schema) {
    if (!(schema.get("type") instanceof String type)) {
      return;
    }
    // Type maps a name it does not know to TYPE_UNSPECIFIED; dropping it keeps the schema valid.
    Type.Known known = new Type(type).knownEnum();
    if (known == Type.Known.TYPE_UNSPECIFIED) {
      schema.remove("type");
    } else {
      schema.put("type", Ascii.toLowerCase(known.name()));
    }
  }

  private static void normalizeSubschemas(Map<String, Object> schema) {
    if (schema.get("properties") instanceof Map<?, ?> properties) {
      properties.values().forEach(ConversionUtils::normalizeSchema);
    }
    normalizeSchema(schema.get("items"));
    if (schema.get("anyOf") instanceof List<?> anyOf) {
      anyOf.forEach(ConversionUtils::normalizeSchema);
    }
  }

  /**
   * Allows null in whichever of {@code type}, {@code enum} and {@code anyOf} {@code schema} has,
   * since JSON Schema has no {@code nullable} keyword.
   */
  private static void allowNull(Map<String, Object> schema) {
    if (schema.get("type") instanceof String type && !type.equals("null")) {
      schema.put("type", ImmutableList.of(type, "null"));
    }
    if (schema.get("enum") instanceof List<?> values && !values.contains(null)) {
      schema.put("enum", append(values, null));
    }
    if (schema.get("anyOf") instanceof List<?> anyOf && !anyOf.contains(NULL_SCHEMA)) {
      schema.put("anyOf", append(anyOf, NULL_SCHEMA));
    }
  }

  private static List<Object> append(List<?> list, @Nullable Object element) {
    List<Object> result = new ArrayList<>(list);
    result.add(element);
    return result;
  }

  private ConversionUtils() {}
}
