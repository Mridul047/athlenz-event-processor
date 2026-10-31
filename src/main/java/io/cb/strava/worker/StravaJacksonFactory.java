package io.cb.strava.worker;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Builds the Jackson {@link JsonMapper} used to parse Strava API responses into {@link
 * com.strava.api.v3.model.DetailedActivity}.
 *
 * <p>The mapper registers {@link JavaTimeModule} (for any {@code java.time} values) and disables
 * {@link DeserializationFeature#FAIL_ON_UNKNOWN_PROPERTIES} because Strava returns far more fields
 * than this project models.
 */
public final class StravaJacksonFactory {

  private StravaJacksonFactory() {}

  public static JsonMapper create() {
    return JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();
  }
}
