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

import static com.google.common.base.Strings.isNullOrEmpty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableSet;
import com.google.genai.types.Schema;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Utility class for converting OpenAPI/MCP JsonSchema to Gemini Schema format.
 *
 * <p>This utility handles: - Converting field names to snake_case - Sanitizing schema types
 * (handling nullable types and arrays) - Filtering format fields based on type - Recursively
 * processing nested schemas - Only keeping fields supported by Gemini
 */
public final class GeminiSchemaUtil {

  private static final ImmutableSet<String> SUPPORTED_FIELDS =
      ImmutableSet.of(
          "type",
          "description",
          "format",
          "enum",
          "required",
          "minimum",
          "maximum",
          "min_length",
          "max_length",
          "pattern",
          "default",
          "nullable",
          "title");

  private static final ImmutableSet<String> SCHEMA_FIELD_NAMES = ImmutableSet.of("items");

  private static final ImmutableSet<String> LIST_SCHEMA_FIELD_NAMES = ImmutableSet.of("any_of");

  // Fields that contain dictionaries of schemas
  private static final ImmutableSet<String> DICT_SCHEMA_FIELD_NAMES = ImmutableSet.of("properties");

  private GeminiSchemaUtil() {}

  /**
   * Converts an OpenAPI/MCP JsonSchema to a Gemini Schema object.
   *
   * @param openApiSchema The input schema in OpenAPI/MCP format
   * @param objectMapper The ObjectMapper to use for JSON processing
   * @return A Gemini Schema object
   * @throws IOException if JSON processing fails
   */
  public static @Nullable Schema toGeminiSchema(JsonSchema openApiSchema, ObjectMapper objectMapper)
      throws IOException {
    if (openApiSchema == null) {
      return null;
    }

    JsonNode schemaNode = objectMapper.valueToTree(openApiSchema);
    ObjectNode sanitizedSchema = sanitizeSchemaFormatsForGemini(schemaNode, objectMapper);
    convertTypesToUpperCase(sanitizedSchema, objectMapper);
    String jsonStr = objectMapper.writeValueAsString(sanitizedSchema);
    return Schema.fromJson(jsonStr);
  }

  /**
   * Converts a string to snake_case.
   *
   * <p>Handles lowerCamelCase, UpperCamelCase, space-separated case, acronyms (e.g., "REST API")
   * and consecutive uppercase letters correctly. Also handles mixed cases with and without spaces.
   *
   * <p>Examples: - camelCase -> camel_case - UpperCamelCase -> upper_camel_case - space separated
   * -> space_separated - REST API -> rest_api
   *
   * @param text The input string
   * @return The snake_case version of the string
   */
  public static String toSnakeCase(String text) {
    if (isNullOrEmpty(text)) {
      return text;
    }

    text = text.replaceAll("[^a-zA-Z0-9]+", "_");

    text = text.replaceAll("([a-z0-9])([A-Z])", "$1_$2");
    text = text.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2");

    text = text.toLowerCase(Locale.ROOT);
    text = text.replaceAll("_+", "_");

    text = text.replaceAll("^_+|_+$", "");

    return text;
  }

  /**
   * Sanitizes the schema type field to ensure it has a valid type.
   *
   * @param schema The schema node to sanitize
   * @param objectMapper The ObjectMapper for creating new nodes
   */
  private static void sanitizeSchemaType(ObjectNode schema, ObjectMapper objectMapper) {
    if (!schema.has("type") || schema.get("type").isNull()) {
      // If no type is specified, default to object
      schema.put("type", "object");
    } else if (schema.get("type").isArray()) {
      // Handle array types (e.g., ["string", "null"])
      ArrayNode typeArray = (ArrayNode) schema.get("type");
      boolean nullable = false;
      String nonNullType = null;

      for (JsonNode t : typeArray) {
        String typeStr = t.asText();
        if (Objects.equals(typeStr, "null")) {
          nullable = true;
        } else if (nonNullType == null) {
          nonNullType = typeStr;
        }
      }

      if (nonNullType == null) {
        nonNullType = "object";
      }

      if (nullable) {
        // Create new array with non-null type and null
        ArrayNode newTypeArray = objectMapper.createArrayNode();
        newTypeArray.add(nonNullType);
        newTypeArray.add("null");
        schema.set("type", newTypeArray);
      } else {
        schema.put("type", nonNullType);
      }
    } else if (Objects.equals(schema.get("type").asText(), "null")) {
      // If type is just "null", make it ["object", "null"]
      ArrayNode newTypeArray = objectMapper.createArrayNode();
      newTypeArray.add("object");
      newTypeArray.add("null");
      schema.set("type", newTypeArray);
    }
  }

  /**
   * Filters and sanitizes the schema to only include fields supported by Gemini.
   *
   * @param schema The input schema node
   * @param objectMapper The ObjectMapper for creating new nodes
   * @return A sanitized schema node
   */
  private static ObjectNode sanitizeSchemaFormatsForGemini(
      JsonNode schema, ObjectMapper objectMapper) {
    if (schema == null || !schema.isObject()) {
      return objectMapper.createObjectNode();
    }

    ObjectNode snakeCaseSchema = objectMapper.createObjectNode();
    ObjectNode originalSchema = (ObjectNode) schema;

    for (Map.Entry<String, JsonNode> entry : originalSchema.properties()) {
      String fieldName = toSnakeCase(entry.getKey());
      JsonNode fieldValue = entry.getValue();

      if (SCHEMA_FIELD_NAMES.contains(fieldName)) {
        // Recursively process schema fields
        snakeCaseSchema.set(fieldName, sanitizeSchemaFormatsForGemini(fieldValue, objectMapper));
      } else if (LIST_SCHEMA_FIELD_NAMES.contains(fieldName)) {
        // Process list of schemas
        if (fieldValue.isArray()) {
          ArrayNode newArray = objectMapper.createArrayNode();
          for (JsonNode value : fieldValue) {
            newArray.add(sanitizeSchemaFormatsForGemini(value, objectMapper));
          }
          snakeCaseSchema.set(fieldName, newArray);
        }
      } else if (DICT_SCHEMA_FIELD_NAMES.contains(fieldName) && fieldValue != null) {
        // Process dictionary of schemas
        if (fieldValue.isObject()) {
          ObjectNode newDict = objectMapper.createObjectNode();
          for (Map.Entry<String, JsonNode> dictEntry : fieldValue.properties()) {
            newDict.set(
                dictEntry.getKey(),
                sanitizeSchemaFormatsForGemini(dictEntry.getValue(), objectMapper));
          }
          snakeCaseSchema.set(fieldName, newDict);
        }
      } else if (Objects.equals(fieldName, "format")
          && fieldValue != null
          && !fieldValue.isNull()) {
        // Special handling of format field
        handleFormatField(originalSchema, fieldName, fieldValue, snakeCaseSchema);
      } else if (SUPPORTED_FIELDS.contains(fieldName)
          && fieldValue != null
          && !fieldValue.isNull()) {
        // Keep supported fields
        snakeCaseSchema.set(fieldName, fieldValue);
      }
    }

    sanitizeSchemaType(snakeCaseSchema, objectMapper);

    return snakeCaseSchema;
  }

  /**
   * Handles the special processing of format fields based on type.
   *
   * @param originalSchema The original schema node
   * @param fieldName The field name (should be "format")
   * @param fieldValue The format value
   * @param snakeCaseSchema The output schema to add the format to
   */
  private static void handleFormatField(
      ObjectNode originalSchema,
      String fieldName,
      JsonNode fieldValue,
      ObjectNode snakeCaseSchema) {

    String format = fieldValue.asText();
    String currentType = null;

    if (originalSchema.has("type")) {
      JsonNode typeNode = originalSchema.get("type");
      if (typeNode.isTextual()) {
        currentType = typeNode.asText();
      } else if (typeNode.isArray() && typeNode.size() > 0) {
        for (JsonNode t : typeNode) {
          if (!Objects.equals(t.asText(), "null")) {
            currentType = t.asText();
            break;
          }
        }
      }
    }

    if (currentType != null) {
      if ((currentType.equals("integer") || currentType.equals("number"))
          && (Objects.equals(format, "int32") || Objects.equals(format, "int64"))) {
        // Only "int32" and "int64" are supported for integer or number type
        snakeCaseSchema.put(fieldName, format);
      } else if (currentType.equals("string")
          && (Objects.equals(format, "date-time") || Objects.equals(format, "enum"))) {
        // Only 'enum' and 'date-time' are supported for STRING type
        snakeCaseSchema.put(fieldName, format);
      }
      // All other format values are dropped
    }
  }

  /**
   * Converts type fields to uppercase for Gemini compatibility.
   *
   * @param node The node to process
   * @param objectMapper The ObjectMapper for creating new nodes
   */
  private static void convertTypesToUpperCase(JsonNode node, ObjectMapper objectMapper) {
    if (node == null || !node.isObject()) {
      return;
    }

    ObjectNode objNode = (ObjectNode) node;

    // Convert type to uppercase
    if (objNode.has("type")) {
      JsonNode typeNode = objNode.get("type");
      if (typeNode.isTextual()) {
        objNode.put("type", typeNode.asText().toUpperCase(Locale.ROOT));
      } else if (typeNode.isArray()) {
        // Handle array types like ["object", "null"]
        // TODO: use gemini json schema once it's ready.
        ArrayNode typeArray = (ArrayNode) typeNode;
        String nonNullType = null;
        boolean hasNull = false;

        for (JsonNode t : typeArray) {
          String typeStr = t.asText();
          if (Objects.equals(typeStr, "null") || Objects.equals(typeStr, "NULL")) {
            hasNull = true;
          } else {
            nonNullType = typeStr.toUpperCase(Locale.ROOT);
          }
        }

        if (nonNullType == null) {
          nonNullType = "OBJECT";
        }

        objNode.put("type", nonNullType);
        if (hasNull) {
          objNode.put("nullable", true);
        }
      }
    }

    if (objNode.has("properties")) {
      JsonNode properties = objNode.get("properties");
      if (properties.isObject()) {
        Iterator<JsonNode> propValues = properties.elements();
        while (propValues.hasNext()) {
          convertTypesToUpperCase(propValues.next(), objectMapper);
        }
      }
    }

    if (objNode.has("items")) {
      convertTypesToUpperCase(objNode.get("items"), objectMapper);
    }

    if (objNode.has("any_of")) {
      JsonNode anyOf = objNode.get("any_of");
      if (anyOf.isArray()) {
        for (JsonNode schema : anyOf) {
          convertTypesToUpperCase(schema, objectMapper);
        }
      }
    }
  }
}
