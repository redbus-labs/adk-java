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

package com.google.adk.plugins.agentanalytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.auto.value.AutoValue;
import com.google.common.base.Utf8;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Utility for parsing, formatting and truncating content for BigQuery logging. */
final class JsonFormatter {
  static final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  static final String TRUNCATION_SUFFIX = "...[truncated]";

  @AutoValue
  abstract static class TruncationResult {
    abstract JsonNode node();

    abstract boolean isTruncated();

    static TruncationResult create(JsonNode node, boolean isTruncated) {
      return new AutoValue_JsonFormatter_TruncationResult(node, isTruncated);
    }
  }

  /** Recursively truncates long strings inside an object and returns a TruncationResult. */
  static TruncationResult smartTruncate(Object obj, int maxLength) {
    if (obj == null) {
      return TruncationResult.create(mapper.nullNode(), false);
    }
    try {
      return recursiveSmartTruncate(mapper.valueToTree(obj), maxLength);
    } catch (IllegalArgumentException e) {
      // Fallback for types that mapper can't handle directly as a tree
      return truncateWithStatus(String.valueOf(obj), maxLength);
    }
  }

  static JsonNode convertToJsonNode(Object obj) {
    if (obj == null) {
      return mapper.nullNode();
    }
    try {
      return mapper.valueToTree(obj);
    } catch (IllegalArgumentException e) {
      // Fallback for types that mapper can't handle directly as a tree
      return mapper.valueToTree(String.valueOf(obj));
    }
  }

  private static TruncationResult recursiveSmartTruncate(JsonNode node, int maxLength) {
    boolean isTruncated = false;
    if (node.isTextual()) {
      String text = node.asText();
      if (Utf8.encodedLength(text) > maxLength) {
        return TruncationResult.create(mapper.valueToTree(truncate(text, maxLength)), true);
      }
      return TruncationResult.create(node, false);
    } else if (node.isObject()) {
      ObjectNode newNode = mapper.createObjectNode();
      Set<Map.Entry<String, JsonNode>> properties = node.properties();
      for (Map.Entry<String, JsonNode> entry : properties) {
        TruncationResult res = recursiveSmartTruncate(entry.getValue(), maxLength);
        newNode.set(entry.getKey(), res.node());
        isTruncated = isTruncated || res.isTruncated();
      }
      return TruncationResult.create(newNode, isTruncated);
    } else if (node.isArray()) {
      ArrayNode newNode = mapper.createArrayNode();
      for (JsonNode element : node) {
        TruncationResult res = recursiveSmartTruncate(element, maxLength);
        newNode.add(res.node());
        isTruncated = isTruncated || res.isTruncated();
      }
      return TruncationResult.create(newNode, isTruncated);
    }
    return TruncationResult.create(node, false);
  }

  static TruncationResult truncateWithStatus(String s, int maxLength) {
    if (s == null) {
      return TruncationResult.create(mapper.nullNode(), false);
    }
    if (Utf8.encodedLength(s) <= maxLength) {
      return TruncationResult.create(mapper.valueToTree(s), false);
    }
    return TruncationResult.create(mapper.valueToTree(truncate(s, maxLength)), true);
  }

  static @Nullable String truncate(String s, int budget) {
    return truncateAndAddSuffix(s, budget, TRUNCATION_SUFFIX);
  }

  static @Nullable String truncateAndAddSuffix(String s, int budget, String suffix) {
    if (s == null) {
      return null;
    }
    if (Utf8.encodedLength(s) <= budget) {
      return s;
    }
    int suffixBytes = Utf8.encodedLength(suffix);
    int effectiveBudget = Math.max(0, budget - suffixBytes);
    // Fallback in case the budget is too small
    if (effectiveBudget == 0) {
      return suffix.substring(0, budget);
    }

    int byteCount = 0;
    int charIndex = 0;
    for (int i = 0; i < s.length(); ) {
      int codePoint = s.codePointAt(i);
      int codePointLen = Character.charCount(codePoint);
      int codePointBytes;
      if (codePoint < 0x80) {
        codePointBytes = 1;
      } else if (codePoint < 0x800) {
        codePointBytes = 2;
      } else if (codePoint < 0x10000) {
        codePointBytes = 3;
      } else {
        codePointBytes = 4;
      }

      if (byteCount + codePointBytes > effectiveBudget) {
        break;
      }
      byteCount += codePointBytes;
      charIndex += codePointLen;
      i += codePointLen;
    }

    return s.substring(0, charIndex) + suffix;
  }

  /** Converts a JsonNode to a standard Java object (Map, List, etc.). */
  public static @Nullable Object toJavaObject(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    return mapper.convertValue(node, Object.class);
  }

  private JsonFormatter() {}
}
