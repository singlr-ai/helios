/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini;

/** Exception thrown when Gemini API operations fail. */
public class GeminiException extends RuntimeException {

  private final int statusCode;

  public GeminiException(String message) {
    super(message);
    this.statusCode = 0;
  }

  public GeminiException(String message, Throwable cause) {
    super(message, cause);
    this.statusCode = 0;
  }

  public GeminiException(String message, int statusCode) {
    super(message);
    this.statusCode = statusCode;
  }

  public GeminiException(String message, int statusCode, Throwable cause) {
    super(message, cause);
    this.statusCode = statusCode;
  }

  public int statusCode() {
    return statusCode;
  }

  public boolean isClientError() {
    return statusCode >= 400 && statusCode < 500;
  }

  public boolean isServerError() {
    return statusCode >= 500;
  }
}
