package com.jobshunter.mcp.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/**
 * Job-search-safe subset of the Jobshunter user profile, returned by {@code get_user_info}.
 *
 * <p>Deliberately excludes {@code verificationToken}, {@code phoneNumber}, {@code roles}, and
 * {@code prompts} from {@link UserInfoResponse}: MCP tool responses are visible to the model and
 * often logged, so secrets and authorization-sensitive fields must not be forwarded here.
 *
 * @param username unique username in Jobshunter.
 * @param email primary user email.
 * @param notifyWhatsapp whether WhatsApp notifications are enabled.
 * @param notifyEmail whether email notifications are enabled.
 * @param emailVerified whether user email is verified.
 * @param cvFilename uploaded CV file name, if present.
 * @param notifiedAt ISO-8601 timestamp of latest notification.
 * @param createdAt ISO-8601 account creation timestamp.
 * @param city user profile city.
 * @param country user profile country.
 * @param jobDomain user profile job domain/category.
 * @param jobRoles preferred job role names.
 * @param jobTypes preferred job type values.
 * @param relocation relocation preference value.
 * @param contractTypes preferred contract type values.
 */
public record UserProfileResponse(
    @JsonPropertyDescription("Unique username in Jobshunter.")
    String username,
    @JsonPropertyDescription("Primary user email.")
    String email,
    @JsonPropertyDescription("Whether WhatsApp notifications are enabled.")
    boolean notifyWhatsapp,
    @JsonPropertyDescription("Whether email notifications are enabled.")
    boolean notifyEmail,
    @JsonPropertyDescription("Whether user email is verified.")
    boolean emailVerified,
    @JsonPropertyDescription("Uploaded CV file name, if present.")
    String cvFilename,
    @JsonPropertyDescription("ISO-8601 timestamp of latest notification.")
    String notifiedAt,
    @JsonPropertyDescription("ISO-8601 account creation timestamp.")
    String createdAt,
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

  public static UserProfileResponse fromUserInfo(UserInfoResponse userInfo) {
    return new UserProfileResponse(
        userInfo.username(),
        userInfo.email(),
        userInfo.notifyWhatsapp(),
        userInfo.notifyEmail(),
        userInfo.emailVerified(),
        userInfo.cvFilename(),
        userInfo.notifiedAt(),
        userInfo.createdAt(),
        userInfo.city(),
        userInfo.country(),
        userInfo.jobDomain(),
        userInfo.jobRoles(),
        userInfo.jobTypes(),
        userInfo.relocation(),
        userInfo.contractTypes()
    );
  }
}
