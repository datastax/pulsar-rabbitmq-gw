/*
 * Copyright DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.oss.pulsar.rabbitmqgw;

import static org.apache.qpid.server.protocol.v0_8.transport.ConnectionCloseOkBody.CONNECTION_CLOSE_OK_0_9;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.apache.qpid.server.bytebuffer.QpidByteBuffer;
import org.apache.qpid.server.protocol.ErrorCodes;
import org.apache.qpid.server.protocol.ProtocolVersion;
import org.apache.qpid.server.protocol.v0_8.AMQShortString;
import org.apache.qpid.server.protocol.v0_8.FieldTable;
import org.apache.qpid.server.protocol.v0_8.transport.AMQBody;
import org.apache.qpid.server.protocol.v0_8.transport.AMQDataBlock;
import org.apache.qpid.server.protocol.v0_8.transport.AMQFrame;
import org.apache.qpid.server.protocol.v0_8.transport.ConnectionCloseBody;
import org.apache.qpid.server.protocol.v0_8.transport.ConnectionCloseOkBody;
import org.apache.qpid.server.protocol.v0_8.transport.ConnectionOpenBody;
import org.apache.qpid.server.protocol.v0_8.transport.ConnectionOpenOkBody;
import org.apache.qpid.server.protocol.v0_8.transport.ConnectionSecureOkBody;
import org.apache.qpid.server.protocol.v0_8.transport.ConnectionStartBody;
import org.apache.qpid.server.protocol.v0_8.transport.ConnectionStartOkBody;
import org.apache.qpid.server.protocol.v0_8.transport.ConnectionTuneBody;
import org.apache.qpid.server.protocol.v0_8.transport.ConnectionTuneOkBody;
import org.apache.qpid.server.protocol.v0_8.transport.HeartbeatBody;
import org.apache.qpid.server.protocol.v0_8.transport.ProtocolInitiation;
import org.apache.qpid.server.transport.ByteBufferSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GatewayConnectionTest {

  private final GatewayConfiguration config = new GatewayConfiguration();
  private final GatewayService gatewayService = new GatewayService(config);
  private final GatewayConnection connection = new GatewayConnection(gatewayService);
  private EmbeddedChannel channel;

  @BeforeEach
  void setup() {
    channel = new EmbeddedChannel(connection, new AMQDataBlockEncoder());
  }

  @Test
  void testReceiveProtocolHeader() {
    AMQFrame frame = sendProtocolHeader();
    assertEquals(0, frame.getChannel());

    AMQBody body = frame.getBodyFrame();

    assertTrue(body instanceof ConnectionStartBody);
    ConnectionStartBody connectionStartBody = (ConnectionStartBody) body;
    assertEquals(0, connectionStartBody.getVersionMajor());
    assertEquals(9, connectionStartBody.getVersionMinor());
    assertEquals(0, connectionStartBody.getServerProperties().size());
    assertEquals("PLAIN", new String(connectionStartBody.getMechanisms(), StandardCharsets.UTF_8));
    assertEquals("en_US", new String(connectionStartBody.getLocales(), StandardCharsets.UTF_8));
  }

  @Test
  void testReceiveUnsupportedProtocolHeader() {
    // Send protocol header for unsupported v1.0
    ProtocolInitiation protocolInitiation =
        new ProtocolInitiation(ProtocolVersion.get((byte) 1, (byte) 0));
    ByteBuf byteBuf = ByteBufAllocator.DEFAULT.buffer();
    protocolInitiation.writePayload(new NettyByteBufferSender(byteBuf));
    channel.writeInbound(byteBuf);

    ProtocolInitiation pi = channel.readOutbound();

    assertEquals(9, pi.getProtocolMajor());
    assertEquals(1, pi.getProtocolMinor());
    // TODO: should close connection ?
    assertTrue(channel.isOpen());
  }

  @Test
  void testReceiveConnectionStartOk() {
    sendProtocolHeader();

    AMQFrame frame = sendConnectionStartOk("PLAIN");

    assertEquals(0, frame.getChannel());
    AMQBody body = frame.getBodyFrame();
    assertTrue(body instanceof ConnectionTuneBody);
    ConnectionTuneBody connectionTuneBody = (ConnectionTuneBody) body;
    assertEquals(256, connectionTuneBody.getChannelMax());
    assertEquals(256 * 1024 - AMQFrame.getFrameOverhead(), connectionTuneBody.getFrameMax());
    assertEquals(0, connectionTuneBody.getHeartbeat());
  }

  @Test
  void testReceiveConnectionStartOkInvalidState() {
    sendProtocolHeader();
    sendConnectionStartOk("PLAIN");

    AMQFrame frame = sendConnectionStartOk("PLAIN");

    assertIsConnectionCloseFrame(frame, ErrorCodes.COMMAND_INVALID);
    assertFalse(channel.isOpen());
  }

  @Test
  void testReceiveConnectionStartOkEmptyMechanism() {
    sendProtocolHeader();

    AMQFrame frame = sendConnectionStartOk("");

    assertIsConnectionCloseFrame(frame, ErrorCodes.CONNECTION_FORCED);
  }

  @Test
  void testReceiveConnectionSecureOkInvalidState() {
    sendProtocolHeader();

    ConnectionSecureOkBody connectionSecureOkBody = new ConnectionSecureOkBody(new byte[0]);
    AMQFrame frame = exchangeData(connectionSecureOkBody.generateFrame(1));

    assertIsConnectionCloseFrame(frame, ErrorCodes.COMMAND_INVALID);
    assertFalse(channel.isOpen());
  }

  @Test
  void testReceiveConnectionTuneOk() {
    sendProtocolHeader();
    sendConnectionStartOk();

    AMQFrame frame = sendConnectionTuneOk();

    assertNull(frame);
    assertEquals(256, connection.getSessionCountLimit());
    assertEquals(128 * 1024, connection.getMaxFrameSize());
    assertEquals(60, connection.getHeartbeatDelay());
  }

  @Test
  void testReceiveConnectionTuneOkInvalidState() {
    sendProtocolHeader();

    AMQFrame frame = sendConnectionTuneOk();

    assertIsConnectionCloseFrame(frame, ErrorCodes.COMMAND_INVALID);
    assertFalse(channel.isOpen());
  }

  @Test
  void testReceiveConnectionTuneOkMaxFrameSizeTooBig() {
    sendProtocolHeader();
    sendConnectionStartOk();

    AMQFrame frame = sendConnectionTuneOk(256, 1024 * 1024, 60);

    assertIsConnectionCloseFrame(frame, ErrorCodes.SYNTAX_ERROR);
  }

  @Test
  void testReceiveConnectionTuneOkMaxFrameSizeTooSmall() {
    sendProtocolHeader();
    sendConnectionStartOk();

    AMQFrame frame = sendConnectionTuneOk(256, 1024, 60);

    assertIsConnectionCloseFrame(frame, ErrorCodes.SYNTAX_ERROR);
  }

  @Test
  void testReceiveConnectionTuneOkMaxFrameSizeImplied() {
    sendProtocolHeader();
    sendConnectionStartOk();

    sendConnectionTuneOk(256, 0, 60);

    assertEquals(256 * 1024 - AMQFrame.getFrameOverhead(), connection.getMaxFrameSize());
  }

  @Test
  void testReceiveConnectionTuneOkChannelMaxImplied() {
    sendProtocolHeader();
    sendConnectionStartOk();

    sendConnectionTuneOk(0, 128 * 1024, 60);

    assertEquals(0xFFFF, connection.getSessionCountLimit());
  }

  @Test
  void testReceiveConnectionOpen() {
    sendProtocolHeader();
    sendConnectionStartOk();
    sendConnectionTuneOk();

    AMQFrame frame = sendConnectionOpen("test-vhost");

    assertEquals(0, frame.getChannel());
    AMQBody body = frame.getBodyFrame();
    assertTrue(body instanceof ConnectionOpenOkBody);
    ConnectionOpenOkBody connectionOpenOkBody = (ConnectionOpenOkBody) body;
    assertEquals("test-vhost", connectionOpenOkBody.getKnownHosts().toString());
    assertEquals("public/test-vhost", connection.getNamespace());
  }

  @Test
  void testReceiveConnectionOpenVhostWithSlash() {
    sendProtocolHeader();
    sendConnectionStartOk();
    sendConnectionTuneOk();

    AMQFrame frame = sendConnectionOpen("/test-vhost");

    assertEquals(0, frame.getChannel());
    AMQBody body = frame.getBodyFrame();
    assertTrue(body instanceof ConnectionOpenOkBody);
    ConnectionOpenOkBody connectionOpenOkBody = (ConnectionOpenOkBody) body;
    assertEquals("/test-vhost", connectionOpenOkBody.getKnownHosts().toString());
    assertEquals("public/test-vhost", connection.getNamespace());
  }

  @Test
  void testReceiveConnectionOpenEmptyVhost() {
    sendProtocolHeader();
    sendConnectionStartOk();
    sendConnectionTuneOk();

    AMQFrame frame = sendConnectionOpen("/");

    assertEquals(0, frame.getChannel());
    AMQBody body = frame.getBodyFrame();
    assertTrue(body instanceof ConnectionOpenOkBody);
    ConnectionOpenOkBody connectionOpenOkBody = (ConnectionOpenOkBody) body;
    assertEquals("/", connectionOpenOkBody.getKnownHosts().toString());
    assertEquals("public/default", connection.getNamespace());
  }

  @Test
  void testReceiveConnectionOpenInvalidState() {
    sendProtocolHeader();

    AMQFrame frame = sendConnectionOpen();

    assertIsConnectionCloseFrame(frame, ErrorCodes.COMMAND_INVALID);
    assertFalse(channel.isOpen());
  }

  @Test
  void testReceiveConnectionClose() {
    sendProtocolHeader();

    AMQFrame frame = sendConnectionClose();

    assertEquals(0, frame.getChannel());
    AMQBody body = frame.getBodyFrame();
    assertTrue(body instanceof ConnectionCloseOkBody);
    ConnectionCloseOkBody connectionCloseOkBody = (ConnectionCloseOkBody) body;
    assertEquals(CONNECTION_CLOSE_OK_0_9.getMethod(), connectionCloseOkBody.getMethod());
    assertFalse(channel.isOpen());
  }

  @Test
  void testReceiveConnectionCloseOk() {
    sendProtocolHeader();

    AMQFrame frame = sendConnectionCloseOk();

    assertNull(frame);
    assertFalse(channel.isOpen());
  }

  @Test
  void testSendConnectionCloseTimeout() throws Exception {
    config.setAmqpConnectionCloseTimeout(100);
    connection.sendConnectionClose(ErrorCodes.NOT_IMPLEMENTED, "test message", 42);

    channel.readOutbound();
    assertTrue(channel.isOpen());

    Thread.sleep(101);
    channel.runPendingTasks();
    assertFalse(channel.isOpen());
  }

  @Test
  void testHeartbeatSentIfIdle() throws Exception {
    sendProtocolHeader();
    sendConnectionStartOk();
    sendConnectionTuneOk(256, 128 * 1024, 1);

    Thread.sleep(1001);
    channel.runPendingTasks();
    AMQFrame frame = channel.readOutbound();

    assertEquals(0, frame.getChannel());
    AMQBody body = frame.getBodyFrame();
    assertTrue(body instanceof HeartbeatBody);
  }

  @Test
  void testHeartbeatTimeout() throws Exception {
    sendProtocolHeader();
    sendConnectionStartOk();
    config.setAmqpHeartbeatTimeoutFactor(1);
    sendConnectionTuneOk(256, 128 * 1024, 1);

    Thread.sleep(1001);
    channel.runPendingTasks();

    assertFalse(channel.isOpen());
  }

  @Test
  void testHeartbeat() throws Exception {
    sendProtocolHeader();
    sendConnectionStartOk();
    config.setAmqpHeartbeatTimeoutFactor(1);
    sendConnectionTuneOk(256, 128 * 1024, 1);

    Thread.sleep(600);
    channel.runPendingTasks();
    exchangeData(HeartbeatBody.FRAME);

    Thread.sleep(600);
    channel.runPendingTasks();

    assertTrue(channel.isOpen());
  }

  private AMQFrame exchangeData(AMQDataBlock data) {
    ByteBuf byteBuf = ByteBufAllocator.DEFAULT.buffer();
    data.writePayload(new NettyByteBufferSender(byteBuf));
    channel.writeInbound(byteBuf);
    return channel.readOutbound();
  }

  private AMQFrame sendProtocolHeader() {
    return exchangeData(new ProtocolInitiation(ProtocolVersion.v0_91));
  }

  private AMQFrame sendConnectionStartOk() {
    return sendConnectionStartOk("PLAIN");
  }

  private AMQFrame sendConnectionStartOk(String mechanism) {
    ConnectionStartOkBody connectionStartOkBody =
        new ConnectionStartOkBody(
            FieldTable.convertToFieldTable(Collections.emptyMap()),
            AMQShortString.createAMQShortString(mechanism),
            new byte[0],
            AMQShortString.createAMQShortString("en_US"));
    return exchangeData(connectionStartOkBody.generateFrame(1));
  }

  private AMQFrame sendConnectionTuneOk() {
    return sendConnectionTuneOk(256, 128 * 1024, 60);
  }

  private AMQFrame sendConnectionTuneOk(int channelMax, long frameMax, int heartbeat) {
    ConnectionTuneOkBody connectionTuneOkBody =
        new ConnectionTuneOkBody(channelMax, frameMax, heartbeat);
    return exchangeData(connectionTuneOkBody.generateFrame(1));
  }

  private AMQFrame sendConnectionOpen() {
    return sendConnectionOpen("");
  }

  private AMQFrame sendConnectionOpen(String vhost) {
    ConnectionOpenBody connectionOpenBody =
        new ConnectionOpenBody(
            AMQShortString.createAMQShortString(vhost),
            AMQShortString.createAMQShortString("test-capabilities"),
            false);
    return exchangeData(connectionOpenBody.generateFrame(1));
  }

  private AMQFrame sendConnectionClose() {
    ConnectionCloseBody connectionCloseBody =
        new ConnectionCloseBody(
            ProtocolVersion.v0_91,
            ErrorCodes.INTERNAL_ERROR,
            AMQShortString.createAMQShortString("test-replyText"),
            42,
            43);
    return exchangeData(connectionCloseBody.generateFrame(1));
  }

  private AMQFrame sendConnectionCloseOk() {
    return exchangeData(CONNECTION_CLOSE_OK_0_9.generateFrame(1));
  }

  private void assertIsConnectionCloseFrame(AMQFrame frame, int errorCode) {
    assertEquals(0, frame.getChannel());
    AMQBody body = frame.getBodyFrame();
    assertTrue(body instanceof ConnectionCloseBody);
    ConnectionCloseBody connectionCloseBody = (ConnectionCloseBody) body;
    assertEquals(errorCode, connectionCloseBody.getReplyCode());
  }

  public static class NettyByteBufferSender implements ByteBufferSender {

    private final ByteBuf byteBuf;

    NettyByteBufferSender(ByteBuf byteBuf) {
      this.byteBuf = byteBuf;
    }

    @Override
    public boolean isDirectBufferPreferred() {
      return true;
    }

    @Override
    public void send(QpidByteBuffer msg) {
      try {
        byteBuf.writeBytes(msg.asInputStream(), msg.remaining());
      } catch (Exception e) {
        // Oops
      }
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}
  }
}
