package com.jobshunter.mcp.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpOAuthControllerInternalAsTest {
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
    registry.add("mcp.authorization-server.key-id", () -> "test-mcp-key-id");
    registry.add("mcp.oauth.google-token-endpoint", () -> mockGoogleTokenServer.url("/oauth/token").toString());
    registry.add("mcp.oauth.google-authorization-endpoint", () -> "https://accounts.google.com/o/oauth2/v2/auth");
    registry.add("mcp.oauth.jwks-uri", () -> "https://www.googleapis.com/oauth2/v3/certs");
    registry.add("mcp.oauth.client-id", () -> "test-client-id");
    registry.add("mcp.oauth.client-secret", () -> "test-client-secret");
    registry.add("mcp.oauth.scope", () -> "openid email profile");
    registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "https://mcp.local");
  }

  @Test
  void shouldIssueMcpAccessTokenInInternalAsMode() throws Exception {
    mockGoogleTokenServer.enqueue(new MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""
            {"access_token":"google-access-token","id_token":"google-id-token","refresh_token":"r1","expires_in":3599}
            """));
    Jwt googleIdentityJwt = new Jwt(
        "google-id-token",
        Instant.now(),
        Instant.now().plusSeconds(300),
        Map.of("alg", "RS256"),
        Map.of("sub", "user-123", "email", "user@example.com", "scope", "openid email profile")
    );
    when(googleIdTokenValidator.validateAndDecode("google-id-token")).thenReturn(googleIdentityJwt);

    ResponseEntity<String> response = postToken(defaultTokenRequest());
    String responseBody = response.getBody();

    assertTrue(responseBody.contains("\"access_token\""));
    assertTrue(responseBody.contains("\"id_token\":\"google-id-token\""));
    assertTrue(responseBody.contains("\"refresh_token\":\"r1\""));
    assertTrue(responseBody.contains("\"token_type\":\"Bearer\""));

    String issuedAccessToken = extractTokenValue(responseBody, "\"access_token\":\"");
    assertNotEquals("google-id-token", issuedAccessToken);
    SignedJWT parsed = SignedJWT.parse(issuedAccessToken);
    assertEquals("test-mcp-key-id", parsed.getHeader().getKeyID());
    assertEquals("https://mcp.local", parsed.getJWTClaimsSet().getIssuer());
    assertEquals(List.of("mcp-api"), parsed.getJWTClaimsSet().getAudience());
    assertEquals("mcp_access", parsed.getJWTClaimsSet().getStringClaim("token_use"));
  }

  @Test
  void shouldExposeLocalJwksInAuthorizationServerMetadata() {
    RestClient restClient = RestClient.builder().baseUrl("http://localhost:" + port).build();
    String metadata = restClient.get()
        .uri("/.well-known/oauth-authorization-server")
        .retrieve()
        .body(String.class);

    assertTrue(metadata.contains("\"issuer\":\"https://mcp.local\""));
    assertTrue(metadata.contains("\"jwks_uri\":\"https://mcp.local/.well-known/jwks.json\""));
  }

  @Test
  void shouldRejectUnsupportedGrantType() {
    LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "refresh_token");
    form.add("code", "auth-code");
    form.add("redirect_uri", "http://localhost/callback");
    form.add("code_verifier", "verifier");

    ResponseEntity<String> response = postToken(form);
    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertTrue(response.getBody().contains("\"error\":\"unsupported_grant_type\""));
  }

  @Test
  void shouldRejectRequestWhenRequiredParameterIsMissing() {
    LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "authorization_code");
    form.add("code", "auth-code");
    form.add("redirect_uri", "http://localhost/callback");

    ResponseEntity<String> response = postToken(form);
    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertTrue(response.getBody().contains("\"error\":\"invalid_request\""));
    assertTrue(response.getBody().contains("code_verifier"));
  }

  @Test
  void shouldAllowTokenRequestWithAdditionalClientParameters() {
    mockGoogleTokenServer.enqueue(new MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""
            {"access_token":"google-access-token","id_token":"google-id-token","expires_in":3599}
            """));
    Jwt googleIdentityJwt = new Jwt(
        "google-id-token",
        Instant.now(),
        Instant.now().plusSeconds(300),
        Map.of("alg", "RS256"),
        Map.of("sub", "user-123", "email", "user@example.com", "scope", "openid email profile")
    );
    when(googleIdTokenValidator.validateAndDecode("google-id-token")).thenReturn(googleIdentityJwt);

    LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "authorization_code");
    form.add("code", "auth-code");
    form.add("redirect_uri", "http://localhost/callback");
    form.add("code_verifier", "verifier");
    form.add("audience", "some-audience");
    form.add("resource", "https://resource.example");
    form.add("client_id", "different-client-id-from-caller");

    ResponseEntity<String> response = postToken(form);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertTrue(response.getBody().contains("\"access_token\""));
  }

  private MultiValueMap<String, String> defaultTokenRequest() {
    LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.setAll(Map.of(
        "grant_type", "authorization_code",
        "code", "auth-code",
        "redirect_uri", "http://localhost/callback",
        "code_verifier", "verifier"));
    return form;
  }

  private ResponseEntity<String> postToken(MultiValueMap<String, String> form) {
    RestClient mcpClient = RestClient.builder().baseUrl("http://localhost:" + port).build();
    try {
      return mcpClient.post()
          .uri("/token")
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body(form)
          .retrieve()
          .toEntity(String.class);
    } catch (HttpClientErrorException ex) {
      return ResponseEntity.status(ex.getStatusCode()).body(ex.getResponseBodyAsString());
    }
  }

  private String extractTokenValue(String responseBody, String marker) {
    int start = responseBody.indexOf(marker);
    if (start < 0) {
      return "";
    }
    int valueStart = start + marker.length();
    int valueEnd = responseBody.indexOf('"', valueStart);
    if (valueEnd < 0) {
      return "";
    }
    return responseBody.substring(valueStart, valueEnd);
  }
}
