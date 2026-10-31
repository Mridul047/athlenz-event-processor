package io.cb.strava.worker.config;

import java.util.Map;

/**
 * All configuration the processor needs, read from environment variables.
 *
 * <p>With no DI framework there is no {@code application.yml} binding; {@link #fromEnv(Map)} reads
 * {@link System#getenv()} (or a supplied map in tests) and applies the same defaults the Micronaut
 * reference used.
 *
 * <table>
 *   <tr><th>Environment variable</th><th>Default</th></tr>
 *   <tr><td>{@code STATE_TABLE_NAME}</td><td>{@code strava-state}</td></tr>
 *   <tr><td>{@code ACTIVITIES_TABLE_NAME}</td><td>{@code strava-activities}</td></tr>
 *   <tr><td>{@code STRAVA_SECRET_ID}</td><td>{@code strava/client-credentials}</td></tr>
 *   <tr><td>{@code STRAVA_API_BASE_URL}</td><td>{@code https://www.strava.com/api/v3}</td></tr>
 *   <tr><td>{@code STRAVA_TOKEN_URL}</td><td>{@code https://www.strava.com/oauth/token}</td></tr>
 * </table>
 */
public record StravaConfiguration(
    String stateTableName,
    String activitiesTableName,
    String stravaSecretId,
    String apiBaseUrl,
    String tokenUrl) {

  public static StravaConfiguration fromEnv(Map<String, String> env) {
    return new StravaConfiguration(
        env.getOrDefault("STATE_TABLE_NAME", "strava-state"),
        env.getOrDefault("ACTIVITIES_TABLE_NAME", "strava-activities"),
        env.getOrDefault("STRAVA_SECRET_ID", "strava/client-credentials"),
        env.getOrDefault("STRAVA_API_BASE_URL", "https://www.strava.com/api/v3"),
        env.getOrDefault("STRAVA_TOKEN_URL", "https://www.strava.com/oauth/token"));
  }
}
