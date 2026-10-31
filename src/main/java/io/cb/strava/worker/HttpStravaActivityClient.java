package io.cb.strava.worker;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link StravaActivityClient} backed by the JDK's built-in {@link HttpClient}.
 *
 * <p>Issues {@code GET {baseUrl}/activities/{id}} with the caller-supplied {@code Authorization}
 * header (the full {@code "Bearer " + token} value) and returns the raw JSON body. A non-2xx
 * response throws, so the triggering SQS message is retried.
 */
public class HttpStravaActivityClient implements StravaActivityClient {

  private static final Logger LOG = LoggerFactory.getLogger(HttpStravaActivityClient.class);

  private final String baseUrl;
  private final HttpClient httpClient;

  public HttpStravaActivityClient(String baseUrl, HttpClient httpClient) {
    // Trim a trailing slash so we can join path segments predictably.
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.httpClient = httpClient;
  }

  @Override
  public String getActivity(long id, String authorization) {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/activities/" + id))
            .header("Authorization", authorization)
            .header("Accept", "application/json")
            .GET()
            .build();

    HttpResponse<String> response;
    try {
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new IllegalStateException("Failed to call Strava activity API for " + id, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted calling Strava activity API for " + id, e);
    }

    int status = response.statusCode();
    if (status / 100 != 2) {
      if (status == 404) {
        // 404 from Strava means the activity is not visible to this token:
        // commonly a private / "Only You" activity when the token lacks
        // activity:read_all, or a token that does not belong to the owner.
        LOG.error(
            "Strava returned 404 for activity {} — not visible to this token "
                + "(private activity requires activity:read_all, or token/owner mismatch)",
            id);
      } else {
        LOG.error("Strava activity API returned HTTP {} for activity {}", status, id);
      }
      throw new IllegalStateException(
          "Strava activity API returned HTTP " + status + " for activity " + id);
    }
    return response.body();
  }
}
