package com.jobshunter.mcp.security;

import org.springframework.http.HttpStatus;

/**
 * Signals that an incoming /authorize or /token request failed OAuth broker validation
 * (missing/invalid parameter, disallowed redirect_uri, unsupported grant/response type, ...).
 */
class OAuthValidationException extends RuntimeException {

  final HttpStatus status;
  final String errorCode;

  OAuthValidationException(HttpStatus status, String errorCode, String message) {
    super(message);
    this.status = status;
    this.errorCode = errorCode;
  }
}
