package com.jobshunter.mcp.dto;

import java.util.List;
import java.util.Set;

/**
 * Client-side mirror of Jobshunter's internal search job status snapshot, returned by the
 * {@code GET} search-status endpoint that {@code wait_for_search} polls.
 *
 * @param searchId the search this snapshot belongs to.
 * @param status one of {@code "IN_PROGRESS"}, {@code "DONE"}, {@code "FAILED"}.
 * @param events progress messages recorded so far, oldest first.
 * @param result populated only when {@code status} is {@code "DONE"}.
 * @param errorMessage populated only when {@code status} is {@code "FAILED"}.
 */
public record SearchJobSnapshot(
    String searchId,
    String status,
    List<SearchStepEvent> events,
    SearchJobsResponse result,
    String errorMessage
) {
  public boolean done() {
    return "DONE".equals(status) || "FAILED".equals(status);
  }

  public List<SearchStepEvent> newEventsSince(Set<String> seenMessages) {
    if (events == null || events.isEmpty()) {
      return List.of();
    }
    return events.stream()
        .filter(event -> event != null && event.message() != null && !seenMessages.contains(event.message()))
        .toList();
  }
}
