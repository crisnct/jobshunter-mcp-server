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

  public JobshunterClient(RestClient jobshunterRestClient, JobshunterProperties properties) {
    this.restClient = jobshunterRestClient;
    this.searchJobsPath = properties.searchJobsPath();
  }

  public SearchJobsResponse searchJobs(List<SearchConfiguration> configurations, String exchangedToken) {
    validateDelegatedToken(exchangedToken);

    try {
      SearchJobsResponse response = restClient.post()
          .uri(searchJobsPath)
          .contentType(MediaType.APPLICATION_JSON)
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + exchangedToken)
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
      if (isTimeout(ex)) {
        throw new JobshunterApiException("Job search timed out.", ex);
      }
      throw new JobshunterApiException("Jobshunter endpoint is not reachable.", ex);
    }
  }

  private void validateDelegatedToken(String exchangedToken) {
    if (!StringUtils.hasText(exchangedToken)) {
      throw new JobshunterApiException("Delegated Jobshunter token is missing.");
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
}
