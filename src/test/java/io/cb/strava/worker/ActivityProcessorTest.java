package io.cb.strava.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.cb.strava.model.StravaWebhookEvent;
import io.cb.strava.oauth.StravaOAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for the worker's core processing logic. The OAuth service, HTTP client, and store are
 * mocked; a real Jackson mapper parses a canned Strava activity JSON so the mapping is exercised
 * end to end.
 */
class ActivityProcessorTest {

  private static final String ACTIVITY_JSON =
      """
      {
        "id": 1360128428,
        "name": "Happy Friday",
        "type": "Ride",
        "sport_type": "MountainBikeRide",
        "distance": 28099.0,
        "moving_time": 4207,
        "elapsed_time": 4410,
        "total_elevation_gain": 516.0,
        "start_date": "2018-02-16T14:52:54Z",
        "average_speed": 6.679,
        "max_speed": 18.5
      }
      """;

  private StravaOAuthService oAuthService;
  private StravaActivityClient activityClient;
  private ActivityStore activityStore;
  private ActivityProcessor processor;

  @BeforeEach
  void setUp() {
    oAuthService = mock(StravaOAuthService.class);
    activityClient = mock(StravaActivityClient.class);
    activityStore = mock(ActivityStore.class);
    JsonMapper mapper =
        JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    processor = new ActivityProcessor(oAuthService, activityClient, activityStore, mapper);
  }

  @Test
  void fetchesWithBearerTokenAndPersistsMappedActivity() {
    when(oAuthService.getValidAccessToken(134815L)).thenReturn("access-abc");
    when(activityClient.getActivity(eq(1360128428L), eq("Bearer access-abc")))
        .thenReturn(ACTIVITY_JSON);
    when(activityStore.saveIfNew(org.mockito.ArgumentMatchers.any())).thenReturn(true);

    StravaWebhookEvent event =
        new StravaWebhookEvent(
            StravaWebhookEvent.ASPECT_CREATE,
            1549560669L,
            1360128428L,
            StravaWebhookEvent.OBJECT_TYPE_ACTIVITY,
            134815L,
            120475L);

    processor.process(event);

    ArgumentCaptor<ActivityRecord> saved = ArgumentCaptor.forClass(ActivityRecord.class);
    verify(activityStore).saveIfNew(saved.capture());
    ActivityRecord r = saved.getValue();
    assertEquals(1360128428L, r.getActivityId());
    assertEquals(134815L, r.getAthleteId());
    assertEquals("Happy Friday", r.getName());
    assertEquals("Ride", r.getType());
    assertEquals("MountainBikeRide", r.getSportType());
    assertEquals(28099.0, r.getDistance());
    assertEquals(4207, r.getMovingTime());
    assertEquals(event.idempotencyKey(), r.getIdempotencyKey());
  }

  @Test
  void skipsDeleteEventWithoutFetchingOrSaving() {
    StravaWebhookEvent event =
        new StravaWebhookEvent(
            StravaWebhookEvent.ASPECT_DELETE,
            1549560669L,
            1360128428L,
            StravaWebhookEvent.OBJECT_TYPE_ACTIVITY,
            134815L,
            120475L);

    processor.process(event);

    verify(oAuthService, never()).getValidAccessToken(anyLong());
    verify(activityStore, never()).saveIfNew(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void skipsNonActivityEvent() {
    StravaWebhookEvent event =
        new StravaWebhookEvent(
            StravaWebhookEvent.ASPECT_CREATE, 1549560669L, 999L, "athlete", 134815L, 120475L);

    processor.process(event);

    verify(oAuthService, never()).getValidAccessToken(anyLong());
    verify(activityStore, never()).saveIfNew(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void duplicateDeliveryIsNotTreatedAsError() {
    when(oAuthService.getValidAccessToken(134815L)).thenReturn("access-abc");
    when(activityClient.getActivity(anyLong(), org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(ACTIVITY_JSON);
    // saveIfNew returns false -> duplicate; process() must complete normally.
    when(activityStore.saveIfNew(org.mockito.ArgumentMatchers.any())).thenReturn(false);

    StravaWebhookEvent event =
        new StravaWebhookEvent(
            StravaWebhookEvent.ASPECT_CREATE,
            1549560669L,
            1360128428L,
            StravaWebhookEvent.OBJECT_TYPE_ACTIVITY,
            134815L,
            120475L);

    processor.process(event); // should not throw
    verify(activityStore).saveIfNew(org.mockito.ArgumentMatchers.any());
  }
}
