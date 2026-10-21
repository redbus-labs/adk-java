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
import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class TrackingHeadersTest {

  @Test
  public void mergeInto_addsOnlyTheTagsACallerValueLacks() {
    ImmutableMap<String, String> merged =
        TrackingHeaders.mergeInto(
            ImmutableMap.of(
                "User-Agent", "my-app/1.0 gl-java/11",
                "X-Goog-Api-Client", "my-lib/2.0 google-adk/0.0.1",
                "X-Custom", "v"));

    assertThat(merged)
        .containsExactly(
            "X-Custom", "v",
            "x-goog-api-client", "my-lib/2.0 google-adk/0.0.1 gl-java/" + JAVA_VERSION.value(),
            "user-agent", "my-app/1.0 gl-java/11 " + TrackingHeaders.ADK_TAG);
  }

  @Test
  public void mergeInto_noCallerValues_addsTrackingHeaders() {
    assertThat(TrackingHeaders.mergeInto(ImmutableMap.of())).isEqualTo(TrackingHeaders.HEADERS);
  }
}
