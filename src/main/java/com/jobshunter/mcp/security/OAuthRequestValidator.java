package com.jobshunter.mcp.security;

import java.net.URI;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;

/**
 * Validates /authorize and /token requests against the OAuth broker's request contract:
 * required parameters, single-valuedness, supported grant/response types, and redirect_uri
 * allowlisting (including optional loopback redirects for native/desktop clients).
 */
@Component
class OAuthRequestValidator {

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
  private final Set<String> allowedRedirectUris;
  private final Set<String> allowedLoopbackRedirectPaths;
  private final Set<String> authorizeAllowedParameters;
  private final Set<String> tokenAllowedParameters;

  OAuthRequestValidator(McpOAuthProperties oauthProperties) {
    this.oauthProperties = oauthProperties;
    this.allowedRedirectUris = normalizeRedirectUris(oauthProperties.allowedRedirectUris());
    this.allowedLoopbackRedirectPaths = normalizeLoopbackPaths(oauthProperties.allowedLoopbackRedirectPaths());
    this.authorizeAllowedParameters =
        buildAllowedParameters(AUTHORIZE_ALLOWED_PARAMETERS, oauthProperties.additionalAuthorizeParameters());
    this.tokenAllowedParameters =
        buildAllowedParameters(TOKEN_REQUIRED_PARAMETERS, oauthProperties.additionalTokenParameters());
    assertRedirectAllowlistConfiguration();
  }

  void validateAuthorizeRequest(MultiValueMap<String, String> params) {
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

  void validateTokenRequest(MultiValueMap<String, String> form) {
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

  String requireSingleText(MultiValueMap<String, String> form, String key) {
    ensureSingleValue(form, key);
    return requireText(form, key);
  }

  private String firstValue(MultiValueMap<String, String> form, String key) {
    return stringValue(form.getFirst(key));
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

  void ensureSingleValue(MultiValueMap<String, String> form, String key) {
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
        .collect(Collectors.toUnmodifiableSet());
  }

  private Set<String> normalizeLoopbackPaths(List<String> paths) {
    if (paths == null) {
      return Set.of();
    }
    return paths.stream()
        .filter(StringUtils::hasText)
        .map(String::trim)
        .map(path -> path.startsWith("/") ? path : "/" + path)
        .collect(Collectors.toUnmodifiableSet());
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

  private String stringValue(Object value) {
    return value == null ? "" : String.valueOf(value);
  }
}
