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

package com.google.adk.utils;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.adk.utils.FeatureDecorator.Experimental;
import com.google.adk.utils.FeatureDecorator.WorkInProgress;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class FeatureDecoratorTest {

  @WorkInProgress("This is a WIP class.")
  private static class WipClass {}

  @Experimental("This is an experimental class.")
  private static class ExperimentalClass {}

  private static class TestClass {
    @WorkInProgress("This is a WIP method.")
    @SuppressWarnings("unused") // Used via reflection in tests
    private void wipMethod() {}

    @Experimental("This is an experimental method.")
    @SuppressWarnings("unused") // Used via reflection in tests
    private void experimentalMethod() {}
  }

  @Test
  public void handle_wipClass_throwsException() {
    UnsupportedOperationException exception =
        assertThrows(
            UnsupportedOperationException.class, () -> FeatureDecorator.handle(WipClass.class));

    assertTrue(exception.getMessage().contains("[WIP] WipClass: This is a WIP class."));
  }

  @Test
  public void handle_wipMethod_throwsException() throws NoSuchMethodException {
    UnsupportedOperationException exception =
        assertThrows(
            UnsupportedOperationException.class,
            () -> FeatureDecorator.handle(TestClass.class.getDeclaredMethod("wipMethod")));

    assertTrue(exception.getMessage().contains("[WIP] wipMethod: This is a WIP method."));
  }

  @Test
  public void handle_experimentalClass_doesNotThrow() {
    // Simply verify that handling experimental classes doesn't throw
    FeatureDecorator.handle(ExperimentalClass.class);
    // If we get here without exception, the test passes
  }

  @Test
  public void handle_experimentalMethod_doesNotThrow() throws NoSuchMethodException {
    // Simply verify that handling experimental methods doesn't throw
    FeatureDecorator.handle(TestClass.class.getDeclaredMethod("experimentalMethod"));
    // If we get here without exception, the test passes
  }

  @Test
  public void handle_normalClass_doesNotThrow() {
    // Verify that handling non-annotated classes works fine
    FeatureDecorator.handle(TestClass.class);
  }
}
