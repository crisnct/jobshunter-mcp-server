package com.jobshunter.mcp.security;

import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class McpSecurityConfig {

  @Bean
  @Order(1)
  SecurityFilterChain mcpSecurityFilterChain(
      HttpSecurity http,
      @Qualifier("mcpJwtDecoder") JwtDecoder mcpJwtDecoder
  ) {
    http.csrf(AbstractHttpConfigurer::disable)
        .securityMatcher("/mcp", "/mcp/**")
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
            .decoder(mcpJwtDecoder)
            .jwtAuthenticationConverter(jwtAuthenticationConverter())));

    return http.build();
  }

  @Bean
  @Order(2)
  SecurityFilterChain publicEndpointsFilterChain(HttpSecurity http) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
    return http.build();
  }

  @Bean("mcpJwtDecoder")
  JwtDecoder jwtDecoder(
      McpAuthorizationServerProperties authorizationServerProperties,
      McpJwtSigningService jwtSigningService
  ) {
    String resolvedIssuer = authorizationServerProperties.issuer();
    String resolvedAudience = authorizationServerProperties.mcpAudience();
    NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder.withPublicKey(jwtSigningService.publicKey()).build();

    OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(resolvedIssuer);
    OAuth2TokenValidator<Jwt> audienceValidator = token -> {
      List<String> audiences = token.getAudience();
      if (audiences != null && audiences.contains(resolvedAudience)) {
        return OAuth2TokenValidatorResult.success();
      }
      return OAuth2TokenValidatorResult.failure(
          new OAuth2Error("invalid_token", "Token audience is invalid for MCP server", null)
      );
    };

    jwtDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(withIssuer, audienceValidator));
    return jwtDecoder;
  }

  @Bean("googleIdTokenDecoder")
  JwtDecoder googleIdTokenDecoder(McpOAuthProperties oauthProperties) {
    NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder.withJwkSetUri(oauthProperties.jwksUri()).build();
    OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(oauthProperties.googleIssuerUri());
    OAuth2TokenValidator<Jwt> audienceValidator = token -> {
      List<String> audiences = token.getAudience();
      if (audiences != null && audiences.contains(oauthProperties.clientId())) {
        return OAuth2TokenValidatorResult.success();
      }
      return OAuth2TokenValidatorResult.failure(
          new OAuth2Error("invalid_token", "Google id_token audience mismatch", null)
      );
    };
    jwtDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(withIssuer, audienceValidator));
    return jwtDecoder;
  }

  private Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter() {
    JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
    authoritiesConverter.setAuthoritiesClaimName("scope");
    authoritiesConverter.setAuthorityPrefix("SCOPE_");

    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
    return converter;
  }
}
