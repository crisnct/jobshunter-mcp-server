package com.jobshunter.mcp.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;

@ExtendWith(MockitoExtension.class)
class InternalJwtDelegatedTokenResolverTest {

  @Mock
  private McpTokenIssuer tokenIssuer;

  @Test
  void shouldIssueDelegatedTokenFromAuthenticatedToken() {
    InternalJwtDelegatedTokenResolver resolver = new InternalJwtDelegatedTokenResolver(tokenIssuer);
    Jwt authenticatedToken = new Jwt(
        "mcp-access-token",
        Instant.now(),
        Instant.now().plusSeconds(300),
        Map.of("alg", "RS256"),
        Map.of("sub", "123", "aud", List.of("mcp-api"))
    );
    when(tokenIssuer.issueDelegatedJobshunterToken(authenticatedToken)).thenReturn("delegated-token");

    String delegatedToken = resolver.resolveDelegatedToken(authenticatedToken);

    assertEquals("delegated-token", delegatedToken);
    assertNotEquals(authenticatedToken.getTokenValue(), delegatedToken);
  }
}
