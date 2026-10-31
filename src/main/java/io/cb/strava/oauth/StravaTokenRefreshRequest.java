package io.cb.strava.oauth;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Form parameters for a Strava OAuth refresh-token request. Sent as {@code
 * application/x-www-form-urlencoded}; field names match the parameter names Strava expects.
 */
public record StravaTokenRefreshRequest(
    @JsonProperty("client_id") String clientId,
    @JsonProperty("client_secret") String clientSecret,
    @JsonProperty("grant_type") String grantType,
    @JsonProperty("refresh_token") String refreshToken) {

  public static StravaTokenRefreshRequest forRefresh(
      String clientId, String clientSecret, String refreshToken) {
    return new StravaTokenRefreshRequest(clientId, clientSecret, "refresh_token", refreshToken);
  }
}
