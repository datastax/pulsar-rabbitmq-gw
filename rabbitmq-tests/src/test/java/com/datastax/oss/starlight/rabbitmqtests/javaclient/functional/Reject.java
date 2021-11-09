// Copyright (c) 2007-2020 VMware, Inc. or its affiliates.  All rights reserved.
//
// This software, the RabbitMQ Java client library, is triple-licensed under the
// Mozilla Public License 2.0 ("MPL"), the GNU General Public License version 2
// ("GPL") and the Apache License version 2 ("ASL"). For the MPL, please see
// LICENSE-MPL-RabbitMQ. For the GPL, please see LICENSE-GPL2.  For the ASL,
// please see LICENSE-APACHE2.
//
// This software is distributed on an "AS IS" basis, WITHOUT WARRANTY OF ANY KIND,
// either express or implied. See the LICENSE file for specific language governing
// rights and limitations of this software.
//
// If you have any questions regarding licensing, please contact us at
// info@rabbitmq.com.

package com.datastax.oss.starlight.rabbitmqtests.javaclient.functional;

import static org.junit.Assert.assertNull;

import com.datastax.oss.starlight.rabbitmqtests.SystemTest;
import com.datastax.oss.starlight.rabbitmqtests.javaclient.QueueingConsumer;
import com.datastax.oss.starlight.rabbitmqtests.javaclient.TestUtils;
import com.datastax.oss.starlight.rabbitmqtests.javaclient.TestUtils.CallableFunction;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import java.util.Collections;
import java.util.UUID;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@Category(SystemTest.class)
@RunWith(Parameterized.class)
public class Reject extends AbstractRejectTest {

  @Parameterized.Parameters
  public static Object[] queueCreators() {
    return new Object[] {
      (CallableFunction<Channel, String>)
          channel -> {
            String q = UUID.randomUUID().toString();
            channel.queueDeclare(
                q, true, false, false, Collections.singletonMap("x-queue-type", "quorum"));
            return q;
          },
      (CallableFunction<Channel, String>)
          channel -> {
            String q = UUID.randomUUID().toString();
            channel.queueDeclare(
                q, true, false, false, Collections.singletonMap("x-queue-type", "classic"));
            return q;
          }
    };
  }

  @Parameterized.Parameter public TestUtils.CallableFunction<Channel, String> queueCreator;

  @Test
  public void reject() throws Exception {
    String q = queueCreator.apply(channel);

    channel.confirmSelect();

    byte[] m1 = "1".getBytes();
    byte[] m2 = "2".getBytes();

    basicPublishVolatile(m1, q);
    basicPublishVolatile(m2, q);

    channel.waitForConfirmsOrDie(1000);

    long tag1 = checkDelivery(TestUtils.basicGet(channel, q, false), m1, false);
    long tag2 = checkDelivery(TestUtils.basicGet(channel, q, false), m2, false);
    QueueingConsumer c = new QueueingConsumer(secondaryChannel);
    String consumerTag = secondaryChannel.basicConsume(q, false, c);
    channel.basicReject(tag2, true);
    long tag3 = checkDelivery(c.nextDelivery(), m2, true);

    // Pulsar-RabbitMQ edit: contrary to AMQP spec, canceling the consumer requeues the unacked
    // messages.
    // secondaryChannel.basicCancel(consumerTag);

    secondaryChannel.basicReject(tag3, false);
    assertNull(TestUtils.basicGet(channel, q, false));
    channel.basicAck(tag1, false);
    channel.basicReject(tag3, false);
    expectError(AMQP.PRECONDITION_FAILED);
  }
}
