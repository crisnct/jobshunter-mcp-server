package com.jobshunter.mcp.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobshunter.mcp.exception.JobshunterApiException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.util.CollectionUtils;
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

@Slf4j
@Controller
public class McpOAuthController {

  private static final String AUTHORIZATION_CODE_GRANT = "authorization_code";
  private static final String RESPONSE_TYPE_CODE = "code";
  private static final String CODE_CHALLENGE_METHOD_S256 = "S256";
  private static final Set<String> AUTHORIZE_ALLOWED_PARAMETERS =
      Set.of(
          "client_id",
          "response_type",
          "redirect_uri",
          "scope",
          "state",
          "code_challenge",
          "code_challenge_method",
          "nonce",
          "prompt",
          "access_type",
          "resource"
      );
  private static final Set<String> TOKEN_REQUIRED_PARAMETERS =
      Set.of("grant_type", "code", "redirect_uri", "code_verifier");

  private final McpOAuthProperties oauthProperties;
  private final McpAuthorizationServerProperties authorizationServerProperties;
  private final GoogleIdTokenValidator googleIdTokenValidator;
  private final McpTokenIssuer tokenIssuer;
  private final RestClient restClient;
  private final ObjectMapper objectMapper;
  private final Set<String> allowedRedirectUris;
  private final Set<String> allowedLoopbackRedirectPaths;
  private final Set<String> authorizeAllowedParameters;
  private final Set<String> tokenAllowedParameters;

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
    this.allowedRedirectUris = normalizeRedirectUris(oauthProperties.allowedRedirectUris());
    this.allowedLoopbackRedirectPaths = normalizeLoopbackPaths(oauthProperties.allowedLoopbackRedirectPaths());
    this.authorizeAllowedParameters =
        buildAllowedParameters(AUTHORIZE_ALLOWED_PARAMETERS, oauthProperties.additionalAuthorizeParameters());
    this.tokenAllowedParameters =
        buildAllowedParameters(TOKEN_REQUIRED_PARAMETERS, oauthProperties.additionalTokenParameters());
    assertRedirectAllowlistConfiguration();
  }

  @GetMapping("/authorize")
  public ResponseEntity<String> authorizeRedirect(@RequestParam MultiValueMap<String, String> params) {
    log.info("OAuth /authorize request received: {}", sanitizeAuthorizeRequest(params));
    try {
      validateAuthorizeRequest(params);
    } catch (OAuthValidationException ex) {
      log.warn(
          "OAuth /authorize validation failed: errorCode={}, message={}, request={}",
          ex.errorCode,
          ex.getMessage(),
          sanitizeAuthorizeRequest(params)
      );
      return oauthError(ex.status, ex.errorCode, ex.getMessage());
    }

    String responseType = requireSingleText(params, "response_type");
    String redirectUri = requireSingleText(params, "redirect_uri");
    String state = requireSingleText(params, "state");
    String codeChallenge = requireSingleText(params, "code_challenge");
    String codeChallengeMethod = requireSingleText(params, "code_challenge_method");
    ensureSingleValue(params, "client_id");
    ensureSingleValue(params, "scope");
    ensureSingleValue(params, "resource");

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
        redactUriQueryValue(redirectUri),
        truncate(state, 48)
    );
    return ResponseEntity.status(302).location(URI.create(redirectUrl)).build();
  }

  @PostMapping(path = "/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
  @ResponseBody
  public ResponseEntity<String> tokenProxy(@RequestBody MultiValueMap<String, String> form) {
    log.info("OAuth /token request received: {}", sanitizeTokenRequest(form));
    try {
      validateTokenRequest(form);
    } catch (OAuthValidationException ex) {
      log.warn(
          "OAuth /token validation failed: errorCode={}, message={}, request={}",
          ex.errorCode,
          ex.getMessage(),
          sanitizeTokenRequest(form)
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
          sanitizeTokenRequest(requestBody)
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
          truncate(ex.getResponseBodyAsString(), 600)
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

  private void validateAuthorizeRequest(MultiValueMap<String, String> params) {
    if (CollectionUtils.isEmpty(params)) {
      throw new OAuthValidationException(HttpStatus.BAD_REQUEST, "invalid_request", "Authorization request is required.");
    }
    rejectUnknownParametersIfConfigured(
        params.keySet(),
        authorizeAllowedParameters,
        oauthProperties.rejectUnknownAuthorizeParameters(),
        "authorize request"
    );

    String responseType = requireSingleText(params, "response_type");
    if (!RESPONSE_TYPE_CODE.equals(responseType)) {
      throw new OAuthValidationException(
          HttpStatus.BAD_REQUEST,
          "unsupported_response_type",
          "Only response_type=code is supported."
      );
    }

    String codeChallengeMethod = requireSingleText(params, "code_challenge_method");
    if (!CODE_CHALLENGE_METHOD_S256.equals(codeChallengeMethod)) {
      throw new OAuthValidationException(
          HttpStatus.BAD_REQUEST,
          "invalid_request",
          "code_challenge_method must be S256."
      );
    }

    requireSingleText(params, "state");
    requireSingleText(params, "code_challenge");
    validateRedirectUri(requireSingleText(params, "redirect_uri"));
  }

  private void validateTokenRequest(MultiValueMap<String, String> form) {
    if (form == null || form.isEmpty()) {
      throw new OAuthValidationException(HttpStatus.BAD_REQUEST, "invalid_request", "Token request body is required.");
    }

    rejectUnknownParametersIfConfigured(
        form.keySet(),
        tokenAllowedParameters,
        oauthProperties.rejectUnknownTokenParameters(),
        "token request"
    );

    ensureSingleValued(form, TOKEN_REQUIRED_PARAMETERS);

    String grantType = firstValue(form, "grant_type");
    if (!AUTHORIZATION_CODE_GRANT.equals(grantType)) {
      throw new OAuthValidationException(
          HttpStatus.BAD_REQUEST,
          "unsupported_grant_type",
          "Only authorization_code grant_type is supported by this token endpoint."
      );
    }

    requireText(form, "code");
    validateRedirectUri(requireSingleText(form, "redirect_uri"));
    requireText(form, "code_verifier");
  }

  private String firstValue(MultiValueMap<String, String> form, String key) {
    return stringValue(form.getFirst(key));
  }

  private String requireSingleText(MultiValueMap<String, String> form, String key) {
    ensureSingleValue(form, key);
    return requireText(form, key);
  }

  private String requireText(MultiValueMap<String, String> form, String key) {
    String value = firstValue(form, key);
    if (!StringUtils.hasText(value)) {
      throw new OAuthValidationException(
          HttpStatus.BAD_REQUEST,
          "invalid_request",
          "Missing required request parameter: " + key + "."
      );
    }
    return value;
  }

  private void ensureSingleValued(MultiValueMap<String, String> form, Collection<String> keys) {
    for (String key : keys) {
      ensureSingleValue(form, key);
    }
  }

  private void ensureSingleValue(MultiValueMap<String, String> form, String key) {
    List<String> values = form.get(key);
    if (values != null && values.size() > 1) {
      throw new OAuthValidationException(
          HttpStatus.BAD_REQUEST,
          "invalid_request",
          "Parameter must be single-valued: " + key + "."
      );
    }
  }

  private void validateRedirectUri(String redirectUriValue) {
    URI redirectUri;
    try {
      redirectUri = URI.create(redirectUriValue);
    } catch (IllegalArgumentException ex) {
      throw new OAuthValidationException(HttpStatus.BAD_REQUEST, "invalid_request", "redirect_uri is not a valid URI.");
    }

    if (!redirectUri.isAbsolute() || !StringUtils.hasText(redirectUri.getScheme()) || !StringUtils.hasText(redirectUri.getHost())) {
      throw new OAuthValidationException(HttpStatus.BAD_REQUEST, "invalid_request", "redirect_uri must be an absolute URI.");
    }
    if (StringUtils.hasText(redirectUri.getFragment())) {
      throw new OAuthValidationException(HttpStatus.BAD_REQUEST, "invalid_request", "redirect_uri must not contain URI fragment.");
    }

    String normalized = redirectUri.normalize().toString();
    if (oauthProperties.enforceRedirectAllowlist() && !isAllowedRedirectUri(redirectUri, normalized)) {
      throw new OAuthValidationException(HttpStatus.BAD_REQUEST, "invalid_request", "redirect_uri is not allowed.");
    }
  }

  private boolean isAllowedRedirectUri(URI redirectUri, String normalizedUri) {
    if (allowedRedirectUris.contains(normalizedUri)) {
      return true;
    }
    if (!oauthProperties.allowLoopbackRedirectUris()) {
      return false;
    }
    return isAllowedLoopbackRedirectUri(redirectUri);
  }

  private boolean isAllowedLoopbackRedirectUri(URI redirectUri) {
    String scheme = stringValue(redirectUri.getScheme());
    String host = stringValue(redirectUri.getHost());
    String path = stringValue(redirectUri.getPath());
    if (!"http".equalsIgnoreCase(scheme)) {
      return false;
    }
    if (!("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host))) {
      return false;
    }
    return allowedLoopbackRedirectPaths.contains(path);
  }

  private void rejectUnknownParametersIfConfigured(
      Set<String> actualParameters,
      Set<String> allowedParameters,
      boolean rejectUnknownParameters,
      String requestType
  ) {
    if (!rejectUnknownParameters) {
      return;
    }
    Set<String> unknown = new HashSet<>(actualParameters);
    unknown.removeAll(allowedParameters);
    if (!unknown.isEmpty()) {
      throw new OAuthValidationException(
          HttpStatus.BAD_REQUEST,
          "invalid_request",
          "Unsupported " + requestType + " parameters: " + String.join(", ", unknown) + "."
      );
    }
  }

  private Set<String> normalizeRedirectUris(List<String> redirectUris) {
    if (redirectUris == null) {
      return Set.of();
    }
    return redirectUris.stream()
        .filter(StringUtils::hasText)
        .map(String::trim)
        .map(URI::create)
        .map(URI::normalize)
        .map(URI::toString)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  private Set<String> normalizeLoopbackPaths(List<String> paths) {
    if (paths == null) {
      return Set.of();
    }
    return paths.stream()
        .filter(StringUtils::hasText)
        .map(String::trim)
        .map(path -> path.startsWith("/") ? path : "/" + path)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  private Set<String> buildAllowedParameters(Set<String> requiredParameters, List<String> additionalParameters) {
    Set<String> result = new HashSet<>(requiredParameters);
    if (additionalParameters != null) {
      additionalParameters.stream()
          .filter(StringUtils::hasText)
          .map(String::trim)
          .forEach(result::add);
    }
    return Set.copyOf(result);
  }

  private void assertRedirectAllowlistConfiguration() {
    if (!oauthProperties.enforceRedirectAllowlist()) {
      return;
    }
    if (!allowedRedirectUris.isEmpty()) {
      return;
    }
    if (oauthProperties.allowLoopbackRedirectUris() && !allowedLoopbackRedirectPaths.isEmpty()) {
      return;
    }
    throw new IllegalStateException(
        "Configure mcp.oauth.allowed-redirect-uris or enable loopback redirects with allowed loopback paths."
    );
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

  private String sanitizeTokenRequest(MultiValueMap<String, String> form) {
    if (form == null) {
      return "{request=null}";
    }
    Map<String, Object> sanitized = new TreeMap<>();
    for (Map.Entry<String, List<String>> entry : form.entrySet()) {
      String key = entry.getKey();
      List<String> values = entry.getValue();
      int count = values == null ? 0 : values.size();
      if (count <= 1) {
        String value = values == null || values.isEmpty() ? "" : values.getFirst();
        sanitized.put(key, sanitizeParameterValue(key, value));
      } else {
        sanitized.put(
            key,
            Map.of(
                "count", count,
                "values", values.stream().map(value -> sanitizeParameterValue(key, value)).toList()
            )
        );
      }
    }
    return sanitized.toString();
  }

  private String sanitizeAuthorizeRequest(MultiValueMap<String, String> params) {
    if (params == null) {
      return "{request=null}";
    }
    Map<String, Object> sanitized = new TreeMap<>();
    for (Map.Entry<String, List<String>> entry : params.entrySet()) {
      String key = entry.getKey();
      List<String> values = entry.getValue();
      int count = values == null ? 0 : values.size();
      if (count <= 1) {
        String value = values == null || values.isEmpty() ? "" : values.getFirst();
        sanitized.put(key, sanitizeAuthorizeParameterValue(key, value));
      } else {
        sanitized.put(
            key,
            Map.of(
                "count", count,
                "values", values.stream().map(value -> sanitizeAuthorizeParameterValue(key, value)).toList()
            )
        );
      }
    }
    return sanitized.toString();
  }

  private String sanitizeAuthorizeParameterValue(String key, String value) {
    if (!StringUtils.hasText(value)) {
      return "<blank>";
    }
    return switch (key) {
      case "code_challenge", "state" -> "sha256:" + shortSha256(value) + " len:" + value.length();
      case "redirect_uri" -> redactUriQueryValue(value);
      default -> truncate(value, 180);
    };
  }

  private String sanitizeParameterValue(String key, String value) {
    if (!StringUtils.hasText(value)) {
      return "<blank>";
    }
    return switch (key) {
      case "code", "code_verifier", "refresh_token", "client_secret" -> "sha256:" + shortSha256(value) + " len:" + value.length();
      case "id_token", "access_token" -> "token(len=" + value.length() + ")";
      default -> truncate(value, 180);
    };
  }

  private String shortSha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(hash, 0, 6);
    } catch (Exception ex) {
      return "unavailable";
    }
  }

  private String truncate(String value, int maxLen) {
    if (value == null) {
      return "";
    }
    if (value.length() <= maxLen) {
      return value;
    }
    return value.substring(0, maxLen) + "...(truncated)";
  }

  private String redactUriQueryValue(String rawUri) {
    try {
      URI uri = URI.create(rawUri);
      String scheme = stringValue(uri.getScheme());
      String host = stringValue(uri.getHost());
      String path = stringValue(uri.getPath());
      int port = uri.getPort();
      if (!StringUtils.hasText(host)) {
        return truncate(rawUri, 180);
      }
      return port > 0
          ? scheme + "://" + host + ":" + port + path
          : scheme + "://" + host + path;
    } catch (Exception ex) {
      return truncate(rawUri, 180);
    }
  }

  private String escapeJson(String value) {
    return value == null ? "" : value.replace("\"", "\\\"");
  }

  private static final class OAuthValidationException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    private OAuthValidationException(HttpStatus status, String errorCode, String message) {
      super(message);
      this.status = status;
      this.errorCode = errorCode;
    }
  }
}
