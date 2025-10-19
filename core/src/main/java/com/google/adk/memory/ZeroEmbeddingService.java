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

import io.reactivex.rxjava3.core.Single;

/** A placeholder implementation of the EmbeddingService that returns a zero vector. */
public class ZeroEmbeddingService implements EmbeddingService {

  private final int dimension;

  public ZeroEmbeddingService(int dimension) {
    this.dimension = dimension;
  }

  @Override
  public Single<double[]> generateEmbedding(String text) {
    return Single.fromCallable(() -> new double[dimension]);
  }
}
