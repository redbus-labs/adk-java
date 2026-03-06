package com.google.adk.a2a.converters;

/** Enum for the type of A2A DataPart metadata. */
public enum A2ADataPartMetadataType {
  FUNCTION_RESPONSE("function_response"),
  FUNCTION_CALL("function_call"),
  CODE_EXECUTION_RESULT("code_execution_result"),
  EXECUTABLE_CODE("executable_code");

  private final String type;

  private A2ADataPartMetadataType(String type) {
    this.type = type;
  }

  public String getType() {
    return type;
  }
}
