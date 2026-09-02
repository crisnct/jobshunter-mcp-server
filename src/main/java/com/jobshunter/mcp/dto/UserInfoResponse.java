package com.jobshunter.mcp.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/**
 * User profile payload returned by {@code get_user_info}.
 *
 * @param username unique username in Jobshunter.
 * @param email primary user email.
 * @param phoneNumber phone number from user profile.
 * @param notifyWhatsapp whether WhatsApp notifications are enabled.
 * @param notifyEmail whether email notifications are enabled.
 * @param emailVerified whether user email is verified.
 * @param verificationToken current verification token, if any.
 * @param cvFilename uploaded CV file name, if present.
 * @param notifiedAt ISO-8601 timestamp of latest notification.
 * @param prompts user prompts/preferences used by search.
 * @param createdAt ISO-8601 account creation timestamp.
 * @param roles security roles assigned to the user.
 * @param city user profile city.
 * @param country user profile country.
 * @param jobDomain user profile job domain/category.
 * @param jobRoles preferred job role names.
 * @param jobTypes preferred job type values.
 * @param relocation relocation preference value.
 * @param contractTypes preferred contract type values.
 */
public record UserInfoResponse(
    @JsonPropertyDescription("Unique username in Jobshunter.")
    String username,
    @JsonPropertyDescription("Primary user email.")
    String email,
    @JsonPropertyDescription("Phone number from user profile.")
    String phoneNumber,
    @JsonPropertyDescription("Whether WhatsApp notifications are enabled.")
    boolean notifyWhatsapp,
    @JsonPropertyDescription("Whether email notifications are enabled.")
    boolean notifyEmail,
    @JsonPropertyDescription("Whether user email is verified.")
    boolean emailVerified,
    @JsonPropertyDescription("Current verification token, if any.")
    String verificationToken,
    @JsonPropertyDescription("Uploaded CV file name, if present.")
    String cvFilename,
    @JsonPropertyDescription("ISO-8601 timestamp of latest notification.")
    String notifiedAt,
    @JsonPropertyDescription("List of user prompts/preferences used by search.")
    List<String> prompts,
    @JsonPropertyDescription("ISO-8601 account creation timestamp.")
    String createdAt,
    @JsonPropertyDescription("Security roles assigned to the user.")
    List<String> roles,
    @JsonPropertyDescription("User profile city.")
    String city,
    @JsonPropertyDescription("User profile country.")
    String country,
    @JsonPropertyDescription("User profile job domain/category.")
    String jobDomain,
    @JsonPropertyDescription("Preferred job role names.")
    List<String> jobRoles,
    @JsonPropertyDescription("Preferred job type values.")
    List<String> jobTypes,
    @JsonPropertyDescription("Relocation preference value.")
    String relocation,
    @JsonPropertyDescription("Preferred contract type values.")
    List<String> contractTypes
) {
}
