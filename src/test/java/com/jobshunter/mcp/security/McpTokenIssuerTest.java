package com.jobshunter.mcp.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class McpTokenIssuerTest {

  @Test
  void shouldIssueAudienceSeparatedTokens() throws ParseException {
    McpAuthorizationServerProperties properties = new McpAuthorizationServerProperties(
        "https://mcp.local",
        "mcp-api",
        "jobshunter-api",
        Duration.ofMinutes(15),
        Duration.ofMinutes(5),
        null,
        "test-key"
    );
    McpJwtSigningService signingService = new McpJwtSigningService(properties);
    McpTokenIssuer tokenIssuer = new McpTokenIssuer(properties, signingService);

    Jwt upstreamJwt = new Jwt(
        "google-id-token",
        Instant.now(),
        Instant.now().plusSeconds(300),
        Map.of("alg", "RS256"),
        Map.of("sub", "user-123", "email", "user@example.com", "scope", "openid email profile")
    );

    String mcpAccessToken = tokenIssuer.issueMcpAccessToken(upstreamJwt);
    String delegatedToken = tokenIssuer.issueDelegatedJobshunterToken(upstreamJwt);

    assertNotEquals(mcpAccessToken, delegatedToken);

    SignedJWT mcpJwt = SignedJWT.parse(mcpAccessToken);
    SignedJWT delegatedJwt = SignedJWT.parse(delegatedToken);

    assertEquals(List.of("mcp-api"), mcpJwt.getJWTClaimsSet().getAudience());
    assertEquals(List.of("jobshunter-api"), delegatedJwt.getJWTClaimsSet().getAudience());
    assertEquals("mcp_access", mcpJwt.getJWTClaimsSet().getStringClaim("token_use"));
    assertEquals("jobshunter_delegated", delegatedJwt.getJWTClaimsSet().getStringClaim("token_use"));
    assertTrue(delegatedJwt.getJWTClaimsSet().getExpirationTime().before(mcpJwt.getJWTClaimsSet().getExpirationTime()));
  }
}
