package com.jobshunter.mcp.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record SearchJobsRequest(
    @NotEmpty List<@Valid SearchConfiguration> searchConfigurations
) {
}
