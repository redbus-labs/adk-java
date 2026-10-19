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

import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class BaseLlmConnectionTest {

  @Test
  public void sendContent_withPartialFlag_delegatesToLegacyImplementation() {
    LegacyConnection connection = new LegacyConnection();
    Content content = Content.fromParts(Part.fromText("context"));

    connection.sendContent(content, true).test().assertComplete().assertNoErrors();
    connection.sendContent(content, false).test().assertComplete().assertNoErrors();

    assertThat(connection.sentContent).containsExactly(content, content).inOrder();
  }

  /** Implements only the original connection API, not the new two-argument overload. */
  private static final class LegacyConnection implements BaseLlmConnection {
    final List<Content> sentContent = new ArrayList<>();

    @Override
    public Completable sendHistory(List<Content> history) {
      return Completable.complete();
    }

    @Override
    public Completable sendContent(Content content) {
      return Completable.fromAction(() -> sentContent.add(content));
    }

    @Override
    public Completable sendRealtime(Blob blob) {
      return Completable.complete();
    }

    @Override
    public Flowable<LlmResponse> receive() {
      return Flowable.empty();
    }

    @Override
    public void close() {}

    @Override
    public void close(Throwable throwable) {}
  }
}
