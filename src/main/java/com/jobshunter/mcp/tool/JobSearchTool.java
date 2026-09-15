package com.jobshunter.mcp.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobshunter.mcp.client.JobshunterClient;
import com.jobshunter.mcp.dto.SearchConfiguration;
import com.jobshunter.mcp.dto.SearchJobsResponse;
import com.jobshunter.mcp.dto.UserInfoResponse;
import com.jobshunter.mcp.exception.ErrorCode;
import com.jobshunter.mcp.exception.JobshunterApiException;
import com.jobshunter.mcp.logging.RequestContext;
import com.jobshunter.mcp.security.DelegatedTokenResolver;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

@Slf4j
@Service
@Validated
public class JobSearchTool {

  private static final String OUTPUT_FORMAT_TABLE = "table";

  private final JobshunterClient jobshunterClient;
  private final DelegatedTokenResolver delegatedTokenResolver;
  private final ObjectMapper objectMapper;

  public JobSearchTool(
      JobshunterClient jobshunterClient, DelegatedTokenResolver delegatedTokenResolver, ObjectMapper objectMapper) {
    this.jobshunterClient = jobshunterClient;
    this.delegatedTokenResolver = delegatedTokenResolver;
    this.objectMapper = objectMapper;
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
      Return profile details for the authenticated Jobshunter user.

      This tool performs a delegated call to Jobshunter internal endpoint /api/internal/me
      and returns user metadata used by search orchestration and personalization.

      Input:
      - outputFormat (optional, default "raw"):
        - "raw": returns the full structured profile as JSON (see fields below) - the shape to
          ask for when the result feeds further programmatic logic.
        - "table": returns the same profile rewritten as a table for a person to read. Uses MCP
          sampling (https://modelcontextprotocol.io - the calling client's own LLM) when the
          client declares that capability; otherwise falls back to a deterministic, locally-built
          table. Never fails solely because sampling is unavailable.

      Response fields (outputFormat=raw):
      - username: unique username used in Jobshunter.
      - email: primary user email.
      - phoneNumber: phone number stored in profile.
      - notifyWhatsapp: whether WhatsApp notifications are enabled.
      - notifyEmail: whether email notifications are enabled.
      - emailVerified: whether the user email is verified.
      - verificationToken: current verification token, if any.
      - cvFilename: uploaded CV file name; empty or null when missing.
      - notifiedAt: ISO-8601 timestamp of last notification event.
      - prompts: list of user prompts/preferences used by search flows.
      - createdAt: ISO-8601 account creation timestamp.
      - roles: security roles assigned to the user.
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
  public Object getUserInfo(
      @ToolParam(
          description = "Output shape: \"raw\" (default) for the structured JSON profile, "
              + "\"table\" for a human-readable table.",
          required = false)
      String outputFormat,
      ToolContext toolContext
  ) {
    log.debug("get_user_info invoked: outputFormat={}", outputFormat);
    return callJobshunter(() -> {
      String userToken = resolveUserToken("get_user_info");
      UserInfoResponse userInfo = jobshunterClient.getUserInfo(userToken);
      return OUTPUT_FORMAT_TABLE.equalsIgnoreCase(outputFormat) ? formatAsTable(userInfo, toolContext) : userInfo;
    });
  }

  private <T> T callJobshunter(Supplier<T> call) {
    String requestId = RequestContext.newRequestId();
    MDC.put(RequestContext.REQUEST_ID_MDC_KEY, requestId);
    try {
      log.info("tools/call started: requestId={}", requestId);
      return call.get();
    } catch (JobshunterApiException ex) {
      throw ex;
    } catch (Exception ex) {
      log.warn("Unexpected error while calling Jobshunter: requestId={}", requestId, ex);
      throw new JobshunterApiException(ErrorCode.UNKNOWN, "Jobshunter returned an unexpected error.", ex);
    } finally {
      MDC.remove(RequestContext.REQUEST_ID_MDC_KEY);
    }
  }

  private String resolveUserToken(String toolName) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!(authentication instanceof JwtAuthenticationToken jwtAuthenticationToken)) {
      throw new JobshunterApiException(
          ErrorCode.AUTH_FAILED, "Authenticated MCP token is required to call " + toolName + ".");
    }
    return delegatedTokenResolver.resolveDelegatedToken(jwtAuthenticationToken.getToken());
  }

  private void validateSearchRequest(List<SearchConfiguration> searchConfigurations) {
    for (SearchConfiguration configuration : searchConfigurations) {
      if (!configuration.searchCompanies() && !configuration.searchWithUserPrompts()) {
        throw new JobshunterApiException(
            ErrorCode.VALIDATION,
            "Invalid search configuration: at least one of searchCompanies or searchWithUserPrompts must be true.");
      }
    }
  }

  /**
   * Formats {@code userInfo} as a table, preferring MCP sampling (the calling client's own LLM)
   * and falling back to a deterministic, locally-built table whenever sampling is unavailable,
   * unsupported by the client, or fails for any reason. This method deliberately never throws
   * for a sampling-related failure - formatting must always succeed.
   */
  private String formatAsTable(UserInfoResponse userInfo, ToolContext toolContext) {
    Optional<McpSyncServerExchange> exchange = McpToolUtils.getMcpExchange(toolContext);
    if (exchange.isEmpty() || exchange.get().getClientCapabilities().sampling() == null) {
      log.debug("MCP client has no sampling capability; using local table formatter for get_user_info.");
      return localTableFormat(userInfo);
    }

    try {
      McpSchema.CreateMessageRequest samplingRequest = McpSchema.CreateMessageRequest.builder(
              List.of(new McpSchema.SamplingMessage(
                  McpSchema.Role.USER,
                  new McpSchema.TextContent(toSamplingPrompt(userInfo)))),
              400)
          .systemPrompt("""
              You turn a Jobshunter user profile info into a table. Use only the facts given in the input, never invent data.
              """)
          .modelPreferences(McpSchema.ModelPreferences.builder()
              .intelligencePriority(0.3)
              .speedPriority(0.8)
              .build())
          .build();

      McpSchema.CreateMessageResult result = exchange.get().createMessage(samplingRequest);
      if (result.content() instanceof McpSchema.TextContent textContent && StringUtils.hasText(textContent.text())) {
        return textContent.text();
      }
      log.warn("MCP sampling returned an empty or unexpected content type for get_user_info(table); "
          + "falling back to local table formatter.");
      return localTableFormat(userInfo);
    } catch (Exception ex) {
      log.warn("MCP sampling failed for get_user_info(table); falling back to local table formatter.", ex);
      return localTableFormat(userInfo);
    }
  }

  /**
   * Serializes {@code userInfo} to JSON for the sampling prompt, using the application's shared
   * Jackson {@link ObjectMapper} so it stays in sync with whatever modules/config that bean
   * carries, rather than hand-picking fields.
   */
  private String toSamplingPrompt(UserInfoResponse userInfo) throws JsonProcessingException {
    Map<String, Object> profileFields = objectMapper.convertValue(userInfo, new TypeReference<>() {});
    return objectMapper.writeValueAsString(profileFields);
  }

  /**
   * Deterministic fallback used when sampling is unavailable or fails - no LLM involved.
   * {@code verificationToken} is intentionally left out here regardless of what the sampling
   * prompt above sends: it is a bearer-style secret with no reason to ever be rendered to a
   * person as part of a "view my profile" table.
   */
  private String localTableFormat(UserInfoResponse userInfo) {
    return """
        | Field | Value |
        |---|---|
        | Username | %s |
        | Email | %s |
        | Phone | %s |
        | City | %s |
        | Country | %s |
        | Job domain | %s |
        | Job roles | %s |
        | Job types | %s |
        | Relocation | %s |
        | Contract types | %s |
        | CV uploaded | %s |
        """.formatted(
        userInfo.username(),
        nullToDash(userInfo.email()),
        nullToDash(userInfo.phoneNumber()),
        nullToDash(userInfo.city()),
        nullToDash(userInfo.country()),
        nullToDash(userInfo.jobDomain()),
        joinOrDash(userInfo.jobRoles()),
        joinOrDash(userInfo.jobTypes()),
        nullToDash(userInfo.relocation()),
        joinOrDash(userInfo.contractTypes()),
        StringUtils.hasText(userInfo.cvFilename()) ? "yes" : "no");
  }

  private String joinOrDash(List<String> values) {
    return (values == null || values.isEmpty()) ? "-" : String.join(", ", values);
  }

  private String nullToDash(String value) {
    return StringUtils.hasText(value) ? value : "-";
  }
}
