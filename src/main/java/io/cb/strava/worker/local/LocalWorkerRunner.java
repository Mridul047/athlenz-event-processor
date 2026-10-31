package io.cb.strava.worker.local;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cb.strava.model.StravaWebhookEvent;
import io.cb.strava.oauth.AthleteTokenStore;
import io.cb.strava.oauth.ClientCredentialsProvider;
import io.cb.strava.oauth.HttpStravaTokenClient;
import io.cb.strava.oauth.StravaOAuthService;
import io.cb.strava.oauth.StravaTokenClient;
import io.cb.strava.worker.ActivityProcessor;
import io.cb.strava.worker.ActivityStore;
import io.cb.strava.worker.HttpStravaActivityClient;
import io.cb.strava.worker.StravaActivityClient;
import io.cb.strava.worker.StravaJacksonFactory;
import io.cb.strava.worker.aws.ProcessorAwsClientFactory;
import io.cb.strava.worker.config.StravaConfiguration;
import java.net.http.HttpClient;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

/**
 * Dev-only local runner for the SQS processor — <strong>not</strong> part of the deployed Lambda
 * path.
 *
 * <p>On AWS the processor is driven by a Lambda SQS event-source mapping that invokes {@link
 * io.cb.strava.worker.WebhookWorkerHandler}. A local emulator (Floci / LocalStack) provides
 * SQS/DynamoDB/Secrets but does not run a Lambda event-source poller, so nothing triggers the
 * handler off the queue locally. This runner closes that gap: it long-polls the queue and drives
 * the exact same core logic ({@link ActivityProcessor}) the handler uses, giving a real end-to-end
 * {@code ingest -> SQS -> processor -> DynamoDB} loop you can run and debug from your IDE.
 *
 * <h2>Semantics (mirroring the Lambda handler against raw SQS)</h2>
 *
 * <ul>
 *   <li><b>Success</b> — the message is deleted from the queue.
 *   <li><b>Transient failure</b> — the message is left on the queue; after the visibility timeout
 *       it is redelivered and, past {@code maxReceiveCount}, redriven to the DLQ.
 *   <li><b>Unparseable</b> — logged and deleted so it does not spin forever.
 * </ul>
 *
 * <h2>Running</h2>
 *
 * <p>Start the emulator and seed resources, then run this class with {@code AWS_ENDPOINT_OVERRIDE}
 * pointed at the emulator and {@code EVENT_QUEUE_URL} set to the queue the ingest app publishes to.
 */
public final class LocalWorkerRunner {

  private static final Logger LOG = LoggerFactory.getLogger(LocalWorkerRunner.class);

  /** Long-poll wait: fewer empty receives, lower CPU churn while idle. */
  private static final int WAIT_TIME_SECONDS = 20;

  /** Process in small batches, like the Lambda event-source mapping would. */
  private static final int MAX_MESSAGES_PER_RECEIVE = 10;

  private LocalWorkerRunner() {}

  public static void main(String[] args) {
    String queueUrl = resolveQueueUrl();

    var env = System.getenv();
    StravaConfiguration config = StravaConfiguration.fromEnv(env);
    String endpointOverride = env.get("AWS_ENDPOINT_OVERRIDE");
    String region = env.get("AWS_REGION");

    ObjectMapper objectMapper = new ObjectMapper();
    var stravaMapper = StravaJacksonFactory.create();

    DynamoDbClient dynamoDbClient =
        ProcessorAwsClientFactory.dynamoDbClient(endpointOverride, region);
    DynamoDbEnhancedClient enhancedClient =
        ProcessorAwsClientFactory.dynamoDbEnhancedClient(dynamoDbClient);
    SecretsManagerClient secretsManagerClient =
        ProcessorAwsClientFactory.secretsManagerClient(endpointOverride, region);
    SqsClient sqsClient = ProcessorAwsClientFactory.sqsClient(endpointOverride, region);
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
    ActivityProcessor processor =
        new ActivityProcessor(oAuthService, activityClient, activityStore, stravaMapper);

    LOG.info("Local worker runner started. Polling {} (Ctrl+C to stop).", queueUrl);
    Runtime.getRuntime()
        .addShutdownHook(new Thread(() -> LOG.info("Shutting down local worker runner.")));

    pollLoop(sqsClient, processor, objectMapper, queueUrl);
  }

  private static void pollLoop(
      SqsClient sqsClient,
      ActivityProcessor processor,
      ObjectMapper objectMapper,
      String queueUrl) {
    while (!Thread.currentThread().isInterrupted()) {
      ReceiveMessageResponse response =
          sqsClient.receiveMessage(
              ReceiveMessageRequest.builder()
                  .queueUrl(queueUrl)
                  .maxNumberOfMessages(MAX_MESSAGES_PER_RECEIVE)
                  .waitTimeSeconds(WAIT_TIME_SECONDS)
                  .build());

      List<Message> messages = response.messages();
      if (messages.isEmpty()) {
        continue;
      }

      for (Message message : messages) {
        handleOne(sqsClient, processor, objectMapper, queueUrl, message);
      }
    }
  }

  /**
   * Process a single message with the same semantics as the Lambda handler, translated to raw SQS:
   * delete on success or unparseable, leave on transient failure so the queue redelivers and
   * eventually redrives to the DLQ.
   */
  private static void handleOne(
      SqsClient sqsClient,
      ActivityProcessor processor,
      ObjectMapper objectMapper,
      String queueUrl,
      Message message) {
    try {
      StravaWebhookEvent event = objectMapper.readValue(message.body(), StravaWebhookEvent.class);
      processor.process(event);
      deleteMessage(sqsClient, queueUrl, message);
      LOG.info("Processed and deleted message {}", message.messageId());
    } catch (java.io.IOException e) {
      // Poison message: will never parse. Drop it (delete) so it does not
      // consume receives forever — same intent as the handler's drop.
      LOG.error("Unparseable message {}; dropping", message.messageId(), e);
      deleteMessage(sqsClient, queueUrl, message);
    } catch (Exception e) {
      // Transient/processing failure: leave the message on the queue.
      LOG.error(
          "Failed to process message {}; leaving on queue for retry/redrive",
          message.messageId(),
          e);
    }
  }

  private static void deleteMessage(SqsClient sqsClient, String queueUrl, Message message) {
    sqsClient.deleteMessage(
        DeleteMessageRequest.builder()
            .queueUrl(queueUrl)
            .receiptHandle(message.receiptHandle())
            .build());
  }

  /**
   * Resolve the queue URL from {@code EVENT_QUEUE_URL}. Required: the processor function does not
   * otherwise need a queue URL (Lambda delivers events to it), so there is no sensible default.
   */
  private static String resolveQueueUrl() {
    String queueUrl = System.getenv("EVENT_QUEUE_URL");
    if (queueUrl == null || queueUrl.isBlank()) {
      throw new IllegalStateException(
          "EVENT_QUEUE_URL is not set. Set it to the queue URL the ingest app publishes to, e.g. "
              + "http://localhost:4566/000000000000/strava-webhook-events");
    }
    return queueUrl;
  }
}
