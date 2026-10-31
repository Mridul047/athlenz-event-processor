package io.cb.strava.worker;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.strava.api.v3.model.DetailedActivity;
import io.cb.strava.model.StravaWebhookEvent;
import io.cb.strava.oauth.StravaOAuthService;
import java.io.IOException;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Core processing for a single Strava webhook event.
 *
 * <p>Steps for an activity {@code create}/{@code update}:
 *
 * <ol>
 *   <li>Resolve a valid access token for the owning athlete (refreshing if needed).
 *   <li>Fetch the detailed activity from the Strava API.
 *   <li>Map it to an {@link ActivityRecord} and persist idempotently.
 * </ol>
 *
 * <p>{@code delete} events are skipped (there is nothing to fetch), as are non-activity events.
 *
 * <p>This class holds the business logic and is independent of SQS/Lambda, which keeps it
 * unit-testable (the AWS Lambda "separate handler from core logic" best practice).
 */
public class ActivityProcessor {

  private static final Logger LOG = LoggerFactory.getLogger(ActivityProcessor.class);

  private final StravaOAuthService oAuthService;
  private final StravaActivityClient activityClient;
  private final ActivityStore activityStore;
  private final JsonMapper jsonMapper;

  public ActivityProcessor(
      StravaOAuthService oAuthService,
      StravaActivityClient activityClient,
      ActivityStore activityStore,
      JsonMapper jsonMapper) {
    this.oAuthService = oAuthService;
    this.activityClient = activityClient;
    this.activityStore = activityStore;
    this.jsonMapper = jsonMapper;
  }

  /**
   * Process one webhook event. Throwing propagates the failure to the caller (the worker handler),
   * which reports the message as a batch item failure so SQS can retry and, after enough attempts,
   * redrive to the DLQ.
   */
  public void process(StravaWebhookEvent event) {
    if (!event.isActivity()) {
      LOG.info("Skipping non-activity event objectType={}", event.objectType());
      return;
    }
    if (StravaWebhookEvent.ASPECT_DELETE.equals(event.aspectType())) {
      LOG.info("Skipping delete event for activity {}", event.objectId());
      return;
    }

    long athleteId = event.ownerId();
    long activityId = event.objectId();

    String accessToken = oAuthService.getValidAccessToken(athleteId);
    DetailedActivity activity = fetchActivity(activityId, accessToken);

    ActivityRecord record = toRecord(event, athleteId, activity);
    boolean written = activityStore.saveIfNew(record);
    if (written) {
      LOG.info("Persisted activity {} for athlete {}", activityId, athleteId);
    } else {
      LOG.info(
          "Duplicate delivery for activity {} (idempotencyKey={}); skipped",
          activityId,
          event.idempotencyKey());
    }
  }

  private DetailedActivity fetchActivity(long activityId, String accessToken) {
    String json = activityClient.getActivity(activityId, "Bearer " + accessToken);
    try {
      return jsonMapper.readValue(json, DetailedActivity.class);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to parse Strava activity " + activityId, e);
    }
  }

  private ActivityRecord toRecord(StravaWebhookEvent event, long athleteId, DetailedActivity a) {
    ActivityRecord record = new ActivityRecord();
    record.setActivityId(a.getId());
    record.setAthleteId(athleteId);
    record.setName(a.getName());
    // type and sportType are generated enums; use their wire value.
    record.setType(a.getType() != null ? a.getType().getValue() : null);
    record.setSportType(a.getSportType() != null ? a.getSportType().getValue() : null);
    // Strava returns Float; widen to Double for storage.
    record.setDistance(a.getDistance() != null ? a.getDistance().doubleValue() : null);
    record.setMovingTime(a.getMovingTime());
    record.setElapsedTime(a.getElapsedTime());
    record.setTotalElevationGain(
        a.getTotalElevationGain() != null ? a.getTotalElevationGain().doubleValue() : null);
    record.setStartDate(a.getStartDate() != null ? a.getStartDate().toString() : null);
    record.setIdempotencyKey(event.idempotencyKey());
    record.setProcessedAt(Instant.now().getEpochSecond());
    return record;
  }
}
