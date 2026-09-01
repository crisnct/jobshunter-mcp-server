package com.jobshunter.mcp.dto;

import jakarta.validation.constraints.NotBlank;

public record SearchConfiguration(
    @NotBlank String provider,
    @NotBlank String model,
    boolean searchCompanies,
    boolean searchWithUserPrompts
) {
}
