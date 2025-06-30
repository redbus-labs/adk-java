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

package com.google.adk.memory;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import java.util.List;

/** Represents the response from a memory search. */
@AutoValue
public abstract class SearchMemoryResponse {

  /** Returns a list of memory entries that relate to the search query. */
  public abstract ImmutableList<MemoryEntry> memories();

  /** Creates a new builder for {@link SearchMemoryResponse}. */
  public static Builder builder() {
    return new AutoValue_SearchMemoryResponse.Builder().setMemories(ImmutableList.of());
  }

  /** Builder for {@link SearchMemoryResponse}. */
  @AutoValue.Builder
  public abstract static class Builder {

    abstract Builder setMemories(ImmutableList<MemoryEntry> memories);

    /** Sets the list of memory entries using a list. */
    public Builder setMemories(List<MemoryEntry> memories) {
      return setMemories(ImmutableList.copyOf(memories));
    }

    /** Builds the immutable {@link SearchMemoryResponse} object. */
    public abstract SearchMemoryResponse build();
  }
}
