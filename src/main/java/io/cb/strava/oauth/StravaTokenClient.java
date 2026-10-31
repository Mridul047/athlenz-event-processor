package io.cb.strava.oauth;

/**
 * Client for Strava's OAuth token endpoint.
 *
 * <p>An interface so the processing logic can be unit-tested with a mock; the production
 * implementation is {@link HttpStravaTokenClient}, built on the JDK's {@link
 * java.net.http.HttpClient}.
 */
public interface StravaTokenClient {

  /**
   * Exchange a refresh token for a fresh access token.
   *
   * @param request the form parameters (client id/secret, grant type, refresh token)
   * @return the new tokens (access token, possibly-rotated refresh token, expiry)
   */
  StravaTokenResponse refresh(StravaTokenRefreshRequest request);
}
