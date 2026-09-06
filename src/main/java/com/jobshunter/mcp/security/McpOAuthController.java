package com.jobshunter.mcp.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobshunter.mcp.exception.JobshunterApiException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * OAuth broker endpoints: /authorize redirects to Google, /token exchanges the resulting
 * authorization code and mints an MCP-scoped access token. Request validation lives in
 * {@link OAuthRequestValidator}; log redaction lives in {@link OAuthLogSanitizer}.
 */
@Slf4j
@Controller
public class McpOAuthController {

  private final McpOAuthProperties oauthProperties;
  private final McpAuthorizationServerProperties authorizationServerProperties;
  private final GoogleIdTokenValidator googleIdTokenValidator;
  private final McpTokenIssuer tokenIssuer;
  private final OAuthRequestValidator requestValidator;
  private final OAuthLogSanitizer logSanitizer;
  private final RestClient restClient;
  private final ObjectMapper objectMapper;

  public McpOAuthController(
      McpOAuthProperties oauthProperties,
      McpAuthorizationServerProperties authorizationServerProperties,
      GoogleIdTokenValidator googleIdTokenValidator,
      McpTokenIssuer tokenIssuer,
      OAuthRequestValidator requestValidator,
      OAuthLogSanitizer logSanitizer,
      RestClient googleRestClient
  ) {
    this.oauthProperties = oauthProperties;
    this.authorizationServerProperties = authorizationServerProperties;
    this.googleIdTokenValidator = googleIdTokenValidator;
    this.tokenIssuer = tokenIssuer;
    this.requestValidator = requestValidator;
    this.logSanitizer = logSanitizer;
    this.restClient = googleRestClient;
    this.objectMapper = new ObjectMapper();
  }

  @GetMapping("/authorize")
  public ResponseEntity<String> authorizeRedirect(@RequestParam MultiValueMap<String, String> params) {
    log.info("OAuth /authorize request received: {}", logSanitizer.sanitizeAuthorizeRequest(params));
    try {
      requestValidator.validateAuthorizeRequest(params);
    } catch (OAuthValidationException ex) {
      log.warn(
          "OAuth /authorize validation failed: errorCode={}, message={}, request={}",
          ex.errorCode,
          ex.getMessage(),
          logSanitizer.sanitizeAuthorizeRequest(params)
      );
      return oauthError(ex.status, ex.errorCode, ex.getMessage());
    }

    String responseType = requestValidator.requireSingleText(params, "response_type");
    String redirectUri = requestValidator.requireSingleText(params, "redirect_uri");
    String state = requestValidator.requireSingleText(params, "state");
    String codeChallenge = requestValidator.requireSingleText(params, "code_challenge");
    String codeChallengeMethod = requestValidator.requireSingleText(params, "code_challenge_method");
    requestValidator.ensureSingleValue(params, "client_id");
    requestValidator.ensureSingleValue(params, "scope");
    requestValidator.ensureSingleValue(params, "resource");

    UriComponentsBuilder builder = UriComponentsBuilder
        .fromUriString(oauthProperties.googleAuthorizationEndpoint())
        .queryParam("response_type", responseType)
        .queryParam("client_id", oauthProperties.clientId())
        .queryParam("redirect_uri", redirectUri)
        .queryParam("scope", oauthProperties.scope())
        .queryParam("state", state)
        .queryParam("code_challenge", codeChallenge)
        .queryParam("code_challenge_method", codeChallengeMethod);

    addIfPresent(builder, "nonce", firstValue(params, "nonce"));
    addIfPresent(builder, "prompt", firstValue(params, "prompt"));
    addIfPresent(builder, "access_type", firstValue(params, "access_type"));

    String redirectUrl = builder.build().encode().toUriString();
    log.info(
        "OAuth /authorize redirecting to upstream authorization endpoint: redirectUri={}, state={}",
        logSanitizer.redactUriQueryValue(redirectUri),
        logSanitizer.truncate(state, 48)
    );
    return ResponseEntity.status(302).location(URI.create(redirectUrl)).build();
  }

  @PostMapping(path = "/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
  @ResponseBody
  public ResponseEntity<String> tokenProxy(@RequestBody MultiValueMap<String, String> form) {
    log.info("OAuth /token request received: {}", logSanitizer.sanitizeTokenRequest(form));
    try {
      requestValidator.validateTokenRequest(form);
    } catch (OAuthValidationException ex) {
      log.warn(
          "OAuth /token validation failed: errorCode={}, message={}, request={}",
          ex.errorCode,
          ex.getMessage(),
          logSanitizer.sanitizeTokenRequest(form)
      );
      return oauthError(ex.status, ex.errorCode, ex.getMessage());
    }

    MultiValueMap<String, String> requestBody = new LinkedMultiValueMap<>(form);
    requestBody.set("client_id", oauthProperties.clientId());
    requestBody.set("client_secret", oauthProperties.clientSecret());
    if (!requestBody.containsKey("scope")) {
      requestBody.add("scope", oauthProperties.scope());
    }

    try {
      log.info(
          "OAuth /token exchanging authorization code upstream: endpoint={}, request={}",
          oauthProperties.googleTokenEndpoint(),
          logSanitizer.sanitizeTokenRequest(requestBody)
      );
      String responseBody = restClient.post()
          .uri(oauthProperties.googleTokenEndpoint())
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body(requestBody)
          .retrieve()
          .body(String.class);

      String normalizedBody = processTokenResponse(responseBody == null ? "{}" : responseBody);
      log.info("OAuth /token exchange successful; response transformed to MCP token contract.");
      return ResponseEntity.ok()
          .contentType(MediaType.APPLICATION_JSON)
          .body(normalizedBody);
    } catch (JobshunterApiException ex) {
      log.warn("OAuth /token failed while normalizing upstream response: {}", ex.getMessage());
      return oauthError(HttpStatus.UNAUTHORIZED, "invalid_token", ex.getMessage());
    } catch (RestClientResponseException ex) {
      log.warn(
          "OAuth /token upstream token endpoint error: status={}, body={}",
          ex.getStatusCode().value(),
          logSanitizer.truncate(ex.getResponseBodyAsString(), 600)
      );
      return ResponseEntity.status(ex.getStatusCode())
          .contentType(MediaType.APPLICATION_JSON)
          .body(ex.getResponseBodyAsString());
    } catch (Exception ex) {
      log.error("OAuth /token unexpected server error.", ex);
      return oauthError(HttpStatus.INTERNAL_SERVER_ERROR, "server_error", "Failed to process token response.");
    }
  }

  private void addIfPresent(UriComponentsBuilder builder, String key, String value) {
    if (StringUtils.hasText(value)) {
      builder.queryParam(key, value);
    }
  }

  private String firstValue(MultiValueMap<String, String> params, String key) {
    return stringValue(params.getFirst(key));
  }

  private String processTokenResponse(String responseBody) {
    return issueInternalMcpTokenResponse(responseBody);
  }

  private String issueInternalMcpTokenResponse(String googleResponseBody) {
    Map<String, Object> googleResponse = parseJsonObject(googleResponseBody);
    String idToken = stringValue(googleResponse.get("id_token"));
    if (!StringUtils.hasText(idToken)) {
      throw new JobshunterApiException("Google token response does not contain id_token required for MCP_INTERNAL_AS.");
    }

    var googleIdentity = googleIdTokenValidator.validateAndDecode(idToken);
    String mcpAccessToken = tokenIssuer.issueMcpAccessToken(googleIdentity);

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("access_token", mcpAccessToken);
    response.put("token_type", "Bearer");
    response.put("expires_in", authorizationServerProperties.mcpAccessTokenTtl().toSeconds());
    response.put("scope", stringValueOrDefault(googleResponse.get("scope"), oauthProperties.scope()));
    response.put("id_token", idToken);

    return toJson(response);
  }

  private Map<String, Object> parseJsonObject(String payload) {
    try {
      return objectMapper.readValue(payload, new TypeReference<>() {
      });
    } catch (Exception ex) {
      throw new JobshunterApiException("Failed to parse OAuth token response.", ex);
    }
  }

  private String toJson(Map<String, Object> map) {
    try {
      return objectMapper.writeValueAsString(map);
    } catch (Exception ex) {
      throw new JobshunterApiException("Failed to serialize MCP token response.", ex);
    }
  }

  private String stringValue(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private String stringValueOrDefault(Object value, String fallback) {
    String stringValue = stringValue(value);
    return StringUtils.hasText(stringValue) ? stringValue : fallback;
  }

  private ResponseEntity<String> oauthError(HttpStatus status, String error, String description) {
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            "{\"error\":\""
                + escapeJson(error)
                + "\",\"error_description\":\""
                + escapeJson(description)
                + "\"}"
        );
  }

  private String escapeJson(String value) {
    return value == null ? "" : value.replace("\"", "\\\"");
  }
}
