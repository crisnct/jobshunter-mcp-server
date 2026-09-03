package com.jobshunter.mcp.security;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpJwksControllerTest {

  @LocalServerPort
  private int port;

  @Test
  void shouldExposeJwksEndpoint() {
    RestClient restClient = RestClient.builder().baseUrl("http://localhost:" + port).build();
    String body = restClient.get()
        .uri("/.well-known/jwks.json")
        .retrieve()
        .body(String.class);

    assertTrue(body.contains("\"keys\""));
    assertTrue(body.contains("\"kty\":\"RSA\""));
    assertTrue(body.contains("\"kid\":\"mcp-key-1\""));
  }
}
