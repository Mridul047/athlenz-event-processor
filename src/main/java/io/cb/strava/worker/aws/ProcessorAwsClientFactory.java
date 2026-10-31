package io.cb.strava.worker.aws;

import java.net.URI;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Builds the AWS SDK v2 clients the processor needs — DynamoDB (plus the enhanced, mapper-style
 * client), Secrets Manager, and SQS (for the dev-only local runner).
 *
 * <p>Without a DI framework these are constructed once in the handler's constructor (the Lambda
 * init phase) and reused across warm invocations — the AWS best practice of initializing SDK
 * clients outside the handler method. We use {@link UrlConnectionHttpClient} for a small dependency
 * footprint and faster cold start.
 *
 * <p>When {@code AWS_ENDPOINT_OVERRIDE} is set (for a local emulator such as Floci / LocalStack)
 * each client is pointed at that endpoint with a fixed region and dummy static credentials. In
 * deployed Lambda the variable is left unset, so the SDK's default endpoint, the Lambda-provided
 * region, and the function's IAM role credentials apply.
 */
public final class ProcessorAwsClientFactory {

  private ProcessorAwsClientFactory() {}

  public static SecretsManagerClient secretsManagerClient(String endpointOverride, String region) {
    return applyOverrides(
            SecretsManagerClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder()),
            endpointOverride,
            region)
        .build();
  }

  public static DynamoDbClient dynamoDbClient(String endpointOverride, String region) {
    return applyOverrides(
            DynamoDbClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder()),
            endpointOverride,
            region)
        .build();
  }

  /** The enhanced client provides a typed, mapper-style API over the low-level DynamoDB client. */
  public static DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient dynamoDbClient) {
    return DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoDbClient).build();
  }

  public static SqsClient sqsClient(String endpointOverride, String region) {
    return applyOverrides(
            SqsClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder()),
            endpointOverride,
            region)
        .build();
  }

  /**
   * Applies the local endpoint override, dummy credentials, and region to a client builder when an
   * endpoint override is configured. When it is not (deployed Lambda), the builder is returned
   * untouched so the SDK's default endpoint, region, and credential resolution apply.
   */
  private static <B extends AwsClientBuilder<B, ?>> B applyOverrides(
      B builder, String endpointOverride, String region) {
    if (endpointOverride != null && !endpointOverride.isBlank()) {
      builder
          .endpointOverride(URI.create(endpointOverride))
          .region(Region.of(region == null || region.isBlank() ? "us-east-1" : region))
          // Emulators accept any credentials; use obvious dummies.
          .credentialsProvider(
              StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")));
    }
    return builder;
  }
}
