package com.jobshunter.mcp.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "mcp.token-exchange")
public record TokenExchangeProperties(
    @NotBlank String stsUrl,
    @NotBlank String audience,
    @NotEmpty List<String> scopes,
    @NotNull Duration timeout,
    @NotBlank String grantType,
    @NotBlank String subjectTokenType,
    @NotBlank String requestedTokenType
) {
}
