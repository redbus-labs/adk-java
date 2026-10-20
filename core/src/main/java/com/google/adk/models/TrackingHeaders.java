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

import static com.google.common.base.StandardSystemProperty.JAVA_VERSION;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.stream.Collectors.joining;

import com.google.adk.Version;
import com.google.common.base.Ascii;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Headers that let Google APIs attribute model calls to ADK, in the format all ADK SDKs use. A
 * header value is a space-separated list of {@code name/version} tags.
 */
final class TrackingHeaders {

  private static final Splitter TAG_SPLITTER = Splitter.on(' ').omitEmptyStrings();

  /** ADK's own tag. */
  static final String ADK_TAG = "google-adk/" + Version.JAVA_ADK_VERSION;

  /** ADK's tag followed by the Java version tag, for clients that send no language tag. */
  static final String VALUE = ADK_TAG + " gl-java/" + JAVA_VERSION.value();

  static final ImmutableMap<String, String> HEADERS =
      ImmutableMap.of("x-goog-api-client", VALUE, "user-agent", VALUE);

  /**
   * Headers for a genai SDK client, which puts its own {@code gl-java} tag first. Both carry only
   * {@link #ADK_TAG}.
   */
  static final ImmutableMap<String, String> GENAI_SDK_HEADERS =
      ImmutableMap.of("x-goog-api-client", ADK_TAG, "user-agent", ADK_TAG);

  /**
   * Like {@link #mergeInto}, but adds only {@link #ADK_TAG}, for headers that the genai SDK appends
   * to its own label.
   */
  static ImmutableMap<String, String> mergeIntoGenAiSdkHeaders(Map<String, String> headers) {
    return mergeInto(headers, ADK_TAG);
  }

  /**
   * Returns {@code headers} with each tracking header, matched case-insensitively, followed by
   * every tag of {@link #VALUE} whose name it lacks.
   */
  static ImmutableMap<String, String> mergeInto(Map<String, String> headers) {
    return mergeInto(headers, VALUE);
  }

  private static ImmutableMap<String, String> mergeInto(
      Map<String, String> headers, String adkTags) {
    Map<String, String> merged = new LinkedHashMap<>();
    Map<String, String> callerValues = new LinkedHashMap<>();
    headers.forEach(
        (name, value) -> {
          String trackingName = Ascii.toLowerCase(name);
          if (HEADERS.containsKey(trackingName)) {
            callerValues.merge(trackingName, value, (first, second) -> first + " " + second);
          } else {
            merged.put(name, value);
          }
        });
    for (String name : HEADERS.keySet()) {
      merged.put(name, extend(callerValues.getOrDefault(name, ""), adkTags));
    }
    return ImmutableMap.copyOf(merged);
  }

  /** Returns {@code callerValue} followed by each tag of {@code adkTags} whose name it lacks. */
  private static String extend(String callerValue, String adkTags) {
    List<String> tags = TAG_SPLITTER.splitToList(callerValue);
    ImmutableSet<String> names =
        tags.stream().map(TrackingHeaders::tagName).collect(toImmutableSet());
    return Stream.concat(
            tags.stream(),
            TAG_SPLITTER.splitToStream(adkTags).filter(tag -> !names.contains(tagName(tag))))
        .collect(joining(" "));
  }

  private static String tagName(String tag) {
    int slash = tag.indexOf('/');
    return slash < 0 ? tag : tag.substring(0, slash);
  }

  private TrackingHeaders() {}
}
