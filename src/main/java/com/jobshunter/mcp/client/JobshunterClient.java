package com.jobshunter.mcp.client;

import com.jobshunter.mcp.config.JobshunterProperties;
import com.jobshunter.mcp.dto.SearchConfiguration;
import com.jobshunter.mcp.dto.SearchJobsResponse;
import com.jobshunter.mcp.exception.JobshunterApiException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class JobshunterClient {
  private final RestClient restClient;
  private final String searchJobsPath;
  private final String baseUrl;

  public JobshunterClient(RestClient jobshunterRestClient, JobshunterProperties properties) {
    this.restClient = jobshunterRestClient;
    this.searchJobsPath = properties.searchJobsPath();
    this.baseUrl = properties.baseUrl();
  }

  public SearchJobsResponse searchJobs(List<SearchConfiguration> configurations, String userToken) {
    validateUserToken(userToken);

    try {
      SearchJobsResponse response = restClient.post()
          .uri(searchJobsPath)
          .contentType(MediaType.APPLICATION_JSON)
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
          .body(configurations)
          .retrieve()
          .body(SearchJobsResponse.class);

      if (response == null) {
        throw new JobshunterApiException("Jobshunter returned an empty response.");
      }
      return response;
    } catch (RestClientResponseException ex) {
      throw mapStatusCode(ex.getStatusCode().value(), ex);
    } catch (ResourceAccessException ex) {
      Throwable rootCause = rootCause(ex);
      if (isTimeout(ex)) {
        throw new JobshunterApiException("Job search timed out.", ex);
      }
      if (isLikelyProtocolMismatch(rootCause)) {
        throw new JobshunterApiException(
            "Jobshunter endpoint closed connection. Check JOBSHUNTER_BASE_URL protocol (https expected on port 8443/443).",
            ex);
      }
      throw new JobshunterApiException("Jobshunter endpoint is not reachable. "+ex.getMessage(), ex);
    }
  }

  private void validateUserToken(String userToken) {
    if (!StringUtils.hasText(userToken)) {
      throw new JobshunterApiException("Authenticated user token is missing.");
    }
  }

  private JobshunterApiException mapStatusCode(int statusCode, Exception ex) {
    return switch (statusCode) {
      case 400 -> new JobshunterApiException("Invalid search configuration.", ex);
      case 401 -> new JobshunterApiException("Jobshunter authentication failed.", ex);
      case 403 -> new JobshunterApiException("Jobshunter authorization failed.", ex);
      case 404 -> new JobshunterApiException("Jobshunter endpoint unavailable.", ex);
      default -> {
        if (statusCode >= 500) {
          yield new JobshunterApiException("Jobshunter returned an unexpected error.", ex);
        }
        yield new JobshunterApiException("Jobshunter request failed with status " + statusCode + ".", ex);
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
