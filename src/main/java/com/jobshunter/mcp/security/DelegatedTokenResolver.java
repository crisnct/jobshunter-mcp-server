package com.jobshunter.mcp.security;

import org.springframework.security.oauth2.jwt.Jwt;

public interface DelegatedTokenResolver {
  String resolveDelegatedToken(Jwt authenticatedToken);
}
