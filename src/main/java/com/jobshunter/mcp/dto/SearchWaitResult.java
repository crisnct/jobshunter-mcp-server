package com.jobshunter.mcp.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/**
 * Response payload returned by {@code wait_for_search}.
 *
 * @param status DONE, FAILED, or IN_PROGRESS.
 * @param searchId present for IN_PROGRESS and FAILED; pass it to the next wait_for_search call.
 * @param result populated only when status is DONE.
 * @param errorMessage populated only when status is FAILED.
 * @param progressEvents all progress messages recorded so far for this search, oldest first.
 */
public record SearchWaitResult(
    @JsonPropertyDescription("DONE when result is populated, FAILED when errorMessage explains what went wrong, "
        + "or IN_PROGRESS if the search is still running - call wait_for_search again with the same searchId.")
    Status status,
    @JsonPropertyDescription("The searchId to pass to another wait_for_search call. Present for IN_PROGRESS and FAILED.")
    String searchId,
    @JsonPropertyDescription("Deduplicated list of job results found by Jobshunter. Populated only when status is DONE.")
    SearchJobsResponse result,
    @JsonPropertyDescription("Populated only when status is FAILED.")
    String errorMessage,
    @JsonPropertyDescription("All progress messages recorded so far for this search, oldest first. "
        + "Present for DONE, FAILED, and IN_PROGRESS.")
    List<String> progressEvents
) {

  public enum Status {DONE, FAILED, IN_PROGRESS}

  public static SearchWaitResult done(SearchJobsResponse result, List<String> progressEvents) {
    return new SearchWaitResult(Status.DONE, null, result, null, progressEvents);
  }

  public static SearchWaitResult failed(String searchId, String errorMessage, List<String> progressEvents) {
    return new SearchWaitResult(Status.FAILED, searchId, null, errorMessage, progressEvents);
  }

  public static SearchWaitResult stillRunning(String searchId, List<String> progressEvents) {
    return new SearchWaitResult(Status.IN_PROGRESS, searchId, null, null, progressEvents);
  }
}
