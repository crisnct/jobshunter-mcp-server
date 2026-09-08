package com.jobshunter.mcp.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class UserProfileResponseTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void shouldMapJobSearchSafeFieldsOnlyFromUserInfo() {
    UserInfoResponse userInfo = new UserInfoResponse(
        "user@example.com",
        "user@example.com",
        "0700000000",
        false,
        true,
        true,
        "secret-verification-token",
        "cv.pdf",
        "2026-09-02T00:00:00Z",
        List.of("Looking for backend roles"),
        "2026-09-01T00:00:00Z",
        List.of("ADMIN"),
        "Cluj",
        "RO",
        "Software",
        List.of("Java Developer"),
        List.of("REMOTE"),
        "NO",
        List.of("FULL_TIME")
    );

    UserProfileResponse profile = UserProfileResponse.fromUserInfo(userInfo);

    assertThat(profile.username()).isEqualTo("user@example.com");
    assertThat(profile.email()).isEqualTo("user@example.com");
    assertThat(profile.notifyWhatsapp()).isFalse();
    assertThat(profile.notifyEmail()).isTrue();
    assertThat(profile.emailVerified()).isTrue();
    assertThat(profile.cvFilename()).isEqualTo("cv.pdf");
    assertThat(profile.notifiedAt()).isEqualTo("2026-09-02T00:00:00Z");
    assertThat(profile.createdAt()).isEqualTo("2026-09-01T00:00:00Z");
    assertThat(profile.city()).isEqualTo("Cluj");
    assertThat(profile.country()).isEqualTo("RO");
    assertThat(profile.jobDomain()).isEqualTo("Software");
    assertThat(profile.jobRoles()).containsExactly("Java Developer");
    assertThat(profile.jobTypes()).containsExactly("REMOTE");
    assertThat(profile.relocation()).isEqualTo("NO");
    assertThat(profile.contractTypes()).containsExactly("FULL_TIME");
  }

  @Test
  void shouldNeverSerializeSensitiveFields() throws Exception {
    UserInfoResponse userInfo = new UserInfoResponse(
        "user@example.com",
        "user@example.com",
        "0700000000",
        false,
        true,
        true,
        "secret-verification-token",
        "cv.pdf",
        null,
        List.of("Looking for backend roles"),
        "2026-09-01T00:00:00Z",
        List.of("ADMIN"),
        "Cluj",
        "RO",
        "Software",
        List.of("Java Developer"),
        List.of("REMOTE"),
        "NO",
        List.of("FULL_TIME")
    );

    String json = objectMapper.writeValueAsString(UserProfileResponse.fromUserInfo(userInfo));

    assertThat(json)
        .doesNotContain("verificationToken")
        .doesNotContain("secret-verification-token")
        .doesNotContain("phoneNumber")
        .doesNotContain("0700000000")
        .doesNotContain("roles")
        .doesNotContain("ADMIN")
        .doesNotContain("prompts")
        .doesNotContain("Looking for backend roles");
  }
}
