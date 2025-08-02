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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A utility class for handling feature decorators.
 *
 * <p>This class provides methods for checking for feature decorators on classes and methods and
 * handling them appropriately.
 */
public final class FeatureDecorator {

  /**
   * Mark a class or a function as a work in progress.
   *
   * <p>By default, decorated functions/classes will raise RuntimeError when used. Set
   * ADK_ALLOW_WIP_FEATURES=true environment variable to bypass this restriction. ADK users are not
   * supposed to set this environment variable.
   *
   * <p>Sample usage:
   *
   * <pre>
   * {@literal @}WorkInProgress("This feature is not ready for production use.")
   * public void myWipFunction() {
   *   // ...
   * }
   * </pre>
   */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR})
  public @interface WorkInProgress {
    /**
     * The message to be displayed when the feature is used. If not provided, a default message will
     * be used.
     */
    String value() default
        "This feature is a work in progress and is not working completely. ADK users are not"
            + " supposed to use it.";
  }

  /**
   * Mark a class or a function as an experimental feature.
   *
   * <p>Sample usage:
   *
   * <pre>
   * // Use with default message
   * {@literal @}Experimental
   * public class ExperimentalClass {
   *   // ...
   * }
   *
   * // Use with custom message
   * {@literal @}Experimental("This API may have breaking change in the future.")
   * public class CustomExperimentalClass {
   *   // ...
   * }
   * </pre>
   */
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR})
  public @interface Experimental {
    /**
     * The message to be displayed when the feature is used. If not provided, a default message will
     * be used.
     */
    String value() default
        "This feature is experimental and may change or be removed in future versions without"
            + " notice. It may introduce breaking changes at any time.";
  }

  private static final Logger logger = LoggerFactory.getLogger(FeatureDecorator.class);

  /**
   * Handles the feature decorators for the given class.
   *
   * @param clazz the class to handle the decorators for
   */
  public static void handle(Class<?> clazz) {
    handleExperimental(clazz);
    handleWorkInProgress(clazz);
  }

  /**
   * Handles the feature decorators for the given method.
   *
   * @param method the method to handle the decorators for
   */
  public static void handle(Method method) {
    handleExperimental(method);
    handleWorkInProgress(method);
  }

  /**
   * Handles the feature decorators for the given constructor.
   *
   * @param constructor the constructor to handle the decorators for
   */
  public static void handle(Constructor<?> constructor) {
    handleExperimental(constructor);
    handleWorkInProgress(constructor);
  }

  private static void handleExperimental(AnnotatedElement element) {
    if (element.isAnnotationPresent(Experimental.class)) {
      Experimental annotation = element.getAnnotation(Experimental.class);
      String message = annotation.value();
      logger.warn("[EXPERIMENTAL] {}: {}", getElementName(element), message);
    }
  }

  private static void handleWorkInProgress(AnnotatedElement element) {
    if (element.isAnnotationPresent(WorkInProgress.class)) {
      String bypassEnvVar = "ADK_ALLOW_WIP_FEATURES";
      String envValue = System.getenv(bypassEnvVar);
      if (envValue == null || !envValue.toLowerCase(Locale.ROOT).equals("true")) {
        WorkInProgress annotation = element.getAnnotation(WorkInProgress.class);
        String message = annotation.value();
        throw new UnsupportedOperationException(
            "[WIP] " + getElementName(element) + ": " + message);
      }
    }
  }

  private static String getElementName(AnnotatedElement element) {
    if (element instanceof Class) {
      return ((Class<?>) element).getSimpleName();
    } else if (element instanceof Method method) {
      return method.getName();
    } else if (element instanceof Constructor) {
      return ((Constructor<?>) element).getDeclaringClass().getSimpleName();
    }
    return "UnknownElement";
  }

  private FeatureDecorator() {}
}
