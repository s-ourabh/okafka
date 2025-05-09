package org.oracle.okafka.tests;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DeleteTopicsResult;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicCollection;
import org.junit.Test;
import org.oracle.okafka.clients.admin.AdminClient;

public class OkafkaDeleteTopic {

	@Test
	public void DeleteTopicTest() {

		try (Admin admin = AdminClient.create(OkafkaSetup.setup())) {
			DeleteTopicsResult delResult = admin.deleteTopics(
					TopicCollection.TopicNameCollection.ofTopicNames(new ArrayList<String>(Arrays.asList("TEQ"))));
			try {
				KafkaFuture<Void> ftr = delResult.all();
				ftr.get();
				System.out.println("Main Thread Out of wait now");
			} catch (InterruptedException | ExecutionException e) {

				throw new IllegalStateException(e);
			}
			System.out.println("Auto Closing admin now");
		} catch (Exception e) {
			System.out.println("Exception while deleting topic " + e);
			e.printStackTrace();
		}
		System.out.println("Test: OkfakaDeleteTopic completed");
	}
}
