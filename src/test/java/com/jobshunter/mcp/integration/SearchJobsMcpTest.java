package com.jobshunter.mcp.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SearchJobsMcpTest {

  private static MockWebServer mockJobshunter;
  private static final String GOOGLE_TOKEN = "google-user-token";

  @MockitoBean
  private JwtDecoder jwtDecoder;

  @LocalServerPort
  private int port;

  @BeforeAll
  static void startMockServer() throws IOException {
    mockJobshunter = new MockWebServer();
    mockJobshunter.start();
  }

  @AfterAll
  static void stopMockServer() throws IOException {
    mockJobshunter.shutdown();
  }

  @DynamicPropertySource
  static void overrideProperties(DynamicPropertyRegistry registry) {
    registry.add("jobshunter.base-url", () -> mockJobshunter.url("/").toString().replaceAll("/$", ""));
    registry.add("jobshunter.search-jobs-path", () -> "/api/internal/search_jobs");
    registry.add("spring.ai.mcp.server.streamable-http.mcp-endpoint", () -> "/mcp");
    registry.add("mcp.security.audience", () -> "mcp-client-id");
    registry.add("mcp.security.required-scope", () -> "");
  }

  @Test
  void shouldExposeSearchJobsToolAndReturnJobshunterResponse() throws Exception {
    Jwt jwt = new Jwt(
        GOOGLE_TOKEN,
        Instant.now(),
        Instant.now().plusSeconds(300),
        Map.of("alg", "none"),
        Map.of("sub", "123", "aud", List.of("mcp-client-id"), "email", "user@example.com")
    );
    when(jwtDecoder.decode(GOOGLE_TOKEN)).thenReturn(jwt);

    RestClient mcpClient = RestClient.builder().baseUrl("http://localhost:" + port).build();
    assertThrows(
        HttpClientErrorException.Unauthorized.class,
        () -> postJsonRpcWithResponse(mcpClient, null, null, "initialize", "0", Map.of())
    );

    mockJobshunter.enqueue(new MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""
            {"jobsFound":[{"url":"https://example.com/job-1","source":"SERP"}]}
            """));

    ResponseEntity<String> initializeResponseEntity = postJsonRpcWithResponse(mcpClient, GOOGLE_TOKEN, null, "initialize", "1", Map.of(
        "protocolVersion", "2025-03-26",
        "capabilities", Map.of(),
        "clientInfo", Map.of("name", "SearchJobsMcpIT", "version", "1.0.0")
    ));
    String initializeResponse = initializeResponseEntity.getBody();
    assertTrue(initializeResponse.contains("\"result\""));
    String sessionId = initializeResponseEntity.getHeaders().getFirst("Mcp-Session-Id");

    String toolsListResponse = postJsonRpc(mcpClient, GOOGLE_TOKEN, sessionId, "tools/list", "2", Map.of());
    assertTrue(toolsListResponse.contains("\"search_jobs\""));
    assertTrue(toolsListResponse.contains("\"get_user_info\""));
    assertTrue(toolsListResponse.contains("searchConfigurations"));

    String toolCallResponse = postJsonRpc(mcpClient, GOOGLE_TOKEN, sessionId, "tools/call", "3", Map.of(
        "name", "search_jobs",
        "arguments", Map.of(
            "searchConfigurations", List.of(
                Map.of(
                    "provider", "GROK",
                    "model", "grok-4-1-fast-non-reasoning",
                    "searchCompanies", false,
                    "searchWithUserPrompts", true
                )
            )
        )
    ));
    assertTrue(toolCallResponse.contains("https://example.com/job-1"));

    RecordedRequest searchRequest = mockJobshunter.takeRequest(2, TimeUnit.SECONDS);
    assertEquals("/api/internal/search_jobs", searchRequest.getPath());
    assertEquals("POST", searchRequest.getMethod());
    assertEquals("Bearer " + GOOGLE_TOKEN, searchRequest.getHeader(HttpHeaders.AUTHORIZATION));
  }

  private String postJsonRpc(
      RestClient mcpClient,
      String bearerToken,
      String sessionId,
      String method,
      String id,
      Map<String, Object> params
  ) {
    return postJsonRpcWithResponse(mcpClient, bearerToken, sessionId, method, id, params).getBody();
  }

  private ResponseEntity<String> postJsonRpcWithResponse(
      RestClient mcpClient,
      String bearerToken,
      String sessionId,
      String method,
      String id,
      Map<String, Object> params
  ) {
    Map<String, Object> payload = Map.of(
        "jsonrpc", "2.0",
        "id", id,
        "method", method,
        "params", params
    );

    RestClient.RequestBodySpec request = mcpClient.post()
        .uri("/mcp")
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON);

    if (bearerToken != null) {
      request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);
    }

    if (sessionId != null) {
      request.header("Mcp-Session-Id", sessionId);
    }

    return request.body(payload)
        .retrieve()
        .toEntity(String.class);
  }
}
