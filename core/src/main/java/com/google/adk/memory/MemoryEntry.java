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
import com.google.genai.types.Content;
import java.time.Instant;
import javax.annotation.Nullable;

/** Represents one memory entry. */
@AutoValue
public abstract class MemoryEntry {

  /** Returns the main content of the memory. */
  public abstract Content content();

  /** Returns the author of the memory, or null if not set. */
  @Nullable
  public abstract String author();

  /**
   * Returns the timestamp when the original content of this memory happened, or null if not set.
   *
   * <p>This string will be forwarded to LLM. Preferred format is ISO 8601 format
   */
  @Nullable
  public abstract String timestamp();

  /** Returns a new builder for creating a {@link MemoryEntry}. */
  public static Builder builder() {
    return new AutoValue_MemoryEntry.Builder();
  }

  /**
   * Creates a new builder with a copy of this entry's values.
   *
   * @return a new {@link Builder} instance.
   */
  public abstract Builder toBuilder();

  /** Builder for {@link MemoryEntry}. */
  @AutoValue.Builder
  public abstract static class Builder {

    /**
     * Sets the main content of the memory.
     *
     * <p>This is a required field.
     */
    public abstract Builder setContent(Content content);

    /** Sets the author of the memory. */
    public abstract Builder setAuthor(@Nullable String author);

    /** Sets the timestamp when the original content of this memory happened. */
    public abstract Builder setTimestamp(@Nullable String timestamp);

    /**
     * A convenience method to set the timestamp from an {@link Instant} object, formatted as an ISO
     * 8601 string.
     *
     * @param instant The timestamp as an Instant object.
     */
    public Builder setTimestamp(Instant instant) {
      return setTimestamp(instant.toString());
    }

    /** Builds the immutable {@link MemoryEntry} object. */
    public abstract MemoryEntry build();
  }
}
