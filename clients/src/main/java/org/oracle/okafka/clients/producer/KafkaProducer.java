/*
 ** OKafka Java Client version 23.4.
 **
 ** Copyright (c) 2019, 2024 Oracle and/or its affiliates.
 ** Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.
 */

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.oracle.okafka.clients.producer;

import org.apache.kafka.clients.ApiVersions;
import org.apache.kafka.clients.ClientDnsLookup;
import org.apache.kafka.clients.ClientRequest;
import org.apache.kafka.clients.ClientResponse;
import org.apache.kafka.clients.ClientUtils;
import org.apache.kafka.clients.CommonClientConfigs;
import org.oracle.okafka.clients.KafkaClient;
import org.oracle.okafka.clients.NetworkClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerGroupMetadata;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetCommitCallback;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Partitioner;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.clients.producer.internals.*;
import org.oracle.okafka.clients.producer.internals.RecordAccumulator;
import org.apache.kafka.clients.producer.internals.TransactionManager;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.errors.ApiException;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.DisconnectException;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.ProducerFencedException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.internals.ClusterResourceListeners;
import org.apache.kafka.common.metrics.JmxReporter;
import org.apache.kafka.common.metrics.KafkaMetricsContext;
import org.apache.kafka.common.metrics.MetricConfig;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.metrics.MetricsContext;
import org.apache.kafka.common.metrics.MetricsReporter;
import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.common.record.AbstractRecords;
import org.apache.kafka.common.record.CompressionType;
import org.apache.kafka.common.record.RecordBatch;
import org.apache.kafka.common.requests.JoinGroupRequest;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.utils.AppInfoParser;
import org.apache.kafka.common.utils.KafkaThread;
import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.common.utils.Utils;
import org.oracle.okafka.clients.Metadata;
import org.oracle.okafka.clients.producer.internals.AQKafkaProducer;
import org.oracle.okafka.clients.producer.internals.OracleTransactionManager;
import org.oracle.okafka.clients.producer.internals.OkafkaProducerMetrics;
import org.oracle.okafka.clients.producer.internals.SenderThread;
import org.oracle.okafka.common.config.SslConfigs;
import org.oracle.okafka.common.errors.FeatureNotSupportedException;
import org.oracle.okafka.common.errors.InvalidLoginCredentialsException;
import org.oracle.okafka.common.requests.MetadataResponse;
import org.oracle.okafka.common.utils.ConnectionUtils;
import org.oracle.okafka.common.utils.TNSParser;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An OKafka client that publishes records to the Oracle's Transactional Event
 * Queue (TxEQ) messaging broker.
 * <P>
 * The producer is <i>thread safe</i>.
 * <p>
 * Here is a simple example of using the producer to send records with strings
 * containing sequential numbers as the key/value pairs.
 * 
 * <pre>
 * {@code
 * Properties props = new Properties();
 * props.put("bootstrap.servers", "localhost:1521");
 * props.put("oracle.service.name", "freepdb1");
 * props.put("oracle.net.tns_admin", ".");
 * props.put("linger.ms", 1);
 * props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
 * props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
 *
 * Producer<String, String> producer = new KafkaProducer<>(props);
 * for (int i = 0; i < 100; i++)
 * 	producer.send(new ProducerRecord<String, String>("my-topic", Integer.toString(i), Integer.toString(i)));
 *
 * producer.close();
 * }</pre>
 * <p>
 * This producer connects to Oracle database instance running on local host on
 * port 1521. This is specified by property <code>bootstrap.servers</code> It
 * connects to service named 'freepdb1' specified using property
 * <code>oracle.service.name</code> It reads authentication and other JDBC
 * driver parameters to connect to Oracle database from the directory specified
 * via property <code>oracle.net.tns_admin</code>
 * </p>
 * <p>
 * The producer consists of a pool of buffer space that holds records that
 * haven't yet been transmitted to the server as well as a background I/O thread
 * that is responsible for turning these records into requests and transmitting
 * them to the Oracle database. Failure to close the producer after use will
 * leak these resources.
 * <p>
 * The {@link #send(ProducerRecord) send()} method is asynchronous. When called
 * it adds the record to a buffer of pending record sends and immediately
 * returns. This allows the producer to batch together individual records for
 * efficiency.
 * <p>
 * If the request fails, the producer can automatically retry. The
 * <code>retries</code> setting defaults to <code>Integer.MAX_VALUE</code>.
 * <p>
 * The producer maintains buffers of unsent records for each partition. These
 * buffers are of a size specified by the <code>batch.size</code> config. Making
 * this larger can result in more batching, but requires more memory (since we
 * will generally have one of these buffers for each active partition).
 * <p>
 * By default a buffer is available to send immediately even if there is
 * additional unused space in the buffer. However if you want to reduce the
 * number of requests you can set <code>linger.ms</code> to something greater
 * than 0. This will instruct the producer to wait up to that number of
 * milliseconds before sending a request in hope that more records will arrive
 * to fill up the same batch. This is analogous to Nagle's algorithm in TCP. For
 * example, in the code snippet above, likely all 100 records would be sent in a
 * single request since we set our linger time to 1 millisecond. However this
 * setting would add 1 millisecond of latency to our request waiting for more
 * records to arrive if we didn't fill up the buffer. Note that records that
 * arrive close together in time will generally batch together even with
 * <code>linger.ms=0</code> so under heavy load batching will occur regardless
 * of the linger configuration; however setting this to something larger than 0
 * can lead to fewer, more efficient requests when not under maximal load at the
 * cost of a small amount of latency.
 * <p>
 * The <code>buffer.memory</code> controls the total amount of memory available
 * to the producer for buffering. If records are sent faster than they can be
 * transmitted to the server then this buffer space will be exhausted. When the
 * buffer space is exhausted additional send calls will block. The threshold for
 * time to block is determined by <code>max.block.ms</code> after which it
 * throws a TimeoutException.
 * <p>
 * The <code>key.serializer</code> and <code>value.serializer</code> instruct
 * how to turn the key and value objects the user provides with their
 * <code>ProducerRecord</code> into bytes. You can use the included
 * {@link org.apache.kafka.common.serialization.ByteArraySerializer} or
 * {@link org.apache.kafka.common.serialization.StringSerializer} for simple
 * string or byte types.
 * <p>
 * From OKafka 23.4, the KafkaProducer supports two additional modes: the
 * idempotent producer and the transactional producer. The idempotent producer
 * strengthens OKafka's delivery semantics from at least once to exactly once
 * delivery. In particular producer retries will no longer introduce duplicates.
 * The transactional producer allows an application to send messages to multiple
 * partitions (and topics!) atomically.
 * </p>
 * <p>
 * To enable idempotence, the <code>enable.idempotence</code> configuration must
 * be set to true. If set, the <code>retries</code> config will default to
 * <code>Integer.MAX_VALUE</code>. There are no API changes for the idempotent
 * producer, so existing applications will not need to be modified to take
 * advantage of this feature.
 * </p>
 * <p>
 * To take advantage of the idempotent producer, it is imperative to avoid
 * application level re-sends since these cannot be de-duplicated. As such, if
 * an application enables idempotence, it is recommended to leave the
 * <code>retries</code> config unset, as it will be defaulted to
 * <code>Integer.MAX_VALUE</code>. Additionally, if a
 * {@link #send(ProducerRecord)} returns an error even with infinite retries
 * (for instance if the message expires in the buffer before being sent), then
 * it is recommended to shut down the producer and check the contents of the
 * last produced message to ensure that it is not duplicated. Finally, the
 * producer can only guarantee idempotence for messages sent within a single
 * session.
 * </p>
 * <p>
 * To use the transactional producer and the attendant APIs, application must
 * set the <code>oracle.transactional.producer</code> configuration property to
 * <code>true</code>. The transactional producer is not <i>thread safe</i>.
 * Application should manage the concurrent access of the transactional
 * producer. Transactional producer does not get benefit of batching. Each
 * message is sent to Oracle Transactional Event Queue broker in a separate
 * request.
 * </p>
 * <p>
 * Transactional producer can use {@link #getDBConnection()} to fetch the
 * database connection which is being used to send the records to the Oracle's
 * Transactional Event Queue broker. {@link #commitTransaction()} will
 * atomically commit the DML operation(s) and send operation(s) performed within
 * the current transaction. {@link #abortTransaction()} will atomically
 * roll-back the DML operation and abort the producer records sent within the
 * current transaction.
 * 
 * </p>
 * <p>
 * All the new transactional APIs are blocking and will throw exceptions on
 * failure. The example below illustrates how the new APIs are meant to be used.
 * It is similar to the example above, except that all 100 messages are part of
 * a single transaction.
 * </p>
 * <p>
 * 
 * <pre>
 * {@code
 * Properties props = new Properties();
 * props.put("bootstrap.servers", "localhost:1521");
 * props.put("oracle.service.name", "freepdb1");
 * props.put("oracle.net.tns_admin",".");
 * props.put("oracle.transactional.producer", "true");
 * Producer<String, String> producer = new KafkaProducer<>(props, new StringSerializer(), new StringSerializer());
 *
 * producer.initTransactions();
 *
 * try {
 *     producer.beginTransaction();
 *     Connection dbConn = ((KafkaProducer<String, String> )producer).getDBConnection();
 *     for (int i = 0; i < 100; i++) {
 *     	   ProducerRecord pRecord = new ProducerRecord<>("my-topic", Integer.toString(i), Integer.toString(i))
 *     	   processRecord(dbConn, pRecord);
 *         producer.send(pRecord);
 *     }
 *     producer.commitTransaction();
 * }catch( DisconnectException dcE) {
 *  // Producer is disconnected from Oracle Transactional Event Queue broker.
 *     producer.close();
 * } 
 * catch (KafkaException e) {
 *     // For all exceptions, just abort the transaction and try again.
 *     producer.abortTransaction();
 * }
 * producer.close();
 * } </pre>
 * </p>
 * <p>
 * As is hinted at in the example, there can be only one open transaction per
 * producer. All messages sent between the {@link #beginTransaction()} and
 * {@link #commitTransaction()} calls will be part of a single transaction. When
 * the <code>transactional.id</code> is specified, all messages sent by the
 * producer must be part of a transaction.
 * </p>
 * <p>
 * The transactional producer uses exceptions to communicate error states. In
 * particular, it is not required to specify callbacks for
 * <code>producer.send()</code> or to call <code>.get()</code> on the returned
 * Future. A <code>KafkaException</code> would be thrown if any of the
 * <code>producer.send()</code> or transactional calls hit an recoverable error
 * during a transaction. A <code>DisconnectException</code> would be thrown if
 * <code> producer.commitTransaction()</code> call hit an irrecoverable error
 * during commit. At this point producer cannot confirm if the operations
 * performed within this transaction were successfully committed or not. See the
 * {@link #send(ProducerRecord)} documentation for more details about detecting
 * errors from a transactional send.
 * </p>
 * 
 * <p>
 * By calling <code>producer.abortTransaction()</code> upon receiving a
 * <code>KafkaException</code> we can ensure that any successful writes are
 * marked as aborted, hence keeping the transactional guarantees.
 * </p>
 * <p>
 * OKafka Transactional Producer can also be created by passing a pre-created
 * Oracle database connection through
 * {@link #KafkaProducer(Properties, Connection)} or similar overloaded
 * constructors. Application must set the
 * <code>oracle.transactional.producer</code> property to true here as well.
 * Transactional producer created this way can be used for
 * 'consume-transform-produce' workflow. Below example depicts that. Here a
 * consumer is created. A database connection is retrieved from the
 * KafkaConsumer and passed to create a KafkaProducer. Consumer consumes records
 * from "my-topic1". While processing the records, a transaction is started and
 * within this transaction processed records are send to topic "my-topic2". When
 * KafkaProducer commits the transaction both the consumed and produced records
 * are committed. When KafkaProducer aborts the transaction, all consumed and
 * produced records are rolled-back. Since producer and consumer are using the
 * same database connection all their operations are either committed or aborted
 * atomically.
 * </p>
 * <p>
 * 
 * <pre>
 * {@code
 * Properties commonProps = new Properties();
 * Properties cProps = new Properties();
 * Properties pProps = new Properties();
 *
 * commonProps.put("bootstrap.servers", "localhost:1521");
 * commonProps.put("oracle.service.name", "freepdb1");
 * commonProps.put("oracle.net.tns_admin", ".");
 * 
 * //Create Consumer 
 * cProps.putAll(commonProps);
 * cProps.put("group.id", "S1");
 * cProps.put("enable.auto.commit", "false");
 * Consumer<String, String> consumer = null;
 * Producer<String, String> producer = null;
 * 
 * try {
 * 	consumer = new KafkaConsumer<String, String>(cProps);
 * 	consumer.subscribe(Arrays.asList("my-topic1"));
 * 	Connection conn = ((KafkaConsumer<String, String>) consumer).getDBConnection();
 * 	// Create Producer
 * 	pProps.put("oracle.transactional.producer", "true");
 * 	producer = new KafkaProducer<String, String>(pProps, conn);
 * 
 * 	while (true) {
 * 		ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(10000));
 * 		if (records != null && records.count() > 0) {
 * 			producer.beginTransaction();
 * 			try {
 * 				for (ConsumerRecord<String, String> consumerRecord : records) {
 * 					ProducerRecord<String, String> pRecord = transform(consumerRecord, "my-topic2");
 * 					porducer.send(pRecord);
 * 				}
 * 				// Commit all consumed and produced records
 * 				producer.commitTransaction();
 * 			} catch (DisconnectException dcE) {
 * 				producer.close();
 * 				throw dcE;
 * 			} catch (KafkaException e) {
 * 				// Re-process all the consumed record
 * 				producer.abortTransaction();
 * 			}
 * 		}
 * 	}
 * } finally {
 * 	producer.close();
 * 	consumer.close();
 * }
 * }
 * </pre>
 * </p>
 * 
 */
public class KafkaProducer<K, V> implements Producer<K, V> {

	private final Logger log;
	private static final String JMX_PREFIX = "kafka.producer";
	public static final String NETWORK_THREAD_PREFIX = "kafka-producer-network-thread";
	public static final String PRODUCER_METRIC_GROUP_NAME = "producer-metrics";

	private final String clientId;
	// Visible for testing
	final Metrics metrics;
	private final Partitioner partitioner;
	private final int maxRequestSize;
	private final long totalMemorySize;
	private final Metadata metadata;
	private final RecordAccumulator accumulator;
	private final SenderThread sender;
	private final Thread ioThread;
	private final CompressionType compressionType;
	private final Sensor errors;
	private final Time time;
	private final Serializer<K> keySerializer;
	private final Serializer<V> valueSerializer;
	private final ProducerConfig producerConfig;
	private final long maxBlockTimeMs;
	private final ProducerInterceptors<K, V> interceptors;
	private final ApiVersions apiVersions;
	private final TransactionManager transactionManager;
	private final KafkaClient client;
	private boolean transactionalProducer = false;
	private AQKafkaProducer aqProducer = null;
	private boolean transactionInitDone = false;
	private OracleTransactionManager oracleTransctionManager;
	// Visible for testing
	private final OkafkaProducerMetrics okpMetrics;

	/**
	 * A producer is instantiated by providing a set of key-value pairs as
	 * configuration. Valid configuration strings are documented <a href=
	 * "http://kafka.apache.org/documentation.html#producerconfigs">here</a>. Values
	 * can be either strings or Objects of the appropriate type (for example a
	 * numeric configuration would accept either the string "42" or the integer 42).
	 * <p>
	 * Note: after creating a {@code KafkaProducer} you must always {@link #close()}
	 * it to avoid resource leaks.
	 * 
	 * @param configs The producer configs
	 *
	 */
	public KafkaProducer(final Map<String, Object> configs) {
		this(configs, null, null);
	}

	/**
	 * A producer is instantiated by providing a set of key-value pairs as
	 * configuration. And a valid Connection object to Oracle Database. Valid
	 * configuration strings are documented <a href=
	 * "https://docs.oracle.com/en/database/oracle/oracle-database/23/adque/Kafka_cient_interface_TEQ.html#GUID-D9254E1B-44FC-44C0-B09A-6A42E40989A3">here</a>.
	 * Values can be either strings or Objects of the appropriate type (for example
	 * a numeric configuration would accept either the string "42" or the integer
	 * 42).
	 * <p>
	 * Note: after creating a {@code KafkaProducer} you must always {@link #close()}
	 * it to avoid resource leaks.
	 * 
	 * @param configs The producer configs
	 * @param conn    Connection to Oracle Database
	 *
	 */
	public KafkaProducer(final Map<String, Object> configs, Connection conn) {
		this(configs, null, null, conn);
	}

	/**
	 * A producer is instantiated by providing a set of key-value pairs as
	 * configuration, a key and a value {@link Serializer}. Valid configuration
	 * strings are documented <a href=
	 * "https://docs.oracle.com/en/database/oracle/oracle-database/23/adque/Kafka_cient_interface_TEQ.html#GUID-D9254E1B-44FC-44C0-B09A-6A42E40989A3">here</a>.
	 * Values can be either strings or Objects of the appropriate type (for example
	 * a numeric configuration would accept either the string "42" or the integer
	 * 42).
	 * <p>
	 * Note: after creating a {@code KafkaProducer} you must always {@link #close()}
	 * it to avoid resource leaks.
	 * 
	 * @param configs         The producer configs
	 * @param keySerializer   The serializer for key that implements
	 *                        {@link Serializer}. The configure() method won't be
	 *                        called in the producer when the serializer is passed
	 *                        in directly.
	 * @param valueSerializer The serializer for value that implements
	 *                        {@link Serializer}. The configure() method won't be
	 *                        called in the producer when the serializer is passed
	 *                        in directly.
	 */
	public KafkaProducer(Map<String, Object> configs, Serializer<K> keySerializer, Serializer<V> valueSerializer) {
		this(new ProducerConfig(ProducerConfig.appendSerializerToConfig(configs, keySerializer, valueSerializer)),
				keySerializer, valueSerializer, null, null, null, Time.SYSTEM, null);
	}

	/**
	 * A producer is instantiated by providing a set of key-value pairs as
	 * configuration, a key and a value {@link Serializer} and Connection to Oracle
	 * Database, versioned 23c and above. Valid configuration strings are documented
	 * <a href=
	 * "https://docs.oracle.com/en/database/oracle/oracle-database/23/adque/Kafka_cient_interface_TEQ.html#GUID-D9254E1B-44FC-44C0-B09A-6A42E40989A3">here</a>.
	 * Values can be either strings or Objects of the appropriate type (for example
	 * a numeric configuration would accept either the string "42" or the integer
	 * 42).
	 * <p>
	 * Note: after creating a {@code KafkaProducer} you must always {@link #close()}
	 * it to avoid resource leaks.
	 * 
	 * @param configs         The producer configs
	 * @param keySerializer   The serializer for key that implements
	 *                        {@link Serializer}. The configure() method won't be
	 *                        called in the producer when the serializer is passed
	 *                        in directly.
	 * @param valueSerializer The serializer for value that implements
	 *                        {@link Serializer}. The configure() method won't be
	 *                        called in the producer when the serializer is passed
	 *                        in directly.
	 * @param conn            Connection to Oracle Database
	 * 
	 */
	public KafkaProducer(Map<String, Object> configs, Serializer<K> keySerializer, Serializer<V> valueSerializer,
			Connection conn) {
		this(new ProducerConfig(ProducerConfig.appendSerializerToConfig(configs, keySerializer, valueSerializer)),
				keySerializer, valueSerializer, null, null, null, Time.SYSTEM, conn);
	}

	/**
	 * A producer is instantiated by providing a set of key-value pairs as
	 * configuration. Valid configuration strings are documented <a href=
	 * "https://docs.oracle.com/en/database/oracle/oracle-database/23/adque/Kafka_cient_interface_TEQ.html#GUID-D9254E1B-44FC-44C0-B09A-6A42E40989A3">here</a>.
	 * <p>
	 * Note: after creating a {@code KafkaProducer} you must always {@link #close()}
	 * it to avoid resource leaks.
	 * 
	 * @param properties The producer configs
	 */
	public KafkaProducer(Properties properties) {
		this(properties, null, null);

	}

	/**
	 * A producer is instantiated by providing a set of key-value pairs as
	 * configuration. Valid configuration strings are documented <a href=
	 * "https://docs.oracle.com/en/database/oracle/oracle-database/23/adque/Kafka_cient_interface_TEQ.html#GUID-D9254E1B-44FC-44C0-B09A-6A42E40989A3">here</a>.
	 * <p>
	 * Note: after creating a {@code KafkaProducer} you must always {@link #close()}
	 * it to avoid resource leaks.
	 * 
	 * @param properties The producer configs
	 * @param conn       Connection to Oracle Database
	 */

	public KafkaProducer(Properties properties, Connection conn) {
		this(properties, null, null, conn);
	}

	/**
	 * A producer is instantiated by providing a set of key-value pairs as
	 * configuration, a key and a value {@link Serializer}. Valid configuration
	 * strings are documented <a href=
	 * "https://docs.oracle.com/en/database/oracle/oracle-database/23/adque/Kafka_cient_interface_TEQ.html#GUID-D9254E1B-44FC-44C0-B09A-6A42E40989A3">here</a>.
	 * <p>
	 * Note: after creating a {@code KafkaProducer} you must always {@link #close()}
	 * it to avoid resource leaks.
	 * 
	 * @param properties      The producer configs
	 * @param keySerializer   The serializer for key that implements
	 *                        {@link Serializer}. The configure() method won't be
	 *                        called in the producer when the serializer is passed
	 *                        in directly.
	 * @param valueSerializer The serializer for value that implements
	 *                        {@link Serializer}. The configure() method won't be
	 *                        called in the producer when the serializer is passed
	 *                        in directly.
	 */
	public KafkaProducer(Properties properties, Serializer<K> keySerializer, Serializer<V> valueSerializer) {
		this(Utils.propsToMap(properties), keySerializer, valueSerializer);
	}

	/**
	 * A producer is instantiated by providing a set of key-value pairs as
	 * configuration, a key and a value {@link Serializer} and Connection to Oracle
	 * Database. Valid configuration strings are documented <a href=
	 * "https://docs.oracle.com/en/database/oracle/oracle-database/23/adque/Kafka_cient_interface_TEQ.html#GUID-D9254E1B-44FC-44C0-B09A-6A42E40989A3">here</a>.
	 * <p>
	 * Note: after creating a {@code KafkaProducer} you must always {@link #close()}
	 * it to avoid resource leaks.
	 * 
	 * @param properties      The producer configs
	 * @param keySerializer   The serializer for key that implements
	 *                        {@link Serializer}. The configure() method won't be
	 *                        called in the producer when the serializer is passed
	 *                        in directly.
	 * @param valueSerializer The serializer for value that implements
	 *                        {@link Serializer}. The configure() method won't be
	 *                        called in the producer when the serializer is passed
	 *                        in directly.
	 * @param conn            Connection to Oracle Database
	 */
	public KafkaProducer(Properties properties, Serializer<K> keySerializer, Serializer<V> valueSerializer,
			Connection conn) {
		this(Utils.propsToMap(properties), keySerializer, valueSerializer, conn);
	}

	// visible for testing
	@SuppressWarnings("unchecked")
	KafkaProducer(ProducerConfig config, Serializer<K> keySerializer, Serializer<V> valueSerializer, Metadata metadata,
			KafkaClient kafkaClient, ProducerInterceptors<K, V> interceptors, Time time, Connection conn) {
		try {
			this.producerConfig = config;
			this.time = time;

			String transactionalId = config.getString(ProducerConfig.TRANSACTIONAL_ID_CONFIG);
			this.clientId = config.getString(ProducerConfig.CLIENT_ID_CONFIG);

			LogContext logContext;
			if (transactionalId == null)
				logContext = new LogContext(String.format("[Producer clientId=%s] ", clientId));
			else
				logContext = new LogContext(
						String.format("[Producer clientId=%s, transactionalId=%s] ", clientId, transactionalId));

			log = logContext.logger(KafkaProducer.class);
			log.trace("Starting the Kafka producer");

			try {
				transactionalProducer = config.getBoolean(ProducerConfig.ORACLE_TRANSACTIONAL_PRODUCER);
				oracleTransctionManager = new OracleTransactionManager(logContext);
			} catch (Exception e) {
				transactionalProducer = false;
			}
			log.debug("Transactioal Producer set to " + transactionalProducer);

			Map<String, String> metricTags = Collections.singletonMap("client-id", clientId);
			MetricConfig metricConfig = new MetricConfig()
					.samples(config.getInt(ProducerConfig.METRICS_NUM_SAMPLES_CONFIG))
					.timeWindow(config.getLong(ProducerConfig.METRICS_SAMPLE_WINDOW_MS_CONFIG), TimeUnit.MILLISECONDS)
					.recordLevel(Sensor.RecordingLevel
							.forName(config.getString(ProducerConfig.METRICS_RECORDING_LEVEL_CONFIG)))
					.tags(metricTags);

			List<MetricsReporter> reporters = config.getConfiguredInstances(
					ProducerConfig.METRIC_REPORTER_CLASSES_CONFIG, MetricsReporter.class,
					Collections.singletonMap(ProducerConfig.CLIENT_ID_CONFIG, clientId));

			JmxReporter jmxReporter = new JmxReporter();
			jmxReporter
					.configure(config.originals(Collections.singletonMap(ProducerConfig.CLIENT_ID_CONFIG, clientId)));
			reporters.add(jmxReporter);

			MetricsContext metricsContext = new KafkaMetricsContext(JMX_PREFIX,
					config.originalsWithPrefix(CommonClientConfigs.METRICS_CONTEXT_PREFIX));

			this.metrics = new Metrics(metricConfig, reporters, time, metricsContext);
			this.okpMetrics = new OkafkaProducerMetrics(metrics);
			this.partitioner = config.getConfiguredInstance(ProducerConfig.PARTITIONER_CLASS_CONFIG, Partitioner.class,
					Collections.singletonMap(ProducerConfig.CLIENT_ID_CONFIG, clientId));

			long retryBackoffMs = config.getLong(ProducerConfig.RETRY_BACKOFF_MS_CONFIG);
			if (keySerializer == null) {
				this.keySerializer = config.getConfiguredInstance(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
						Serializer.class);
				this.keySerializer.configure(
						config.originals(Collections.singletonMap(ProducerConfig.CLIENT_ID_CONFIG, clientId)), true);
			} else {
				config.ignore(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG);
				this.keySerializer = keySerializer;
			}
			if (valueSerializer == null) {
				this.valueSerializer = config.getConfiguredInstance(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
						Serializer.class);
				this.valueSerializer.configure(
						config.originals(Collections.singletonMap(ProducerConfig.CLIENT_ID_CONFIG, clientId)), false);
			} else {
				config.ignore(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG);
				this.valueSerializer = valueSerializer;
			}

			List<ProducerInterceptor<K, V>> interceptorList = (List) config.getConfiguredInstances(
					ProducerConfig.INTERCEPTOR_CLASSES_CONFIG, ProducerInterceptor.class,
					Collections.singletonMap(ProducerConfig.CLIENT_ID_CONFIG, clientId));
			if (interceptors != null)
				this.interceptors = interceptors;
			else
				this.interceptors = new ProducerInterceptors<>(interceptorList);

			ClusterResourceListeners clusterResourceListeners = configureClusterResourceListeners(keySerializer,
					valueSerializer, interceptorList, reporters);

			this.maxRequestSize = config.getInt(ProducerConfig.MAX_REQUEST_SIZE_CONFIG);
			this.totalMemorySize = config.getLong(ProducerConfig.BUFFER_MEMORY_CONFIG);
			this.compressionType = CompressionType.forName(config.getString(ProducerConfig.COMPRESSION_TYPE_CONFIG));

			this.maxBlockTimeMs = config.getLong(ProducerConfig.MAX_BLOCK_MS_CONFIG);
			int deliveryTimeoutMs = configureDeliveryTimeout(config, log);

			this.apiVersions = new ApiVersions();
			this.transactionManager = configureTransactionState(config, logContext);

			if (transactionalProducer) {
				this.accumulator = null;
			} else {
				this.accumulator = new RecordAccumulator(logContext, config.getInt(ProducerConfig.BATCH_SIZE_CONFIG),
						this.compressionType, lingerMs(config), retryBackoffMs, deliveryTimeoutMs, metrics,
						PRODUCER_METRIC_GROUP_NAME, time, apiVersions, transactionManager,
						new BufferPool(this.totalMemorySize, config.getInt(ProducerConfig.BATCH_SIZE_CONFIG), metrics,
								time, PRODUCER_METRIC_GROUP_NAME));
			}

			/*
			 * List<InetSocketAddress> addresses = ClientUtils.parseAndValidateAddresses(
			 * config.getList(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG),
			 * config.getString(ProducerConfig.CLIENT_DNS_LOOKUP_CONFIG)); if (metadata !=
			 * null) { this.metadata = metadata; } else { this.metadata = new
			 * ProducerMetadata(retryBackoffMs,
			 * config.getLong(ProducerConfig.METADATA_MAX_AGE_CONFIG),
			 * config.getLong(ProducerConfig.METADATA_MAX_IDLE_CONFIG), logContext,
			 * clusterResourceListeners, Time.SYSTEM); this.metadata.bootstrap(addresses); }
			 */

			List<InetSocketAddress> addresses = null;
			String serviceName = null;
			String instanceName = null;

			if (config.getString(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG).trim().equalsIgnoreCase("PLAINTEXT")) {

				addresses = ClientUtils.parseAndValidateAddresses(
						config.getList(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG),
						ClientDnsLookup.RESOLVE_CANONICAL_BOOTSTRAP_SERVERS_ONLY);
				serviceName = config.getString(ProducerConfig.ORACLE_SERVICE_NAME);
				instanceName = config.getString(ProducerConfig.ORACLE_INSTANCE_NAME);
			} else {
				if (config.getString(SslConfigs.TNS_ALIAS) == null)
					throw new InvalidLoginCredentialsException("Please provide valid connection string");
				TNSParser parser = new TNSParser(config);
				parser.readFile();
				String connStr = parser.getConnectionString(config.getString(SslConfigs.TNS_ALIAS).toUpperCase());
				if (connStr == null)
					throw new InvalidLoginCredentialsException("Please provide valid connection string");
				String host = parser.getProperty(connStr, "HOST");
				String portStr = parser.getProperty(connStr, "PORT");
				serviceName = parser.getProperty(connStr, "SERVICE_NAME");
				int port;
				if (host == null || portStr == null || serviceName == null)
					throw new InvalidLoginCredentialsException("Please provide valid connection string");
				try {
					port = Integer.parseInt(portStr);
				} catch (NumberFormatException nfe) {
					throw new InvalidLoginCredentialsException("Please provide valid connection string");
				}
				instanceName = parser.getProperty(connStr, "INSTANCE_NAME");
				addresses = new ArrayList<>();
				addresses.add(new InetSocketAddress(host, port));
			}
			if (metadata != null) {
				this.metadata = metadata;
			} else {
				this.metadata = new Metadata(retryBackoffMs, config.getLong(ProducerConfig.METADATA_MAX_AGE_CONFIG),
						true, true, clusterResourceListeners, config);

				/*
				 * this.metadata.update(Cluster.bootstrap(addresses, prodConfigs, serviceName,
				 * instanceName), Collections.<String>emptySet(), time.milliseconds());
				 */

				{ // Changes for 2.8.1 :: Create Bootstrap Cluster and pass it to metadata.update
					// We must have OKafka Node with Service Name and Instance Name placed in the
					// bootstrap cluster.
					// For cluster created here, isBootstrapConfigured is not set to TRUE because it
					// is not public

					ArrayList<Node> bootStrapNodeList = new ArrayList<Node>(addresses.size());
					int id = -1;
					ConnectionUtils.remDuplicateEntries(addresses);
					for (InetSocketAddress inetAddr : addresses) {
						org.oracle.okafka.common.Node bootStrapNode = new org.oracle.okafka.common.Node(id--,
								inetAddr.getHostName(), inetAddr.getPort(), serviceName, instanceName);
						bootStrapNode.setBootstrapFlag(true);
						bootStrapNodeList.add((Node) bootStrapNode);
					}
					Cluster bootStrapCluster = new Cluster(null, bootStrapNodeList, new ArrayList<>(0),
							Collections.emptySet(), Collections.emptySet());
					this.metadata.update(bootStrapCluster, Collections.<String>emptySet(), time.milliseconds(), true);
				}
			}

			this.errors = this.metrics.sensor("errors");
			if (kafkaClient != null) {
				client = kafkaClient;
			} else {
				aqProducer = new AQKafkaProducer(logContext, config, time, this.metadata, this.metrics,
						this.oracleTransctionManager);
				client = new NetworkClient(aqProducer, this.metadata, clientId,
						config.getLong(AdminClientConfig.RECONNECT_BACKOFF_MS_CONFIG),
						config.getLong(AdminClientConfig.RECONNECT_BACKOFF_MAX_MS_CONFIG),
						config.getInt(AdminClientConfig.SEND_BUFFER_CONFIG),
						config.getInt(AdminClientConfig.RECEIVE_BUFFER_CONFIG), (int) TimeUnit.HOURS.toMillis(1), time,
						logContext);
			}
			if (transactionalProducer) {
				this.sender = null;
				this.ioThread = null;
				log.debug("Setting externally supplied database conneciton ");
				aqProducer.setExternalDbConnection(conn);
			} else {
				this.sender = newSender(logContext, kafkaClient, this.metadata);
				String ioThreadName = NETWORK_THREAD_PREFIX + " | " + clientId;
				this.ioThread = new KafkaThread(ioThreadName, this.sender, true);
				this.ioThread.start();
			}
			config.logUnused();

			AppInfoParser.registerAppInfo(JMX_PREFIX, clientId, metrics, time.milliseconds());
			log.debug("Kafka producer started");
		} catch (Throwable t) {
			// call close methods if internal objects are already constructed this is to
			// prevent resource leak. see KAFKA-2121
			close(Duration.ofMillis(0), true);
			// now propagate the exception
			throw new KafkaException("Failed to construct kafka producer", t);
		}
	}

	// visible for testing
	SenderThread newSender(LogContext logContext, KafkaClient kafkaClient, Metadata metadata) {
		int maxInflightRequests = configureInflightRequests(producerConfig);
		// int requestTimeoutMs =
		// producerConfig.getInt(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG);
		// ChannelBuilder channelBuilder =
		// ClientUtils.createChannelBuilder(producerConfig, time, logContext);
		// ProducerMetrics metricsRegistry = new ProducerMetrics(this.metrics);
		// Sensor throttleTimeSensor =
		// Sender.throttleTimeSensor(metricsRegistry.senderMetrics);

		/*
		 * KafkaClient client = kafkaClient != null ? kafkaClient : new NetworkClient(
		 * new
		 * Selector(producerConfig.getLong(ProducerConfig.CONNECTIONS_MAX_IDLE_MS_CONFIG
		 * ), this.metrics, time, "producer", channelBuilder, logContext), metadata,
		 * clientId, maxInflightRequests,
		 * producerConfig.getLong(ProducerConfig.RECONNECT_BACKOFF_MS_CONFIG),
		 * producerConfig.getLong(ProducerConfig.RECONNECT_BACKOFF_MAX_MS_CONFIG),
		 * producerConfig.getInt(ProducerConfig.SEND_BUFFER_CONFIG),
		 * producerConfig.getInt(ProducerConfig.RECEIVE_BUFFER_CONFIG),
		 * requestTimeoutMs, producerConfig.getLong(ProducerConfig.
		 * SOCKET_CONNECTION_SETUP_TIMEOUT_MS_CONFIG),
		 * producerConfig.getLong(ProducerConfig.
		 * SOCKET_CONNECTION_SETUP_TIMEOUT_MAX_MS_CONFIG),
		 * ClientDnsLookup.forConfig(producerConfig.getString(ProducerConfig.
		 * CLIENT_DNS_LOOKUP_CONFIG)), time, true, apiVersions, throttleTimeSensor,
		 * logContext);
		 */

		short acks = configureAcks(producerConfig, log);

		/*
		 * return new Sender(logContext, client, metadata, this.accumulator,
		 * maxInflightRequests == 1,
		 * producerConfig.getInt(ProducerConfig.MAX_REQUEST_SIZE_CONFIG), acks,
		 * producerConfig.getInt(ProducerConfig.RETRIES_CONFIG),
		 * metricsRegistry.senderMetrics, time, requestTimeoutMs,
		 * producerConfig.getLong(ProducerConfig.RETRY_BACKOFF_MS_CONFIG),
		 * this.transactionManager, apiVersions);
		 */
		int retries = configureRetries(producerConfig, producerConfig.idempotenceEnabled(), log);

		ProducerMetrics metricsRegistry = new ProducerMetrics(this.metrics);

		return new SenderThread(logContext, this.clientId, client, this.metadata, this.accumulator,
				maxInflightRequests == 1, this.producerConfig, acks, retries, metricsRegistry.senderMetrics,
				Time.SYSTEM);
	}

	private static int configureRetries(ProducerConfig config, boolean idempotenceEnabled, Logger log) {
		boolean userConfiguredRetries = false;
		if (config.originals().containsKey(ProducerConfig.RETRIES_CONFIG)) {
			userConfiguredRetries = true;
		}
		if (idempotenceEnabled && !userConfiguredRetries) {
			// We recommend setting infinite retries when the idempotent producer is
			// enabled, so it makes sense to make
			// this the default.
			log.info("Overriding the default retries config to the recommended value of {} since the idempotent "
					+ "producer is enabled.", Integer.MAX_VALUE);
			return Integer.MAX_VALUE;
		}
		if (idempotenceEnabled && config.getInt(ProducerConfig.RETRIES_CONFIG) == 0) {
			throw new ConfigException(
					"Must set " + ProducerConfig.RETRIES_CONFIG + " to non-zero when using the idempotent producer.");
		}
		return config.getInt(ProducerConfig.RETRIES_CONFIG);
	}

	private static int lingerMs(ProducerConfig config) {
		return (int) Math.min(config.getLong(ProducerConfig.LINGER_MS_CONFIG), Integer.MAX_VALUE);
	}

	private static int configureDeliveryTimeout(ProducerConfig config, Logger log) {
		int deliveryTimeoutMs = config.getInt(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG);
		int lingerMs = lingerMs(config);
		int requestTimeoutMs = config.getInt(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG);
		int lingerAndRequestTimeoutMs = (int) Math.min((long) lingerMs + requestTimeoutMs, Integer.MAX_VALUE);

		if (deliveryTimeoutMs < lingerAndRequestTimeoutMs) {
			if (config.originals().containsKey(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG)) {
				// throw an exception if the user explicitly set an inconsistent value
				throw new ConfigException(
						ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG + " should be equal to or larger than "
								+ ProducerConfig.LINGER_MS_CONFIG + " + " + ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG);
			} else {
				// override deliveryTimeoutMs default value to lingerMs + requestTimeoutMs for
				// backward compatibility
				deliveryTimeoutMs = lingerAndRequestTimeoutMs;
				log.warn("{} should be equal to or larger than {} + {}. Setting it to {}.",
						ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, ProducerConfig.LINGER_MS_CONFIG,
						ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, deliveryTimeoutMs);
			}
		}
		return deliveryTimeoutMs;
	}

	private TransactionManager configureTransactionState(ProducerConfig config, LogContext logContext) {

		TransactionManager transactionManager = null;

		// OKafka does not support Kafka Transactions

		/*
		 * final boolean userConfiguredIdempotence =
		 * config.originals().containsKey(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG);
		 * final boolean userConfiguredTransactions =
		 * config.originals().containsKey(ProducerConfig.TRANSACTIONAL_ID_CONFIG); if
		 * (userConfiguredTransactions && !userConfiguredIdempotence)
		 * log.info("Overriding the default {} to true since {} is specified.",
		 * ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,
		 * ProducerConfig.TRANSACTIONAL_ID_CONFIG);
		 * 
		 * if (config.idempotenceEnabled()) { final String transactionalId =
		 * config.getString(ProducerConfig.TRANSACTIONAL_ID_CONFIG); final int
		 * transactionTimeoutMs =
		 * config.getInt(ProducerConfig.TRANSACTION_TIMEOUT_CONFIG); final long
		 * retryBackoffMs = config.getLong(ProducerConfig.RETRY_BACKOFF_MS_CONFIG);
		 * final boolean autoDowngradeTxnCommit =
		 * config.getBoolean(ProducerConfig.AUTO_DOWNGRADE_TXN_COMMIT);
		 * transactionManager = new TransactionManager( logContext, transactionalId,
		 * transactionTimeoutMs, retryBackoffMs, apiVersions, autoDowngradeTxnCommit);
		 * 
		 * if (transactionManager.isTransactional())
		 * log.info("Instantiated a transactional producer."); else
		 * log.info("Instantiated an idempotent producer."); }
		 */
		return transactionManager;
	}

	private static int configureInflightRequests(ProducerConfig config) {
		if (config.idempotenceEnabled() && 5 < config.getInt(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION)) {
			throw new ConfigException("Must set " + ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION
					+ " to at most 5" + " to use the idempotent producer.");
		}
		return config.getInt(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION);
	}

	private static short configureAcks(ProducerConfig config, Logger log) {
		boolean userConfiguredAcks = config.originals().containsKey(ProducerConfig.ACKS_CONFIG);
		short acks = Short.parseShort(config.getString(ProducerConfig.ACKS_CONFIG));

		if (config.idempotenceEnabled()) {
			if (!userConfiguredAcks)
				log.info("Overriding the default {} to all since idempotence is enabled.", ProducerConfig.ACKS_CONFIG);
			else if (acks != -1)
				throw new ConfigException(
						"Must set " + ProducerConfig.ACKS_CONFIG + " to all in order to use the idempotent "
								+ "producer. Otherwise we cannot guarantee idempotence.");
		}
		return acks;
	}

	/**
	 * Needs to be called before any other methods when the transactional.id is set
	 * in the configuration.
	 *
	 * This method does the following: 1. Ensures any transactions initiated by
	 * previous instances of the producer with the same transactional.id are
	 * completed. If the previous instance had failed with a transaction in
	 * progress, it will be aborted. If the last transaction had begun completion,
	 * but not yet finished, this method awaits its completion. 2. Gets the internal
	 * producer id and epoch, used in all future transactional messages issued by
	 * the producer.
	 *
	 * Note that this method will raise {@link TimeoutException} if the
	 * transactional state cannot be initialized before expiration of
	 * {@code max.block.ms}. Additionally, it will raise {@link InterruptException}
	 * if interrupted. It is safe to retry in either case, but once the
	 * transactional state has been successfully initialized, this method should no
	 * longer be used.
	 *
	 * @throws IllegalStateException                                      if no
	 *                                                                    transactional.id
	 *                                                                    has been
	 *                                                                    configured
	 * @throws org.apache.kafka.common.errors.UnsupportedVersionException fatal
	 *                                                                    error
	 *                                                                    indicating
	 *                                                                    the broker
	 *                                                                    does not
	 *                                                                    support
	 *                                                                    transactions
	 *                                                                    (i.e. if
	 *                                                                    its
	 *                                                                    version is
	 *                                                                    lower than
	 *                                                                    0.11.0.0)
	 * @throws org.apache.kafka.common.errors.AuthorizationException      fatal
	 *                                                                    error
	 *                                                                    indicating
	 *                                                                    that the
	 *                                                                    configured
	 *                                                                    transactional.id
	 *                                                                    is not
	 *                                                                    authorized.
	 *                                                                    See the
	 *                                                                    exception
	 *                                                                    for more
	 *                                                                    details
	 * @throws KafkaException                                             if the
	 *                                                                    producer
	 *                                                                    has
	 *                                                                    encountered
	 *                                                                    a previous
	 *                                                                    fatal
	 *                                                                    error or
	 *                                                                    for any
	 *                                                                    other
	 *                                                                    unexpected
	 *                                                                    error
	 * @throws TimeoutException                                           if the
	 *                                                                    time taken
	 *                                                                    for
	 *                                                                    initialize
	 *                                                                    the
	 *                                                                    transaction
	 *                                                                    has
	 *                                                                    surpassed
	 *                                                                    <code>max.block.ms</code>.
	 * @throws InterruptException                                         if the
	 *                                                                    thread is
	 *                                                                    interrupted
	 *                                                                    while
	 *                                                                    blocked
	 */
	public void initTransactions() {
		// throwIfNoTransactionManager();
		throwIfProducerClosed();
		transactionInitDone = true;
		oracleTransctionManager.initTxn();
		/*
		 * TransactionalRequestResult result =
		 * transactionManager.initializeTransactions(); sender.wakeup();
		 * result.await(maxBlockTimeMs, TimeUnit.MILLISECONDS);
		 */
	}

	/**
	 * Should be called before the start of each new transaction. Note that prior to
	 * the first invocation of this method, you must invoke
	 * {@link #initTransactions()} exactly one time.
	 *
	 * @throws IllegalStateException                                        if no
	 *                                                                      transactional.id
	 *                                                                      has been
	 *                                                                      configured
	 *                                                                      or if
	 *                                                                      {@link #initTransactions()}
	 *                                                                      has not
	 *                                                                      yet been
	 *                                                                      invoked
	 * @throws ProducerFencedException                                      if
	 *                                                                      another
	 *                                                                      producer
	 *                                                                      with the
	 *                                                                      same
	 *                                                                      transactional.id
	 *                                                                      is
	 *                                                                      active
	 * @throws org.apache.kafka.common.errors.InvalidProducerEpochException if the
	 *                                                                      producer
	 *                                                                      has
	 *                                                                      attempted
	 *                                                                      to
	 *                                                                      produce
	 *                                                                      with an
	 *                                                                      old
	 *                                                                      epoch to
	 *                                                                      the
	 *                                                                      partition
	 *                                                                      leader.
	 *                                                                      See the
	 *                                                                      exception
	 *                                                                      for more
	 *                                                                      details
	 * @throws org.apache.kafka.common.errors.UnsupportedVersionException   fatal
	 *                                                                      error
	 *                                                                      indicating
	 *                                                                      the
	 *                                                                      broker
	 *                                                                      does not
	 *                                                                      support
	 *                                                                      transactions
	 *                                                                      (i.e. if
	 *                                                                      its
	 *                                                                      version
	 *                                                                      is lower
	 *                                                                      than
	 *                                                                      0.11.0.0)
	 * @throws org.apache.kafka.common.errors.AuthorizationException        fatal
	 *                                                                      error
	 *                                                                      indicating
	 *                                                                      that the
	 *                                                                      configured
	 *                                                                      transactional.id
	 *                                                                      is not
	 *                                                                      authorized.
	 *                                                                      See the
	 *                                                                      exception
	 *                                                                      for more
	 *                                                                      details
	 * @throws KafkaException                                               if the
	 *                                                                      producer
	 *                                                                      has
	 *                                                                      encountered
	 *                                                                      a
	 *                                                                      previous
	 *                                                                      fatal
	 *                                                                      error or
	 *                                                                      for any
	 *                                                                      other
	 *                                                                      unexpected
	 *                                                                      error
	 */
	public void beginTransaction() throws ProducerFencedException {
		// throwIfNoTransactionManager();
		throwIfProducerClosed();
		long nowNanos = time.nanoseconds();
		if (oracleTransctionManager.getDBConnection() == null) {
			Connection conn = getDBConnection(true);
			oracleTransctionManager.setDBConnection(conn);
		}
		oracleTransctionManager.beginTransaction();
		okpMetrics.recordBeginTxn(time.nanoseconds() - nowNanos);
	}

	/**
	 * This method is not supported for this release of OKafka. It will throw
	 * FeatureNotSupportedException if invoked.
	 * 
	 * Sends a list of specified offsets to the consumer group coordinator, and also
	 * marks those offsets as part of the current transaction. These offsets will be
	 * considered committed only if the transaction is committed successfully. The
	 * committed offset should be the next message your application will consume,
	 * i.e. lastProcessedMessageOffset + 1.
	 * <p>
	 * This method should be used when you need to batch consumed and produced
	 * messages together, typically in a consume-transform-produce pattern. Thus,
	 * the specified {@code consumerGroupId} should be the same as config parameter
	 * {@code group.id} of the used {@link KafkaConsumer consumer}. Note, that the
	 * consumer should have {@code enable.auto.commit=false} and should also not
	 * commit offsets manually (via {@link KafkaConsumer#commitSync(Map) sync} or
	 * {@link KafkaConsumer#commitAsync(Map, OffsetCommitCallback) async} commits).
	 *
	 * @throws IllegalStateException                                               if
	 *                                                                             no
	 *                                                                             transactional.id
	 *                                                                             has
	 *                                                                             been
	 *                                                                             configured,
	 *                                                                             no
	 *                                                                             transaction
	 *                                                                             has
	 *                                                                             been
	 *                                                                             started
	 * @throws ProducerFencedException                                             fatal
	 *                                                                             error
	 *                                                                             indicating
	 *                                                                             another
	 *                                                                             producer
	 *                                                                             with
	 *                                                                             the
	 *                                                                             same
	 *                                                                             transactional.id
	 *                                                                             is
	 *                                                                             active
	 * @throws org.apache.kafka.common.errors.UnsupportedVersionException          fatal
	 *                                                                             error
	 *                                                                             indicating
	 *                                                                             the
	 *                                                                             broker
	 *                                                                             does
	 *                                                                             not
	 *                                                                             support
	 *                                                                             transactions
	 *                                                                             (i.e.
	 *                                                                             if
	 *                                                                             its
	 *                                                                             version
	 *                                                                             is
	 *                                                                             lower
	 *                                                                             than
	 *                                                                             0.11.0.0)
	 * @throws org.apache.kafka.common.errors.UnsupportedForMessageFormatException fatal
	 *                                                                             error
	 *                                                                             indicating
	 *                                                                             the
	 *                                                                             message
	 *                                                                             format
	 *                                                                             used
	 *                                                                             for
	 *                                                                             the
	 *                                                                             offsets
	 *                                                                             topic
	 *                                                                             on
	 *                                                                             the
	 *                                                                             broker
	 *                                                                             does
	 *                                                                             not
	 *                                                                             support
	 *                                                                             transactions
	 * @throws org.apache.kafka.common.errors.AuthorizationException               fatal
	 *                                                                             error
	 *                                                                             indicating
	 *                                                                             that
	 *                                                                             the
	 *                                                                             configured
	 *                                                                             transactional.id
	 *                                                                             is
	 *                                                                             not
	 *                                                                             authorized,
	 *                                                                             or
	 *                                                                             the
	 *                                                                             consumer
	 *                                                                             group
	 *                                                                             id
	 *                                                                             is
	 *                                                                             not
	 *                                                                             authorized.
	 * @throws org.apache.kafka.common.errors.InvalidProducerEpochException        if
	 *                                                                             the
	 *                                                                             producer
	 *                                                                             has
	 *                                                                             attempted
	 *                                                                             to
	 *                                                                             produce
	 *                                                                             with
	 *                                                                             an
	 *                                                                             old
	 *                                                                             epoch
	 *                                                                             to
	 *                                                                             the
	 *                                                                             partition
	 *                                                                             leader.
	 *                                                                             See
	 *                                                                             the
	 *                                                                             exception
	 *                                                                             for
	 *                                                                             more
	 *                                                                             details
	 * @throws KafkaException                                                      if
	 *                                                                             the
	 *                                                                             producer
	 *                                                                             has
	 *                                                                             encountered
	 *                                                                             a
	 *                                                                             previous
	 *                                                                             fatal
	 *                                                                             or
	 *                                                                             abortable
	 *                                                                             error,
	 *                                                                             or
	 *                                                                             for
	 *                                                                             any
	 *                                                                             other
	 *                                                                             unexpected
	 *                                                                             error
	 */
	public void sendOffsetsToTransaction(Map<TopicPartition, OffsetAndMetadata> offsets, String consumerGroupId)
			throws ProducerFencedException {
		throw new FeatureNotSupportedException(
				"Sending offset to transaction feature is not suported for this release.");
		// sendOffsetsToTransaction(offsets, new
		// ConsumerGroupMetadata(consumerGroupId));
	}

	/**
	 * This method is not supported for this release of OKafka. It will throw
	 * FeatureNotSupportedException if invoked.
	 * 
	 * Sends a list of specified offsets to the consumer group coordinator, and also
	 * marks those offsets as part of the current transaction. These offsets will be
	 * considered committed only if the transaction is committed successfully. The
	 * committed offset should be the next message your application will consume,
	 * i.e. lastProcessedMessageOffset + 1.
	 * <p>
	 * This method should be used when you need to batch consumed and produced
	 * messages together, typically in a consume-transform-produce pattern. Thus,
	 * the specified {@code groupMetadata} should be extracted from the used
	 * {@link KafkaConsumer consumer} via {@link KafkaConsumer#groupMetadata()} to
	 * leverage consumer group metadata for stronger fencing than
	 * {@link #sendOffsetsToTransaction(Map, String)} which only sends with consumer
	 * group id.
	 *
	 * <p>
	 * Note, that the consumer should have {@code enable.auto.commit=false} and
	 * should also not commit offsets manually (via
	 * {@link KafkaConsumer#commitSync(Map) sync} or
	 * {@link KafkaConsumer#commitAsync(Map, OffsetCommitCallback) async} commits).
	 * This method will raise {@link TimeoutException} if the producer cannot send
	 * offsets before expiration of {@code max.block.ms}. Additionally, it will
	 * raise {@link InterruptException} if interrupted.
	 *
	 * @throws IllegalStateException                                               if
	 *                                                                             no
	 *                                                                             transactional.id
	 *                                                                             has
	 *                                                                             been
	 *                                                                             configured
	 *                                                                             or
	 *                                                                             no
	 *                                                                             transaction
	 *                                                                             has
	 *                                                                             been
	 *                                                                             started.
	 * @throws ProducerFencedException                                             fatal
	 *                                                                             error
	 *                                                                             indicating
	 *                                                                             another
	 *                                                                             producer
	 *                                                                             with
	 *                                                                             the
	 *                                                                             same
	 *                                                                             transactional.id
	 *                                                                             is
	 *                                                                             active
	 * @throws org.apache.kafka.common.errors.UnsupportedVersionException          fatal
	 *                                                                             error
	 *                                                                             indicating
	 *                                                                             the
	 *                                                                             broker
	 *                                                                             does
	 *                                                                             not
	 *                                                                             support
	 *                                                                             transactions
	 *                                                                             (i.e.
	 *                                                                             if
	 *                                                                             its
	 *                                                                             version
	 *                                                                             is
	 *                                                                             lower
	 *                                                                             than
	 *                                                                             0.11.0.0)
	 *                                                                             or
	 *                                                                             the
	 *                                                                             broker
	 *                                                                             doesn't
	 *                                                                             support
	 *                                                                             latest
	 *                                                                             version
	 *                                                                             of
	 *                                                                             transactional
	 *                                                                             API
	 *                                                                             with
	 *                                                                             consumer
	 *                                                                             group
	 *                                                                             metadata
	 *                                                                             (i.e.
	 *                                                                             if
	 *                                                                             its
	 *                                                                             version
	 *                                                                             is
	 *                                                                             lower
	 *                                                                             than
	 *                                                                             2.5.0).
	 * @throws org.apache.kafka.common.errors.UnsupportedForMessageFormatException fatal
	 *                                                                             error
	 *                                                                             indicating
	 *                                                                             the
	 *                                                                             message
	 *                                                                             format
	 *                                                                             used
	 *                                                                             for
	 *                                                                             the
	 *                                                                             offsets
	 *                                                                             topic
	 *                                                                             on
	 *                                                                             the
	 *                                                                             broker
	 *                                                                             does
	 *                                                                             not
	 *                                                                             support
	 *                                                                             transactions
	 * @throws org.apache.kafka.common.errors.AuthorizationException               fatal
	 *                                                                             error
	 *                                                                             indicating
	 *                                                                             that
	 *                                                                             the
	 *                                                                             configured
	 *                                                                             transactional.id
	 *                                                                             is
	 *                                                                             not
	 *                                                                             authorized,
	 *                                                                             or
	 *                                                                             the
	 *                                                                             consumer
	 *                                                                             group
	 *                                                                             id
	 *                                                                             is
	 *                                                                             not
	 *                                                                             authorized.
	 * @throws org.apache.kafka.clients.consumer.CommitFailedException             if
	 *                                                                             the
	 *                                                                             commit
	 *                                                                             failed
	 *                                                                             and
	 *                                                                             cannot
	 *                                                                             be
	 *                                                                             retried
	 *                                                                             (e.g.
	 *                                                                             if
	 *                                                                             the
	 *                                                                             consumer
	 *                                                                             has
	 *                                                                             been
	 *                                                                             kicked
	 *                                                                             out
	 *                                                                             of
	 *                                                                             the
	 *                                                                             group).
	 *                                                                             Users
	 *                                                                             should
	 *                                                                             handle
	 *                                                                             this
	 *                                                                             by
	 *                                                                             aborting
	 *                                                                             the
	 *                                                                             transaction.
	 * @throws org.apache.kafka.common.errors.FencedInstanceIdException            if
	 *                                                                             this
	 *                                                                             producer
	 *                                                                             instance
	 *                                                                             gets
	 *                                                                             fenced
	 *                                                                             by
	 *                                                                             broker
	 *                                                                             due
	 *                                                                             to
	 *                                                                             a
	 *                                                                             mis-configured
	 *                                                                             consumer
	 *                                                                             instance
	 *                                                                             id
	 *                                                                             within
	 *                                                                             group
	 *                                                                             metadata.
	 * @throws org.apache.kafka.common.errors.InvalidProducerEpochException        if
	 *                                                                             the
	 *                                                                             producer
	 *                                                                             has
	 *                                                                             attempted
	 *                                                                             to
	 *                                                                             produce
	 *                                                                             with
	 *                                                                             an
	 *                                                                             old
	 *                                                                             epoch
	 *                                                                             to
	 *                                                                             the
	 *                                                                             partition
	 *                                                                             leader.
	 *                                                                             See
	 *                                                                             the
	 *                                                                             exception
	 *                                                                             for
	 *                                                                             more
	 *                                                                             details
	 * @throws KafkaException                                                      if
	 *                                                                             the
	 *                                                                             producer
	 *                                                                             has
	 *                                                                             encountered
	 *                                                                             a
	 *                                                                             previous
	 *                                                                             fatal
	 *                                                                             or
	 *                                                                             abortable
	 *                                                                             error,
	 *                                                                             or
	 *                                                                             for
	 *                                                                             any
	 *                                                                             other
	 *                                                                             unexpected
	 *                                                                             error
	 * @throws TimeoutException                                                    if
	 *                                                                             the
	 *                                                                             time
	 *                                                                             taken
	 *                                                                             for
	 *                                                                             sending
	 *                                                                             offsets
	 *                                                                             has
	 *                                                                             surpassed
	 *                                                                             max.block.ms.
	 * @throws InterruptException                                                  if
	 *                                                                             the
	 *                                                                             thread
	 *                                                                             is
	 *                                                                             interrupted
	 *                                                                             while
	 *                                                                             blocked
	 */
	public void sendOffsetsToTransaction(Map<TopicPartition, OffsetAndMetadata> offsets,
			ConsumerGroupMetadata groupMetadata) throws ProducerFencedException {

		throw new FeatureNotSupportedException(
				"Sending offset to transaction feature is not suported for this release.");
		/*
		 * throwIfInvalidGroupMetadata(groupMetadata); throwIfNoTransactionManager();
		 * throwIfProducerClosed(); TransactionalRequestResult result =
		 * transactionManager.sendOffsetsToTransaction(offsets, groupMetadata);
		 * sender.wakeup(); result.await(maxBlockTimeMs, TimeUnit.MILLISECONDS);+
		 */
	}

	/**
	 * Commits the ongoing transaction. This method will flush any unsent records
	 * before actually committing the transaction.
	 *
	 * Further, if any of the {@link #send(ProducerRecord)} calls which were part of
	 * the transaction hit irrecoverable errors, this method will throw the last
	 * received exception immediately and the transaction will not be committed. So
	 * all {@link #send(ProducerRecord)} calls in a transaction must succeed in
	 * order for this method to succeed.
	 *
	 * Note that this method will raise {@link TimeoutException} if the transaction
	 * cannot be committed before expiration of {@code max.block.ms}. Additionally
	 * {@link InterruptException} if interrupted. It is safe to retry in either
	 * case, but it is not possible to attempt a different operation (such as
	 * abortTransaction) since the commit may already be in the progress of
	 * completing. If not retrying, the only option is to close the producer.
	 *
	 * @throws IllegalStateException                                        if no
	 *                                                                      transactional.id
	 *                                                                      has been
	 *                                                                      configured
	 *                                                                      or no
	 *                                                                      transaction
	 *                                                                      has been
	 *                                                                      started
	 * @throws ProducerFencedException                                      fatal
	 *                                                                      error
	 *                                                                      indicating
	 *                                                                      another
	 *                                                                      producer
	 *                                                                      with the
	 *                                                                      same
	 *                                                                      transactional.id
	 *                                                                      is
	 *                                                                      active
	 * @throws org.apache.kafka.common.errors.UnsupportedVersionException   fatal
	 *                                                                      error
	 *                                                                      indicating
	 *                                                                      the
	 *                                                                      broker
	 *                                                                      does not
	 *                                                                      support
	 *                                                                      transactions
	 *                                                                      (i.e. if
	 *                                                                      its
	 *                                                                      version
	 *                                                                      is lower
	 *                                                                      than
	 *                                                                      0.11.0.0)
	 * @throws org.apache.kafka.common.errors.AuthorizationException        fatal
	 *                                                                      error
	 *                                                                      indicating
	 *                                                                      that the
	 *                                                                      configured
	 *                                                                      transactional.id
	 *                                                                      is not
	 *                                                                      authorized.
	 *                                                                      See the
	 *                                                                      exception
	 *                                                                      for more
	 *                                                                      details
	 * @throws org.apache.kafka.common.errors.InvalidProducerEpochException if the
	 *                                                                      producer
	 *                                                                      has
	 *                                                                      attempted
	 *                                                                      to
	 *                                                                      produce
	 *                                                                      with an
	 *                                                                      old
	 *                                                                      epoch to
	 *                                                                      the
	 *                                                                      partition
	 *                                                                      leader.
	 *                                                                      See the
	 *                                                                      exception
	 *                                                                      for more
	 *                                                                      details
	 * @throws KafkaException                                               if the
	 *                                                                      producer
	 *                                                                      has
	 *                                                                      encountered
	 *                                                                      a
	 *                                                                      previous
	 *                                                                      fatal or
	 *                                                                      abortable
	 *                                                                      error,
	 *                                                                      or for
	 *                                                                      any
	 *                                                                      other
	 *                                                                      unexpected
	 *                                                                      error
	 * @throws TimeoutException                                             if the
	 *                                                                      time
	 *                                                                      taken
	 *                                                                      for
	 *                                                                      committing
	 *                                                                      the
	 *                                                                      transaction
	 *                                                                      has
	 *                                                                      surpassed
	 *                                                                      <code>max.block.ms</code>.
	 * @throws InterruptException                                           if the
	 *                                                                      thread
	 *                                                                      is
	 *                                                                      interrupted
	 *                                                                      while
	 *                                                                      blocked
	 */
	public void commitTransaction() throws DisconnectException, KafkaException {

		if (!transactionalProducer) {
			throw new KafkaException("KafkaProducer is not an Oracle Transactional Producer."
					+ "Please set oracle.transactional.producer property to true.");
		}
		throwIfProducerClosed();
		try {
			long nowNanos = time.nanoseconds();
			oracleTransctionManager.commitTransaction();
			okpMetrics.recordCommitTxn(time.nanoseconds() - nowNanos);
		} catch (DisconnectException dE) {
			throw dE;
		} catch (KafkaException kE) {
			throw kE;
		} catch (Exception e) {
			KafkaException okafkaE = new KafkaException("Exception while committing transaction:" + e.getMessage(), e);
			throw okafkaE;
		}

		/*
		 * TransactionalRequestResult result = transactionManager.beginCommit();
		 * sender.wakeup(); result.await(maxBlockTimeMs, TimeUnit.MILLISECONDS);
		 */
	}

	/**
	 * Aborts the ongoing transaction. Any unflushed produce messages will be
	 * aborted when this call is made. This call will throw an exception immediately
	 * if any prior {@link #send(ProducerRecord)} calls failed with a
	 * {@link ProducerFencedException} or an instance of
	 * {@link org.apache.kafka.common.errors.AuthorizationException}.
	 *
	 * Note that this method will raise {@link TimeoutException} if the transaction
	 * cannot be aborted before expiration of {@code max.block.ms}. Additionally, it
	 * will raise {@link InterruptException} if interrupted. It is safe to retry in
	 * either case, but it is not possible to attempt a different operation (such as
	 * commitTransaction) since the abort may already be in the progress of
	 * completing. If not retrying, the only option is to close the producer.
	 *
	 * @throws IllegalStateException                                        if no
	 *                                                                      transactional.id
	 *                                                                      has been
	 *                                                                      configured
	 *                                                                      or no
	 *                                                                      transaction
	 *                                                                      has been
	 *                                                                      started
	 * @throws ProducerFencedException                                      fatal
	 *                                                                      error
	 *                                                                      indicating
	 *                                                                      another
	 *                                                                      producer
	 *                                                                      with the
	 *                                                                      same
	 *                                                                      transactional.id
	 *                                                                      is
	 *                                                                      active
	 * @throws org.apache.kafka.common.errors.InvalidProducerEpochException if the
	 *                                                                      producer
	 *                                                                      has
	 *                                                                      attempted
	 *                                                                      to
	 *                                                                      produce
	 *                                                                      with an
	 *                                                                      old
	 *                                                                      epoch to
	 *                                                                      the
	 *                                                                      partition
	 *                                                                      leader.
	 *                                                                      See the
	 *                                                                      exception
	 *                                                                      for more
	 *                                                                      details
	 * @throws org.apache.kafka.common.errors.UnsupportedVersionException   fatal
	 *                                                                      error
	 *                                                                      indicating
	 *                                                                      the
	 *                                                                      broker
	 *                                                                      does not
	 *                                                                      support
	 *                                                                      transactions
	 *                                                                      (i.e. if
	 *                                                                      its
	 *                                                                      version
	 *                                                                      is lower
	 *                                                                      than
	 *                                                                      0.11.0.0)
	 * @throws org.apache.kafka.common.errors.AuthorizationException        fatal
	 *                                                                      error
	 *                                                                      indicating
	 *                                                                      that the
	 *                                                                      configured
	 *                                                                      transactional.id
	 *                                                                      is not
	 *                                                                      authorized.
	 *                                                                      See the
	 *                                                                      exception
	 *                                                                      for more
	 *                                                                      details
	 * @throws KafkaException                                               if the
	 *                                                                      producer
	 *                                                                      has
	 *                                                                      encountered
	 *                                                                      a
	 *                                                                      previous
	 *                                                                      fatal
	 *                                                                      error or
	 *                                                                      for any
	 *                                                                      other
	 *                                                                      unexpected
	 *                                                                      error
	 * @throws TimeoutException                                             if the
	 *                                                                      time
	 *                                                                      taken
	 *                                                                      for
	 *                                                                      aborting
	 *                                                                      the
	 *                                                                      transaction
	 *                                                                      has
	 *                                                                      surpassed
	 *                                                                      <code>max.block.ms</code>.
	 * @throws InterruptException                                           if the
	 *                                                                      thread
	 *                                                                      is
	 *                                                                      interrupted
	 *                                                                      while
	 *                                                                      blocked
	 */
	public void abortTransaction() throws ProducerFencedException {
		// throwIfNoTransactionManager();
		throwIfProducerClosed();
		log.info("Aborting incomplete transaction");
		if (!transactionalProducer) {
			throw new KafkaException("KafkaProducer is not an Oracle Transactional Producer."
					+ "Please set oracle.transactional.producer property to true.");
		}
		try {
			long nowNanos = time.nanoseconds();
			oracleTransctionManager.abortTransaction();
			okpMetrics.recordAbortTxn(time.nanoseconds() - nowNanos);
		}
		catch (DisconnectException dE) {
			throw dE;
		} catch (Exception e) {
			KafkaException okafkaE = new KafkaException("Exception while aborting transaction:" + e.getMessage(), e);
			throw okafkaE;
		}
	}

	/**
	 * Asynchronously send a record to a topic. Equivalent to
	 * <code>send(record, null)</code>. See {@link #send(ProducerRecord, Callback)}
	 * for details.
	 */
	@Override
	public Future<RecordMetadata> send(ProducerRecord<K, V> record) {
		ProducerRecord<K,V> recordUp = new ProducerRecord<K,V>(record.topic().toUpperCase(),record.partition(), record.timestamp(),  record.key(), record.value(), record.headers());
		return send(recordUp, null);
	}

	/**
	 * Asynchronously send a record to a topic and invoke the provided callback when
	 * the send has been acknowledged.
	 * <p>
	 * The send is asynchronous and this method will return immediately once the
	 * record has been stored in the buffer of records waiting to be sent. This
	 * allows sending many records in parallel without blocking to wait for the
	 * response after each one.
	 * <p>
	 * The result of the send is a {@link RecordMetadata} specifying the partition
	 * the record was sent to, the offset it was assigned and the timestamp of the
	 * record. If {@link org.apache.kafka.common.record.TimestampType#CREATE_TIME
	 * CreateTime} is used by the topic, the timestamp will be the user provided
	 * timestamp or the record send time if the user did not specify a timestamp for
	 * the record. If
	 * {@link org.apache.kafka.common.record.TimestampType#LOG_APPEND_TIME
	 * LogAppendTime} is used for the topic, the timestamp will be the Kafka broker
	 * local time when the message is appended.
	 * <p>
	 * Since the send call is asynchronous it returns a
	 * {@link java.util.concurrent.Future Future} for the {@link RecordMetadata}
	 * that will be assigned to this record. Invoking
	 * {@link java.util.concurrent.Future#get() get()} on this future will block
	 * until the associated request completes and then return the metadata for the
	 * record or throw any exception that occurred while sending the record.
	 * <p>
	 * If you want to simulate a simple blocking call you can call the
	 * <code>get()</code> method immediately:
	 *
	 * <pre>
	 * {@code
	 * byte[] key = "key".getBytes();
	 * byte[] value = "value".getBytes();
	 * ProducerRecord<byte[],byte[]> record = new ProducerRecord<byte[],byte[]>("my-topic", key, value)
	 * producer.send(record).get();
	 * }</pre>
	 * <p>
	 * Fully non-blocking usage can make use of the {@link Callback} parameter to
	 * provide a callback that will be invoked when the request is complete.
	 *
	 * <pre>
	 * {@code
	 * ProducerRecord<byte[], byte[]> record = new ProducerRecord<byte[], byte[]>("the-topic", key, value);
	 * producer.send(myRecord, new Callback() {
	 * 	public void onCompletion(RecordMetadata metadata, Exception e) {
	 * 		if (e != null) {
	 * 			e.printStackTrace();
	 * 		} else {
	 * 			System.out.println("The offset of the record we just sent is: " + metadata.offset());
	 * 		}
	 * 	}
	 * });
	 * }
	 * </pre>
	 *
	 * Callbacks for records being sent to the same partition are guaranteed to
	 * execute in order. That is, in the following example <code>callback1</code> is
	 * guaranteed to execute before <code>callback2</code>:
	 *
	 * <pre>
	 * {@code
	 * producer.send(new ProducerRecord<byte[], byte[]>(topic, partition, key1, value1), callback1);
	 * producer.send(new ProducerRecord<byte[], byte[]>(topic, partition, key2, value2), callback2);
	 * }
	 * </pre>
	 * <p>
	 * When used as part of a transaction, it is not necessary to define a callback
	 * or check the result of the future in order to detect errors from
	 * <code>send</code>. If any of the send calls failed with an irrecoverable
	 * error, the final {@link #commitTransaction()} call will fail and throw the
	 * exception from the last failed send. When this happens, your application
	 * should call {@link #abortTransaction()} to reset the state and continue to
	 * send data.
	 * </p>
	 * <p>
	 * Some transactional send errors cannot be resolved with a call to
	 * {@link #abortTransaction()}. In particular, if a transactional send finishes
	 * with a {@link ProducerFencedException}, a
	 * {@link org.apache.kafka.common.errors.OutOfOrderSequenceException}, a
	 * {@link org.apache.kafka.common.errors.UnsupportedVersionException}, or an
	 * {@link org.apache.kafka.common.errors.AuthorizationException}, then the only
	 * option left is to call {@link #close()}. Fatal errors cause the producer to
	 * enter a defunct state in which future API calls will continue to raise the
	 * same underyling error wrapped in a new {@link KafkaException}.
	 * </p>
	 * <p>
	 * It is a similar picture when idempotence is enabled, but no
	 * <code>transactional.id</code> has been configured. In this case,
	 * {@link org.apache.kafka.common.errors.UnsupportedVersionException} and
	 * {@link org.apache.kafka.common.errors.AuthorizationException} are considered
	 * fatal errors. However, {@link ProducerFencedException} does not need to be
	 * handled. Additionally, it is possible to continue sending after receiving an
	 * {@link org.apache.kafka.common.errors.OutOfOrderSequenceException}, but doing
	 * so can result in out of order delivery of pending messages. To ensure proper
	 * ordering, you should close the producer and create a new instance.
	 * </p>
	 * <p>
	 * If the message format of the destination topic is not upgraded to 0.11.0.0,
	 * idempotent and transactional produce requests will fail with an
	 * {@link org.apache.kafka.common.errors.UnsupportedForMessageFormatException}
	 * error. If this is encountered during a transaction, it is possible to abort
	 * and continue. But note that future sends to the same topic will continue
	 * receiving the same exception until the topic is upgraded.
	 * </p>
	 * <p>
	 * Note that callbacks will generally execute in the I/O thread of the producer
	 * and so should be reasonably fast or they will delay the sending of messages
	 * from other threads. If you want to execute blocking or computationally
	 * expensive callbacks it is recommended to use your own
	 * {@link java.util.concurrent.Executor} in the callback body to parallelize
	 * processing.
	 *
	 * @param record   The record to send
	 * @param callback A user-supplied callback to execute when the record has been
	 *                 acknowledged by the server (null indicates no callback)
	 *
	 * @throws AuthenticationException if authentication fails. See the exception
	 *                                 for more details
	 * @throws AuthorizationException  fatal error indicating that the producer is
	 *                                 not allowed to write
	 * @throws IllegalStateException   if a transactional.id has been configured and
	 *                                 no transaction has been started, or when send
	 *                                 is invoked after producer has been closed.
	 * @throws InterruptException      If the thread is interrupted while blocked
	 * @throws SerializationException  If the key or value are not valid objects
	 *                                 given the configured serializers
	 * @throws KafkaException          If a Kafka related error occurs that does not
	 *                                 belong to the public API exceptions.
	 */
	@Override
	public Future<RecordMetadata> send(ProducerRecord<K, V> record, Callback callback) {
		// intercept the record, which can be potentially modified; this method does not
		// throw exceptions
		ProducerRecord<K, V> interceptedRecord = this.interceptors.onSend(record);
		return doSend(interceptedRecord, callback);
	}

	// Verify that this producer instance has not been closed. This method throws
	// IllegalStateException if the producer
	// has already been closed.
	private void throwIfProducerClosed() {
		if (transactionalProducer) {
			if (aqProducer == null || aqProducer.isClosed())
				throw new IllegalStateException("Cannot perform operation after producer has been closed");
		} else if (sender == null || !sender.isRunning())
			throw new IllegalStateException("Cannot perform operation after producer has been closed");
	}

	/**
	 * Implementation of asynchronously send a record to a topic.
	 */
	private Future<RecordMetadata> doSend(ProducerRecord<K, V> record, Callback callback) {
		TopicPartition tp = null;
		try {
			if (!transactionalProducer) {
				throwIfProducerClosed();
			}
			// first make sure the metadata for the topic is available
			long nowMs = time.milliseconds();
			ClusterAndWaitTime clusterAndWaitTime;
			try {
				clusterAndWaitTime = waitOnMetadata(record.topic(), record.partition(), nowMs, maxBlockTimeMs);
			} catch (KafkaException e) {
				if (metadata.isClosed())
					throw new KafkaException("Producer closed while metadata fetch was in progress", e);
				throw e;
			}
			nowMs += clusterAndWaitTime.waitedOnMetadataMs;
			long remainingWaitMs = Math.max(0, maxBlockTimeMs - clusterAndWaitTime.waitedOnMetadataMs);
			Cluster cluster = clusterAndWaitTime.cluster;

			byte[] serializedKey;
			try {
				serializedKey = keySerializer.serialize(record.topic(), record.headers(), record.key());
			} catch (ClassCastException cce) {
				throw new SerializationException("Can't convert key of class " + record.key().getClass().getName()
						+ " to class " + producerConfig.getClass(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG).getName()
						+ " specified in key.serializer", cce);
			}

			byte[] serializedValue;
			try {
				serializedValue = valueSerializer.serialize(record.topic(), record.headers(), record.value());

			} catch (ClassCastException cce) {
				throw new SerializationException("Can't convert value of class " + record.value().getClass().getName()
						+ " to class " + producerConfig.getClass(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG).getName()
						+ " specified in value.serializer", cce);
			}
			int partition = partition(record, serializedKey, serializedValue, cluster);
			tp = new TopicPartition(record.topic(), partition);

			setReadOnly(record.headers());
			Header[] headers = record.headers().toArray();

			int serializedSize = AbstractRecords.estimateSizeInBytesUpperBound(apiVersions.maxUsableProduceMagic(),
					compressionType, serializedKey, serializedValue, headers);

			ensureValidRecordSize(serializedSize);

			long timestamp = record.timestamp() == null ? nowMs : record.timestamp();
			if (log.isTraceEnabled()) {
				log.trace("Attempting to append record {} with callback {} to topic {} partition {}", record, callback,
						record.topic(), partition);
			}
			// producer callback will make sure to call both 'callback' and interceptor
			// callback
			Callback interceptCallback = new InterceptorCallback<>(callback, this.interceptors, tp);

			if (transactionalProducer) {
				return aqProducer.transactionalSend(tp, serializedKey, serializedValue, headers, interceptCallback);
			} else {
				RecordAccumulator.RecordAppendResult result = accumulator.append(tp, timestamp, serializedKey,
						serializedValue, headers, interceptCallback, remainingWaitMs, true, nowMs);

				if (result.abortForNewBatch) {
					int prevPartition = partition;
					partitioner.onNewBatch(record.topic(), cluster, prevPartition);
					partition = partition(record, serializedKey, serializedValue, cluster);
					tp = new TopicPartition(record.topic(), partition);
					if (log.isTraceEnabled()) {
						log.trace(
								"Retrying append due to new batch creation for topic {} partition {}. The old partition was {}",
								record.topic(), partition, prevPartition);
					}
					// producer callback will make sure to call both 'callback' and interceptor
					// callback
					interceptCallback = new InterceptorCallback<>(callback, this.interceptors, tp);
					result = accumulator.append(tp, timestamp, serializedKey, serializedValue, headers,
							interceptCallback, remainingWaitMs, false, nowMs);
				}

				if (result.batchIsFull || result.newBatchCreated) {
					log.trace("Waking up the sender since topic {} partition {} is either full or getting a new batch",
							record.topic(), partition);
					this.sender.wakeup();
				}
				return result.future;
			}

			// handling exceptions and record the errors;
			// for API exceptions return them in the future,
			// for other exceptions throw directly
		} catch (DisconnectException e) {
			this.errors.record();
			this.interceptors.onSendError(record, tp, e);
			throw e;
		} catch (ApiException e) {
			log.debug("Exception occurred during message send:", e);
			if (callback != null)
				callback.onCompletion(null, e);
			this.errors.record();
			this.interceptors.onSendError(record, tp, e);
			return new FutureFailure(e);
		} catch (InterruptedException e) {
			this.errors.record();
			this.interceptors.onSendError(record, tp, e);
			throw new InterruptException(e);
		} catch (KafkaException e) {
			this.errors.record();
			this.interceptors.onSendError(record, tp, e);
			throw e;
		} catch (Exception e) {
			// we notify interceptor about all exceptions, since onSend is called before
			// anything else in this method
			this.interceptors.onSendError(record, tp, e);
			throw e;
		}
	}

	private void setReadOnly(Headers headers) {
		if (headers instanceof RecordHeaders) {
			((RecordHeaders) headers).setReadOnly();
		}
	}

	/**
	 * Wait for cluster metadata including partitions for the given topic to be
	 * available.
	 * 
	 * @param topic     The topic we want metadata for
	 * @param partition A specific partition expected to exist in metadata, or null
	 *                  if there's no preference
	 * @param nowMs     The current time in ms
	 * @param maxWaitMs The maximum time in ms for waiting on the metadata
	 * @return The cluster containing topic metadata and the amount of time we
	 *         waited in ms
	 * @throws TimeoutException if metadata could not be refreshed within
	 *                          {@code max.block.ms}
	 * @throws KafkaException   for all Kafka-related exceptions, including the case
	 *                          where this method is called after producer close
	 */
	private ClusterAndWaitTime waitOnMetadata(String topic, Integer partition, long nowMs, long maxWaitMs)
			throws InterruptedException, DisconnectException {
		// add topic to metadata topic list if it is not there already and reset expiry
		Cluster cluster = metadata.fetch();

		if (cluster.invalidTopics().contains(topic))
			throw new InvalidTopicException(topic);

		metadata.add(topic, nowMs);
		Integer partitionsCount = cluster.partitionCountForTopic(topic);
		// Return cached metadata if we have it, and if the record's partition is either
		// undefined
		// or within the known partition range
		if (partitionsCount != null && (partition == null || partition < partitionsCount)) {
			return new ClusterAndWaitTime(cluster, 0);
		}

		long remainingWaitMs = maxWaitMs;
		long elapsed = 0;
		if (transactionalProducer) {
			org.oracle.okafka.common.requests.AbstractRequest.Builder<org.oracle.okafka.common.requests.MetadataRequest> metadataRequest = null;
			List<String> topicList = new ArrayList<>(metadata.topics());
			metadataRequest = new org.oracle.okafka.common.requests.MetadataRequest.Builder(topicList,
					metadata.allowAutoTopicCreation(), topicList);

			Node requestNode = metadata.getLeader() != null ? metadata.getLeader() : metadata.fetch().nodes().get(0);

			// ToDo: Check if it is right to use the first node always. We may have a valid
			// connection already with node 2.

			ClientRequest clientRequest = client.newClientRequest((org.oracle.okafka.common.Node) requestNode,
					metadataRequest, time.milliseconds(), true);

			// ToDo: Check if node needs to be send instead of null
			Connection conn = getDBConnection(true);
			log.debug("Fetch Metadata using connection " + conn);
			ClientResponse response = aqProducer.getMetadataNow(clientRequest, conn,
					(org.oracle.okafka.common.Node) requestNode, true);
			MetadataResponse mResponse = (MetadataResponse) response.responseBody();
			if (response.wasDisconnected()) {
				String excpMsg = "Exception while fetching Metadata. Database connection found closed.";
				throw new DisconnectException(excpMsg);
			}
			for (String topicM : metadata.topics()) {
				try {
					aqProducer.fetchQueueParameters(topicM, conn, metadata.topicParaMap);
				} catch (SQLException e) {
					log.error("Exception while fetching TEQ parameters and updating metadata " + e.getMessage());
				}
			}
			elapsed = time.milliseconds() - nowMs;
			Cluster newCluster = mResponse.cluster();
			metadata.update(newCluster, null, time.milliseconds(), false);
			return new ClusterAndWaitTime(newCluster, elapsed);
		}

		// Issue metadata requests until we have metadata for the topic and the
		// requested partition,
		// or until maxWaitTimeMs is exceeded. This is necessary in case the metadata
		// is stale and the number of partitions for this topic has increased in the
		// meantime.
		long nowNanos = time.nanoseconds();
		do {
			if (partition != null) {
				log.trace("Requesting metadata update for partition {} of topic {}.", partition, topic);
			} else {
				log.trace("Requesting metadata update for topic {}.", topic);
			}
			// metadata.add(topic, nowMs + elapsed);
			// int version = metadata.requestUpdateForTopic(topic);
			int version = metadata.requestUpdate();
			sender.wakeup();
			try {
				metadata.awaitUpdate(version, remainingWaitMs);
			} catch (TimeoutException ex) {
				// Rethrow with original maxWaitMs to prevent logging exception with
				// remainingWaitMs
				throw new TimeoutException(
						String.format("Topic %s not present in metadata after %d ms.", topic, maxWaitMs));
			}
			cluster = metadata.fetch();
			elapsed = time.milliseconds() - nowMs;
			if (elapsed >= maxWaitMs) {
				throw new TimeoutException(partitionsCount == null
						? String.format("Topic %s not present in metadata after %d ms.", topic, maxWaitMs)
						: String.format(
								"Partition %d of topic %s with partition count %d is not present in metadata after %d ms.",
								partition, topic, partitionsCount, maxWaitMs));
			}
			// metadata.maybeThrowExceptionForTopic(topic);
			remainingWaitMs = maxWaitMs - elapsed;
			partitionsCount = cluster.partitionCountForTopic(topic);
		} while (partitionsCount == null || (partition != null && partition >= partitionsCount));
		okpMetrics.recordMetadataWait(time.nanoseconds() - nowNanos);
		return new ClusterAndWaitTime(cluster, elapsed);
	}

	/**
	 * Validate that the record size isn't too large
	 */
	private void ensureValidRecordSize(int size) {
		if (size > maxRequestSize)
			throw new RecordTooLargeException("The message is " + size + " bytes when serialized which is larger than "
					+ maxRequestSize + ", which is the value of the " + ProducerConfig.MAX_REQUEST_SIZE_CONFIG
					+ " configuration.");
		if (size > totalMemorySize)
			throw new RecordTooLargeException("The message is " + size
					+ " bytes when serialized which is larger than the total memory buffer you have configured with the "
					+ ProducerConfig.BUFFER_MEMORY_CONFIG + " configuration.");
	}

	/**
	 * Invoking this method makes all buffered records immediately available to send
	 * (even if <code>linger.ms</code> is greater than 0) and blocks on the
	 * completion of the requests associated with these records. The post-condition
	 * of <code>flush()</code> is that any previously sent record will have
	 * completed (e.g. <code>Future.isDone() == true</code>). A request is
	 * considered completed when it is successfully acknowledged according to the
	 * <code>acks</code> configuration you have specified or else it results in an
	 * error.
	 * <p>
	 * Other threads can continue sending records while one thread is blocked
	 * waiting for a flush call to complete, however no guarantee is made about the
	 * completion of records sent after the flush call begins.
	 * <p>
	 * This method can be useful when consuming from some input system and producing
	 * into Kafka. The <code>flush()</code> call gives a convenient way to ensure
	 * all previously sent messages have actually completed.
	 * <p>
	 * This example shows how to consume from one Kafka topic and produce to another
	 * Kafka topic:
	 * 
	 * <pre>
	 * {@code
	 * for(ConsumerRecord<String, String> record: consumer.poll(100))
	 *     producer.send(new ProducerRecord("my-topic", record.key(), record.value());
	 * producer.flush();
	 * consumer.commitSync();
	 * }
	 * </pre>
	 *
	 * Note that the above example may drop records if the produce request fails. If
	 * we want to ensure that this does not occur we need to set
	 * <code>retries=&lt;large_number&gt;</code> in our config.
	 * </p>
	 * <p>
	 * Applications don't need to call this method for transactional producers,
	 * since the {@link #commitTransaction()} will flush all buffered records before
	 * performing the commit. This ensures that all the
	 * {@link #send(ProducerRecord)} calls made since the previous
	 * {@link #beginTransaction()} are completed before the commit.
	 * </p>
	 *
	 * @throws InterruptException If the thread is interrupted while blocked
	 */
	@Override
	public void flush() {
		log.trace("Flushing accumulated records in producer.");
		long start = time.nanoseconds();
		this.accumulator.beginFlush();
		this.sender.wakeup();
		try {
			this.accumulator.awaitFlushCompletion();
		} catch (InterruptedException e) {
			throw new InterruptException("Flush interrupted.", e);
		} finally {
			okpMetrics.recordFlush(time.nanoseconds() - start);
		}
	}

	/**
	 * Get the partition metadata for the given topic. This can be used for custom
	 * partitioning.
	 * 
	 * @throws AuthenticationException if authentication fails. See the exception
	 *                                 for more details
	 * @throws AuthorizationException  if not authorized to the specified topic. See
	 *                                 the exception for more details
	 * @throws InterruptException      if the thread is interrupted while blocked
	 * @throws TimeoutException        if metadata could not be refreshed within
	 *                                 {@code max.block.ms}
	 * @throws KafkaException          for all Kafka-related exceptions, including
	 *                                 the case where this method is called after
	 *                                 producer close
	 */
	@Override
	public List<PartitionInfo> partitionsFor(String topic) {
		Objects.requireNonNull(topic, "topic cannot be null");
		try {
			return waitOnMetadata(topic, null, time.milliseconds(), maxBlockTimeMs).cluster.partitionsForTopic(topic);
		} catch (InterruptedException e) {
			throw new InterruptException(e);
		}
	}

	/**
	 * Get the full set of internal metrics maintained by the producer.
	 */
	@Override
	public Map<MetricName, ? extends Metric> metrics() {
		return Collections.unmodifiableMap(this.metrics.metrics());
	}

	/**
	 * Close this producer. This method blocks until all previously sent requests
	 * complete. This method is equivalent to
	 * <code>close(Long.MAX_VALUE, TimeUnit.MILLISECONDS)</code>.
	 * <p>
	 * <strong>If close() is called from {@link Callback}, a warning message will be
	 * logged and close(0, TimeUnit.MILLISECONDS) will be called instead. We do this
	 * because the sender thread would otherwise try to join itself and block
	 * forever.</strong>
	 * <p>
	 *
	 * @throws InterruptException If the thread is interrupted while blocked.
	 * @throws KafkaException     If a unexpected error occurs while trying to close
	 *                            the client, this error should be treated as fatal
	 *                            and indicate the client is no longer functionable.
	 */
	@Override
	public void close() {
		close(Duration.ofMillis(Long.MAX_VALUE));
	}

	/**
	 * This method waits up to <code>timeout</code> for the producer to complete the
	 * sending of all incomplete requests.
	 * <p>
	 * If the producer is unable to complete all requests before the timeout
	 * expires, this method will fail any unsent and unacknowledged records
	 * immediately. It will also abort the ongoing transaction if it's not already
	 * completing.
	 * <p>
	 * If invoked from within a {@link Callback} this method will not block and will
	 * be equivalent to <code>close(Duration.ofMillis(0))</code>. This is done since
	 * no further sending will happen while blocking the I/O thread of the producer.
	 *
	 * @param timeout The maximum time to wait for producer to complete any pending
	 *                requests. The value should be non-negative. Specifying a
	 *                timeout of zero means do not wait for pending send requests to
	 *                complete.
	 * @throws InterruptException       If the thread is interrupted while blocked.
	 * @throws KafkaException           If a unexpected error occurs while trying to
	 *                                  close the client, this error should be
	 *                                  treated as fatal and indicate the client is
	 *                                  no longer functionable.
	 * @throws IllegalArgumentException If the <code>timeout</code> is negative.
	 *
	 */
	@Override
	public void close(Duration timeout) {
		close(timeout, false);
	}

	private void close(Duration timeout, boolean swallowException) {
		long timeoutMs = timeout.toMillis();
		if (timeoutMs < 0)
			throw new IllegalArgumentException("The timeout cannot be negative.");

		log.info("Closing the Kafka producer with timeoutMillis = {} ms.", timeoutMs);

		// this will keep track of the first encountered exception
		AtomicReference<Throwable> firstException = new AtomicReference<>();

		if (transactionalProducer) {
			if (aqProducer != null)
				aqProducer.close();
		}
		boolean invokedFromCallback = false;
		if (this.ioThread != null)
			invokedFromCallback = Thread.currentThread() == this.ioThread;

		if (timeoutMs > 0) {
			if (invokedFromCallback) {
				log.warn(
						"Overriding close timeout {} ms to 0 ms in order to prevent useless blocking due to self-join. "
								+ "This means you have incorrectly invoked close with a non-zero timeout from the producer call-back.",
						timeoutMs);
			} else {
				// Try to close gracefully.
				if (this.sender != null)
					this.sender.initiateClose();
				if (this.ioThread != null) {
					try {
						this.ioThread.join(timeoutMs);
					} catch (InterruptedException t) {
						firstException.compareAndSet(null, new InterruptException(t));
						log.error("Interrupted while joining ioThread", t);
					}
				}
			}
		}

		if (this.sender != null && this.ioThread != null && this.ioThread.isAlive()) {
			log.info("Proceeding to force close the producer since pending requests could not be completed "
					+ "within timeout {} ms.", timeoutMs);
			this.sender.forceClose();
			// Only join the sender thread when not calling from callback.
			if (!invokedFromCallback) {
				try {
					this.ioThread.join();
				} catch (InterruptedException e) {
					firstException.compareAndSet(null, new InterruptException(e));
				}
			}
		}

		Utils.closeQuietly(interceptors, "producer interceptors", firstException);
		Utils.closeQuietly(metrics, "producer metrics", firstException);
		Utils.closeQuietly(keySerializer, "producer keySerializer", firstException);
		Utils.closeQuietly(valueSerializer, "producer valueSerializer", firstException);
		Utils.closeQuietly(partitioner, "producer partitioner", firstException);
		AppInfoParser.unregisterAppInfo(JMX_PREFIX, clientId, metrics);
		Throwable exception = firstException.get();
		if (exception != null && !swallowException) {
			if (exception instanceof InterruptException) {
				throw (InterruptException) exception;
			}
			throw new KafkaException("Failed to close kafka producer", exception);
		}
		log.debug("Kafka producer has been closed");
	}

	private ClusterResourceListeners configureClusterResourceListeners(Serializer<K> keySerializer,
			Serializer<V> valueSerializer, List<?>... candidateLists) {
		ClusterResourceListeners clusterResourceListeners = new ClusterResourceListeners();
		for (List<?> candidateList : candidateLists)
			clusterResourceListeners.maybeAddAll(candidateList);

		clusterResourceListeners.maybeAdd(keySerializer);
		clusterResourceListeners.maybeAdd(valueSerializer);
		return clusterResourceListeners;
	}

	/**
	 * computes partition for given record. if the record has partition returns the
	 * value otherwise calls configured partitioner class to compute the partition.
	 */
	private int partition(ProducerRecord<K, V> record, byte[] serializedKey, byte[] serializedValue, Cluster cluster) {
		Integer partition = record.partition();
		return partition != null ? partition
				: partitioner.partition(record.topic(), record.key(), serializedKey, record.value(), serializedValue,
						cluster);
	}

	private void throwIfInvalidGroupMetadata(ConsumerGroupMetadata groupMetadata) {
		if (groupMetadata == null) {
			throw new IllegalArgumentException("Consumer group metadata could not be null");
		} else if (groupMetadata.generationId() > 0
				&& JoinGroupRequest.UNKNOWN_MEMBER_ID.equals(groupMetadata.memberId())) {
			throw new IllegalArgumentException(
					"Passed in group metadata " + groupMetadata + " has generationId > 0 but member.id ");
		}
	}

	private void throwIfNoTransactionManager() {
		if (transactionManager == null)
			throw new IllegalStateException("Cannot use transactional methods without enabling transactions "
					+ "by setting the " + ProducerConfig.TRANSACTIONAL_ID_CONFIG + " configuration property");
	}

	// Visible for testing
	String getClientId() {
		return clientId;
	}

	private static class ClusterAndWaitTime {
		final Cluster cluster;
		final long waitedOnMetadataMs;

		ClusterAndWaitTime(Cluster cluster, long waitedOnMetadataMs) {
			this.cluster = cluster;
			this.waitedOnMetadataMs = waitedOnMetadataMs;
		}
	}

	private static class FutureFailure implements Future<RecordMetadata> {

		private final ExecutionException exception;

		public FutureFailure(Exception exception) {
			this.exception = new ExecutionException(exception);
		}

		@Override
		public boolean cancel(boolean interrupt) {
			return false;
		}

		@Override
		public RecordMetadata get() throws ExecutionException {
			throw this.exception;
		}

		@Override
		public RecordMetadata get(long timeout, TimeUnit unit) throws ExecutionException {
			throw this.exception;
		}

		@Override
		public boolean isCancelled() {
			return false;
		}

		@Override
		public boolean isDone() {
			return true;
		}

	}

	/**
	 * A callback called when producer request is complete. It in turn calls
	 * user-supplied callback (if given) and notifies producer interceptors about
	 * the request completion.
	 */
	private static class InterceptorCallback<K, V> implements Callback {
		private final Callback userCallback;
		private final ProducerInterceptors<K, V> interceptors;
		private final TopicPartition tp;

		private InterceptorCallback(Callback userCallback, ProducerInterceptors<K, V> interceptors, TopicPartition tp) {
			this.userCallback = userCallback;
			this.interceptors = interceptors;
			this.tp = tp;
		}

		public void onCompletion(RecordMetadata metadata, Exception exception) {
			metadata = metadata != null ? metadata
					: new RecordMetadata(tp, -1, -1, RecordBatch.NO_TIMESTAMP, -1L, -1, -1);
			this.interceptors.onAcknowledgement(metadata, exception);
			if (this.userCallback != null)
				this.userCallback.onCompletion(metadata, exception);
		}
	}

	private Connection getDBConnection(boolean force) throws KafkaException {
		if (!transactionalProducer) {
			throw new KafkaException("KafkaProducer is not an Oracle Transactional Producer."
					+ "Please set oracle.transactional.producer property to true.");
		}
		try {
			return aqProducer.getDBConnection(force);
		} catch (Exception e) {
			throw new KafkaException("Failed to fetch Oracle Database Connection for this producer", e);
		}
	}

	/**
	 * This method returns the database connection used by this KafkaProducer.
	 * 
	 * @throws KafkaException if <code>oracle.transactional.producer</code> property
	 *                        is not set to true or KafkaProducer fails to create a
	 *                        database connection.
	 */
	public Connection getDBConnection() throws KafkaException {
		return getDBConnection(true);
	}

	/**
	 * This method is not yet supported.
	 */
	@Override
	public Uuid clientInstanceId(Duration timeout) {
		throw new FeatureNotSupportedException("This feature is not suported for this release.");
	}
}
