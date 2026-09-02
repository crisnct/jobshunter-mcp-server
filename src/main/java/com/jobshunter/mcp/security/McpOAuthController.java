package com.jobshunter.mcp.security;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

@Controller
public class McpOAuthController {
  private static final Pattern ID_TOKEN_PATTERN = Pattern.compile("\"id_token\"\\s*:\\s*\"([^\"]+)\"");
  private static final Pattern ACCESS_TOKEN_PATTERN = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"");
  private static final Pattern TOKEN_TYPE_PATTERN = Pattern.compile("\"token_type\"\\s*:\\s*\"([^\"]+)\"");

  private final McpOAuthProperties oauthProperties;
  private final RestClient restClient;

  public McpOAuthController(McpOAuthProperties oauthProperties) {
    this.oauthProperties = oauthProperties;
    this.restClient = RestClient.create();
  }

  @GetMapping("/.well-known/oauth-protected-resource")
  @ResponseBody
  public Map<String, Object> protectedResourceMetadata(HttpServletRequest request) {
    String issuer = publicBaseUrl(request);
    return Map.of(
        "resource", issuer + "/mcp",
        "authorization_servers", List.of(issuer),
        "bearer_methods_supported", List.of("header"),
        "scopes_supported", scopesList(),
        "tls_client_certificate_bound_access_tokens", false
    );
  }

  @GetMapping("/.well-known/oauth-authorization-server")
  @ResponseBody
  public Map<String, Object> authorizationServerMetadata(HttpServletRequest request) {
    String issuer = publicBaseUrl(request);
    return Map.ofEntries(
        Map.entry("issuer", issuer),
        Map.entry("authorization_endpoint", issuer + "/authorize"),
        Map.entry("token_endpoint", issuer + "/token"),
        Map.entry("jwks_uri", oauthProperties.jwksUri()),
        Map.entry("response_types_supported", List.of("code")),
        Map.entry("grant_types_supported", List.of("authorization_code", "refresh_token")),
        Map.entry("subject_types_supported", List.of("public")),
        Map.entry("id_token_signing_alg_values_supported", List.of("RS256")),
        Map.entry("token_endpoint_auth_methods_supported", List.of("none")),
        Map.entry("code_challenge_methods_supported", List.of("S256")),
        Map.entry("scopes_supported", scopesList())
    );
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

      String normalizedBody = normalizeTokenResponse(responseBody == null ? "{}" : responseBody);
      return ResponseEntity.ok()
          .contentType(MediaType.APPLICATION_JSON)
          .body(normalizedBody);
    } catch (RestClientResponseException ex) {
      return ResponseEntity.status(ex.getStatusCode())
          .contentType(MediaType.APPLICATION_JSON)
          .body(ex.getResponseBodyAsString());
    }
  }

  private String publicBaseUrl(HttpServletRequest request) {
    String proto = headerOrDefault(request, "X-Forwarded-Proto", request.getScheme());
    String host = headerOrDefault(request, "Host", request.getServerName());
    return proto + "://" + host;
  }

  private String headerOrDefault(HttpServletRequest request, String header, String defaultValue) {
    String value = request.getHeader(header);
    return StringUtils.hasText(value) ? value : defaultValue;
  }

  private List<String> scopesList() {
    return List.of(oauthProperties.scope().split("\\s+"));
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

  private String normalizeTokenResponse(String responseBody) {
    Matcher idTokenMatcher = ID_TOKEN_PATTERN.matcher(responseBody);
    if (!idTokenMatcher.find()) {
      return responseBody;
    }

    String idToken = idTokenMatcher.group(1);
    String normalized = ACCESS_TOKEN_PATTERN.matcher(responseBody)
        .replaceFirst("\"access_token\":\"" + Matcher.quoteReplacement(idToken) + "\"");

    if (!TOKEN_TYPE_PATTERN.matcher(normalized).find()) {
      normalized = normalized.replaceFirst("\\{", "{\"token_type\":\"Bearer\",");
    }
    return normalized;
  }
}
