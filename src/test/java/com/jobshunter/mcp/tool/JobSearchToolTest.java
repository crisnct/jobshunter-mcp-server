package com.jobshunter.mcp.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jobshunter.mcp.client.JobshunterClient;
import com.jobshunter.mcp.dto.SearchConfiguration;
import com.jobshunter.mcp.dto.SearchJobResult;
import com.jobshunter.mcp.dto.SearchJobsResponse;
import com.jobshunter.mcp.dto.UserInfoResponse;
import com.jobshunter.mcp.exception.JobshunterApiException;
import com.jobshunter.mcp.security.DelegatedTokenResolver;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class JobSearchToolTest {

  @Mock
  private JobshunterClient jobshunterClient;

  @Mock
  private DelegatedTokenResolver delegatedTokenResolver;

  @InjectMocks
  private JobSearchTool jobSearchTool;

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
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
    when(jobshunterClient.searchJobs(request, "delegated-token")).thenReturn(expected);

    SearchJobsResponse actual = jobSearchTool.searchJobs(request);

    assertEquals(expected, actual);
    verify(jobshunterClient).searchJobs(request, "delegated-token");
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
    verifyNoInteractions(jobshunterClient);
  }

  @Test
  void shouldRejectRequestWhenGoogleTokenIsMissingFromContext() {
    List<SearchConfiguration> request = List.of(
        new SearchConfiguration("GROK", "grok-4-1-fast-non-reasoning", false, true)
    );

    JobshunterApiException ex = assertThrows(JobshunterApiException.class, () -> jobSearchTool.searchJobs(request));
    assertEquals("Authenticated MCP token is required to call search_jobs.", ex.getMessage());
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

    UserInfoResponse actual = jobSearchTool.getUserInfo();

    assertEquals(expected, actual);
    verify(jobshunterClient).getUserInfo("delegated-token");
  }

  @Test
  void shouldRejectUserInfoRequestWhenGoogleTokenIsMissingFromContext() {
    JobshunterApiException ex = assertThrows(JobshunterApiException.class, () -> jobSearchTool.getUserInfo());
    assertEquals("Authenticated MCP token is required to call get_user_info.", ex.getMessage());
  }
}
