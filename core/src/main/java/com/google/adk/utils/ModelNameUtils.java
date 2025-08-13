package com.google.adk.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ModelNameUtils {
  private static final Pattern GEMINI_2_PATTERN = Pattern.compile("^gemini-2\\..*");
  private static final Pattern PATH_PATTERN =
      Pattern.compile("^projects/[^/]+/locations/[^/]+/publishers/[^/]+/models/(.+)$");

  public static boolean isGemini2Model(String modelString) {
    if (modelString == null) {
      return false;
    }
    String modelName = extractModelName(modelString);
    return GEMINI_2_PATTERN.matcher(modelName).matches();
  }

  /**
   * Extract the actual model name from either simple or path-based format.
   *
   * @param modelString Either a simple model name like "gemini-2.5-pro" or a path-based model name
   *     like "projects/.../models/gemini-2.0-flash-001"
   * @return The extracted model name (e.g., "gemini-2.5-pro")
   */
  private static String extractModelName(String modelString) {
    Matcher matcher = PATH_PATTERN.matcher(modelString);
    if (matcher.matches()) {
      return matcher.group(1);
    }
    return modelString;
  }

  private ModelNameUtils() {}
}
