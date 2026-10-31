package io.cb.strava.worker;

import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * DynamoDB-backed store for persisted activities, with idempotent writes.
 *
 * <p>The {@link #saveIfNew(ActivityRecord)} method performs a conditional put that only succeeds
 * when the same webhook delivery has not already been persisted. This makes the worker safe against
 * SQS at-least-once delivery: replays of the same event do not create duplicate work or
 * double-count.
 */
public class ActivityStore {

  private final DynamoDbTable<ActivityRecord> table;

  public ActivityStore(DynamoDbEnhancedClient enhancedClient, String tableName) {
    this.table = enhancedClient.table(tableName, TableSchema.fromBean(ActivityRecord.class));
  }

  /**
   * Persist the activity unless this exact delivery was already processed.
   *
   * <p>The condition allows the write when either:
   *
   * <ul>
   *   <li>no item exists for this activity id yet, or
   *   <li>an item exists but with a <em>different</em> idempotency key (for example a later
   *       "update" event for the same activity).
   * </ul>
   *
   * It rejects the write only when an item with the <em>same</em> idempotency key already exists —
   * i.e. a true duplicate delivery.
   *
   * @return {@code true} if the item was written; {@code false} if it was a duplicate delivery and
   *     the write was skipped
   */
  public boolean saveIfNew(ActivityRecord record) {
    Expression condition =
        Expression.builder()
            .expression("attribute_not_exists(activityId) OR idempotencyKey <> :k")
            .expressionValues(
                Map.of(":k", AttributeValue.builder().s(record.getIdempotencyKey()).build()))
            .build();

    try {
      table.putItem(
          PutItemEnhancedRequest.builder(ActivityRecord.class)
              .item(record)
              .conditionExpression(condition)
              .build());
      return true;
    } catch (ConditionalCheckFailedException duplicate) {
      // Same delivery already persisted; safe to ignore.
      return false;
    }
  }

  public Optional<ActivityRecord> findByActivityId(long activityId) {
    return Optional.ofNullable(table.getItem(Key.builder().partitionValue(activityId).build()));
  }
}
