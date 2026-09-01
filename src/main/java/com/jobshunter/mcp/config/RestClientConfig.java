package com.jobshunter.mcp.config;

import java.io.InputStream;
import java.security.KeyStore;
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

  private ClientHttpRequestFactory jobshunterRequestFactory(JobshunterProperties properties) {
    java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
        .connectTimeout(properties.connectTimeout())
        .sslContext(buildSslContext(properties))
        .build();

    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(properties.responseTimeout());
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
