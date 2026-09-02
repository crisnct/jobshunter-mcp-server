package com.jobshunter.mcp.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/**
 * Response payload returned by {@code search_jobs}.
 *
 * @param jobsFound deduplicated list of jobs found during the synchronous search.
 */
public record SearchJobsResponse(
    @JsonPropertyDescription("Deduplicated list of job results found by Jobshunter.")
    List<SearchJobResult> jobsFound
) {
}
