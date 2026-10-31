package io.cb.strava.worker;

/**
 * Client for the Strava activity API.
 *
 * <p>An interface so the processing logic can be unit-tested with a mock; the production
 * implementation is {@link HttpStravaActivityClient}, built on the JDK's {@link
 * java.net.http.HttpClient}. The response is returned as a raw JSON string and parsed by the caller
 * into {@link com.strava.api.v3.model.DetailedActivity}.
 */
public interface StravaActivityClient {

  /**
   * Fetch the detailed representation of an activity.
   *
   * @param id the Strava activity id (the webhook event's object_id)
   * @param authorization the {@code Bearer <access_token>} header value
   * @return the activity as a raw JSON string
   */
  String getActivity(long id, String authorization);
}
