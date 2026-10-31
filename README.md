# athlenz-event-processor

## Overview

A standalone Strava **webhook processor** function. Plain **Java 21** on the AWS
Lambda `java21` runtime, built with **Maven**, deployed with the **AWS SAM
CLI**. No application framework — the handler implements the AWS Lambda
`RequestHandler` directly against the SQS event types. Only helper libraries are
used (Jackson for JSON, AWS SDK v2 for DynamoDB and Secrets Manager, and the
JDK's built-in `java.net.http.HttpClient` for the Strava API calls).

This is the worker side of the Strava webhook pipeline. It consumes the SQS
queue that the [ingest app](../athlenz-event-ingestor) publishes validated
webhook events to.

---

## Architecture

The processor sits at the worker end of the pipeline. It consumes the SQS queue
owned by the ingest stack; for each activity event it resolves (and refreshes)
the owning athlete's OAuth access token — reading the Strava client credentials
from AWS Secrets Manager — fetches the detailed activity from the Strava API,
and persists it idempotently to DynamoDB.

---

## How it works

For each activity `create`/`update` event the processor:

1. Resolves a valid OAuth access token for the owning athlete, refreshing it
   via Strava's token endpoint when it has expired (or is within a 60s buffer).
   The Strava client credentials are read from AWS Secrets Manager.
2. Fetches the detailed activity from the Strava API.
3. Maps it to a compact `ActivityRecord` and persists it to DynamoDB
   **idempotently** (a conditional put keyed on the delivery's idempotency key).

`delete` and non-activity events are skipped. The handler returns an
`SQSBatchResponse` listing only the messages that failed, so with
`ReportBatchItemFailures` enabled SQS retries just those messages; repeated
failures are redriven to the dead-letter queue. Unparseable messages are logged
and dropped (never retried forever).

---

## Project layout

```
athlenz-event-processor/
├── pom.xml                       # Maven build; shade plugin -> fat jar
├── template.yaml                 # SAM: Lambda + DynamoDB + Secret (consumes the ingest stack's SQS queue)
├── samconfig.toml.example        # copy to samconfig.toml for plain `sam deploy`
└── src/
    ├── main/java/io/cb/strava/
    │   ├── model/
    │   │   └── StravaWebhookEvent.java    # the webhook payload record
    │   ├── oauth/
    │   │   ├── StravaOAuthService.java        # token resolution + refresh
    │   │   ├── AthleteToken.java              # DynamoDB bean
    │   │   ├── AthleteTokenStore.java         # DynamoDB store
    │   │   ├── ClientCredentialsProvider.java # Secrets Manager + cache
    │   │   ├── StravaClientCredentials.java   # credentials DTO
    │   │   ├── StravaTokenResponse.java       # token response DTO
    │   │   ├── StravaTokenRefreshRequest.java # refresh request DTO
    │   │   ├── StravaTokenClient.java         # token client interface
    │   │   └── HttpStravaTokenClient.java     # HttpClient-backed impl
    │   └── worker/
    │       ├── WebhookWorkerHandler.java      # Lambda entrypoint (SQS)
    │       ├── ActivityProcessor.java         # core logic
    │       ├── ActivityRecord.java            # DynamoDB bean
    │       ├── ActivityStore.java             # idempotent DynamoDB store
    │       ├── StravaActivityClient.java      # activity client interface
    │       ├── HttpStravaActivityClient.java  # HttpClient-backed impl
    │       ├── StravaJacksonFactory.java      # Jackson mapper for Strava JSON
    │       ├── config/StravaConfiguration.java # env-var config
    │       ├── aws/ProcessorAwsClientFactory.java # AWS SDK v2 client factory
    │       └── local/LocalWorkerRunner.java   # dev-only SQS poller
    ├── main/resources/
    │   ├── simplelogger.properties
    │   └── strava-swagger-v3.json          # Strava OpenAPI spec (model generation)
    └── test/java/io/cb/strava/                # JUnit 5 + Mockito
```

The Strava `DetailedActivity` model (and the other Strava API model types) are
**OpenAPI-generated at build time** from `src/main/resources/strava-swagger-v3.json`
by the `openapi-generator-maven-plugin` (models only — `generateApis=false`),
into `com.strava.api.v3.model` under `target/generated-sources/openapi`. This
matches the reference `strava-webhook-processor` project. Being generated, the
models are not checked in; they are (re)created on every `mvn` build.

---

## Configuration

### Environment variables

| Variable                | Purpose                                                        | Default                              |
|-------------------------|----------------------------------------------------------------|--------------------------------------|
| `STATE_TABLE_NAME`      | DynamoDB table of per-athlete OAuth tokens.                    | `strava-state`                       |
| `ACTIVITIES_TABLE_NAME` | DynamoDB table of persisted activities.                       | `strava-activities`                  |
| `STRAVA_SECRET_ID`      | Secrets Manager id of the Strava client credentials.          | `strava/client-credentials`          |
| `STRAVA_API_BASE_URL`   | Strava activity API base URL.                                 | `https://www.strava.com/api/v3`      |
| `STRAVA_TOKEN_URL`      | Strava OAuth token endpoint.                                  | `https://www.strava.com/oauth/token` |
| `AWS_ENDPOINT_OVERRIDE` | Optional local emulator endpoint (e.g. `http://localhost:4566`). | _(unset)_                         |
| `AWS_REGION`            | Provided by Lambda; used only for the emulator case.          | `us-east-1`                          |
| `EVENT_QUEUE_URL`       | Queue URL for the dev-only `LocalWorkerRunner`.               | _(required by the runner only)_      |

### Deploy parameters

SAM template parameters (`template.yaml`):

| Parameter         | Purpose                                                                                      |
|-------------------|----------------------------------------------------------------------------------------------|
| `StravaSecretId`  | Name/id of the Secrets Manager secret holding the Strava client credentials (JSON).          |
| `EventQueueArn`   | ARN of the SQS queue to consume. **Owned by the ingest stack** — pass its `EventQueueArn` output here. |
| `EventQueueName`  | Name of that SQS queue (ingest's `EventQueueName` output); used only to scope the SQS poller IAM policy. |

---

## AWS resources

This stack creates two DynamoDB tables and a Secrets Manager secret for the
Strava client credentials.

Stack outputs:

- `ConsumedQueueArn` — the ingest-owned SQS queue this processor consumes
- `StateTableName` / `ActivitiesTableName` — the DynamoDB table names

---

## Build

```bash
mvn clean package
```

Produces the deployable fat jar at `target/athlenz-event-processor.jar`.

---

## Test

```bash
mvn test
```

---

## Deploy

> **Deploy the ingest stack first.** The SQS queue (and its DLQ) is **owned by
> the ingest stack** (`athlenz-event-ingestor`). This processor
> does **not** create a queue; it consumes the ingest stack's queue, whose ARN
> and name you pass in as parameters. Deploying this stack before the ingest
> stack exists will fail because the queue ARN would not yet be available.

```bash
sam build

sam deploy --guided \
  --parameter-overrides \
    EventQueueArn=<ingest stack's EventQueueArn output> \
    EventQueueName=<ingest stack's EventQueueName output> \
    StravaSecretId=strava/client-credentials
```

Grab the two queue values from the ingest stack's outputs:

```bash
aws cloudformation describe-stacks \
  --stack-name athlenz-event-ingestor \
  --query "Stacks[0].Outputs" --output table
```

The Secrets Manager secret this stack creates starts with a placeholder value —
update it after the first deploy, for example with
`aws secretsmanager put-secret-value`. On later deploys, copy
`samconfig.toml.example` to `samconfig.toml` (fill in the queue ARN/name and
secret id) and run plain `sam deploy`.

---

## Relationship to other services

The ingest app ([`athlenz-event-ingestor`](../athlenz-event-ingestor))
answers Strava's webhook callback and publishes validated activity events to an
SQS queue. **That queue, and its dead-letter queue, are owned by the ingest
stack.** This processor consumes the same queue rather than creating its own —
you pass the ingest stack's `EventQueueArn` / `EventQueueName` outputs in as the
`EventQueueArn` / `EventQueueName` parameters here. This gives a single shared
queue across the two stacks: ingest publishes, this processor consumes, and
messages that repeatedly fail processing are redriven to the ingest stack's DLQ.

The connector ([`athlenz-strava-connector`](../athlenz-strava-connector)) seeds
athlete OAuth tokens into the shared DynamoDB token store (the `strava-state`
table) that this processor reads when resolving an athlete's access token.

Deploy order is therefore **ingest first, then processor**.
