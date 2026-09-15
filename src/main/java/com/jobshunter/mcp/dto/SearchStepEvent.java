package com.jobshunter.mcp.dto;

/**
 * One progress checkpoint reported by Jobshunter for an in-progress search.
 * Client-side mirror of Jobshunter's internal search step event.
 *
 * @param message human-readable progress message.
 */
public record SearchStepEvent(String message) {
}
