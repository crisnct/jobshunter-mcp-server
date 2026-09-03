package com.jobshunter.mcp.security;

import com.jobshunter.mcp.exception.JobshunterApiException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

@Component
public class GoogleIdTokenValidator {
  private final JwtDecoder googleIdTokenDecoder;

  public GoogleIdTokenValidator(@Qualifier("googleIdTokenDecoder") JwtDecoder googleIdTokenDecoder) {
    this.googleIdTokenDecoder = googleIdTokenDecoder;
  }

  public Jwt validateAndDecode(String idToken) {
    try {
      return googleIdTokenDecoder.decode(idToken);
    } catch (JwtException ex) {
      throw new JobshunterApiException("Google identity token validation failed.", ex);
    }
  }
}
