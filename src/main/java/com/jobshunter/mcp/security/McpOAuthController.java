package com.jobshunter.mcp.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobshunter.mcp.exception.JobshunterApiException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
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

@Controller
public class McpOAuthController {

  private final McpOAuthProperties oauthProperties;
  private final McpAuthorizationServerProperties authorizationServerProperties;
  private final GoogleIdTokenValidator googleIdTokenValidator;
  private final McpTokenIssuer tokenIssuer;
  private final RestClient restClient;
  private final ObjectMapper objectMapper;

  public McpOAuthController(
      McpOAuthProperties oauthProperties,
      McpAuthorizationServerProperties authorizationServerProperties,
      GoogleIdTokenValidator googleIdTokenValidator,
      McpTokenIssuer tokenIssuer
  ) {

    this.oauthProperties = oauthProperties;
    this.authorizationServerProperties = authorizationServerProperties;
    this.googleIdTokenValidator = googleIdTokenValidator;
    this.tokenIssuer = tokenIssuer;
    this.restClient = RestClient.create();
    this.objectMapper = new ObjectMapper();
  }

  @GetMapping("/authorize")
  public ResponseEntity<Void> authorizeRedirect(@RequestParam Map<String, String> params) {
    UriComponentsBuilder builder = UriComponentsBuilder
        .fromUriString(oauthProperties.googleAuthorizationEndpoint())
        .queryParam("response_type", getOrDefault(params, "response_type", "code"))
        .queryParam("client_id", oauthProperties.clientId())
        .queryParam("redirect_uri", params.get("redirect_uri"))
        .queryParam("scope", oauthProperties.scope())
        .queryParam("state", params.get("state"));

    addIfPresent(builder, "code_challenge", params.get("code_challenge"));
    addIfPresent(builder, "code_challenge_method", params.get("code_challenge_method"));
    addIfPresent(builder, "nonce", params.get("nonce"));
    addIfPresent(builder, "prompt", params.get("prompt"));
    addIfPresent(builder, "access_type", params.get("access_type"));

    String redirectUrl = builder.build().encode().toUriString();
    return ResponseEntity.status(302).location(URI.create(redirectUrl)).build();
  }

  @PostMapping(path = "/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
  @ResponseBody
  public ResponseEntity<String> tokenProxy(@RequestBody MultiValueMap<String, String> form) {
    MultiValueMap<String, String> requestBody = new org.springframework.util.LinkedMultiValueMap<>(form);
    requestBody.set("client_id", oauthProperties.clientId());
    requestBody.set("client_secret", oauthProperties.clientSecret());
    if (!requestBody.containsKey("scope")) {
      requestBody.add("scope", oauthProperties.scope());
    }

    try {
      String responseBody = restClient.post()
          .uri(oauthProperties.googleTokenEndpoint())
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body(requestBody)
          .retrieve()
          .body(String.class);

      String normalizedBody = processTokenResponse(responseBody == null ? "{}" : responseBody);
      return ResponseEntity.ok()
          .contentType(MediaType.APPLICATION_JSON)
          .body(normalizedBody);
    } catch (JobshunterApiException ex) {
      return ResponseEntity.status(401)
          .contentType(MediaType.APPLICATION_JSON)
          .body("{\"error\":\"invalid_token\",\"error_description\":\"" + escapeJson(ex.getMessage()) + "\"}");
    } catch (RestClientResponseException ex) {
      return ResponseEntity.status(ex.getStatusCode())
          .contentType(MediaType.APPLICATION_JSON)
          .body(ex.getResponseBodyAsString());
    } catch (Exception ex) {
      return ResponseEntity.status(500)
          .contentType(MediaType.APPLICATION_JSON)
          .body("{\"error\":\"server_error\",\"error_description\":\"Failed to process token response.\"}");
    }
  }

  private void addIfPresent(UriComponentsBuilder builder, String key, String value) {
    if (StringUtils.hasText(value)) {
      builder.queryParam(key, value);
    }
  }

  private String getOrDefault(Map<String, String> params, String key, String fallback) {
    String value = params.get(key);
    return StringUtils.hasText(value) ? value : fallback;
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
    response.put("expires_in", authorizationServerProperties.accessTokenTtl().toSeconds());
    response.put("scope", stringValueOrDefault(googleResponse.get("scope"), oauthProperties.scope()));
    response.put("id_token", idToken);

    if (googleResponse.get("refresh_token") != null) {
      response.put("refresh_token", googleResponse.get("refresh_token"));
    }
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

  private String escapeJson(String value) {
    return value == null ? "" : value.replace("\"", "\\\"");
  }
}
