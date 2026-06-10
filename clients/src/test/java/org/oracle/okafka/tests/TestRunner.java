package org.oracle.okafka.tests;

import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;

class TestRunner {

	private static final Class<?>[] TEST_CLASSES = new Class<?>[] { SimpleOkafkaAdmin.class,
			SimpleOkafkaProducer.class, SimpleOkafkaConsumer.class, OkafkaListTopics.class,
			OkafkaDescribeTopics.class, OkafkaDescribeTopicsById.class, OkafkaCreatePartitions.class,
			OkafkaDeleteTopic.class, OkafkaDeleteTopicById.class, OkafkaListOffsets.class,
			OkafkaBeginningOffsets.class, OkafkaEndOffsets.class, OkafkaFetchCommittedOffset.class,
			OkafkaAutoOffsetReset.class, OkafkaSeekToBeginning.class, OkafkaSeekToEnd.class,
			OkafkaUnsubscribe.class, OkafkaOffsetsForTimes.class, OkafkaPartitionsFor.class,
			OkafkaPosition.class, OkafkaConsumerOffsetsLifecycle.class, ListConsumerGroups.class,
			ListConsumerGroupOffsets.class, DeleteConsumerGroups.class, ProducerMetricsTest.class,
			ConsumerMetricsTest.class };

	public static void main(String[] args) {
		Result result = JUnitCore.runClasses(TEST_CLASSES);

		for (Failure failure : result.getFailures()) {
			System.err.println("Test failure: " + failure);
			System.err.println(failure.getTrace());
		}

		System.out.println("Tests run: " + result.getRunCount());
		System.out.println("Tests failed: " + result.getFailureCount());
		System.out.println("Tests successful: " + result.wasSuccessful());

		if (!result.wasSuccessful()) {
			System.exit(1);
		}
	}
}
