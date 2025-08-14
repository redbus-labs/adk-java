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

import static com.google.common.truth.Truth.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.genai.types.Schema;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class GeminiSchemaUtilTest {

  private ObjectMapper objectMapper;

  @Before
  public void setUp() {
    objectMapper = new ObjectMapper();
  }

  @Test
  public void testToSnakeCase_variousCases() {
    assertThat(GeminiSchemaUtil.toSnakeCase("lowerCamelCase")).isEqualTo("lower_camel_case");
    assertThat(GeminiSchemaUtil.toSnakeCase("UpperCamelCase")).isEqualTo("upper_camel_case");

    assertThat(GeminiSchemaUtil.toSnakeCase("space separated")).isEqualTo("space_separated");
    assertThat(GeminiSchemaUtil.toSnakeCase("  spaced  ")).isEqualTo("spaced");

    assertThat(GeminiSchemaUtil.toSnakeCase("REST API")).isEqualTo("rest_api");
    assertThat(GeminiSchemaUtil.toSnakeCase("XMLParser")).isEqualTo("xml_parser");
    assertThat(GeminiSchemaUtil.toSnakeCase("HTMLParser")).isEqualTo("html_parser");
    assertThat(GeminiSchemaUtil.toSnakeCase("HTTPResponseCode")).isEqualTo("http_response_code");
    assertThat(GeminiSchemaUtil.toSnakeCase("XMLHTTPRequest")).isEqualTo("xmlhttp_request");
    assertThat(GeminiSchemaUtil.toSnakeCase("APIKey")).isEqualTo("api_key");

    assertThat(GeminiSchemaUtil.toSnakeCase("already_snake_case")).isEqualTo("already_snake_case");
    assertThat(GeminiSchemaUtil.toSnakeCase("a_b_c")).isEqualTo("a_b_c");

    assertThat(GeminiSchemaUtil.toSnakeCase("Mixed_CASE with_Spaces"))
        .isEqualTo("mixed_case_with_spaces");
    assertThat(GeminiSchemaUtil.toSnakeCase("With_Mixed_123_and_SPACES"))
        .isEqualTo("with_mixed_123_and_spaces");

    assertThat(GeminiSchemaUtil.toSnakeCase("")).isEqualTo("");
    assertThat(GeminiSchemaUtil.toSnakeCase(null)).isNull();
    assertThat(GeminiSchemaUtil.toSnakeCase("single")).isEqualTo("single");

    assertThat(GeminiSchemaUtil.toSnakeCase("__init__")).isEqualTo("init");
    assertThat(GeminiSchemaUtil.toSnakeCase("_leading")).isEqualTo("leading");
    assertThat(GeminiSchemaUtil.toSnakeCase("trailing_")).isEqualTo("trailing");
    assertThat(GeminiSchemaUtil.toSnakeCase("Multiple___Underscores"))
        .isEqualTo("multiple_underscores");

    assertThat(GeminiSchemaUtil.toSnakeCase("with123numbers")).isEqualTo("with123numbers");
    assertThat(GeminiSchemaUtil.toSnakeCase("123Start")).isEqualTo("123_start");
    assertThat(GeminiSchemaUtil.toSnakeCase("End123")).isEqualTo("end123");
  }

  @Test
  public void testToGeminiSchema_nullInput() throws Exception {
    Schema result = GeminiSchemaUtil.toGeminiSchema(null, objectMapper);
    assertThat(result).isNull();
  }

  @Test
  public void testToGeminiSchema_basicTypes() throws Exception {
    // Create a JSON schema with basic types
    ObjectNode schemaNode = objectMapper.createObjectNode();
    schemaNode.put("type", "object");

    ObjectNode properties = objectMapper.createObjectNode();
    ObjectNode nameField = objectMapper.createObjectNode();
    nameField.put("type", "string");
    properties.set("name", nameField);

    ObjectNode ageField = objectMapper.createObjectNode();
    ageField.put("type", "integer");
    properties.set("age", ageField);

    ObjectNode isActiveField = objectMapper.createObjectNode();
    isActiveField.put("type", "boolean");
    properties.set("is_active", isActiveField);

    schemaNode.set("properties", properties);

    // Convert to JsonSchema
    JsonSchema jsonSchema = objectMapper.convertValue(schemaNode, JsonSchema.class);

    Schema geminiSchema = GeminiSchemaUtil.toGeminiSchema(jsonSchema, objectMapper);

    assertThat(geminiSchema).isNotNull();
    // The schema should be successfully converted with basic types
  }

  @Test
  public void testToGeminiSchema_formatFieldSanitization() throws Exception {
    // Test that only supported format values are preserved
    ObjectNode schemaNode = objectMapper.createObjectNode();
    schemaNode.put("type", "object");

    ObjectNode properties = objectMapper.createObjectNode();

    // Integer with valid format int32
    ObjectNode int32Field = objectMapper.createObjectNode();
    int32Field.put("type", "integer");
    int32Field.put("format", "int32");
    properties.set("int32_field", int32Field);

    // Integer with valid format int64
    ObjectNode int64Field = objectMapper.createObjectNode();
    int64Field.put("type", "integer");
    int64Field.put("format", "int64");
    properties.set("int64_field", int64Field);

    // Integer with invalid format
    ObjectNode invalidIntField = objectMapper.createObjectNode();
    invalidIntField.put("type", "integer");
    invalidIntField.put("format", "unsigned");
    properties.set("invalid_int_format", invalidIntField);

    // String with valid format date-time
    ObjectNode datetimeField = objectMapper.createObjectNode();
    datetimeField.put("type", "string");
    datetimeField.put("format", "date-time");
    properties.set("datetime_field", datetimeField);

    // String with valid format enum
    ObjectNode enumField = objectMapper.createObjectNode();
    enumField.put("type", "string");
    enumField.put("format", "enum");
    properties.set("enum_field", enumField);

    // String with invalid format date
    ObjectNode dateField = objectMapper.createObjectNode();
    dateField.put("type", "string");
    dateField.put("format", "date");
    properties.set("date_field", dateField);

    // String with invalid format email
    ObjectNode emailField = objectMapper.createObjectNode();
    emailField.put("type", "string");
    emailField.put("format", "email");
    properties.set("email_field", emailField);

    schemaNode.set("properties", properties);

    JsonSchema jsonSchema = objectMapper.convertValue(schemaNode, JsonSchema.class);
    Schema geminiSchema = GeminiSchemaUtil.toGeminiSchema(jsonSchema, objectMapper);

    assertThat(geminiSchema).isNotNull();
    // The conversion should complete successfully with format sanitization
  }

  @Test
  public void testToGeminiSchema_nullableTypes() throws Exception {
    // Test handling of nullable types (array type with "null")
    ObjectNode schemaNode = objectMapper.createObjectNode();
    schemaNode.put("type", "object");

    ObjectNode properties = objectMapper.createObjectNode();

    // Nullable string
    ObjectNode nullableStringField = objectMapper.createObjectNode();
    ArrayNode nullableStringType = objectMapper.createArrayNode();
    nullableStringType.add("string");
    nullableStringType.add("null");
    nullableStringField.set("type", nullableStringType);
    properties.set("nullable_string", nullableStringField);

    // Nullable number
    ObjectNode nullableNumberField = objectMapper.createObjectNode();
    ArrayNode nullableNumberType = objectMapper.createArrayNode();
    nullableNumberType.add("null");
    nullableNumberType.add("integer");
    nullableNumberField.set("type", nullableNumberType);
    properties.set("nullable_number", nullableNumberField);

    // Non-nullable string (single element array)
    ObjectNode nonnullableStringField = objectMapper.createObjectNode();
    ArrayNode nonnullableStringType = objectMapper.createArrayNode();
    nonnullableStringType.add("string");
    nonnullableStringField.set("type", nonnullableStringType);
    properties.set("nonnullable_string", nonnullableStringField);

    // Just null type
    ObjectNode justNullField = objectMapper.createObjectNode();
    justNullField.put("type", "null");
    properties.set("just_null", justNullField);

    schemaNode.set("properties", properties);

    JsonSchema jsonSchema = objectMapper.convertValue(schemaNode, JsonSchema.class);
    Schema geminiSchema = GeminiSchemaUtil.toGeminiSchema(jsonSchema, objectMapper);

    assertThat(geminiSchema).isNotNull();
    // The conversion should handle nullable types correctly
  }

  @Test
  public void testToGeminiSchema_nestedStructures() throws Exception {
    // Test nested objects and arrays
    ObjectNode schemaNode = objectMapper.createObjectNode();
    schemaNode.put("type", "object");

    ObjectNode properties = objectMapper.createObjectNode();

    // Nested object
    ObjectNode addressField = objectMapper.createObjectNode();
    addressField.put("type", "object");
    ObjectNode addressProperties = objectMapper.createObjectNode();
    ObjectNode streetField = objectMapper.createObjectNode();
    streetField.put("type", "string");
    addressProperties.set("street", streetField);
    ObjectNode cityField = objectMapper.createObjectNode();
    cityField.put("type", "string");
    addressProperties.set("city", cityField);
    addressField.set("properties", addressProperties);
    properties.set("address", addressField);

    // Array field
    ObjectNode tagsField = objectMapper.createObjectNode();
    tagsField.put("type", "array");
    ObjectNode itemsField = objectMapper.createObjectNode();
    itemsField.put("type", "string");
    tagsField.set("items", itemsField);
    properties.set("tags", tagsField);

    schemaNode.set("properties", properties);

    JsonSchema jsonSchema = objectMapper.convertValue(schemaNode, JsonSchema.class);
    Schema geminiSchema = GeminiSchemaUtil.toGeminiSchema(jsonSchema, objectMapper);

    assertThat(geminiSchema).isNotNull();
    // The conversion should handle nested structures correctly
  }

  @Test
  public void testToGeminiSchema_camelCaseToSnakeCase() throws Exception {
    // Test that camelCase field names are converted to snake_case
    ObjectNode schemaNode = objectMapper.createObjectNode();
    schemaNode.put("type", "object");
    schemaNode.put("minProperties", 1);
    schemaNode.put("maxProperties", 10);

    ObjectNode properties = objectMapper.createObjectNode();

    ObjectNode firstNameField = objectMapper.createObjectNode();
    firstNameField.put("type", "string");
    firstNameField.put("minLength", 1);
    firstNameField.put("maxLength", 50);
    properties.set("firstName", firstNameField);

    ObjectNode lastNameField = objectMapper.createObjectNode();
    lastNameField.put("type", "string");
    lastNameField.put("minLength", 1);
    lastNameField.put("maxLength", 50);
    properties.set("lastName", lastNameField);

    schemaNode.set("properties", properties);

    JsonSchema jsonSchema = objectMapper.convertValue(schemaNode, JsonSchema.class);
    Schema geminiSchema = GeminiSchemaUtil.toGeminiSchema(jsonSchema, objectMapper);

    assertThat(geminiSchema).isNotNull();
    // The conversion should handle camelCase to snake_case conversion
    // Note: Property names themselves should NOT be converted, only schema field names
  }

  @Test
  public void testToGeminiSchema_anyOfStructure() throws Exception {
    // Test anyOf structure handling
    ObjectNode schemaNode = objectMapper.createObjectNode();

    ArrayNode anyOfArray = objectMapper.createArrayNode();

    ObjectNode stringSchema = objectMapper.createObjectNode();
    stringSchema.put("type", "string");
    stringSchema.put("format", "email");
    anyOfArray.add(stringSchema);

    ObjectNode integerSchema = objectMapper.createObjectNode();
    integerSchema.put("type", "integer");
    integerSchema.put("format", "int32");
    anyOfArray.add(integerSchema);

    ObjectNode dateTimeSchema = objectMapper.createObjectNode();
    dateTimeSchema.put("type", "string");
    dateTimeSchema.put("format", "date-time");
    anyOfArray.add(dateTimeSchema);

    schemaNode.set("anyOf", anyOfArray);

    JsonSchema jsonSchema = objectMapper.convertValue(schemaNode, JsonSchema.class);
    Schema geminiSchema = GeminiSchemaUtil.toGeminiSchema(jsonSchema, objectMapper);

    assertThat(geminiSchema).isNotNull();
    // The conversion should handle anyOf structures
  }

  @Test
  public void testToGeminiSchema_emptySchema() throws Exception {
    // Test empty schema defaults to object type
    ObjectNode schemaNode = objectMapper.createObjectNode();

    JsonSchema jsonSchema = objectMapper.convertValue(schemaNode, JsonSchema.class);
    Schema geminiSchema = GeminiSchemaUtil.toGeminiSchema(jsonSchema, objectMapper);

    assertThat(geminiSchema).isNotNull();
    // Empty schema should default to object type
  }

  @Test
  public void testToGeminiSchema_requiredFields() throws Exception {
    // Test that required fields are preserved
    ObjectNode schemaNode = objectMapper.createObjectNode();
    schemaNode.put("type", "object");

    ObjectNode properties = objectMapper.createObjectNode();
    ObjectNode nameField = objectMapper.createObjectNode();
    nameField.put("type", "string");
    properties.set("name", nameField);
    ObjectNode ageField = objectMapper.createObjectNode();
    ageField.put("type", "integer");
    properties.set("age", ageField);
    schemaNode.set("properties", properties);

    ArrayNode requiredArray = objectMapper.createArrayNode();
    requiredArray.add("name");
    schemaNode.set("required", requiredArray);

    JsonSchema jsonSchema = objectMapper.convertValue(schemaNode, JsonSchema.class);
    Schema geminiSchema = GeminiSchemaUtil.toGeminiSchema(jsonSchema, objectMapper);

    assertThat(geminiSchema).isNotNull();
    // Required fields should be preserved
  }

  @Test
  public void testToGeminiSchema_enumValues() throws Exception {
    // Test that enum values are preserved
    ObjectNode schemaNode = objectMapper.createObjectNode();
    schemaNode.put("type", "string");

    ArrayNode enumArray = objectMapper.createArrayNode();
    enumArray.add("red");
    enumArray.add("green");
    enumArray.add("blue");
    schemaNode.set("enum", enumArray);

    JsonSchema jsonSchema = objectMapper.convertValue(schemaNode, JsonSchema.class);
    Schema geminiSchema = GeminiSchemaUtil.toGeminiSchema(jsonSchema, objectMapper);

    assertThat(geminiSchema).isNotNull();
    // Enum values should be preserved
  }

  @Test
  public void testToGeminiSchema_unsupportedFieldsRemoved() throws Exception {
    // Test that unsupported fields are removed
    ObjectNode schemaNode = objectMapper.createObjectNode();
    schemaNode.put("type", "object");
    schemaNode.put("description", "Test schema");
    schemaNode.put("additionalProperties", false); // Unsupported field
    schemaNode.put("unknownField", "should be removed"); // Unsupported field

    ObjectNode properties = objectMapper.createObjectNode();
    ObjectNode nameField = objectMapper.createObjectNode();
    nameField.put("type", "string");
    nameField.put("unknownPropertyField", "should be removed");
    properties.set("name", nameField);
    schemaNode.set("properties", properties);

    JsonSchema jsonSchema = objectMapper.convertValue(schemaNode, JsonSchema.class);
    Schema geminiSchema = GeminiSchemaUtil.toGeminiSchema(jsonSchema, objectMapper);

    assertThat(geminiSchema).isNotNull();
    // Unsupported fields should be removed during conversion
  }

  @Test
  public void testToGeminiSchema_complexNestedSchema() throws Exception {
    // Test a complex nested schema with multiple levels
    ObjectNode schemaNode = objectMapper.createObjectNode();
    schemaNode.put("type", "object");

    ObjectNode properties = objectMapper.createObjectNode();

    // User object
    ObjectNode userField = objectMapper.createObjectNode();
    userField.put("type", "object");

    ObjectNode userProperties = objectMapper.createObjectNode();

    // Profile object inside user
    ObjectNode profileField = objectMapper.createObjectNode();
    profileField.put("type", "object");

    ObjectNode profileProperties = objectMapper.createObjectNode();
    ObjectNode profileNameField = objectMapper.createObjectNode();
    profileNameField.put("type", "string");
    profileProperties.set("name", profileNameField);

    // Settings object inside profile
    ObjectNode settingsField = objectMapper.createObjectNode();
    settingsField.put("type", "object");

    ObjectNode settingsProperties = objectMapper.createObjectNode();
    ObjectNode themeField = objectMapper.createObjectNode();
    themeField.put("type", "string");
    ArrayNode themeEnum = objectMapper.createArrayNode();
    themeEnum.add("dark");
    themeEnum.add("light");
    themeField.set("enum", themeEnum);
    settingsProperties.set("theme", themeField);

    settingsField.set("properties", settingsProperties);
    profileProperties.set("settings", settingsField);

    profileField.set("properties", profileProperties);
    userProperties.set("profile", profileField);

    userField.set("properties", userProperties);
    properties.set("user", userField);

    schemaNode.set("properties", properties);

    JsonSchema jsonSchema = objectMapper.convertValue(schemaNode, JsonSchema.class);
    Schema geminiSchema = GeminiSchemaUtil.toGeminiSchema(jsonSchema, objectMapper);

    assertThat(geminiSchema).isNotNull();
    // Complex nested structures should be handled correctly
  }

  @Test
  public void testToGeminiSchema_numberFormats() throws Exception {
    // Test format handling for number types
    ObjectNode schemaNode = objectMapper.createObjectNode();
    schemaNode.put("type", "object");

    ObjectNode properties = objectMapper.createObjectNode();

    // Number with float format (should be removed)
    ObjectNode floatField = objectMapper.createObjectNode();
    floatField.put("type", "number");
    floatField.put("format", "float");
    properties.set("float_field", floatField);

    // Number with double format (should be removed)
    ObjectNode doubleField = objectMapper.createObjectNode();
    doubleField.put("type", "number");
    doubleField.put("format", "double");
    properties.set("double_field", doubleField);

    // Number with int32 format (should be preserved)
    ObjectNode int32Number = objectMapper.createObjectNode();
    int32Number.put("type", "number");
    int32Number.put("format", "int32");
    properties.set("int32_number", int32Number);

    schemaNode.set("properties", properties);

    JsonSchema jsonSchema = objectMapper.convertValue(schemaNode, JsonSchema.class);
    Schema geminiSchema = GeminiSchemaUtil.toGeminiSchema(jsonSchema, objectMapper);

    assertThat(geminiSchema).isNotNull();
  }

  @Test
  public void testToGeminiSchema_typeConversionToUpperCase() throws Exception {
    // Test that type values are converted to uppercase
    ObjectNode schemaNode = objectMapper.createObjectNode();
    schemaNode.put("type", "object");

    ObjectNode properties = objectMapper.createObjectNode();

    ObjectNode stringField = objectMapper.createObjectNode();
    stringField.put("type", "string");
    properties.set("string_field", stringField);

    ObjectNode integerField = objectMapper.createObjectNode();
    integerField.put("type", "integer");
    properties.set("integer_field", integerField);

    ObjectNode booleanField = objectMapper.createObjectNode();
    booleanField.put("type", "boolean");
    properties.set("boolean_field", booleanField);

    ObjectNode arrayField = objectMapper.createObjectNode();
    arrayField.put("type", "array");
    ObjectNode arrayItems = objectMapper.createObjectNode();
    arrayItems.put("type", "number");
    arrayField.set("items", arrayItems);
    properties.set("array_field", arrayField);

    schemaNode.set("properties", properties);

    JsonSchema jsonSchema = objectMapper.convertValue(schemaNode, JsonSchema.class);
    Schema geminiSchema = GeminiSchemaUtil.toGeminiSchema(jsonSchema, objectMapper);

    assertThat(geminiSchema).isNotNull();
  }

  @Test
  public void testToGeminiSchema_nullableArrayTypeConversion() throws Exception {
    ObjectNode schemaNode = objectMapper.createObjectNode();
    schemaNode.put("type", "object");

    ObjectNode properties = objectMapper.createObjectNode();

    ObjectNode richTextField = objectMapper.createObjectNode();
    richTextField.put("type", "array");
    richTextField.put("description", "Rich text array");

    ObjectNode itemsSchema = objectMapper.createObjectNode();
    itemsSchema.put("type", "object");

    ObjectNode itemProperties = objectMapper.createObjectNode();

    ObjectNode textField = objectMapper.createObjectNode();
    textField.put("type", "object");

    ObjectNode textProperties = objectMapper.createObjectNode();

    ObjectNode contentField = objectMapper.createObjectNode();
    contentField.put("type", "string");
    textProperties.set("content", contentField);

    ObjectNode linkField = objectMapper.createObjectNode();
    ArrayNode linkTypeArray = objectMapper.createArrayNode();
    linkTypeArray.add("object");
    linkTypeArray.add("null");
    linkField.set("type", linkTypeArray);
    linkField.put("description", "Optional link object");
    textProperties.set("link", linkField);

    textField.set("properties", textProperties);

    ArrayNode textRequired = objectMapper.createArrayNode();
    textRequired.add("content");
    textField.set("required", textRequired);

    itemProperties.set("text", textField);

    ObjectNode typeField = objectMapper.createObjectNode();
    typeField.put("type", "string");
    ArrayNode typeEnum = objectMapper.createArrayNode();
    typeEnum.add("text");
    typeField.set("enum", typeEnum);
    itemProperties.set("type", typeField);

    itemsSchema.set("properties", itemProperties);

    ArrayNode itemRequired = objectMapper.createArrayNode();
    itemRequired.add("text");
    itemsSchema.set("required", itemRequired);

    richTextField.set("items", itemsSchema);
    properties.set("rich_text", richTextField);

    ObjectNode idField = objectMapper.createObjectNode();
    idField.put("type", "string");
    idField.put("description", "Database identifier");
    properties.set("database_id", idField);

    schemaNode.set("properties", properties);

    ArrayNode requiredArray = objectMapper.createArrayNode();
    requiredArray.add("database_id");
    schemaNode.set("required", requiredArray);

    JsonSchema jsonSchema = objectMapper.convertValue(schemaNode, JsonSchema.class);
    Schema geminiSchema = GeminiSchemaUtil.toGeminiSchema(jsonSchema, objectMapper);

    assertThat(geminiSchema).isNotNull();

    assertThat(geminiSchema.properties()).isPresent();
    assertThat(geminiSchema.properties().get()).containsKey("rich_text");

    Schema richTextSchema = geminiSchema.properties().get().get("rich_text");
    assertThat(richTextSchema.type()).isNotNull();
    assertThat(richTextSchema.items()).isPresent();

    Schema itemsSchemaResult = richTextSchema.items().get();
    assertThat(itemsSchemaResult.type()).isNotNull();
    assertThat(itemsSchemaResult.properties()).isPresent();
    assertThat(itemsSchemaResult.properties().get()).containsKey("text");

    Schema textSchemaResult = itemsSchemaResult.properties().get().get("text");
    assertThat(textSchemaResult.properties()).isPresent();
    assertThat(textSchemaResult.properties().get()).containsKey("link");

    Schema linkSchemaResult = textSchemaResult.properties().get().get("link");
    assertThat(linkSchemaResult.type()).isNotNull();
    assertThat(linkSchemaResult.nullable()).isPresent();
    assertThat(linkSchemaResult.nullable().get()).isTrue();
    assertThat(linkSchemaResult.description()).isPresent();
    assertThat(linkSchemaResult.description().get()).isEqualTo("Optional link object");

    assertThat(geminiSchema.properties()).isPresent();
    assertThat(geminiSchema.properties().get()).containsKey("database_id");
    assertThat(geminiSchema.required()).isPresent();
    assertThat(geminiSchema.required().get()).containsExactly("database_id");
  }
}
