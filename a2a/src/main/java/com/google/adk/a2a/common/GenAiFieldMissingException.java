package com.google.adk.a2a.common;

/** Exception thrown when the the genai class has an empty field. */
public class GenAiFieldMissingException extends RuntimeException {
  public GenAiFieldMissingException(String message) {
    super(message);
  }

  public GenAiFieldMissingException(String message, Throwable cause) {
    super(message, cause);
  }
}
