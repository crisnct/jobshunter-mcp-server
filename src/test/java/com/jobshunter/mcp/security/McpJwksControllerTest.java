package com.jobshunter.mcp.security;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpJwksControllerTest {

  @LocalServerPort
  private int port;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void shouldExposeJwksEndpoint() throws Exception {
    RestClient restClient = RestClient.builder().baseUrl("http://localhost:" + port).build();
    String body = restClient.get()
        .uri("/.well-known/jwks.json")
        .retrieve()
        .body(String.class);

    Map<String, Object> jwks = objectMapper.readValue(body, new TypeReference<>() {});
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> keys = (List<Map<String, Object>>) jwks.get("keys");

    assertTrue(keys != null && !keys.isEmpty());
    Map<String, Object> firstKey = keys.getFirst();
    assertTrue("RSA".equals(firstKey.get("kty")));
    assertTrue(firstKey.get("kid") != null && !String.valueOf(firstKey.get("kid")).isBlank());
  }
}
