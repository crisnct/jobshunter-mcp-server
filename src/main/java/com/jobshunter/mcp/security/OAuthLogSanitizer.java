package com.jobshunter.mcp.security;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiFunction;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;

/**
 * Redacts sensitive OAuth request/response values (authorization codes, verifiers, tokens,
 * client secrets) before they are logged, while keeping enough shape - lengths, hashes,
 * non-sensitive fields - to debug requests from log output alone.
 */
@Component
class OAuthLogSanitizer {

  String sanitizeAuthorizeRequest(MultiValueMap<String, String> params) {
    return sanitize(params, this::sanitizeAuthorizeParameterValue);
  }

  String sanitizeTokenRequest(MultiValueMap<String, String> form) {
    return sanitize(form, this::sanitizeTokenParameterValue);
  }

  private String sanitize(MultiValueMap<String, String> parameters, BiFunction<String, String, String> valueSanitizer) {
    if (parameters == null) {
      return "{request=null}";
    }
    Map<String, Object> sanitized = new TreeMap<>();
    for (Map.Entry<String, List<String>> entry : parameters.entrySet()) {
      String key = entry.getKey();
      List<String> values = entry.getValue();
      int count = values == null ? 0 : values.size();
      if (count <= 1) {
        String value = values == null || values.isEmpty() ? "" : values.getFirst();
        sanitized.put(key, valueSanitizer.apply(key, value));
      } else {
        sanitized.put(
            key,
            Map.of(
                "count", count,
                "values", values.stream().map(value -> valueSanitizer.apply(key, value)).toList()
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

  private String sanitizeTokenParameterValue(String key, String value) {
    if (!StringUtils.hasText(value)) {
      return "<blank>";
    }
    return switch (key) {
      case "code", "code_verifier", "refresh_token", "client_secret" -> "sha256:" + shortSha256(value) + " len:" + value.length();
      case "id_token", "access_token" -> "token(len=" + value.length() + ")";
      default -> truncate(value, 180);
    };
  }

  String redactUriQueryValue(String rawUri) {
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

  String truncate(String value, int maxLen) {
    if (value == null) {
      return "";
    }
    if (value.length() <= maxLen) {
      return value;
    }
    return value.substring(0, maxLen) + "...(truncated)";
  }

  private String shortSha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash, 0, 6);
    } catch (Exception ex) {
      return "unavailable";
    }
  }

  private String stringValue(Object value) {
    return value == null ? "" : String.valueOf(value);
  }
}
