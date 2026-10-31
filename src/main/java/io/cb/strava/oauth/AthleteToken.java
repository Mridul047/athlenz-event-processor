package io.cb.strava.oauth;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

/**
 * Per-athlete OAuth tokens, persisted in DynamoDB.
 *
 * <p>Keyed by the Strava athlete (owner) id. Stores the current access token, the rotating refresh
 * token, and the access token's absolute expiry (epoch seconds) so the service can decide whether a
 * refresh is needed before calling the Strava API.
 *
 * <p>This is a mutable JavaBean because the DynamoDB Enhanced client's default {@code
 * BeanTableSchema} maps via getters/setters. The item is a secret at rest; the table should have
 * encryption enabled (default for new tables) and least-privilege access.
 */
@DynamoDbBean
public class AthleteToken {

  private Long athleteId;
  private String accessToken;
  private String refreshToken;
  private long expiresAt;

  @DynamoDbPartitionKey
  public Long getAthleteId() {
    return athleteId;
  }

  public void setAthleteId(Long athleteId) {
    this.athleteId = athleteId;
  }

  public String getAccessToken() {
    return accessToken;
  }

  public void setAccessToken(String accessToken) {
    this.accessToken = accessToken;
  }

  public String getRefreshToken() {
    return refreshToken;
  }

  public void setRefreshToken(String refreshToken) {
    this.refreshToken = refreshToken;
  }

  /** Absolute expiry of the access token, in epoch seconds. */
  public long getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(long expiresAt) {
    this.expiresAt = expiresAt;
  }
}
