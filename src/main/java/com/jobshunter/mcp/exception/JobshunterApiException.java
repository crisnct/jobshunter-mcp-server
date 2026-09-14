package com.jobshunter.mcp.exception;

import lombok.Getter;

@Getter
public class JobshunterApiException extends RuntimeException {

  private final ErrorCode errorCode;

  public JobshunterApiException(String message) {
    this(ErrorCode.UNKNOWN, message);
  }

  public JobshunterApiException(String message, Throwable cause) {
    this(ErrorCode.UNKNOWN, message, cause);
  }

  public JobshunterApiException(ErrorCode errorCode, String message) {
    super(message);
    this.errorCode = errorCode;
  }

  public JobshunterApiException(ErrorCode errorCode, String message, Throwable cause) {
    super(message, cause);
    this.errorCode = errorCode;
  }
}
