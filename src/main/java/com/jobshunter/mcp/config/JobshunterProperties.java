package com.jobshunter.mcp.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "jobshunter")
public record JobshunterProperties(
    @NotBlank String baseUrl,
    @NotBlank String searchJobsPath,
    @NotBlank String userInfoPath,
    @NotNull Duration connectTimeout,
    @NotNull Duration responseTimeout,
    Ssl ssl
) {

  public record Ssl(
      Resource trustStore,
      String trustStorePassword,
      String trustStoreType
  ) {
  }
}
