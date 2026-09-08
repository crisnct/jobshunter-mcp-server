package com.jobshunter.mcp.tool;

import com.jobshunter.mcp.client.JobshunterClient;
import com.jobshunter.mcp.dto.SearchConfiguration;
import com.jobshunter.mcp.dto.SearchJobsResponse;
import com.jobshunter.mcp.dto.UserInfoResponse;
import com.jobshunter.mcp.dto.UserProfileResponse;
import com.jobshunter.mcp.exception.JobshunterApiException;
import com.jobshunter.mcp.security.DelegatedTokenResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

@Slf4j
@Service
@Validated
public class JobSearchTool {

  private final JobshunterClient jobshunterClient;
  private final DelegatedTokenResolver delegatedTokenResolver;

  public JobSearchTool(JobshunterClient jobshunterClient, DelegatedTokenResolver delegatedTokenResolver) {
    this.jobshunterClient = jobshunterClient;
    this.delegatedTokenResolver = delegatedTokenResolver;
  }

  @Tool(name = "search_jobs", description = """
      Run a synchronous job search in Jobshunter for the authenticated user.

      Input:
      - searchConfigurations: list of provider/model configurations.
      - provider: AI provider name configured in Jobshunter (example: GROK, SERP, GEMINI, GPT).
      - model: exact model identifier for the selected provider.
      - searchCompanies: when true, search by company-based heuristics.
      - searchWithUserPrompts: when true, search using the user's stored prompts/preferences.
      - Validation rule: at least one of searchCompanies or searchWithUserPrompts must be true.

      Response:
      - jobsFound: deduplicated list of job matches.
      - jobsFound[].url: canonical URL of the found job posting.
      - jobsFound[].source: source platform/provider where the job was found.

      Authentication:
      - Requires a valid MCP bearer token already authenticated on /mcp.
      - The tool resolves a delegated bearer token and forwards it to Jobshunter internal API.
      """)
  public SearchJobsResponse searchJobs(
      @ToolParam(description = """
          Array of search configuration objects.
          Each object contains:
          - provider (string, required): provider key configured in Jobshunter.
          - model (string, required): model key for the provider.
          - searchCompanies (boolean): include company-oriented search strategy.
          - searchWithUserPrompts (boolean): include user-prompt-oriented strategy.
          Constraint: at least one boolean must be true.
          """)
      @NotEmpty List<@Valid SearchConfiguration> searchConfigurations
  ) {
    validateSearchRequest(searchConfigurations);
    log.debug("search_jobs invoked: configurations={}", searchConfigurations.size());
    return callJobshunter(() -> {
      String userToken = resolveUserToken("search_jobs");
      return jobshunterClient.searchJobs(searchConfigurations, userToken);
    });
  }

  @Tool(name = "get_user_info", description = """
      Return a job-search-safe subset of profile details for the authenticated Jobshunter user.

      This tool performs a delegated call to Jobshunter internal endpoint /api/internal/me
      and returns user metadata used by search orchestration and personalization. Secrets and
      authorization-sensitive fields (verification token, phone number, security roles, and
      stored prompts) are never included in the response.

      Response fields:
      - username: unique username used in Jobshunter.
      - email: primary user email.
      - notifyWhatsapp: whether WhatsApp notifications are enabled.
      - notifyEmail: whether email notifications are enabled.
      - emailVerified: whether the user email is verified.
      - cvFilename: uploaded CV file name; empty or null when missing.
      - notifiedAt: ISO-8601 timestamp of last notification event.
      - createdAt: ISO-8601 account creation timestamp.
      - city: profile city.
      - country: profile country.
      - jobDomain: profile job domain/category.
      - jobRoles: preferred job role names.
      - jobTypes: preferred job type values.
      - relocation: relocation preference value.
      - contractTypes: preferred contract type values.

      Authentication:
      - Requires a valid MCP bearer token already authenticated on /mcp.
      - The tool resolves a delegated bearer token and forwards it to Jobshunter internal API.
      """)
  public UserProfileResponse getUserInfo() {
    log.debug("get_user_info invoked");
    return callJobshunter(() -> {
      String userToken = resolveUserToken("get_user_info");
      UserInfoResponse userInfo = jobshunterClient.getUserInfo(userToken);
      return UserProfileResponse.fromUserInfo(userInfo);
    });
  }

  private <T> T callJobshunter(Supplier<T> call) {
    try {
      return call.get();
    } catch (JobshunterApiException ex) {
      throw ex;
    } catch (Exception ex) {
      log.warn("Unexpected error while calling Jobshunter.", ex);
      throw new JobshunterApiException("Jobshunter returned an unexpected error.", ex);
    }
  }

  private String resolveUserToken(String toolName) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!(authentication instanceof JwtAuthenticationToken jwtAuthenticationToken)) {
      throw new JobshunterApiException("Authenticated MCP token is required to call " + toolName + ".");
    }
    return delegatedTokenResolver.resolveDelegatedToken(jwtAuthenticationToken.getToken());
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
