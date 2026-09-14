package com.jobshunter.mcp.logging;

import java.util.UUID;
import org.slf4j.MDC;

/** Correlation id propagated through logs and forwarded to Jobshunter for a single {@code tools/call}. */
public final class RequestContext {

  public static final String REQUEST_ID_HEADER = "X-Request-Id";
  public static final String REQUEST_ID_MDC_KEY = "requestId";

  private RequestContext() {
  }

  public static String newRequestId() {
    return UUID.randomUUID().toString();
  }

  public static String currentRequestId() {
    return MDC.get(REQUEST_ID_MDC_KEY);
  }
}
