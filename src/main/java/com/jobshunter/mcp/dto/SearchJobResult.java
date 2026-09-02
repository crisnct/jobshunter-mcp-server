package com.jobshunter.mcp.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * One job entry returned by Jobshunter.
 *
 * @param url canonical URL of the job posting.
 * @param source source platform/provider where the job was found.
 */
public record SearchJobResult(
    @JsonPropertyDescription("Canonical URL of the job posting.")
    String url,
    @JsonPropertyDescription("Source platform/provider where the job was found.")
    String source
) {
}
