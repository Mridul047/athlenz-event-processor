package io.cb.strava.oauth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The Strava application's OAuth client credentials.
 *
 * <p>Stored as a JSON secret in AWS Secrets Manager (never in source control or environment
 * variables), for example:
 *
 * <pre>{@code { "client_id": "12345", "client_secret": "abc...def" } }</pre>
 *
 * <p>These identify <em>our application</em> to Strava and are used when exchanging or refreshing a
 * user's OAuth tokens.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StravaClientCredentials(
    @JsonProperty("client_id") String clientId,
    @JsonProperty("client_secret") String clientSecret) {}
