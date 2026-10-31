package io.cb.strava.worker;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cb.strava.model.StravaWebhookEvent;
import io.cb.strava.oauth.AthleteTokenStore;
import io.cb.strava.oauth.ClientCredentialsProvider;
import io.cb.strava.oauth.HttpStravaTokenClient;
import io.cb.strava.oauth.StravaOAuthService;
import io.cb.strava.oauth.StravaTokenClient;
import io.cb.strava.worker.aws.ProcessorAwsClientFactory;
import io.cb.strava.worker.config.StravaConfiguration;
import java.io.IOException;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/**
 * SQS-triggered worker Lambda.
 *
 * <p>No framework: this implements the Lambda {@link RequestHandler} directly against the SQS event
 * types. Collaborators are constructed once in the no-arg constructor (the Lambda init phase) and
 * reused across warm invocations.
 *
 * <p>The handler processes each message independently and returns an {@link SQSBatchResponse}
 * listing only the messages that failed. With partial batch responses enabled on the event source
 * mapping ({@code ReportBatchItemFailures}), SQS retries just the failed messages instead of the
 * whole batch — successfully processed messages are not re-delivered. Failures that persist past
 * the queue's {@code maxReceiveCount} are redriven to the DLQ.
 *
 * <h2>Configuration (environment variables)</h2>
 *
 * <ul>
 *   <li>{@code STATE_TABLE_NAME} — DynamoDB table of per-athlete OAuth tokens.
 *   <li>{@code ACTIVITIES_TABLE_NAME} — DynamoDB table of persisted activities.
 *   <li>{@code STRAVA_SECRET_ID} — Secrets Manager id holding the Strava client credentials.
 *   <li>{@code STRAVA_API_BASE_URL} — Strava activity API base URL.
 *   <li>{@code STRAVA_TOKEN_URL} — Strava OAuth token endpoint.
 *   <li>{@code AWS_ENDPOINT_OVERRIDE} / {@code AWS_REGION} — optional, for local emulators.
 * </ul>
 */
public class WebhookWorkerHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {

  private static final Logger LOG = LoggerFactory.getLogger(WebhookWorkerHandler.class);

  private final ActivityProcessor processor;
  private final ObjectMapper objectMapper;

  /** Lambda invokes this no-arg constructor once per container (init phase). */
  public WebhookWorkerHandler() {
    this(System.getenv());
  }

  /** Package-private constructor for tests: supply the environment directly. */
  WebhookWorkerHandler(Map<String, String> env) {
    StravaConfiguration config = StravaConfiguration.fromEnv(env);

    this.objectMapper = new ObjectMapper();
    var stravaMapper = StravaJacksonFactory.create();

    String endpointOverride = env.get("AWS_ENDPOINT_OVERRIDE");
    String region = env.get("AWS_REGION");

    DynamoDbClient dynamoDbClient =
        ProcessorAwsClientFactory.dynamoDbClient(endpointOverride, region);
    DynamoDbEnhancedClient enhancedClient =
        ProcessorAwsClientFactory.dynamoDbEnhancedClient(dynamoDbClient);
    SecretsManagerClient secretsManagerClient =
        ProcessorAwsClientFactory.secretsManagerClient(endpointOverride, region);
    HttpClient httpClient = HttpClient.newHttpClient();

    AthleteTokenStore tokenStore = new AthleteTokenStore(enhancedClient, config.stateTableName());
    ClientCredentialsProvider credentialsProvider =
        new ClientCredentialsProvider(secretsManagerClient, objectMapper, config.stravaSecretId());
    StravaTokenClient tokenClient =
        new HttpStravaTokenClient(config.tokenUrl(), httpClient, objectMapper);
    StravaOAuthService oAuthService =
        new StravaOAuthService(tokenStore, credentialsProvider, tokenClient);

    StravaActivityClient activityClient =
        new HttpStravaActivityClient(config.apiBaseUrl(), httpClient);
    ActivityStore activityStore = new ActivityStore(enhancedClient, config.activitiesTableName());

    this.processor =
        new ActivityProcessor(oAuthService, activityClient, activityStore, stravaMapper);

    LOG.info(
        "Processor initialized: stateTable={} activitiesTable={} secretId={} apiBaseUrl={} endpointOverride={}",
        config.stateTableName(),
        config.activitiesTableName(),
        config.stravaSecretId(),
        config.apiBaseUrl(),
        (endpointOverride == null || endpointOverride.isBlank()) ? "(real AWS)" : endpointOverride);
  }

  /** Package-private constructor for tests: supply the collaborators directly. */
  WebhookWorkerHandler(ActivityProcessor processor, ObjectMapper objectMapper) {
    this.processor = processor;
    this.objectMapper = objectMapper;
  }

  @Override
  public SQSBatchResponse handleRequest(SQSEvent input, Context context) {
    List<SQSBatchResponse.BatchItemFailure> failures = new ArrayList<>();
    int received = input.getRecords().size();
    LOG.info("Received SQS batch: size={}", received);

    for (SQSEvent.SQSMessage message : input.getRecords()) {
      String messageId = message.getMessageId();
      try {
        StravaWebhookEvent event =
            objectMapper.readValue(message.getBody(), StravaWebhookEvent.class);
        LOG.info(
            "Processing message {}: athleteId={} activityId={} aspect={}",
            messageId,
            event.ownerId(),
            event.objectId(),
            event.aspectType());
        processor.process(event);
      } catch (IOException e) {
        // A message we cannot even parse will never succeed on retry. Log and
        // DO NOT add to failures, so it is not retried forever; it is
        // effectively dropped (and visible in logs).
        LOG.error("Dropping unparseable message {}", messageId, e);
      } catch (Exception e) {
        // Transient/processing failure: report it so SQS retries just this message.
        LOG.error("Failed to process message {}; will be retried", messageId, e);
        failures.add(
            SQSBatchResponse.BatchItemFailure.builder().withItemIdentifier(messageId).build());
      }
    }

    LOG.info("Batch complete: received={} failed={}", received, failures.size());
    return SQSBatchResponse.builder().withBatchItemFailures(failures).build();
  }
}
