package com.jobshunter.mcp.security;

import java.time.Instant;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
public class McpTokenIssuer {
  private final McpAuthorizationServerProperties properties;
  private final McpJwtSigningService jwtSigningService;

  public McpTokenIssuer(
      McpAuthorizationServerProperties properties,
      McpJwtSigningService jwtSigningService
  ) {
    this.properties = properties;
    this.jwtSigningService = jwtSigningService;
  }

  public String issueMcpAccessToken(Jwt upstreamIdentityToken) {
    Instant expiresAt = Instant.now().plus(properties.mcpAccessTokenTtl());
    String subject = resolveSubject(upstreamIdentityToken);
    Map<String, Object> claims = Map.of(
        "email", stringClaim(upstreamIdentityToken, "email"),
        "scope", stringClaim(upstreamIdentityToken, "scope"),
        "token_use", properties.mcpAccessTokenUse()
    );
    log.debug(
        "Issuing MCP access token: subject={}, audience={}, expiresAt={}",
        subject, properties.mcpAudience(), expiresAt
    );
    return jwtSigningService.signToken(subject, properties.mcpAudience(), expiresAt, claims);
  }

  public String issueDelegatedJobshunterToken(Jwt authenticatedMcpToken) {
    Instant expiresAt = Instant.now().plus(properties.jobshunterDelegatedTokenTtl());
    String subject = resolveSubject(authenticatedMcpToken);
    Map<String, Object> claims = Map.of(
        "email", stringClaim(authenticatedMcpToken, "email"),
        "scope", stringClaim(authenticatedMcpToken, "scope"),
        "token_use", properties.jobshunterDelegatedTokenUse()
    );
    log.debug(
        "Issuing delegated Jobshunter token: subject={}, audience={}, expiresAt={}",
        subject, properties.jobshunterAudience(), expiresAt
    );
    return jwtSigningService.signToken(subject, properties.jobshunterAudience(), expiresAt, claims);
  }

  private String resolveSubject(Jwt token) {
    String subject = token.getSubject();
    if (StringUtils.hasText(subject)) {
      return subject;
    }
    return token.getClaimAsString("email");
  }

  private String stringClaim(Jwt token, String claimName) {
    String value = token.getClaimAsString(claimName);
    return value == null ? "" : value;
  }
}
