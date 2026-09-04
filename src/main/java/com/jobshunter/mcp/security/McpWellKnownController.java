package com.jobshunter.mcp.security;

import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/.well-known")
public class McpWellKnownController {
  private final McpOAuthProperties oauthProperties;
  private final McpJwtSigningService jwtSigningService;
  private final McpAuthorizationServerProperties authorizationServerProperties;

  public McpWellKnownController(
      McpOAuthProperties oauthProperties,
      McpJwtSigningService jwtSigningService,
      McpAuthorizationServerProperties authorizationServerProperties
  ) {
    this.oauthProperties = oauthProperties;
    this.jwtSigningService = jwtSigningService;
    this.authorizationServerProperties = authorizationServerProperties;
  }

  @GetMapping("/oauth-protected-resource")
  public Map<String, Object> protectedResourceMetadata() {
    String issuer = publicBaseUrl();
    return Map.of(
        "resource", issuer + "/mcp",
        "authorization_servers", List.of(issuer),
        "bearer_methods_supported", List.of("header"),
        "scopes_supported", scopesList(),
        "tls_client_certificate_bound_access_tokens", false
    );
  }

  @GetMapping("/oauth-authorization-server")
  public Map<String, Object> authorizationServerMetadata() {
    String issuer = publicBaseUrl();
    String jwksUri = issuer + "/.well-known/jwks.json";
    return Map.ofEntries(
        Map.entry("issuer", issuer),
        Map.entry("authorization_endpoint", issuer + "/authorize"),
        Map.entry("token_endpoint", issuer + "/token"),
        Map.entry("jwks_uri", jwksUri),
        Map.entry("response_types_supported", List.of("code")),
        Map.entry("grant_types_supported", List.of("authorization_code", "refresh_token")),
        Map.entry("subject_types_supported", List.of("public")),
        Map.entry("id_token_signing_alg_values_supported", List.of("RS256")),
        Map.entry("token_endpoint_auth_methods_supported", List.of("none")),
        Map.entry("code_challenge_methods_supported", List.of("S256")),
        Map.entry("scopes_supported", scopesList())
    );
  }

  @GetMapping("/jwks.json")
  public Map<String, Object> jwks() {
    return jwtSigningService.jwks();
  }

  private String publicBaseUrl() {
    String issuer = authorizationServerProperties.issuer();
    if (!StringUtils.hasText(issuer)) {
      throw new IllegalStateException("mcp.authorization-server.issuer must not be blank");
    }
    return issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
  }

  private List<String> scopesList() {
    return List.of(oauthProperties.scope().split("\\s+"));
  }
}
