package com.jobshunter.mcp.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.jobshunter.mcp.config.TokenExchangeProperties;
import com.jobshunter.mcp.exception.JobshunterApiException;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TokenExchangeClientTest {

  private static MockWebServer mockStsServer;

  @BeforeAll
  static void startServer() throws IOException {
    mockStsServer = new MockWebServer();
    mockStsServer.start();
  }

  @AfterAll
  static void stopServer() throws IOException {
    mockStsServer.shutdown();
  }

  @Test
  void shouldExchangeTokenAndCacheBySubjectAudienceScope() throws Exception {
    mockStsServer.enqueue(new MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""
            {"access_token":"delegated-token-1","expires_in":300,"token_type":"Bearer"}
            """));

    String stsUrl = mockStsServer.url("/v1/token").toString();
    TokenExchangeProperties properties = new TokenExchangeProperties(
        stsUrl,
        "jobshunter-internal",
        List.of("search_jobs.execute"),
        Duration.ofSeconds(10),
        "urn:ietf:params:oauth:grant-type:token-exchange",
        "urn:ietf:params:oauth:token-type:access_token",
        "urn:ietf:params:oauth:token-type:access_token"
    );

    TokenExchangeClient client = new TokenExchangeClient(properties);
    String exchangedTokenFirst = client.exchangeToken("google-user-token");
    String exchangedTokenSecond = client.exchangeToken("google-user-token");

    assertEquals("delegated-token-1", exchangedTokenFirst);
    assertEquals("delegated-token-1", exchangedTokenSecond);
    assertEquals(1, mockStsServer.getRequestCount());

    RecordedRequest request = mockStsServer.takeRequest();
    String body = request.getBody().readUtf8();
    assertEquals("POST", request.getMethod());
    org.junit.jupiter.api.Assertions.assertTrue(body.contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Atoken-exchange"));
    org.junit.jupiter.api.Assertions.assertTrue(body.contains("subject_token=google-user-token"));
    org.junit.jupiter.api.Assertions.assertTrue(body.contains("audience=jobshunter-internal"));
    org.junit.jupiter.api.Assertions.assertTrue(body.contains("scope=search_jobs.execute"));
  }

  @Test
  void shouldFailWhenSubjectTokenIsMissing() {
    TokenExchangeProperties properties = new TokenExchangeProperties(
        "https://sts.googleapis.com/v1/token",
        "jobshunter-internal",
        List.of("search_jobs.execute"),
        Duration.ofSeconds(10),
        "urn:ietf:params:oauth:grant-type:token-exchange",
        "urn:ietf:params:oauth:token-type:access_token",
        "urn:ietf:params:oauth:token-type:access_token"
    );

    TokenExchangeClient client = new TokenExchangeClient(properties);
    JobshunterApiException ex = assertThrows(JobshunterApiException.class, () -> client.exchangeToken(""));
    assertEquals("Missing authenticated user token for delegated authentication.", ex.getMessage());
  }
}
