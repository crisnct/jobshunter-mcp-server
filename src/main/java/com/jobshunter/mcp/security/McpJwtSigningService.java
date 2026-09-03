package com.jobshunter.mcp.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.BadJWTException;
import com.jobshunter.mcp.exception.JobshunterApiException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class McpJwtSigningService {
  private final McpAuthorizationServerProperties properties;
  private final RSAKey rsaKey;
  private final RSASSASigner signer;

  public McpJwtSigningService(McpAuthorizationServerProperties properties) {
    this.properties = properties;
    this.rsaKey = loadRsaKey(properties);
    try {
      this.signer = new RSASSASigner(this.rsaKey.toPrivateKey());
    } catch (JOSEException ex) {
      throw new IllegalStateException("Failed to initialize JWT signer for MCP authorization server.", ex);
    }
  }

  public String signToken(String subject, String audience, Instant expiresAt, Map<String, Object> extraClaims) {
    Instant now = Instant.now();
    JWTClaimsSet.Builder claimsBuilder = new JWTClaimsSet.Builder()
        .jwtID(UUID.randomUUID().toString())
        .issuer(properties.issuer())
        .subject(subject)
        .audience(audience)
        .issueTime(Date.from(now))
        .notBeforeTime(Date.from(now))
        .expirationTime(Date.from(expiresAt));

    extraClaims.forEach(claimsBuilder::claim);

    SignedJWT signedJwt = new SignedJWT(
        new JWSHeader.Builder(JWSAlgorithm.RS256)
            .type(JOSEObjectType.JWT)
            .keyID(properties.keyId())
            .build(),
        claimsBuilder.build()
    );

    try {
      signedJwt.sign(signer);
      return signedJwt.serialize();
    } catch (JOSEException ex) {
      throw new JobshunterApiException("Failed to issue MCP JWT token.", ex);
    }
  }

  public RSAPublicKey publicKey() {
    try {
      return rsaKey.toRSAPublicKey();
    } catch (JOSEException ex) {
      throw new IllegalStateException("Failed to load MCP authorization server public key.", ex);
    }
  }

  public Map<String, Object> jwks() {
    return new JWKSet(rsaKey.toPublicJWK()).toJSONObject();
  }

  private RSAKey loadRsaKey(McpAuthorizationServerProperties properties) {
    if (StringUtils.hasText(properties.signingKeyPem())) {
      return parseRsaKeyFromPem(properties);
    }
    return generateEphemeralRsaKey(properties.keyId());
  }

  private RSAKey parseRsaKeyFromPem(McpAuthorizationServerProperties properties) {
    try {
      String pem = properties.signingKeyPem()
          .replace("-----BEGIN PRIVATE KEY-----", "")
          .replace("-----END PRIVATE KEY-----", "")
          .replaceAll("\\s", "");
      byte[] keyBytes = Base64.getDecoder().decode(pem);
      PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
      if (!(privateKey instanceof RSAPrivateKey rsaPrivateKey)) {
        throw new BadJWTException("Provided MCP signing key is not RSA.");
      }

      if (!(rsaPrivateKey instanceof RSAPrivateCrtKey rsaPrivateCrtKey)) {
        throw new BadJWTException("Provided MCP signing key does not expose CRT parameters.");
      }

      RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(
          new java.security.spec.RSAPublicKeySpec(rsaPrivateCrtKey.getModulus(), rsaPrivateCrtKey.getPublicExponent()));
      return new RSAKey.Builder(publicKey)
          .privateKey(rsaPrivateKey)
          .keyID(properties.keyId())
          .build();
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to parse MCP authorization server signing key.", ex);
    }
  }

  private RSAKey generateEphemeralRsaKey(String keyId) {
    try {
      KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
      keyPairGenerator.initialize(2048);
      KeyPair keyPair = keyPairGenerator.generateKeyPair();
      return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
          .privateKey((RSAPrivateKey) keyPair.getPrivate())
          .keyID(keyId)
          .build();
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to generate MCP ephemeral signing key.", ex);
    }
  }
}
