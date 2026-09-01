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
import com.jobshunter.mcp.exception.JobshunterApiException;
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

    List<SearchConfiguration> request = List.of(
        new SearchConfiguration("GROK", "grok-4-1-fast-non-reasoning", false, true),
        new SearchConfiguration("SERP", "google_jobs", false, true)
    );

    SearchJobsResponse expected = new SearchJobsResponse(List.of(
        new SearchJobResult("https://example.com/job-1", "SERP")
    ));
    when(jobshunterClient.searchJobs(request, "google-user-token")).thenReturn(expected);

    SearchJobsResponse actual = jobSearchTool.searchJobs(request);

    assertEquals(expected, actual);
    verify(jobshunterClient).searchJobs(request, "google-user-token");
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
    assertEquals("Authenticated Google token is required to call search_jobs.", ex.getMessage());
  }
}
