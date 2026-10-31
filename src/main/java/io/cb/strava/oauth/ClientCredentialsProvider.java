package io.cb.strava.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

/**
 * Supplies the Strava client credentials, read from AWS Secrets Manager.
 *
 * <p>The secret is fetched lazily on first use and cached for the lifetime of the execution
 * environment. Caching avoids a Secrets Manager call on every invocation (lower latency and cost)
 * while still picking up a rotated secret whenever Lambda starts a fresh environment.
 *
 * <p>Credential values are never logged.
 */
public class ClientCredentialsProvider {

  private static final Logger LOG = LoggerFactory.getLogger(ClientCredentialsProvider.class);

  private final SecretsManagerClient secretsManagerClient;
  private final ObjectMapper objectMapper;
  private final String secretId;
  private final AtomicReference<StravaClientCredentials> cache = new AtomicReference<>();

  public ClientCredentialsProvider(
      SecretsManagerClient secretsManagerClient, ObjectMapper objectMapper, String secretId) {
    this.secretsManagerClient = secretsManagerClient;
    this.objectMapper = objectMapper;
    this.secretId = secretId;
  }

  public StravaClientCredentials get() {
    StravaClientCredentials cached = cache.get();
    if (cached != null) {
      return cached;
    }
    StravaClientCredentials loaded = load();
    // If two invocations race, either result is equally valid.
    cache.compareAndSet(null, loaded);
    return cache.get();
  }

  private StravaClientCredentials load() {
    String secretJson =
        secretsManagerClient
            .getSecretValue(GetSecretValueRequest.builder().secretId(secretId).build())
            .secretString();
    try {
      StravaClientCredentials creds =
          objectMapper.readValue(secretJson, StravaClientCredentials.class);
      LOG.info("Loaded Strava client credentials from secret {}", secretId);
      return creds;
    } catch (IOException e) {
      // Do not include the secret payload in the message.
      LOG.error("Failed to parse Strava client credentials from secret {}", secretId, e);
      throw new IllegalStateException(
          "Failed to parse Strava client credentials from secret " + secretId, e);
    }
  }
}
