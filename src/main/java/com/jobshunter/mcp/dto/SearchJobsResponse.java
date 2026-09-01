package com.jobshunter.mcp.dto;

import java.util.List;

public record SearchJobsResponse(
    List<SearchJobResult> jobsFound
) {
}
