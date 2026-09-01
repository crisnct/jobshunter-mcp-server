package com.jobshunter.mcp.exception;

public class JobshunterApiException extends RuntimeException {

  public JobshunterApiException(String message) {
    super(message);
  }

  public JobshunterApiException(String message, Throwable cause) {
    super(message, cause);
  }
}
