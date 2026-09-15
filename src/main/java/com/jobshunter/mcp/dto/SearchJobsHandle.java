package com.jobshunter.mcp.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Handle returned by {@code search_jobs}, used to track an in-progress search via
 * {@code wait_for_search}.
 *
 * @param searchId identifier for the started search; pass this to {@code wait_for_search}.
 * @param configurationsSubmitted number of search configurations accepted for this search.
 */
public record SearchJobsHandle(
    @JsonPropertyDescription("Identifier for the started search. Pass this to wait_for_search to track progress.")
    String searchId,
    @JsonPropertyDescription("Number of search configurations accepted for this search.")
    int configurationsSubmitted
) {
}
