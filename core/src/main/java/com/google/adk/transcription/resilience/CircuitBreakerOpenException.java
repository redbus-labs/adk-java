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

package com.google.adk.transcription.resilience;

/**
 * Exception thrown when a circuit breaker is in the OPEN state and rejecting calls. This indicates
 * that the downstream service has experienced too many failures and the circuit breaker is
 * protecting the system from further load.
 *
 * @author Sandeep Belgavi
 * @since 2026-08-04
 */
public class CircuitBreakerOpenException extends RuntimeException {

  /** Creates a new CircuitBreakerOpenException with a default message. */
  public CircuitBreakerOpenException() {
    super("Circuit breaker is OPEN — calls are being rejected to protect the downstream service");
  }

  /**
   * Creates a new CircuitBreakerOpenException with a custom message.
   *
   * @param message the detail message
   */
  public CircuitBreakerOpenException(String message) {
    super(message);
  }

  /**
   * Creates a new CircuitBreakerOpenException with a message and cause.
   *
   * @param message the detail message
   * @param cause the underlying cause
   */
  public CircuitBreakerOpenException(String message, Throwable cause) {
    super(message, cause);
  }
}
