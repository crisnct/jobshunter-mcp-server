package com.jobshunter.mcp.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.nimbusds.jwt.SignedJWT;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SearchJobsMcpInternalAsTest {

  private static MockWebServer mockJobshunter;
  private static final String MCP_ACCESS_TOKEN = "mcp-access-token";

  @MockitoBean("mcpJwtDecoder")
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
    registry.add("mcp.authorization-server.issuer", () -> "https://mcp.local");
    registry.add("mcp.authorization-server.mcp-audience", () -> "mcp-api");
    registry.add("mcp.authorization-server.jobshunter-audience", () -> "jobshunter-api");
    registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "https://mcp.local");
  }

  @Test
  void shouldForwardDelegatedTokenDistinctFromMcpBearer() throws Exception {
    Jwt mcpJwt = new Jwt(
        MCP_ACCESS_TOKEN,
        Instant.now(),
        Instant.now().plusSeconds(300),
        Map.of("alg", "none"),
        Map.of("sub", "123", "aud", List.of("mcp-api"), "email", "user@example.com", "scope", "profile email")
    );
    when(jwtDecoder.decode(MCP_ACCESS_TOKEN)).thenReturn(mcpJwt);

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

    ResponseEntity<String> initializeResponseEntity = postJsonRpcWithResponse(mcpClient, MCP_ACCESS_TOKEN, null, "initialize", "1", Map.of(
        "protocolVersion", "2025-03-26",
        "capabilities", Map.of(),
        "clientInfo", Map.of("name", "SearchJobsMcpInternalAsTest", "version", "1.0.0")
    ));
    String sessionId = initializeResponseEntity.getHeaders().getFirst("Mcp-Session-Id");

    String toolCallResponse = postJsonRpc(mcpClient, MCP_ACCESS_TOKEN, sessionId, "tools/call", "3", Map.of(
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
    String delegatedToken = searchRequest.getHeader(HttpHeaders.AUTHORIZATION).replace("Bearer ", "");
    assertNotEquals(MCP_ACCESS_TOKEN, delegatedToken);

    SignedJWT delegatedJwt = SignedJWT.parse(delegatedToken);
    assertEquals("https://mcp.local", delegatedJwt.getJWTClaimsSet().getIssuer());
    assertEquals(List.of("jobshunter-api"), delegatedJwt.getJWTClaimsSet().getAudience());
    assertEquals("jobshunter_delegated", delegatedJwt.getJWTClaimsSet().getStringClaim("token_use"));
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
