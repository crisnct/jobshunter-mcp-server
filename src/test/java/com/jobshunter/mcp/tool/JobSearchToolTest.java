package com.jobshunter.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jobshunter.mcp.client.JobshunterClient;
import com.jobshunter.mcp.dto.SearchConfiguration;
import com.jobshunter.mcp.dto.SearchJobResult;
import com.jobshunter.mcp.dto.SearchJobsResponse;
import com.jobshunter.mcp.dto.UserInfoResponse;
import com.jobshunter.mcp.exception.ErrorCode;
import com.jobshunter.mcp.exception.JobshunterApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobshunter.mcp.logging.RequestContext;
import com.jobshunter.mcp.security.DelegatedTokenResolver;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class JobSearchToolTest {

  @Mock
  private JobshunterClient jobshunterClient;

  @Mock
  private DelegatedTokenResolver delegatedTokenResolver;

  @Mock
  private McpSyncServerExchange mcpSyncServerExchange;

  @Spy
  private ObjectMapper objectMapper = new ObjectMapper();

  @InjectMocks
  private JobSearchTool jobSearchTool;

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
    MDC.clear();
  }

  @Test
  void shouldDelegateToClientAndReturnResponse() {
    Jwt jwt = new Jwt(
        "google-user-token",
        Instant.now(),
        Instant.now().plusSeconds(300),
        Map.of("alg", "none"),
        Map.of("sub", "user1", "scope", "profile email")
    );
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    when(delegatedTokenResolver.resolveDelegatedToken(jwt)).thenReturn("delegated-token");

    List<SearchConfiguration> request = List.of(
        new SearchConfiguration("GROK", "grok-4-1-fast-non-reasoning", false, true),
        new SearchConfiguration("SERP", "google_jobs", false, true)
    );

    SearchJobsResponse expected = new SearchJobsResponse(List.of(
        new SearchJobResult("https://example.com/job-1", "SERP")
    ));
    when(jobshunterClient.searchJobs(request, "delegated-token")).thenAnswer(invocation -> {
      assertThat(MDC.get(RequestContext.REQUEST_ID_MDC_KEY)).isNotBlank();
      return expected;
    });

    SearchJobsResponse actual = jobSearchTool.searchJobs(request);

    assertEquals(expected, actual);
    verify(jobshunterClient).searchJobs(request, "delegated-token");
    assertThat(MDC.get(RequestContext.REQUEST_ID_MDC_KEY)).isNull();
  }

  @Test
  void shouldRejectInvalidSearchConfiguration() {
    List<SearchConfiguration> request = List.of(
        new SearchConfiguration("GROK", "grok-4-1-fast-non-reasoning", false, false)
    );

    JobshunterApiException ex = assertThrows(JobshunterApiException.class, () -> jobSearchTool.searchJobs(request));
    assertEquals(
        "Invalid search configuration: at least one of searchCompanies or searchWithUserPrompts must be true.",
        ex.getMessage()
    );
    assertEquals(ErrorCode.VALIDATION, ex.getErrorCode());
    verifyNoInteractions(jobshunterClient);
  }

  @Test
  void shouldRejectRequestWhenGoogleTokenIsMissingFromContext() {
    List<SearchConfiguration> request = List.of(
        new SearchConfiguration("GROK", "grok-4-1-fast-non-reasoning", false, true)
    );

    JobshunterApiException ex = assertThrows(JobshunterApiException.class, () -> jobSearchTool.searchJobs(request));
    assertEquals("Authenticated MCP token is required to call search_jobs.", ex.getMessage());
    assertEquals(ErrorCode.AUTH_FAILED, ex.getErrorCode());
  }

  @Test
  void shouldReturnUserInfoFromDelegatedToken() {
    Jwt jwt = new Jwt(
        "google-user-token",
        Instant.now(),
        Instant.now().plusSeconds(300),
        Map.of("alg", "none"),
        Map.of("sub", "user1", "scope", "profile email")
    );
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    when(delegatedTokenResolver.resolveDelegatedToken(jwt)).thenReturn("delegated-token");
    UserInfoResponse expected = new UserInfoResponse(
        "user@example.com",
        "user@example.com",
        "0700000000",
        false,
        true,
        true,
        null,
        "cv.pdf",
        null,
        List.of(),
        "2026-09-01T00:00:00Z",
        List.of("USER"),
        "Cluj",
        "RO",
        "Software",
        List.of("Java Developer"),
        List.of("REMOTE"),
        "NO",
        List.of("FULL_TIME")
    );
    when(jobshunterClient.getUserInfo("delegated-token")).thenReturn(expected);

    Object actual = jobSearchTool.getUserInfo(null, new ToolContext(Map.of()));

    assertEquals(expected, actual);
    verify(jobshunterClient).getUserInfo("delegated-token");
  }

  @Test
  void shouldReturnRawUserInfoWhenOutputFormatIsBlank() {
    authenticateAsUser1();
    UserInfoResponse expected = sampleUserInfo();
    when(jobshunterClient.getUserInfo("delegated-token")).thenReturn(expected);

    Object actual = jobSearchTool.getUserInfo("", new ToolContext(Map.of()));

    assertEquals(expected, actual);
  }

  @Test
  void shouldRejectUserInfoRequestWhenGoogleTokenIsMissingFromContext() {
    JobshunterApiException ex = assertThrows(
        JobshunterApiException.class, () -> jobSearchTool.getUserInfo(null, new ToolContext(Map.of())));
    assertEquals("Authenticated MCP token is required to call get_user_info.", ex.getMessage());
    assertEquals(ErrorCode.AUTH_FAILED, ex.getErrorCode());
  }

  @Test
  void shouldWrapUnexpectedExceptionsAsUnknownAndClearMdc() {
    Jwt jwt = new Jwt(
        "google-user-token",
        Instant.now(),
        Instant.now().plusSeconds(300),
        Map.of("alg", "none"),
        Map.of("sub", "user1", "scope", "profile email")
    );
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    when(delegatedTokenResolver.resolveDelegatedToken(jwt)).thenReturn("delegated-token");
    when(jobshunterClient.getUserInfo("delegated-token")).thenThrow(new IllegalStateException("boom"));

    JobshunterApiException ex = assertThrows(
        JobshunterApiException.class, () -> jobSearchTool.getUserInfo(null, new ToolContext(Map.of())));
    assertEquals("Jobshunter returned an unexpected error.", ex.getMessage());
    assertEquals(ErrorCode.UNKNOWN, ex.getErrorCode());
    assertThat(MDC.get(RequestContext.REQUEST_ID_MDC_KEY)).isNull();
  }

  @Test
  void shouldReturnSampledTableWhenClientSupportsSampling() {
    authenticateAsUser1();
    when(jobshunterClient.getUserInfo("delegated-token")).thenReturn(sampleUserInfo());
    when(mcpSyncServerExchange.getClientCapabilities())
        .thenReturn(McpSchema.ClientCapabilities.builder().sampling().build());
    when(mcpSyncServerExchange.createMessage(any())).thenReturn(McpSchema.CreateMessageResult.builder()
        .role(McpSchema.Role.ASSISTANT)
        .content(new McpSchema.TextContent("| Field | Value |\n|---|---|\n| Username | Cristian |"))
        .model("test-model")
        .build());

    Object actual = jobSearchTool.getUserInfo("table", toolContextWithExchange());

    assertEquals("| Field | Value |\n|---|---|\n| Username | Cristian |", actual);
    verify(mcpSyncServerExchange).createMessage(any());
  }

  @Test
  void shouldTreatOutputFormatCaseInsensitively() {
    authenticateAsUser1();
    when(jobshunterClient.getUserInfo("delegated-token")).thenReturn(sampleUserInfo());
    when(mcpSyncServerExchange.getClientCapabilities())
        .thenReturn(McpSchema.ClientCapabilities.builder().build());

    Object actual = jobSearchTool.getUserInfo("TABLE", toolContextWithExchange());

    assertThat(actual).asString().contains("Cristian");
  }

  @Test
  void shouldFallBackToLocalTableWhenClientHasNoSamplingCapability() {
    authenticateAsUser1();
    when(jobshunterClient.getUserInfo("delegated-token")).thenReturn(sampleUserInfo());
    when(mcpSyncServerExchange.getClientCapabilities())
        .thenReturn(McpSchema.ClientCapabilities.builder().build());

    Object actual = jobSearchTool.getUserInfo("table", toolContextWithExchange());

    assertThat(actual).asString().contains("Cristian").contains("Timișoara").contains("Java Developer");
    verify(mcpSyncServerExchange, never()).createMessage(any());
  }

  @Test
  void shouldFallBackToLocalTableWhenToolContextHasNoExchange() {
    authenticateAsUser1();
    when(jobshunterClient.getUserInfo("delegated-token")).thenReturn(sampleUserInfo());

    Object actual = jobSearchTool.getUserInfo("table", new ToolContext(Map.of()));

    assertThat(actual).asString().contains("Cristian").contains("Timișoara");
  }

  @Test
  void shouldFallBackToLocalTableWhenSamplingThrows() {
    authenticateAsUser1();
    when(jobshunterClient.getUserInfo("delegated-token")).thenReturn(sampleUserInfo());
    when(mcpSyncServerExchange.getClientCapabilities())
        .thenReturn(McpSchema.ClientCapabilities.builder().sampling().build());
    when(mcpSyncServerExchange.createMessage(any())).thenThrow(new RuntimeException("client timed out"));

    Object actual = jobSearchTool.getUserInfo("table", toolContextWithExchange());

    assertThat(actual).asString().contains("Cristian").contains("Timișoara").contains("Java Developer");
  }

  @Test
  void shouldNeverRenderVerificationTokenInLocalTableFallback() {
    authenticateAsUser1();
    when(jobshunterClient.getUserInfo("delegated-token")).thenReturn(new UserInfoResponse(
        "Cristian", "user@example.com", "0700000000", false, true, true,
        "super-secret-verification-token", "cv.pdf", null, List.of(), "2026-09-01T00:00:00Z",
        List.of("USER"), "Timișoara", "RO", "Software", List.of("Java Developer"), List.of("REMOTE"),
        "NO", List.of("FULL_TIME")));

    Object actual = jobSearchTool.getUserInfo("table", new ToolContext(Map.of()));

    assertThat(actual).asString().doesNotContain("super-secret-verification-token");
  }

  @Test
  void shouldRejectTableUserInfoRequestWhenGoogleTokenIsMissingFromContext() {
    JobshunterApiException ex = assertThrows(
        JobshunterApiException.class, () -> jobSearchTool.getUserInfo("table", toolContextWithExchange()));
    assertEquals("Authenticated MCP token is required to call get_user_info.", ex.getMessage());
    assertEquals(ErrorCode.AUTH_FAILED, ex.getErrorCode());
    verifyNoInteractions(jobshunterClient);
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

  private ToolContext toolContextWithExchange() {
    return new ToolContext(Map.of(McpToolUtils.TOOL_CONTEXT_MCP_EXCHANGE_KEY, mcpSyncServerExchange));
  }

  private UserInfoResponse sampleUserInfo() {
    return new UserInfoResponse(
        "Cristian",
        "user@example.com",
        "0700000000",
        false,
        true,
        true,
        null,
        "cv.pdf",
        null,
        List.of(),
        "2026-09-01T00:00:00Z",
        List.of("USER"),
        "Timișoara",
        "RO",
        "Software",
        List.of("Java Developer"),
        List.of("REMOTE"),
        "NO",
        List.of("FULL_TIME")
    );
  }
}
