package com.jobshunter.mcp.config;

import com.jobshunter.mcp.security.McpOAuthProperties;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.security.KeyStore;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {
  @Bean
  RestClient jobshunterRestClient(JobshunterProperties properties) {
    return RestClient.builder()
        .baseUrl(properties.baseUrl())
        .requestFactory(jobshunterRequestFactory(properties))
        .build();
  }

  @Bean
  RestClient googleRestClient(McpOAuthProperties properties) {
    return RestClient.builder()
        .requestFactory(timeoutRequestFactory(
            properties.connectTimeout(), properties.responseTimeout(), defaultSslContext()))
        .build();
  }

  private ClientHttpRequestFactory jobshunterRequestFactory(JobshunterProperties properties) {
    return timeoutRequestFactory(
        properties.connectTimeout(), properties.responseTimeout(), buildSslContext(properties));
  }

  private ClientHttpRequestFactory timeoutRequestFactory(
      Duration connectTimeout, Duration responseTimeout, SSLContext sslContext) {
    HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .sslContext(sslContext)
        .build();

    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(responseTimeout);
    return requestFactory;
  }

  private SSLContext buildSslContext(JobshunterProperties properties) {
    JobshunterProperties.Ssl ssl = properties.ssl();
    if (ssl == null || ssl.trustStore() == null) {
      return defaultSslContext();
    }

    if (!StringUtils.hasText(ssl.trustStorePassword())) {
      throw new IllegalStateException("jobshunter.ssl.trust-store-password is required when trust-store is configured");
    }

    String trustStoreType = StringUtils.hasText(ssl.trustStoreType()) ? ssl.trustStoreType() : "PKCS12";
    try (InputStream inputStream = ssl.trustStore().getInputStream()) {
      KeyStore trustStore = KeyStore.getInstance(trustStoreType);
      trustStore.load(inputStream, ssl.trustStorePassword().toCharArray());

      javax.net.ssl.TrustManagerFactory trustManagerFactory =
          javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
      trustManagerFactory.init(trustStore);

      SSLContext sslContext = SSLContext.getInstance("TLS");
      sslContext.init(null, trustManagerFactory.getTrustManagers(), null);
      return sslContext;
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to initialize Jobshunter SSL trust store", ex);
    }
  }

  private SSLContext defaultSslContext() {
    try {
      return SSLContext.getDefault();
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to load default SSL context", ex);
    }
  }
}
