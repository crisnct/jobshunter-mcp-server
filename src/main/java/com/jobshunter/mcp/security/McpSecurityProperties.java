package com.jobshunter.mcp.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "mcp.security")
public record McpSecurityProperties(
    @NotBlank String audience,
    String requiredScope
) {
}
