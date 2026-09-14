package com.jobshunter.mcp.exception;

/** Machine-readable classification for {@link JobshunterApiException}, letting MCP clients branch on error type
 * instead of string-matching messages. */
public enum ErrorCode {
  AUTH_FAILED,
  TIMEOUT,
  UPSTREAM_UNAVAILABLE,
  VALIDATION,
  UNKNOWN
}
