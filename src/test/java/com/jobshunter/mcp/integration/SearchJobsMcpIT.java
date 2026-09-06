package com.jobshunter.mcp.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SearchJobsMcpIT {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static MockWebServer mockJobshunter;

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
    registry.add("jobshunter.search-jobs-path", () -> "/api/search_jobs");
    registry.add("jobshunter.csrf-path", () -> "/api/auth/csrf-token");
    registry.add("jobshunter.auth-token", () -> "integration-token");
    registry.add("jobshunter.device-id", () -> "integration-device");
    registry.add("spring.ai.mcp.server.streamable-http.mcp-endpoint", () -> "/mcp");
  }

  @Test
  void shouldExposeSearchJobsToolAndReturnJobshunterResponse() throws Exception {
    mockJobshunter.enqueue(new MockResponse()
        .setHeader("Content-Type", "application/json")
        .setHeader("Set-Cookie", "XSRF-TOKEN=csrf-it-token; Path=/; HttpOnly")
        .setBody("""
            {"token":"csrf-it-token","headerName":"X-XSRF-TOKEN","parameterName":"_csrf"}
            """));
    mockJobshunter.enqueue(new MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""
            {"jobsFound":[{"url":"https://example.com/job-1","source":"SERP"}]}
            """));

    RestClient mcpClient = RestClient.builder().baseUrl("http://localhost:" + port).build();

    String initializeResponse = postJsonRpc(mcpClient, "initialize", "1", Map.of(
        "protocolVersion", "2025-03-26",
        "capabilities", Map.of(),
        "clientInfo", Map.of("name", "SearchJobsMcpIT", "version", "1.0.0")
    ));
    assertTrue(initializeResponse.contains("\"result\""));

    String toolsListResponse = postJsonRpc(mcpClient, "tools/list", "2", Map.of());
    assertTrue(toolsListResponse.contains("\"search_jobs\""));
    assertTrue(toolsListResponse.contains("searchConfigurations"));

    String toolCallResponse = postJsonRpc(mcpClient, "tools/call", "3", Map.of(
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

    RecordedRequest csrfRequest = mockJobshunter.takeRequest(2, TimeUnit.SECONDS);
    RecordedRequest searchRequest = mockJobshunter.takeRequest(2, TimeUnit.SECONDS);

    assertEquals("/api/auth/csrf-token", csrfRequest.getPath());
    assertEquals("GET", csrfRequest.getMethod());
    assertEquals("/api/search_jobs", searchRequest.getPath());
    assertEquals("POST", searchRequest.getMethod());
    assertEquals("Bearer integration-token", searchRequest.getHeader("Authorization"));
    assertEquals("csrf-it-token", searchRequest.getHeader("X-XSRF-TOKEN"));
    assertTrue(searchRequest.getHeader("Cookie").contains("device_id=integration-device"));
    assertTrue(searchRequest.getHeader("Cookie").contains("XSRF-TOKEN=csrf-it-token"));
  }

  private String postJsonRpc(RestClient mcpClient, String method, String id, Map<String, Object> params) {
    Map<String, Object> payload = Map.of(
        "jsonrpc", "2.0",
        "id", id,
        "method", method,
        "params", params
    );

    return mcpClient.post()
        .uri("/mcp")
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.APPLICATION_JSON)
        .body(payload)
        .retrieve()
        .body(String.class);
  }
}
