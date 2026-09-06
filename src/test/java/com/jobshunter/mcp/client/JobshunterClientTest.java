package com.jobshunter.mcp.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
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

    jobshunterClient = new JobshunterClient(restClient, propertiesWithBaseUrl(BASE_URL));
  }

  private static JobshunterProperties propertiesWithBaseUrl(String baseUrl) {
    return new JobshunterProperties(
        baseUrl,
        "/api/internal/search_jobs",
        "/api/internal/me",
        Duration.ofSeconds(5),
        Duration.ofMinutes(5),
        new JobshunterProperties.Ssl(null, null, "PKCS12")
    );
  }

  /** A request factory that fails every request with a fixed IOException, simulating a
   * connection-level failure (timeout, closed channel, refused connection) that
   * {@link MockRestServiceServer} cannot express since it only stubs HTTP responses. */
  private record ThrowingClientHttpRequestFactory(IOException failure) implements ClientHttpRequestFactory {
    @Override
    public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) throws IOException {
      throw failure;
    }
  }

  private static JobshunterClient clientThatFailsWith(IOException failure, String baseUrl) {
    RestClient restClient = RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(new ThrowingClientHttpRequestFactory(failure))
        .build();
    return new JobshunterClient(restClient, propertiesWithBaseUrl(baseUrl));
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

    assertThat(response.jobsFound()).hasSize(1);
    assertThat(response.jobsFound().getFirst().url()).isEqualTo("https://example.com/job-1");
    mockServer.verify();
  }

  @ParameterizedTest(name = "status {0} maps to \"{1}\"")
  @CsvSource({
      "400, Invalid search configuration.",
      "401, Jobshunter authentication failed.",
      "403, Jobshunter authorization failed.",
      "404, Jobshunter endpoint unavailable.",
      "409, Jobshunter request failed with status 409.",
      "500, Jobshunter returned an unexpected error.",
      "503, Jobshunter returned an unexpected error."
  })
  void shouldMapErrorStatusCodesToExpectedMessages(int statusCode, String expectedMessage) {
    mockServer.expect(requestTo(BASE_URL + "/api/internal/search_jobs"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withStatus(HttpStatusCode.valueOf(statusCode)));

    assertThatThrownBy(() -> jobshunterClient.searchJobs(
        List.of(new SearchConfiguration("GROK", "grok-4-1-fast-non-reasoning", false, true)),
        "user-id-token"
    ))
        .isInstanceOf(JobshunterApiException.class)
        .hasMessage(expectedMessage);

    mockServer.verify();
  }

  @Test
  void shouldMapTimeoutToTimedOutMessage() {
    JobshunterClient client = clientThatFailsWith(new SocketTimeoutException("Read timed out"), BASE_URL);

    assertThatThrownBy(() -> client.searchJobs(
        List.of(new SearchConfiguration("GROK", "grok-4-1-fast-non-reasoning", false, true)),
        "user-id-token"
    ))
        .isInstanceOf(JobshunterApiException.class)
        .hasMessage("Job search timed out.");
  }

  @Test
  void shouldMapClosedChannelOnHttpsPortToProtocolMismatchMessage() {
    String httpBaseUrl = "http://localhost:8443";
    JobshunterClient client = clientThatFailsWith(new ClosedChannelException(), httpBaseUrl);

    assertThatThrownBy(() -> client.searchJobs(
        List.of(new SearchConfiguration("GROK", "grok-4-1-fast-non-reasoning", false, true)),
        "user-id-token"
    ))
        .isInstanceOf(JobshunterApiException.class)
        .hasMessageContaining("Jobshunter endpoint closed connection")
        .hasMessageContaining("JOBSHUNTER_BASE_URL protocol");
  }

  @Test
  void shouldMapOtherConnectionFailureToUnreachableMessage() {
    JobshunterClient client = clientThatFailsWith(new ConnectException("Connection refused"), BASE_URL);

    assertThatThrownBy(() -> client.searchJobs(
        List.of(new SearchConfiguration("GROK", "grok-4-1-fast-non-reasoning", false, true)),
        "user-id-token"
    ))
        .isInstanceOf(JobshunterApiException.class)
        .hasMessageStartingWith("Jobshunter endpoint is not reachable.");
  }

  @Test
  void shouldThrowWhenSearchJobsResponseBodyIsEmpty() {
    mockServer.expect(requestTo(BASE_URL + "/api/internal/search_jobs"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess());

    assertThatThrownBy(() -> jobshunterClient.searchJobs(
        List.of(new SearchConfiguration("GROK", "grok-4-1-fast-non-reasoning", false, true)),
        "user-id-token"
    ))
        .isInstanceOf(JobshunterApiException.class)
        .hasMessage("Jobshunter returned an empty response.");

    mockServer.verify();
  }

  @Test
  void shouldThrowWhenUserInfoResponseBodyIsEmpty() {
    mockServer.expect(requestTo(BASE_URL + "/api/internal/me"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess());

    assertThatThrownBy(() -> jobshunterClient.getUserInfo("user-id-token"))
        .isInstanceOf(JobshunterApiException.class)
        .hasMessage("Jobshunter returned an empty user response.");

    mockServer.verify();
  }

  @Test
  void shouldFailFastWhenUserTokenIsMissing() {
    RestClient restClient = RestClient.builder().baseUrl(BASE_URL).build();
    JobshunterClient client = new JobshunterClient(restClient, propertiesWithBaseUrl(BASE_URL));

    assertThatThrownBy(() -> client.searchJobs(
        List.of(new SearchConfiguration("GROK", "grok-4-1-fast-non-reasoning", false, true)),
        ""
    ))
        .isInstanceOf(JobshunterApiException.class)
        .hasMessage("Authenticated user token is missing.");
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

    assertThat(response.username()).isEqualTo("user@example.com");
    assertThat(response.email()).isEqualTo("user@example.com");
    assertThat(response.jobTypes()).first().isEqualTo("REMOTE");
    mockServer.verify();
  }
}
