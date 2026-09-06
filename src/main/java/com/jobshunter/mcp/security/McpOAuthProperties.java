package com.jobshunter.mcp.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
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
    @NotBlank String scope,
    @DefaultValue("true") boolean enforceRedirectAllowlist,
    List<String> allowedRedirectUris,
    @DefaultValue("true") boolean allowLoopbackRedirectUris,
    @DefaultValue("/callback") List<String> allowedLoopbackRedirectPaths,
    @DefaultValue("true") boolean rejectUnknownAuthorizeParameters,
    @DefaultValue("true") boolean rejectUnknownTokenParameters,
    List<String> additionalAuthorizeParameters,
    List<String> additionalTokenParameters,
    @NotNull @DefaultValue("5s") Duration connectTimeout,
    @NotNull @DefaultValue("15s") Duration responseTimeout
) {
}
