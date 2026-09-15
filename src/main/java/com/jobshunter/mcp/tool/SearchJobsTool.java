package com.jobshunter.mcp.tool;

import com.jobshunter.mcp.client.JobshunterClient;
import com.jobshunter.mcp.dto.SearchConfiguration;
import com.jobshunter.mcp.dto.SearchJobSnapshot;
import com.jobshunter.mcp.dto.SearchJobResult;
import com.jobshunter.mcp.dto.SearchJobsHandle;
import com.jobshunter.mcp.dto.SearchStepEvent;
import com.jobshunter.mcp.dto.SearchWaitResult;
import com.jobshunter.mcp.exception.ErrorCode;
import com.jobshunter.mcp.exception.JobshunterApiException;
import com.jobshunter.mcp.logging.RequestContext;
import com.jobshunter.mcp.security.DelegatedTokenResolver;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

@Slf4j
@Service
@Validated
public class SearchJobsTool {

  /**
   * Ceiling for a single wait_for_search. Kept under {@code spring.ai.mcp.server.request-timeout}
   * (5m) so the MCP session can still send the final tool result. Live hunt messages are pushed
   * via progress/logging notifications during this window — they must not wait for this return.
   */
  private static final int MAX_SAFE_WAIT_SECONDS = 240;

  private final JobshunterClient jobshunterClient;
  private final DelegatedTokenResolver delegatedTokenResolver;
  private final Duration pollInterval;

  // A single constructor, so Spring's constructor-injection is unambiguous; tests pass a much
  // shorter pollInterval directly so an IN_PROGRESS/timeout case doesn't have to burn real
  // wall-clock time.
  public SearchJobsTool(
      JobshunterClient jobshunterClient,
      DelegatedTokenResolver delegatedTokenResolver,
      @Value("${jobshunter.search.poll-interval:1s}") Duration pollInterval) {
    this.jobshunterClient = jobshunterClient;
    this.delegatedTokenResolver = delegatedTokenResolver;
    this.pollInterval = pollInterval;
  }

  @McpTool(name = "search_jobs", description = """
      Start a job search across the given configurations. Returns immediately with a searchId -
      call wait_for_search once with that id. Hunt messages stream live during that call.

      Input:
      - searchConfigurations: list of provider/model configurations.
      - provider: AI provider name configured in Jobshunter (example: GROK, SERP, GEMINI, GPT).
      - model: exact model identifier for the selected provider.
      - searchCompanies: when true, search by company-based heuristics.
      - searchWithUserPrompts: when true, search using the user's stored prompts/preferences.
      - Validation rule: at least one of searchCompanies or searchWithUserPrompts must be true.

      Response:
      - searchId: pass this to wait_for_search to track progress and retrieve results.
      - configurationsSubmitted: number of configurations accepted for this search.

      Authentication:
      - Requires a valid MCP bearer token already authenticated on /mcp.
      - The tool resolves a delegated bearer token and forwards it to Jobshunter internal API.
      """)
  public SearchJobsHandle searchJobs(
      @McpToolParam(required = true, description = """
          Array of search configuration objects.
          Each object contains:
          - provider (string, required): provider key configured in Jobshunter.
          - model (string, required): model key for the provider.
          - searchCompanies (boolean): include company-oriented search strategy.
          - searchWithUserPrompts (boolean): include user-prompt-oriented strategy.
          Constraint: at least one boolean must be true.
          """)
      @NotEmpty List<@Valid SearchConfiguration> searchConfigurations
  ) {
    validateSearchRequest(searchConfigurations);
    log.debug("search_jobs invoked: configurations={}", searchConfigurations.size());
    return callJobshunter(() -> {
      String userToken = resolveUserToken("search_jobs");
      return jobshunterClient.startSearch(searchConfigurations, userToken);
    });
  }

  @McpTool(name = "wait_for_search", description = """
      Wait for a search started with search_jobs to finish, for up to maxWaitSeconds. Stay on
      this single call — hunt messages are streamed live as MCP progress (and logging)
      notifications while the search runs. Do not poll this tool once per event.

      Input:
      - searchId: the id returned by search_jobs.
      - maxWaitSeconds: how long to keep this call open waiting for DONE (use 120-240).

      While this call is running, each hunt message is pushed immediately as a progress
      notification (and as a logging notification if the client has no progress token).
      Quote those live messages to the user as they arrive.

      Final response text:
      - Hunt progress: all hunt messages recorded, oldest first.
      - status: DONE, FAILED, or IN_PROGRESS (call again only if still IN_PROGRESS after timeout).
      - result / jobsFound: populated only when status is DONE.
      - errorMessage: populated only when status is FAILED.
      - searchId: present for IN_PROGRESS and FAILED.

      Authentication:
      - Requires a valid MCP bearer token already authenticated on /mcp.
      - The tool resolves a delegated bearer token and forwards it to Jobshunter internal API.
      """)
  public CallToolResult waitForSearch(
      @McpToolParam(required = true, description = "The searchId returned by search_jobs.")
      String searchId,
      @McpToolParam(required = true, description = "How long to keep this call open waiting for the search "
          + "to finish, in seconds. Use 120-240 so hunt messages can stream live.")
      int maxWaitSeconds,
      McpSyncRequestContext context
  ) {
    log.debug("wait_for_search invoked: searchId={}, maxWaitSeconds={}", searchId, maxWaitSeconds);
    return callJobshunter(() -> {
      String userToken = resolveUserToken("wait_for_search");
      ProgressSink sink = new ProgressSink() {
        @Override
        public void report(long progressIndex, String message) {
          notifyLiveUpdate(context, progressIndex, message);
        }

        @Override
        public void keepAlive() {
          pingClient(context);
        }
      };
      SearchWaitResult result = pollUntilDoneOrTimeout(searchId, maxWaitSeconds, userToken, sink);
      log.info("wait_for_search finished: searchId={}, status={}, eventsSoFar={}",
          searchId, result.status(), result.progressEvents() == null ? 0 : result.progressEvents().size());
      return toCallToolResult(result);
    });
  }

  /**
   * Framework-free core logic, package-private so it's directly unit-testable without
   * mocking McpSyncRequestContext. Stays open until the search finishes or the wait elapses,
   * reporting each newly observed hunt message through {@code sink} as soon as it appears.
   */
  SearchWaitResult pollUntilDoneOrTimeout(
      String searchId, int maxWaitSeconds, String userToken, ProgressSink sink) {
    int boundedWait = clampWaitSeconds(maxWaitSeconds);
    Instant deadline = Instant.now().plusSeconds(boundedWait);
    int notified = 0;

    while (true) {
      SearchJobSnapshot snapshot = jobshunterClient.getSearchSnapshot(searchId, userToken);
      List<String> events = snapshotProgressEvents(snapshot);

      for (int i = notified; i < events.size(); i++) {
        sink.report(i + 1L, events.get(i));
      }
      notified = events.size();

      if (snapshot.done()) {
        return toWaitResult(snapshot);
      }
      if (!Instant.now().isBefore(deadline)) {
        return SearchWaitResult.stillRunning(searchId, events);
      }

      sink.keepAlive();
      sleepQuietly(pollInterval);
    }
  }

  int clampWaitSeconds(int requested) {
    return Math.clamp(requested, 1, MAX_SAFE_WAIT_SECONDS);
  }

  private SearchWaitResult toWaitResult(SearchJobSnapshot snapshot) {
    List<String> events = snapshotProgressEvents(snapshot);
    return switch (snapshot.status()) {
      case "DONE" -> SearchWaitResult.done(snapshot.result(), events);
      case "FAILED" -> SearchWaitResult.failed(snapshot.searchId(), snapshot.errorMessage(), events);
      default -> SearchWaitResult.stillRunning(snapshot.searchId(), events);
    };
  }

  private static List<String> snapshotProgressEvents(SearchJobSnapshot snapshot) {
    if (snapshot.events() == null || snapshot.events().isEmpty()) {
      return List.of();
    }
    return snapshot.events().stream()
        .filter(event -> event != null && event.message() != null)
        .map(SearchStepEvent::message)
        .toList();
  }

  /**
   * Push each hunt message live. {@code notifications/progress} needs a client progress token;
   * {@code notifications/message} (info) does not. Both are best-effort so a client that
   * supports neither still gets the final tool result.
   */
  private void notifyLiveUpdate(McpSyncRequestContext context, long progressIndex, String message) {
    try {
      context.progress(p -> p.progress(progressIndex).message(message));
    } catch (RuntimeException ex) {
      log.debug("Skipping MCP progress notification: {}", ex.getMessage());
    }
    try {
      context.info(message);
    } catch (RuntimeException ex) {
      log.debug("Skipping MCP logging notification: {}", ex.getMessage());
    }
  }

  private void pingClient(McpSyncRequestContext context) {
    try {
      context.ping();
    } catch (RuntimeException ex) {
      log.debug("Skipping MCP ping: {}", ex.getMessage());
    }
  }

  /**
   * Text-only on purpose: Claude reads {@code content[].text} reliably, but often skips string
   * arrays inside {@code structuredContent}.
   */
  private CallToolResult toCallToolResult(SearchWaitResult result) {
    return CallToolResult.builder()
        .addTextContent(formatWaitResultForClient(result))
        .build();
  }

  static String formatWaitResultForClient(SearchWaitResult result) {
    List<String> events = result.progressEvents() == null ? List.of() : result.progressEvents();

    StringBuilder text = new StringBuilder();
    text.append("Hunt progress:\n");
    if (events.isEmpty()) {
      text.append("(none yet)\n");
    } else {
      for (String event : events) {
        text.append("- ").append(event).append('\n');
      }
    }
    text.append('\n');
    text.append("status: ").append(result.status()).append('\n');
    if (result.searchId() != null) {
      text.append("searchId: ").append(result.searchId()).append('\n');
    }
    if (result.errorMessage() != null) {
      text.append("errorMessage: ").append(result.errorMessage()).append('\n');
    }
    if (result.result() != null && result.result().jobsFound() != null) {
      var jobs = result.result().jobsFound();
      text.append("jobsFound (").append(jobs.size()).append("):\n");
      for (SearchJobResult job : jobs) {
        text.append("- ").append(job.url());
        if (job.source() != null) {
          text.append(" (").append(job.source()).append(')');
        }
        text.append('\n');
      }
    }
    return text.toString().trim();
  }

  private void sleepQuietly(Duration duration) {
    try {
      Thread.sleep(duration);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for search progress", e);
    }
  }

  private <T> T callJobshunter(Supplier<T> call) {
    String requestId = RequestContext.newRequestId();
    MDC.put(RequestContext.REQUEST_ID_MDC_KEY, requestId);
    try {
      log.info("tools/call started: requestId={}", requestId);
      return call.get();
    } catch (JobshunterApiException ex) {
      throw ex;
    } catch (Exception ex) {
      log.warn("Unexpected error while calling Jobshunter: requestId={}", requestId, ex);
      throw new JobshunterApiException(ErrorCode.UNKNOWN, "Jobshunter returned an unexpected error.", ex);
    } finally {
      MDC.remove(RequestContext.REQUEST_ID_MDC_KEY);
    }
  }

  private String resolveUserToken(String toolName) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!(authentication instanceof JwtAuthenticationToken jwtAuthenticationToken)) {
      throw new JobshunterApiException(
          ErrorCode.AUTH_FAILED, "Authenticated MCP token is required to call " + toolName + ".");
    }
    return delegatedTokenResolver.resolveDelegatedToken(jwtAuthenticationToken.getToken());
  }

  private void validateSearchRequest(List<SearchConfiguration> searchConfigurations) {
    for (SearchConfiguration configuration : searchConfigurations) {
      if (!configuration.searchCompanies() && !configuration.searchWithUserPrompts()) {
        throw new JobshunterApiException(
            ErrorCode.VALIDATION,
            "Invalid search configuration: at least one of searchCompanies or searchWithUserPrompts must be true.");
      }
    }
  }

  @FunctionalInterface
  interface ProgressSink {
    void report(long progressIndex, String message);

    default void keepAlive() {
    }
  }
}
