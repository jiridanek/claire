/*
 * Copyright Broker QE authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.brokerqe.claire.paging;

import io.brokerqe.claire.ArtemisVersion;
import io.brokerqe.claire.Constants;
import io.brokerqe.claire.AbstractSystemTests;
import io.brokerqe.claire.ResourceManager;
import io.brokerqe.claire.client.JmsClient;
import io.brokerqe.claire.client.AmqpUtil;
import io.brokerqe.claire.container.ArtemisContainer;
import io.brokerqe.claire.exception.ClaireRuntimeException;
import io.brokerqe.claire.junit.TestValidSince;
import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.qpid.jms.JmsConnectionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.Message;
import javax.jms.Queue;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@TestValidSince(ArtemisVersion.VERSION_2_28)
public class MaxReadMessagesAndBytesTests extends AbstractSystemTests {

    private static final Logger LOGGER = LoggerFactory.getLogger(MaxReadMessagesAndBytesTests.class);

    private ArtemisContainer artemisInstance;

    @BeforeAll
    void setupEnv() {
        String artemisName = "artemis";
        LOGGER.info("Creating artemis instance: " + artemisName);
        String tuneFile = generateYacfgProfilesContainerTestDir("tune.yaml.jinja2");
        artemisInstance = getArtemisInstance(artemisName, tuneFile);
    }

    @Test
    void maxReadMessagesTest() {
        String addressName = "testPagingMaxReadMessagesAs10";
        String queueName = "testPagingMaxReadMessagesAs10";

        // create a qpid jms client
        String artemisAmqpHostAndPort = artemisInstance.getHostAndPort(ArtemisContainer.DEFAULT_ALL_PROTOCOLS_PORT);
        String url = AmqpUtil.buildAmqpUrl(artemisAmqpHostAndPort);

        LOGGER.info("Creating client producer");
        JmsClient client = ResourceManager.getJmsClient("client-1", new JmsConnectionFactory(url))
                .withCredentials(Constants.ARTEMIS_INSTANCE_USER_NAME, Constants.ARTEMIS_INSTANCE_USER_PASS)
                .withDestination(Queue.class, queueName);

        // specific message property
        Map<String, String> msgPropertyMap = new HashMap<>();
        String propKey = "color";
        String propValue = "blue";
        msgPropertyMap.put(propKey, propValue);
        String msgSelector = propKey + "='" + propValue + "'";

        // produce random messages to generate a queue which will be paged
        int numOfProducedMessages = 19;
        LOGGER.info("Producing {} messages to queue {}", numOfProducedMessages, queueName);
        client.produce(numOfProducedMessages, true);
        client.getProducedMsgs();
        client.produce(1, msgPropertyMap, true);
        numOfProducedMessages++;

        LOGGER.info("Ensure queue contains {} messages", numOfProducedMessages);
        ensureQueueCount(artemisInstance, addressName, queueName, RoutingType.ANYCAST, numOfProducedMessages);

        // ensure address is paging
        ensureBrokerIsPaging(artemisInstance, addressName, true);

        // ensure number of pages are equal 1
        ensureBrokerPagingCount(artemisInstance, addressName, 1);

        // try to consume a paged messages and fail
        client.consume(1, msgSelector, Constants.DURATION_1_SECOND, true);
        Throwable thrown = catchThrowable(client::getConsumedMsgs);
        assertThat(thrown).isInstanceOf(ClaireRuntimeException.class)
               .hasMessageContaining(JmsClient.TIMEOUT_EXCEED_OR_CONSUMER_WAS_CLOSED);
        client.clearConsumedMsgs();

        // consume messages in the queue to allow the paged one to go memory
        client.consume(15, true);

        // produce a bit more random messages to but not enough to enter in paging mode again
        client.produce(4, true);
        numOfProducedMessages += 4;

        // try to consume the message which was paged
        client.consume(1, propKey + "=" + msgPropertyMap.get(propKey), Constants.DURATION_5_SECONDS, true);

        // consume the rest of messages
        client.consume(8, true);

        // ensure address not is paging
        ensureBrokerIsPaging(artemisInstance, addressName, false);

        // ensure number of pages are equal 0
        ensureBrokerPagingCount(artemisInstance, addressName, 0);

        // ensure produced and consumed message are the same
        Map<String, Message> producedMsgs = client.getProducedMsgs();
        Map<String, Message> consumedMsgs = client.getConsumedMsgs();
        ensureSameMessages(numOfProducedMessages, producedMsgs, consumedMsgs);
    }

    @Test
    void maxReadBytesTest() {
        // queue name
        String addressName = "testPagingMaxReadBytesAs10K";
        String queueName = "testPagingMaxReadBytesAs10K";

        // specific message property
        Map<String, String> msgPropertyMap = new HashMap<>();
        String propKey = "color";
        String propValue = "red";
        msgPropertyMap.put(propKey, propValue);
        String msgSelector = propKey + "='" + propValue + "'";

        // create a qpid jms client
        String artemisAmqpHostAndPort = artemisInstance.getHostAndPort(ArtemisContainer.DEFAULT_ALL_PROTOCOLS_PORT);
        String url = AmqpUtil.buildAmqpUrl(artemisAmqpHostAndPort);

        LOGGER.info("Creating client producer");
        JmsClient client = ResourceManager.getJmsClient("client-1", new JmsConnectionFactory(url))
                .withCredentials(Constants.ARTEMIS_INSTANCE_USER_NAME, Constants.ARTEMIS_INSTANCE_USER_PASS)
                .withDestination(Queue.class, queueName);

        // produce 10 random message to generate a queue which will be paged
        int totalProducedMessages = 10;
        client.produce(totalProducedMessages, 1, true);

        // produce 1 message which will be paged with specific property to be retrieved later
        client.produce(1, 1, msgPropertyMap, true);
        totalProducedMessages++;

        LOGGER.info("Ensure queue contains {} messages", totalProducedMessages);
        ensureQueueCount(artemisInstance, addressName, queueName, RoutingType.ANYCAST, totalProducedMessages);

        // ensure address is paging
        ensureBrokerIsPaging(artemisInstance, queueName, true);

        // ensure number of pages are equal 1
        ensureBrokerPagingCount(artemisInstance, queueName, 1);

        // try to consume a paged messages and fail
        client.consume(1, msgSelector, Constants.DURATION_1_SECOND, true);
        Throwable thrown = catchThrowable(client::getConsumedMsgs);
        assertThat(thrown).isInstanceOf(ClaireRuntimeException.class)
                .hasMessageContaining(JmsClient.TIMEOUT_EXCEED_OR_CONSUMER_WAS_CLOSED);
        client.clearConsumedMsgs();

        // consume messages in the queue to allow the paged one to go memory
        client.consume(5, true);

        // produce a bit more random messages to but not enough to enter in paging mode again
        client.produce(4, 1, true);
        totalProducedMessages += 4;

        // try to consume the message which was paged
        client.consume(1, msgSelector, Constants.DURATION_5_SECONDS, true);

        // consume the rest of messages
        client.consume(9, true);

        // ensure address not is paging
        ensureBrokerIsPaging(artemisInstance, queueName, false);

        // ensure number of pages are equal 0
        ensureBrokerPagingCount(artemisInstance, queueName, 0);

        // ensure produced and consumed message are the same
        Map<String, Message> producedMsgs = client.getProducedMsgs();
        Map<String, Message> consumedMsgs = client.getConsumedMsgs();
        ensureSameMessages(totalProducedMessages, producedMsgs, consumedMsgs);
    }

}
