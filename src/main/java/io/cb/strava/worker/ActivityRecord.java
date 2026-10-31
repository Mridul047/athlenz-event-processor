package io.cb.strava.worker;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

/**
 * A persisted summary of a Strava activity, stored in the activities table.
 *
 * <p>Keyed by the Strava activity id. We store a flattened subset of the detailed activity (the
 * fields a reader of the series would find useful), plus the {@code idempotencyKey} of the webhook
 * delivery that produced it and the time it was processed.
 *
 * <p>Mutable JavaBean for the DynamoDB Enhanced client's bean schema.
 */
@DynamoDbBean
public class ActivityRecord {

  private Long activityId;
  private Long athleteId;
  private String name;
  private String type;
  private String sportType;
  private Double distance;
  private Integer movingTime;
  private Integer elapsedTime;
  private Double totalElevationGain;
  private String startDate;
  private String idempotencyKey;
  private long processedAt;

  @DynamoDbPartitionKey
  public Long getActivityId() {
    return activityId;
  }

  public void setActivityId(Long activityId) {
    this.activityId = activityId;
  }

  public Long getAthleteId() {
    return athleteId;
  }

  public void setAthleteId(Long athleteId) {
    this.athleteId = athleteId;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public String getSportType() {
    return sportType;
  }

  public void setSportType(String sportType) {
    this.sportType = sportType;
  }

  public Double getDistance() {
    return distance;
  }

  public void setDistance(Double distance) {
    this.distance = distance;
  }

  public Integer getMovingTime() {
    return movingTime;
  }

  public void setMovingTime(Integer movingTime) {
    this.movingTime = movingTime;
  }

  public Integer getElapsedTime() {
    return elapsedTime;
  }

  public void setElapsedTime(Integer elapsedTime) {
    this.elapsedTime = elapsedTime;
  }

  public Double getTotalElevationGain() {
    return totalElevationGain;
  }

  public void setTotalElevationGain(Double totalElevationGain) {
    this.totalElevationGain = totalElevationGain;
  }

  public String getStartDate() {
    return startDate;
  }

  public void setStartDate(String startDate) {
    this.startDate = startDate;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public void setIdempotencyKey(String idempotencyKey) {
    this.idempotencyKey = idempotencyKey;
  }

  /** Epoch seconds when this activity was processed/persisted. */
  public long getProcessedAt() {
    return processedAt;
  }

  public void setProcessedAt(long processedAt) {
    this.processedAt = processedAt;
  }
}
