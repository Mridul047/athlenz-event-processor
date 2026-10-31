package io.cb.strava.oauth;

import java.util.Optional;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

/**
 * DynamoDB-backed store for per-athlete OAuth tokens.
 *
 * <p>Thin repository over the DynamoDB Enhanced client. The {@link DynamoDbTable} handle is
 * resolved once at construction (during Lambda init) and reused, consistent with the "initialize
 * clients outside the handler" guidance.
 */
public class AthleteTokenStore {

  private final DynamoDbTable<AthleteToken> table;

  public AthleteTokenStore(DynamoDbEnhancedClient enhancedClient, String tableName) {
    this.table = enhancedClient.table(tableName, TableSchema.fromBean(AthleteToken.class));
  }

  public Optional<AthleteToken> findByAthleteId(long athleteId) {
    AthleteToken item = table.getItem(Key.builder().partitionValue(athleteId).build());
    return Optional.ofNullable(item);
  }

  public void save(AthleteToken token) {
    table.putItem(token);
  }
}
