package com.jobshunter.mcp.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpSecurityFilterChainIT {

  private static MockWebServer mockGoogleTokenServer;

  @MockitoBean
  private GoogleIdTokenValidator googleIdTokenValidator;

  @LocalServerPort
  private int port;

  @BeforeAll
  static void startMockGoogleTokenServer() throws IOException {
    mockGoogleTokenServer = new MockWebServer();
    mockGoogleTokenServer.start();
  }

  @AfterAll
  static void stopMockGoogleTokenServer() throws IOException {
    mockGoogleTokenServer.shutdown();
  }

  @DynamicPropertySource
  static void overrideProperties(DynamicPropertyRegistry registry) {
    registry.add("mcp.authorization-server.issuer", () -> "https://mcp.local");
    registry.add("mcp.authorization-server.mcp-audience", () -> "mcp-api");
    registry.add("mcp.authorization-server.jobshunter-audience", () -> "jobshunter-api");
    registry.add("mcp.oauth.google-token-endpoint", () -> mockGoogleTokenServer.url("/oauth/token").toString());
    registry.add("mcp.oauth.google-authorization-endpoint", () -> "https://accounts.google.com/o/oauth2/v2/auth");
    registry.add("mcp.oauth.jwks-uri", () -> "https://www.googleapis.com/oauth2/v3/certs");
    registry.add("mcp.oauth.client-id", () -> "test-client-id");
    registry.add("mcp.oauth.client-secret", () -> "test-client-secret");
    registry.add("mcp.oauth.scope", () -> "openid email profile");
    registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "https://mcp.local");
  }

  @Test
  void shouldAllowPublicDiscoveryEndpointsWithoutAuthentication() {
    assertEquals(HttpStatus.OK, get("/.well-known/oauth-authorization-server").getStatusCode());
    assertEquals(HttpStatus.OK, get("/.well-known/oauth-protected-resource").getStatusCode());
    assertEquals(HttpStatus.OK, get("/.well-known/jwks.json").getStatusCode());
  }

  @Test
  void shouldAllowAuthorizeEndpointWithoutAuthentication() {
    ResponseEntity<Void> response = client().get()
        .uri("/authorize?redirect_uri=http://localhost/callback&state=test-state")
        .retrieve()
        .toBodilessEntity();

    assertEquals(HttpStatus.FOUND, response.getStatusCode());
    assertTrue(response.getHeaders().getFirst(HttpHeaders.LOCATION) != null);
  }

  @Test
  void shouldAllowTokenEndpointWithoutAuthentication() {
    mockGoogleTokenServer.enqueue(new MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""
            {"access_token":"google-access-token","id_token":"google-id-token","expires_in":3599}
            """));
    when(googleIdTokenValidator.validateAndDecode("google-id-token")).thenReturn(
        new Jwt(
            "google-id-token",
            Instant.now(),
            Instant.now().plusSeconds(300),
            Map.of("alg", "RS256"),
            Map.of("sub", "user-123", "email", "user@example.com", "scope", "openid email profile")
        )
    );

    LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "authorization_code");
    form.add("code", "auth-code");
    form.add("redirect_uri", "http://localhost/callback");
    form.add("code_verifier", "verifier");

    ResponseEntity<String> response = client().post()
        .uri("/token")
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .body(form)
        .retrieve()
        .toEntity(String.class);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertTrue(response.getBody().contains("\"access_token\""));
  }

  @Test
  void shouldRequireAuthenticationForMcpEndpoint() {
    assertEquals(HttpStatus.UNAUTHORIZED, postMcp(null).getStatusCode());
    assertEquals(HttpStatus.UNAUTHORIZED, postMcp("not-a-valid-jwt").getStatusCode());
  }

  @Test
  void shouldDenyAnyNonAllowlistedEndpoint() {
    assertEquals(HttpStatus.FORBIDDEN, statusForForbiddenPath("/"));
    assertEquals(HttpStatus.FORBIDDEN, statusForForbiddenPath("/random-path"));
    assertEquals(HttpStatus.FORBIDDEN, statusForForbiddenPath("/actuator/health"));
  }

  private ResponseEntity<Void> get(String path) {
    return client().get().uri(path).retrieve().toBodilessEntity();
  }

  private ResponseEntity<String> postMcp(String bearerToken) {
    Map<String, Object> payload = Map.of(
        "jsonrpc", "2.0",
        "id", "1",
        "method", "initialize",
        "params", Map.of()
    );

    RestClient.RequestBodySpec request = client().post()
        .uri("/mcp")
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON);
    if (bearerToken != null) {
      request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);
    }

    try {
      return request.body(payload).retrieve().toEntity(String.class);
    } catch (HttpClientErrorException ex) {
      return ResponseEntity.status(ex.getStatusCode()).body(ex.getResponseBodyAsString());
    }
  }

  private HttpStatus statusForForbiddenPath(String path) {
    try {
      client().get().uri(path).retrieve().toBodilessEntity();
      return HttpStatus.OK;
    } catch (HttpClientErrorException ex) {
      return HttpStatus.valueOf(ex.getStatusCode().value());
    }
  }

  private RestClient client() {
    return RestClient.builder().baseUrl("http://localhost:" + port).build();
  }
}
