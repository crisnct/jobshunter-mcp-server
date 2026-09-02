package com.jobshunter.mcp.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.jobshunter.mcp.config.JobshunterProperties;
import com.jobshunter.mcp.dto.SearchConfiguration;
import com.jobshunter.mcp.dto.SearchJobsResponse;
import com.jobshunter.mcp.dto.UserInfoResponse;
import com.jobshunter.mcp.exception.JobshunterApiException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class JobshunterClientTest {

  private static final String BASE_URL = "https://localhost:8444";

  private JobshunterClient jobshunterClient;
  private MockRestServiceServer mockServer;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);

    mockServer = MockRestServiceServer.bindTo(builder).build();
    RestClient restClient = builder.build();

    JobshunterProperties properties = new JobshunterProperties(
        BASE_URL,
        "/api/internal/search_jobs",
        "/api/internal/me",
        Duration.ofSeconds(5),
        Duration.ofMinutes(5),
        new JobshunterProperties.Ssl(null, null, "PKCS12")
    );

    jobshunterClient = new JobshunterClient(restClient, properties);
  }

  @Test
  void shouldPostConfigurationsAndMapSearchJobsResponse() {
    String requestJson = """
        [
          {
            "provider":"GROK",
            "model":"grok-4-1-fast-non-reasoning",
            "searchCompanies":false,
            "searchWithUserPrompts":true
          }
        ]
        """;

    String responseJson = """
        {
          "jobsFound":[
            {"url":"https://example.com/job-1","source":"SERP"}
          ]
        }
        """;

    mockServer.expect(requestTo(BASE_URL + "/api/internal/search_jobs"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer user-id-token"))
        .andExpect(content().json(requestJson, true))
        .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

    SearchJobsResponse response = jobshunterClient.searchJobs(
        List.of(new SearchConfiguration("GROK", "grok-4-1-fast-non-reasoning", false, true)),
        "user-id-token"
    );

    assertEquals(1, response.jobsFound().size());
    assertEquals("https://example.com/job-1", response.jobsFound().getFirst().url());
    mockServer.verify();
  }

  @Test
  void shouldMap401ToAuthenticationFailedMessage() {
    mockServer.expect(requestTo(BASE_URL + "/api/internal/search_jobs"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

    JobshunterApiException ex = assertThrows(
        JobshunterApiException.class,
        () -> jobshunterClient.searchJobs(
            List.of(new SearchConfiguration("GROK", "grok-4-1-fast-non-reasoning", false, true)),
            "user-id-token"
        )
    );

    assertEquals("Jobshunter authentication failed.", ex.getMessage());
    mockServer.verify();
  }

  @Test
  void shouldFailFastWhenUserTokenIsMissing() {
    JobshunterProperties properties = new JobshunterProperties(
        BASE_URL,
        "/api/internal/search_jobs",
        "/api/internal/me",
        Duration.ofSeconds(5),
        Duration.ofMinutes(5),
        new JobshunterProperties.Ssl(null, null, "PKCS12")
    );

    RestClient restClient = RestClient.builder().baseUrl(BASE_URL).build();
    JobshunterClient client = new JobshunterClient(restClient, properties);

    JobshunterApiException ex = assertThrows(
        JobshunterApiException.class,
        () -> client.searchJobs(
            List.of(new SearchConfiguration("GROK", "grok-4-1-fast-non-reasoning", false, true)),
            ""
        )
    );

    assertEquals("Authenticated user token is missing.", ex.getMessage());
  }

  @Test
  void shouldGetUserInfoFromInternalMeEndpoint() {
    String responseJson = """
        {
          "username":"user@example.com",
          "email":"user@example.com",
          "phoneNumber":"0700000000",
          "notifyWhatsapp":false,
          "notifyEmail":true,
          "emailVerified":true,
          "verificationToken":null,
          "cvFilename":"cv.pdf",
          "notifiedAt":null,
          "prompts":[],
          "createdAt":"2026-09-01T00:00:00Z",
          "roles":["USER"],
          "city":"Cluj",
          "country":"RO",
          "jobDomain":"Software",
          "jobRoles":["Java Developer"],
          "jobTypes":["REMOTE"],
          "relocation":"NO",
          "contractTypes":["FULL_TIME"]
        }
        """;

    mockServer.expect(requestTo(BASE_URL + "/api/internal/me"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer user-id-token"))
        .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

    UserInfoResponse response = jobshunterClient.getUserInfo("user-id-token");

    assertEquals("user@example.com", response.username());
    assertEquals("user@example.com", response.email());
    assertEquals("REMOTE", response.jobTypes().getFirst());
    mockServer.verify();
  }
}
