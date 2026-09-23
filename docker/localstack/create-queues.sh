#!/bin/bash
# Creates the queues radar-ingestion expects, with the same attributes stage 8's Terraform will
# apply. Runs once, when LocalStack reports ready.
#
# The attributes are not decoration: the consumer's transient-failure backoff is built on the
# visibility timeout, and a local queue with a different one would behave differently from
# production in exactly the situation hardest to reproduce. See docs/runbook.md.
set -euo pipefail

QUEUE=radar-procurement-discovered
DLQ="${QUEUE}-dlq"

awslocal sqs create-queue --queue-name "${DLQ}" \
  --attributes MessageRetentionPeriod=1209600

DLQ_ARN=$(awslocal sqs get-queue-attributes \
  --queue-url "$(awslocal sqs get-queue-url --queue-name "${DLQ}" --output text)" \
  --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)

awslocal sqs create-queue --queue-name "${QUEUE}" --attributes "$(cat <<JSON
{
  "VisibilityTimeout": "30",
  "MessageRetentionPeriod": "345600",
  "ReceiveMessageWaitTimeSeconds": "20",
  "RedrivePolicy": "{\"maxReceiveCount\":\"3\",\"deadLetterTargetArn\":\"${DLQ_ARN}\"}"
}
JSON
)"

echo "created ${QUEUE} and ${DLQ}"
