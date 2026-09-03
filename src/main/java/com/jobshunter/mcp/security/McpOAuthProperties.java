package com.jobshunter.mcp.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "mcp.oauth")
public record McpOAuthProperties(
    @NotBlank String googleAuthorizationEndpoint,
    @NotBlank String googleTokenEndpoint,
    @NotBlank String jwksUri,
    @DefaultValue("https://accounts.google.com") String googleIssuerUri,
    @NotBlank String clientId,
    @NotBlank String clientSecret,
    @NotBlank String scope
) {
}
