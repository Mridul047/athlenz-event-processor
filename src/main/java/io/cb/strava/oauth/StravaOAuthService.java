package io.cb.strava.oauth;

import java.time.Clock;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves a valid Strava access token for an athlete, refreshing it when it has expired (or is
 * about to).
 *
 * <p>Flow:
 *
 * <ol>
 *   <li>Load the athlete's stored tokens from DynamoDB.
 *   <li>If the access token is still valid (with a safety buffer), return it.
 *   <li>Otherwise call Strava's token endpoint with the refresh token, persist the rotated tokens,
 *       and return the new access token.
 * </ol>
 *
 * <p>Strava rotates refresh tokens, so the refresh response's refresh token is always written back.
 * Token values are never logged.
 */
public class StravaOAuthService {

  private static final Logger LOG = LoggerFactory.getLogger(StravaOAuthService.class);

  /**
   * Refresh slightly ahead of the real expiry so an in-flight API call does not fail with an
   * expired token due to clock skew or request latency.
   */
  private static final long EXPIRY_BUFFER_SECONDS = 60;

  private final AthleteTokenStore tokenStore;
  private final ClientCredentialsProvider credentialsProvider;
  private final StravaTokenClient tokenClient;
  private final Clock clock;

  public StravaOAuthService(
      AthleteTokenStore tokenStore,
      ClientCredentialsProvider credentialsProvider,
      StravaTokenClient tokenClient) {
    this(tokenStore, credentialsProvider, tokenClient, Clock.systemUTC());
  }

  /** Package-visible constructor allowing a fixed clock in tests. */
  StravaOAuthService(
      AthleteTokenStore tokenStore,
      ClientCredentialsProvider credentialsProvider,
      StravaTokenClient tokenClient,
      Clock clock) {
    this.tokenStore = tokenStore;
    this.credentialsProvider = credentialsProvider;
    this.tokenClient = tokenClient;
    this.clock = clock;
  }

  /**
   * @param athleteId the Strava athlete (owner) id
   * @return a currently-valid access token for that athlete
   * @throws NoSuchElementException if we have no stored tokens for the athlete (they have not
   *     completed OAuth authorization)
   */
  public String getValidAccessToken(long athleteId) {
    AthleteToken token =
        tokenStore
            .findByAthleteId(athleteId)
            .orElseThrow(
                () ->
                    new NoSuchElementException("No stored Strava tokens for athlete " + athleteId));

    if (!isExpired(token)) {
      LOG.info(
          "Using stored access token for athlete {} (expiresAt={})",
          athleteId,
          token.getExpiresAt());
      return token.getAccessToken();
    }

    LOG.info("Access token for athlete {} expired or near expiry; refreshing", athleteId);
    return refreshAndStore(token);
  }

  private boolean isExpired(AthleteToken token) {
    long nowEpochSeconds = clock.instant().getEpochSecond();
    return token.getExpiresAt() <= nowEpochSeconds + EXPIRY_BUFFER_SECONDS;
  }

  private String refreshAndStore(AthleteToken token) {
    StravaClientCredentials creds = credentialsProvider.get();
    StravaTokenResponse response =
        tokenClient.refresh(
            StravaTokenRefreshRequest.forRefresh(
                creds.clientId(), creds.clientSecret(), token.getRefreshToken()));

    token.setAccessToken(response.accessToken());
    // Strava rotates the refresh token; always persist the latest.
    token.setRefreshToken(response.refreshToken());
    token.setExpiresAt(response.expiresAt() != null ? response.expiresAt() : 0L);
    tokenStore.save(token);

    LOG.info("Refreshed and stored tokens for athlete {}", token.getAthleteId());
    return token.getAccessToken();
  }
}
