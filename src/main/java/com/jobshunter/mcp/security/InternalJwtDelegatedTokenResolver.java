package com.jobshunter.mcp.security;

import org.springframework.security.oauth2.jwt.Jwt;

public class InternalJwtDelegatedTokenResolver implements DelegatedTokenResolver {
  private final McpTokenIssuer tokenIssuer;

  public InternalJwtDelegatedTokenResolver(McpTokenIssuer tokenIssuer) {
    this.tokenIssuer = tokenIssuer;
  }

  @Override
  public String resolveDelegatedToken(Jwt authenticatedToken) {
    return tokenIssuer.issueDelegatedJobshunterToken(authenticatedToken);
  }
}
