package com.jobshunter.mcp.security;

import java.time.Duration;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "mcp.authorization-server")
public record McpAuthorizationServerProperties(
    @NotBlank String issuer,
    @NotBlank String mcpAudience,
    @NotBlank String jobshunterAudience,
    @DefaultValue("PT15M") Duration mcpAccessTokenTtl,
    @DefaultValue("mcp_access") String mcpAccessTokenUse,
    @DefaultValue("PT5M") Duration jobshunterDelegatedTokenTtl,
    @DefaultValue("jobshunter_delegated") String jobshunterDelegatedTokenUse,
    String signingKeyPem,
    @NotBlank @DefaultValue("mcp-key-1") String keyId
) {
}
