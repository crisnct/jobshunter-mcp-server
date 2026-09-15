package com.jobshunter.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jobshunter.mcp.client.JobshunterClient;
import com.jobshunter.mcp.dto.SearchConfiguration;
import com.jobshunter.mcp.dto.SearchJobResult;
import com.jobshunter.mcp.dto.SearchJobSnapshot;
import com.jobshunter.mcp.dto.SearchJobsHandle;
import com.jobshunter.mcp.dto.SearchJobsResponse;
import com.jobshunter.mcp.dto.SearchStepEvent;
import com.jobshunter.mcp.dto.SearchWaitResult;
import com.jobshunter.mcp.exception.ErrorCode;
import com.jobshunter.mcp.exception.JobshunterApiException;
import com.jobshunter.mcp.security.DelegatedTokenResolver;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class SearchJobsToolTest {

  private final JobshunterClient jobshunterClient = mock(JobshunterClient.class);
  private final DelegatedTokenResolver delegatedTokenResolver = mock(DelegatedTokenResolver.class);
  private final SearchJobsTool tool =
      new SearchJobsTool(jobshunterClient, delegatedTokenResolver, Duration.ofMillis(10));

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void searchJobsDelegatesToClientAndReturnsHandle() {
    authenticateAsUser1();
    List<SearchConfiguration> configs = List.of(
        new SearchConfiguration("GROK", "grok-4-1-fast-non-reasoning", true, false));
    when(jobshunterClient.startSearch(configs, "delegated-token"))
        .thenReturn(new SearchJobsHandle("search-123", 1));

    SearchJobsHandle handle = tool.searchJobs(configs);

    assertThat(handle.searchId()).isEqualTo("search-123");
    assertThat(handle.configurationsSubmitted()).isEqualTo(1);
  }

  @Test
  void searchJobsRejectsInvalidSearchConfiguration() {
    List<SearchConfiguration> configs = List.of(
        new SearchConfiguration("GROK", "grok-4-1-fast-non-reasoning", false, false));

    JobshunterApiException ex = assertThrows(JobshunterApiException.class, () -> tool.searchJobs(configs));

    assertEquals(
        "Invalid search configuration: at least one of searchCompanies or searchWithUserPrompts must be true.",
        ex.getMessage());
    assertEquals(ErrorCode.VALIDATION, ex.getErrorCode());
    verifyNoInteractions(jobshunterClient);
  }

  @Test
  void searchJobsRejectsRequestWhenGoogleTokenIsMissingFromContext() {
    List<SearchConfiguration> configs = List.of(
        new SearchConfiguration("GROK", "grok-4-1-fast-non-reasoning", false, true));

    JobshunterApiException ex = assertThrows(JobshunterApiException.class, () -> tool.searchJobs(configs));

    assertEquals("Authenticated MCP token is required to call search_jobs.", ex.getMessage());
    assertEquals(ErrorCode.AUTH_FAILED, ex.getErrorCode());
  }

  @Test
  void waitForSearchReturnsTextProgressEvenWhenMcpProgressNotificationsFail() {
    authenticateAsUser1();
    SearchJobSnapshot done = new SearchJobSnapshot("s1", "DONE",
        List.of(new SearchStepEvent("Processing started"),
            new SearchStepEvent("Starting hunt orchestration")),
        new SearchJobsResponse(List.of()), null);
    when(jobshunterClient.getSearchSnapshot("s1", "delegated-token")).thenReturn(done);
    McpSyncRequestContext context = mock(McpSyncRequestContext.class);
    org.mockito.Mockito.doThrow(new IllegalStateException("No progress token"))
        .when(context).progress(org.mockito.ArgumentMatchers.<java.util.function.Consumer<org.springframework.ai.mcp.annotation.context.McpRequestContextTypes.ProgressSpec>>any());

    var callResult = tool.waitForSearch("s1", 5, context);

    assertThat(callResult.content().getFirst()).isInstanceOf(io.modelcontextprotocol.spec.McpSchema.TextContent.class);
    String text = ((io.modelcontextprotocol.spec.McpSchema.TextContent) callResult.content().getFirst()).text();
    assertThat(text).startsWith("Hunt progress:");
    assertThat(text).contains("Processing started");
    assertThat(text).contains("Starting hunt orchestration");
    assertThat(text).contains("status: DONE");
    assertThat(callResult.structuredContent()).isNull();
  }

  @Test
  void waitForSearchPushesLiveUpdatesUntilDone() {
    authenticateAsUser1();
    SearchJobSnapshot first = new SearchJobSnapshot("s1", "IN_PROGRESS",
        List.of(new SearchStepEvent("Processing started")), null, null);
    SearchJobSnapshot done = new SearchJobSnapshot("s1", "DONE",
        List.of(new SearchStepEvent("Processing started"),
            new SearchStepEvent("Starting hunt orchestration")),
        new SearchJobsResponse(List.of()), null);
    when(jobshunterClient.getSearchSnapshot("s1", "delegated-token")).thenReturn(first, done);
    McpSyncRequestContext context = mock(McpSyncRequestContext.class);

    var callResult = tool.waitForSearch("s1", 5, context);

    String text = ((io.modelcontextprotocol.spec.McpSchema.TextContent) callResult.content().getFirst()).text();
    assertThat(text).contains("Processing started");
    assertThat(text).contains("Starting hunt orchestration");
    assertThat(text).contains("status: DONE");
    org.mockito.Mockito.verify(context, org.mockito.Mockito.atLeastOnce())
        .progress(org.mockito.ArgumentMatchers.<java.util.function.Consumer<org.springframework.ai.mcp.annotation.context.McpRequestContextTypes.ProgressSpec>>any());
    org.mockito.Mockito.verify(context).info("Processing started");
    org.mockito.Mockito.verify(context).info("Starting hunt orchestration");
    org.mockito.Mockito.verify(context, org.mockito.Mockito.atLeastOnce()).ping();
  }

  @Test
  void formatWaitResultForClientListsProgressEvents() {
    String text = SearchJobsTool.formatWaitResultForClient(SearchWaitResult.stillRunning(
        "101,102",
        List.of("Processing started", "Starting hunt orchestration")));

    assertThat(text).startsWith("Hunt progress:");
    assertThat(text).contains("status: IN_PROGRESS");
    assertThat(text).contains("searchId: 101,102");
    assertThat(text).contains("- Processing started");
    assertThat(text).contains("- Starting hunt orchestration");
  }

  @Test
  void formatWaitResultForClientListsJobUrls() {
    String text = SearchJobsTool.formatWaitResultForClient(SearchWaitResult.done(
        new SearchJobsResponse(List.of(new SearchJobResult("https://example.com/job-1", "SERP"))),
        List.of("Processing started")));

    assertThat(text).contains("- Processing started");
    assertThat(text).contains("status: DONE");
    assertThat(text).contains("https://example.com/job-1 (SERP)");
  }

  @Test
  void waitForSearchRejectsRequestWhenGoogleTokenIsMissingFromContext() {
    McpSyncRequestContext context = mock(McpSyncRequestContext.class);

    JobshunterApiException ex = assertThrows(
        JobshunterApiException.class, () -> tool.waitForSearch("s1", 5, context));

    assertEquals("Authenticated MCP token is required to call wait_for_search.", ex.getMessage());
    assertEquals(ErrorCode.AUTH_FAILED, ex.getErrorCode());
    verifyNoInteractions(jobshunterClient);
  }

  @Test
  void relaysNewEventsLiveAndWaitsUntilDone() {
    SearchJobSnapshot first = new SearchJobSnapshot("s1", "IN_PROGRESS",
        List.of(new SearchStepEvent("Processing started")), null, null);
    SearchJobSnapshot second = new SearchJobSnapshot("s1", "IN_PROGRESS",
        List.of(new SearchStepEvent("Processing started"),
            new SearchStepEvent("Starting hunt orchestration")), null, null);
    SearchJobSnapshot done = new SearchJobSnapshot("s1", "DONE",
        List.of(new SearchStepEvent("Processing started"),
            new SearchStepEvent("Starting hunt orchestration"),
            new SearchStepEvent("GROK: completed")),
        new SearchJobsResponse(List.of()), null);
    when(jobshunterClient.getSearchSnapshot("s1", "delegated-token")).thenReturn(first, second, done);

    List<String> seen = new ArrayList<>();
    SearchWaitResult result = tool.pollUntilDoneOrTimeout(
        "s1", 5, "delegated-token", (progressIndex, message) -> seen.add(progressIndex + ":" + message));

    assertThat(result.status()).isEqualTo(SearchWaitResult.Status.DONE);
    assertThat(result.progressEvents()).containsExactly(
        "Processing started", "Starting hunt orchestration", "GROK: completed");
    assertThat(seen).containsExactly(
        "1:Processing started", "2:Starting hunt orchestration", "3:GROK: completed");
    verify(jobshunterClient, times(3)).getSearchSnapshot("s1", "delegated-token");
  }

  @Test
  void returnsDoneWhenSearchFinishes() {
    SearchJobSnapshot inProgress = new SearchJobSnapshot("s1", "IN_PROGRESS",
        List.of(new SearchStepEvent("Checked Acme Corp")), null, null);
    SearchJobSnapshot done = new SearchJobSnapshot("s1", "DONE",
        List.of(new SearchStepEvent("Checked Acme Corp")),
        new SearchJobsResponse(List.of(new SearchJobResult("https://x.test/1", "grok"))), null);
    when(jobshunterClient.getSearchSnapshot("s1", "delegated-token")).thenReturn(inProgress, done);

    SearchWaitResult result = tool.pollUntilDoneOrTimeout("s1", 5, "delegated-token", (progressIndex, message) -> {
    });

    assertThat(result.status()).isEqualTo(SearchWaitResult.Status.DONE);
    assertThat(result.result().jobsFound()).hasSize(1);
    assertThat(result.progressEvents()).containsExactly("Checked Acme Corp");
  }

  @Test
  void returnsStillRunningOncePollWindowElapsesWithoutCompletion() {
    SearchJobSnapshot stillGoing = new SearchJobSnapshot("s2", "IN_PROGRESS", List.of(), null, null);
    when(jobshunterClient.getSearchSnapshot("s2", "delegated-token")).thenReturn(stillGoing);

    SearchWaitResult result = tool.pollUntilDoneOrTimeout("s2", 1, "delegated-token", (progressIndex, message) -> {
    });

    assertThat(result.status()).isEqualTo(SearchWaitResult.Status.IN_PROGRESS);
    assertThat(result.searchId()).isEqualTo("s2");
    assertThat(result.progressEvents()).isEmpty();
  }

  @Test
  void includesSnapshotEventsOnInProgressTimeout() {
    SearchJobSnapshot stillGoing = new SearchJobSnapshot("s2", "IN_PROGRESS",
        List.of(new SearchStepEvent("Processing started"),
            new SearchStepEvent("Starting hunt orchestration")),
        null, null);
    when(jobshunterClient.getSearchSnapshot("s2", "delegated-token")).thenReturn(stillGoing);

    SearchWaitResult result = tool.pollUntilDoneOrTimeout("s2", 1, "delegated-token", (progressIndex, message) -> {
    });

    assertThat(result.status()).isEqualTo(SearchWaitResult.Status.IN_PROGRESS);
    assertThat(result.progressEvents()).containsExactly(
        "Processing started", "Starting hunt orchestration");
  }

  @Test
  void relaysFailureAsFailedStatusWithErrorMessage() {
    SearchJobSnapshot failed = new SearchJobSnapshot("s3", "FAILED", List.of(), null, "GROK request timed out");
    when(jobshunterClient.getSearchSnapshot("s3", "delegated-token")).thenReturn(failed);

    SearchWaitResult result = tool.pollUntilDoneOrTimeout("s3", 5, "delegated-token", (progressIndex, message) -> {
    });

    assertThat(result.status()).isEqualTo(SearchWaitResult.Status.FAILED);
    assertThat(result.errorMessage()).isEqualTo("GROK request timed out");
    assertThat(result.progressEvents()).isEmpty();
  }

  @Test
  void clampsWaitSecondsToTheSafeCeiling() {
    assertThat(tool.clampWaitSeconds(5)).isEqualTo(5);
    assertThat(tool.clampWaitSeconds(0)).isEqualTo(1);
    assertThat(tool.clampWaitSeconds(-10)).isEqualTo(1);
    assertThat(tool.clampWaitSeconds(10_000)).isEqualTo(240);
  }

  private void authenticateAsUser1() {
    Jwt jwt = new Jwt(
        "google-user-token",
        Instant.now(),
        Instant.now().plusSeconds(300),
        Map.of("alg", "none"),
        Map.of("sub", "user1", "scope", "profile email")
    );
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    when(delegatedTokenResolver.resolveDelegatedToken(jwt)).thenReturn("delegated-token");
  }
}
