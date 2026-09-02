package com.jobshunter.mcp.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.NotBlank;

/**
 * One provider/model configuration used by {@code search_jobs}.
 *
 * @param provider provider key configured in Jobshunter (for example: GROK, SERP, GEMINI, GPT).
 * @param model model identifier configured for the selected provider.
 * @param searchCompanies when true, include company-oriented search strategy.
 * @param searchWithUserPrompts when true, include user-prompt-oriented strategy.
 */
public record SearchConfiguration(
    @NotBlank
    @JsonPropertyDescription("Provider key configured in Jobshunter (example: GROK, SERP, GEMINI, GPT).")
    String provider,
    @NotBlank
    @JsonPropertyDescription("Model identifier configured for the selected provider. Model for SERP is google_jobs. Model for GROK is grok-4-1-fast-non-reasoning")
    String model,
    @JsonPropertyDescription("If true, include company-oriented search strategy.")
    boolean searchCompanies,
    @JsonPropertyDescription("If true, include user-prompt-oriented search strategy.")
    boolean searchWithUserPrompts
) {
}
