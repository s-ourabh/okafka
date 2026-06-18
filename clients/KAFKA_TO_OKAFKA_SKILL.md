---
name: kafka-to-okafka
description: Convert Java applications from Apache Kafka clients to Oracle OKafka clients backed by Oracle Database TEQ. Use when asked to migrate Kafka producer, consumer, admin, service, worker, command-line, Spring, or test code to OKafka; replace Kafka client classes/imports/configuration with OKafka equivalents; check unsupported API usage; preserve Kafka-compatible behavior where OKafka supports it; and document Oracle TEQ runtime configuration, semantic differences, and required validation.
---

# Kafka To OKafka

Use this skill when converting any Java codebase that currently uses Apache Kafka Java clients into an OKafka application. Tests are only one supported input shape; the main goal is application migration.

## First Step

Read the conversion reference:

- `KAFKA_TO_OKAFKA_CONVERSION_GUIDE.md`

Then inspect the target application and the local OKafka client source. If the user provides an Apache Kafka source tree, use it only as a compatibility reference for public API differences.

Useful local OKafka references in this repository:

- `clients/src/main/java/org/oracle/okafka/clients/producer/KafkaProducer.java`
- `clients/src/main/java/org/oracle/okafka/clients/consumer/KafkaConsumer.java`
- `clients/src/main/java/org/oracle/okafka/clients/admin/KafkaAdminClient.java`
- `clients/src/main/java/org/oracle/okafka/clients/CommonClientConfigs.java`
- `clients/src/test/java/org/oracle/okafka/tests`

If the repo also has the `okafka-clients` skill or local OKafka context notes, read those after this skill when implementation details are needed.

## Workflow

1. Inventory the Kafka usage.
   - Find imports under `org.apache.kafka.clients.producer`, `consumer`, and `admin`.
   - Find framework wiring such as Spring Kafka factories, dependency declarations, property files, environment variables, Docker/CI config, and app startup scripts.
   - Find config keys, serializers/deserializers, topic creation, subscription, assignment, offset, transaction, metrics, interceptors, callbacks, and admin usage.
   - Find unsupported or risky API calls before editing.

2. Update build and dependency wiring.
   - Make the application compile against the OKafka client artifact/source used by the project.
   - Keep Apache Kafka common/client API dependencies that OKafka still uses for interfaces and shared model types.
   - Remove direct Kafka broker-only runtime assumptions from config, scripts, and documentation.

3. Replace public client classes with OKafka equivalents.
   - Producer: use `org.oracle.okafka.clients.producer.KafkaProducer`.
   - Consumer: use `org.oracle.okafka.clients.consumer.KafkaConsumer`.
   - Admin factory/client: use `org.oracle.okafka.clients.admin.AdminClient` or OKafka admin types where required.
   - Keep Apache Kafka shared model types when OKafka APIs still use them, such as `ProducerRecord`, `ConsumerRecord`, `NewTopic`, `TopicPartition`, serializers, deserializers, and common admin result/spec types.
   - Prefer Kafka interfaces (`Producer`, `Consumer`, `Admin`) for variables and method signatures when possible, with OKafka concrete classes only at construction boundaries.

4. Convert application runtime configuration.
   - Add Oracle DB connection settings: `bootstrap.servers`, `oracle.service.name`, `oracle.net.tns_admin`, and `security.protocol`.
   - For PLAINTEXT, ensure `ojdbc.properties` exists in `oracle.net.tns_admin` with `user` and `password`.
   - For SSL/wallet, ensure wallet files, `tnsnames.ora`, optional `ojdbc.properties`, and `tns.alias` are configured.
   - Keep application-owned config such as topic names, poll limits, retry policy, or batch sizes separate from the `Properties` passed to OKafka clients when those keys are not OKafka client keys.
   - Preserve serializer/deserializer and group configs that OKafka supports.
   - Keep secrets and wallet locations environment-specific; do not hard-code user-machine paths into reusable code.

5. Check behavioral differences.
   - OKafka consumer supports one subscribed topic at a time.
   - Pattern subscription and manual assignment are unsupported.
   - Offset-map commit overloads, pause/resume, wakeup, enforceRebalance, and some newer clientInstanceId APIs are unsupported.
   - Many modern Admin APIs unrelated to TEQ topic/group/offset flows are unsupported.
   - Several modern Admin, Consumer, and Producer APIs throw OKafka `FeatureNotSupportedException`.
   - Topic names are commonly uppercased internally; watch case-sensitive assumptions.
   - Metadata, topic operations, offsets, and group operations are real Oracle Database operations.
   - Transactional producer code must be reviewed for OKafka's Oracle-specific transaction mode and `oracle.transactional.producer`.
   - Compare behavior against current OKafka tests before declaring an API unsupported; support may have changed.

6. If the input is test code, make converted tests runnable in a cloned environment.
   - Put reusable test configuration under test resources, for example `src/test/resources/test.config`, not beside Java source files.
   - Load test config from the classpath first, or from a stable repo-relative resource path if the project already has that convention.
   - Treat `oracle.net.tns_admin` as a directory value. For a repo-local PLAINTEXT setup, it may point to `src/test/resources`; for wallets or machine-specific files, preserve the user's absolute/custom directory.
   - Keep secrets out of generic examples. If `ojdbc.properties` is committed only as a template, use placeholder values and document that users must edit it locally.
   - Convert standalone `main` runners into JUnit/TestNG tests when the project uses Gradle test tasks, so IDEs and `gradle test`/`gradle integrationTest` can discover them.
   - Use unique topic and group names in tests to avoid conflicts when users rerun tests or run them from CI.
   - Add converted tests to any explicit suite runner only when that runner is part of the project's supported flow.

7. Validate incrementally.
   - Compile first.
   - Run a focused smoke test for admin/create topic, producer/send, consumer/poll.
   - Run the converted application path with a real Oracle TEQ database, wallet/credentials, and expected topics.
   - Then run broader integration tests if a configured Oracle TEQ database is available.
   - For converted tests in this repo, config should come from `clients/src/test/resources/test.config` and `ojdbc.properties`.
   - Document exact commands for application startup, single-test, all-test, and suite-runner execution as applicable.

## Output Expectations

When finishing a conversion, report:

- imports/classes changed
- build/dependency changes required
- config changes required from the user
- environment files or templates required to run the converted application
- run commands for the application, Gradle tasks, or IDE as applicable
- APIs that were unsupported or changed semantically
- tests/commands run
- behavior not validated because it needs Oracle DB or TEQ setup
