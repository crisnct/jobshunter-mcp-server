package com.jobshunter.mcp.tool;

import com.jobshunter.mcp.client.JobshunterClient;
import com.jobshunter.mcp.client.TokenExchangeClient;
import com.jobshunter.mcp.dto.SearchConfiguration;
import com.jobshunter.mcp.dto.SearchJobsResponse;
import com.jobshunter.mcp.exception.JobshunterApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
public class JobSearchTool {

  private final JobshunterClient jobshunterClient;
  private final TokenExchangeClient tokenExchangeClient;

  public JobSearchTool(JobshunterClient jobshunterClient, TokenExchangeClient tokenExchangeClient) {
    this.jobshunterClient = jobshunterClient;
    this.tokenExchangeClient = tokenExchangeClient;
  }

  @Tool(name = "search_jobs", description = """
      Search for jobs using Jobshunter.
      Executes the configured providers synchronously and returns jobs found.
      """)
  public SearchJobsResponse searchJobs(
      @ToolParam(description = "Jobshunter provider configurations for the search")
      @NotEmpty List<@Valid SearchConfiguration> searchConfigurations
  ) {
    validateSearchRequest(searchConfigurations);
    try {
      String userToken = resolveUserToken();
      String exchangedToken = tokenExchangeClient.exchangeToken(userToken);
      return jobshunterClient.searchJobs(searchConfigurations, exchangedToken);
    } catch (JobshunterApiException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new JobshunterApiException("Jobshunter returned an unexpected error.", ex);
    }
  }

  private String resolveUserToken() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!(authentication instanceof JwtAuthenticationToken jwtAuthenticationToken)) {
      throw new JobshunterApiException("Authenticated Google token is required to call search_jobs.");
    }
    return jwtAuthenticationToken.getToken().getTokenValue();
  }

  private void validateSearchRequest(List<SearchConfiguration> searchConfigurations) {
    for (SearchConfiguration configuration : searchConfigurations) {
      if (!configuration.searchCompanies() && !configuration.searchWithUserPrompts()) {
        throw new JobshunterApiException(
            "Invalid search configuration: at least one of searchCompanies or searchWithUserPrompts must be true.");
      }
    }
  }
}
