package io.cb.strava.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * {@link StravaTokenClient} backed by the JDK's built-in {@link HttpClient}.
 *
 * <p>Strava expects the refresh request as {@code application/x-www-form-urlencoded} parameters, so
 * we URL-encode the form body ourselves and parse the JSON response with Jackson. A non-2xx
 * response throws, so the triggering SQS message is retried.
 */
public class HttpStravaTokenClient implements StravaTokenClient {

  private final String tokenUrl;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  public HttpStravaTokenClient(String tokenUrl, HttpClient httpClient, ObjectMapper objectMapper) {
    this.tokenUrl = tokenUrl;
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
  }

  @Override
  public StravaTokenResponse refresh(StravaTokenRefreshRequest request) {
    Map<String, String> form = new LinkedHashMap<>();
    form.put("client_id", request.clientId());
    form.put("client_secret", request.clientSecret());
    form.put("grant_type", request.grantType());
    form.put("refresh_token", request.refreshToken());

    HttpRequest httpRequest =
        HttpRequest.newBuilder()
            .uri(URI.create(tokenUrl))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(encodeForm(form)))
            .build();

    HttpResponse<String> response;
    try {
      response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new IllegalStateException("Failed to call Strava token endpoint", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted calling Strava token endpoint", e);
    }

    if (response.statusCode() / 100 != 2) {
      // Do not log the body: it may echo back credential-adjacent data. Throw so
      // the message is retried.
      throw new IllegalStateException(
          "Strava token endpoint returned HTTP " + response.statusCode());
    }

    try {
      return objectMapper.readValue(response.body(), StravaTokenResponse.class);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to parse Strava token response", e);
    }
  }

  private static String encodeForm(Map<String, String> form) {
    StringJoiner joiner = new StringJoiner("&");
    for (Map.Entry<String, String> entry : form.entrySet()) {
      String value = entry.getValue() == null ? "" : entry.getValue();
      joiner.add(
          URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
              + "="
              + URLEncoder.encode(value, StandardCharsets.UTF_8));
    }
    return joiner.toString();
  }
}
