package com.jobshunter.mcp.client;

import com.jobshunter.mcp.config.JobshunterProperties;
import com.jobshunter.mcp.dto.SearchConfiguration;
import com.jobshunter.mcp.dto.SearchJobSnapshot;
import com.jobshunter.mcp.dto.SearchJobsHandle;
import com.jobshunter.mcp.dto.UserInfoResponse;
import com.jobshunter.mcp.exception.ErrorCode;
import com.jobshunter.mcp.exception.JobshunterApiException;
import com.jobshunter.mcp.logging.RequestContext;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Slf4j
@Component
public class JobshunterClient {
  private final RestClient restClient;
  private final String searchJobsPath;
  private final String searchJobStatusPath;
  private final String userInfoPath;
  private final String baseUrl;

  public JobshunterClient(RestClient jobshunterRestClient, JobshunterProperties properties) {
    this.restClient = jobshunterRestClient;
    this.searchJobsPath = properties.searchJobsPath();
    this.searchJobStatusPath = properties.searchJobStatusPath();
    this.userInfoPath = properties.userInfoPath();
    this.baseUrl = properties.baseUrl();
  }

  /**
   * Registers a search with Jobshunter and returns immediately with a handle; the search
   * itself keeps running server-side and is tracked via {@link #getSearchSnapshot}.
   */
  public SearchJobsHandle startSearch(List<SearchConfiguration> configurations, String userToken) {
    validateUserToken(userToken);
    log.debug("Calling Jobshunter start search: path={}, configurations={}", searchJobsPath, configurations.size());

    return execute("Search start", () -> {
      StartSearchResponse response = restClient.post()
          .uri(searchJobsPath)
          .contentType(MediaType.APPLICATION_JSON)
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
          .headers(this::forwardRequestId)
          .body(configurations)
          .retrieve()
          .body(StartSearchResponse.class);

      if (response == null || !StringUtils.hasText(response.searchId())) {
        throw new JobshunterApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "Jobshunter did not return a searchId.");
      }
      return new SearchJobsHandle(response.searchId(), configurations.size());
    });
  }

  /**
   * Fetches the current status snapshot for a search started with {@link #startSearch}.
   */
  public SearchJobSnapshot getSearchSnapshot(String searchId, String userToken) {
    validateUserToken(userToken);
    log.debug("Calling Jobshunter search snapshot: searchId={}", searchId);

    return execute("Search snapshot", () -> {
      SearchJobSnapshot response = restClient.get()
          .uri(searchJobStatusPath, searchId)
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
          .headers(this::forwardRequestId)
          .retrieve()
          .body(SearchJobSnapshot.class);

      if (response == null) {
        throw new JobshunterApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "Jobshunter returned an empty search snapshot.");
      }
      return response;
    });
  }

  private record StartSearchResponse(String searchId) {
  }

  public UserInfoResponse getUserInfo(String userToken) {
    validateUserToken(userToken);
    log.debug("Calling Jobshunter user info: path={}", userInfoPath);

    return execute("User info request", () -> {
      UserInfoResponse response = restClient.get()
          .uri(userInfoPath)
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
          .headers(this::forwardRequestId)
          .retrieve()
          .body(UserInfoResponse.class);
      if (response == null) {
        throw new JobshunterApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "Jobshunter returned an empty user response.");
      }
      return response;
    });
  }

  private void forwardRequestId(HttpHeaders headers) {
    String requestId = RequestContext.currentRequestId();
    if (StringUtils.hasText(requestId)) {
      headers.set(RequestContext.REQUEST_ID_HEADER, requestId);
    }
  }

  private <T> T execute(String operationLabel, Supplier<T> call) {
    try {
      return call.get();
    } catch (RestClientResponseException ex) {
      log.warn("{} failed: requestId={}, status={}, message={}",
          operationLabel, RequestContext.currentRequestId(), ex.getStatusCode().value(), ex.getMessage());
      throw mapStatusCode(ex.getStatusCode().value(), ex);
    } catch (ResourceAccessException ex) {
      Throwable rootCause = rootCause(ex);
      if (isTimeout(ex)) {
        log.warn("{} timed out: requestId={}, message={}", operationLabel, RequestContext.currentRequestId(), ex.getMessage());
        throw new JobshunterApiException(ErrorCode.TIMEOUT, operationLabel + " timed out.", ex);
      }
      if (isLikelyProtocolMismatch(rootCause)) {
        log.warn("{} failed due to likely protocol mismatch: requestId={}, message={}",
            operationLabel, RequestContext.currentRequestId(), ex.getMessage());
        throw new JobshunterApiException(
            ErrorCode.UPSTREAM_UNAVAILABLE,
            "Jobshunter endpoint closed connection. Check JOBSHUNTER_BASE_URL protocol (https expected on port 8443/443).",
            ex);
      }
      log.warn("{} failed, Jobshunter endpoint unreachable: requestId={}, message={}",
          operationLabel, RequestContext.currentRequestId(), ex.getMessage());
      throw new JobshunterApiException(
          ErrorCode.UPSTREAM_UNAVAILABLE, "Jobshunter endpoint is not reachable. " + ex.getMessage(), ex);
    }
  }

  private void validateUserToken(String userToken) {
    if (!StringUtils.hasText(userToken)) {
      throw new JobshunterApiException(ErrorCode.AUTH_FAILED, "Authenticated user token is missing.");
    }
  }

  private JobshunterApiException mapStatusCode(int statusCode, Exception ex) {
    return switch (statusCode) {
      case 400 -> new JobshunterApiException(ErrorCode.VALIDATION, "Invalid search configuration.", ex);
      case 401 -> new JobshunterApiException(ErrorCode.AUTH_FAILED, "Jobshunter authentication failed.", ex);
      case 403 -> new JobshunterApiException(ErrorCode.AUTH_FAILED, "Jobshunter authorization failed.", ex);
      case 404 -> new JobshunterApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "Jobshunter endpoint unavailable.", ex);
      default -> {
        if (statusCode >= 500) {
          yield new JobshunterApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "Jobshunter returned an unexpected error.", ex);
        }
        yield new JobshunterApiException(
            ErrorCode.VALIDATION, "Jobshunter request failed with status " + statusCode + ".", ex);
      }
    };
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

  private Throwable rootCause(Throwable throwable) {
    Throwable current = throwable;
    Throwable root = throwable;
    while (current != null) {
      root = current;
      current = current.getCause();
    }
    return root;
  }

  private boolean isLikelyProtocolMismatch(Throwable rootCause) {
    if (rootCause == null) {
      return false;
    }
    if (!baseUrl.startsWith("http://")) {
      return false;
    }
    return rootCause instanceof java.nio.channels.ClosedChannelException
        && (baseUrl.contains(":8443") || baseUrl.contains(":443"));
  }
}
