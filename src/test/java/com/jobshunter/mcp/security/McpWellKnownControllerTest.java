package com.jobshunter.mcp.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

class McpWellKnownControllerTest {
  private McpWellKnownController controller;

  @BeforeEach
  void setUp() {
    McpOAuthProperties oauthProperties = new McpOAuthProperties(
        "https://accounts.google.com/o/oauth2/v2/auth",
        "https://oauth2.googleapis.com/token",
        "https://www.googleapis.com/oauth2/v3/certs",
        "https://accounts.google.com",
        "client-id",
        "client-secret",
        "openid email profile");
    McpAuthorizationServerProperties authorizationServerProperties = new McpAuthorizationServerProperties(
        "https://mcp.local/",
        "mcp-api",
        "jobshunter-api",
        Duration.ofMinutes(15),
        Duration.ofMinutes(5),
        "jobshunter_delegated",
        null,
        "mcp-key-1");
    McpJwtSigningService jwtSigningService = mock(McpJwtSigningService.class);
    when(jwtSigningService.jwks()).thenReturn(Map.of("keys", List.of()));
    controller = new McpWellKnownController(oauthProperties, jwtSigningService, authorizationServerProperties);
  }

  @Test
  void shouldBuildAuthorizationServerMetadataFromConfiguredIssuerOnly() {
    Map<String, Object> metadata = controller.authorizationServerMetadata();
    assertEquals("https://mcp.local", metadata.get("issuer"));
    assertEquals("https://mcp.local/authorize", metadata.get("authorization_endpoint"));
    assertEquals("https://mcp.local/token", metadata.get("token_endpoint"));
    assertEquals("https://mcp.local/.well-known/jwks.json", metadata.get("jwks_uri"));
  }

  @Test
  void shouldBuildProtectedResourceMetadataFromConfiguredIssuerOnly() {
    Map<String, Object> metadata = controller.protectedResourceMetadata();
    assertEquals("https://mcp.local/mcp", metadata.get("resource"));
    assertEquals(List.of("https://mcp.local"), metadata.get("authorization_servers"));
  }

  @Test
  void shouldNotDependOnRequestHeadersForDiscoveryMetadata() throws Exception {
    Method authorizationServerMethod = McpWellKnownController.class.getMethod("authorizationServerMetadata");
    Method protectedResourceMethod = McpWellKnownController.class.getMethod("protectedResourceMetadata");
    assertEquals(0, authorizationServerMethod.getParameterCount());
    assertEquals(0, protectedResourceMethod.getParameterCount());
    assertTrue(authorizationServerMethod.toGenericString().contains("authorizationServerMetadata"));
    assertTrue(protectedResourceMethod.toGenericString().contains("protectedResourceMetadata"));
  }
}
