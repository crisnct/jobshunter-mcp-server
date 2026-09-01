package com.jobshunter.mcp.client;

import com.jobshunter.mcp.config.TokenExchangeProperties;
import com.jobshunter.mcp.exception.JobshunterApiException;
import java.net.http.HttpTimeoutException;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class TokenExchangeClient {

  private static final long EXPIRY_SKEW_SECONDS = 30;

  private final RestClient restClient;
  private final TokenExchangeProperties properties;
  private final ConcurrentHashMap<String, CachedToken> tokenCache;

  public TokenExchangeClient(TokenExchangeProperties properties) {
    java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
        .connectTimeout(properties.timeout())
        .build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(properties.timeout());

    this.restClient = RestClient.builder()
        .requestFactory(requestFactory)
        .build();
    this.properties = properties;
    this.tokenCache = new ConcurrentHashMap<>();
  }

  public String exchangeToken(String subjectToken) {
    if (!StringUtils.hasText(subjectToken)) {
      throw new JobshunterApiException("Missing authenticated user token for delegated authentication.");
    }

    String cacheKey = buildCacheKey(subjectToken);
    CachedToken cachedToken = tokenCache.get(cacheKey);
    if (cachedToken != null && !cachedToken.isExpired()) {
      return cachedToken.tokenValue();
    }

    TokenExchangeResponse response;
    try {
      response = restClient.post()
          .uri(properties.stsUrl())
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body(buildExchangeForm(subjectToken))
          .retrieve()
          .body(TokenExchangeResponse.class);
    } catch (RestClientResponseException ex) {
      throw new JobshunterApiException("Token exchange with Google STS failed.", ex);
    } catch (ResourceAccessException ex) {
      if (isTimeout(ex)) {
        throw new JobshunterApiException("Token exchange request timed out.", ex);
      }
      throw new JobshunterApiException("Token exchange endpoint is not reachable.", ex);
    }

    if (response == null || !StringUtils.hasText(response.accessToken())) {
      throw new JobshunterApiException("Google STS returned an invalid token exchange response.");
    }

    Instant expiresAt = Instant.now().plusSeconds(Math.max(1L, response.expiresIn() - EXPIRY_SKEW_SECONDS));
    tokenCache.put(cacheKey, new CachedToken(response.accessToken(), expiresAt));
    return response.accessToken();
  }

  private MultiValueMap<String, String> buildExchangeForm(String subjectToken) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", properties.grantType());
    form.add("subject_token", subjectToken);
    form.add("subject_token_type", properties.subjectTokenType());
    form.add("requested_token_type", properties.requestedTokenType());
    form.add("audience", properties.audience());
    form.add("scope", String.join(" ", properties.scopes()));
    return form;
  }

  private String buildCacheKey(String subjectToken) {
    return subjectToken + "|" + properties.audience() + "|" + String.join(",", properties.scopes());
  }

  private boolean isTimeout(Throwable throwable) {
    Throwable current = throwable;
    while (current != null) {
      if (current instanceof HttpTimeoutException || current instanceof java.net.SocketTimeoutException) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  @SuppressWarnings("unused")
  private record TokenExchangeResponse(
      String access_token,
      Long expires_in,
      String token_type,
      String issued_token_type,
      String scope
  ) {
    String accessToken() {
      return access_token;
    }

    long expiresIn() {
      return expires_in == null ? 60L : expires_in;
    }
  }

  private record CachedToken(String tokenValue, Instant expiresAt) {
    boolean isExpired() {
      return Instant.now().isAfter(expiresAt);
    }
  }
}
