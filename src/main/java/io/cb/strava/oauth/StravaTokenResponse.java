package io.cb.strava.oauth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response body returned by Strava's OAuth token endpoint when exchanging an authorization code or
 * refreshing an access token.
 *
 * <p>Strava rotates refresh tokens: a refresh call may return a <em>new</em> refresh token that
 * must replace the stored one. See <a
 * href="https://developers.strava.com/docs/authentication/">Strava authentication docs</a>.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StravaTokenResponse(
    @JsonProperty("access_token") String accessToken,
    @JsonProperty("refresh_token") String refreshToken,
    @JsonProperty("expires_at") Long expiresAt,
    @JsonProperty("expires_in") Long expiresIn,
    @JsonProperty("token_type") String tokenType) {}
