package io.setu.server;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.setu.core.broker.Broker;
import io.setu.core.protocol.*;
import io.setu.server.dispatcher.BrokerRequestHandler;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class RequestLoggingTests {
    @Test
    void groupAndMemberLineBreaksCannotForgeLogEntries() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(BrokerRequestHandler.class);
        Level previous = logger.getLevel();
        var capture = new ListAppender<ILoggingEvent>();
        capture.start(); logger.addAppender(capture); logger.setLevel(Level.DEBUG);
        try (var broker = new io.setu.core.broker.DefaultBroker(1024)) {
            var handler = new BrokerRequestHandler(broker);
            broker.execute(new BrokerRequest(1, Command.CREATE_TOPIC, "orders", -1, -1, -1, null, 1, null, null));
            String group = "group\r\nFORGED", member = "member\nFORGED";
            var response = ProtocolCodec.decodeResponse(handler.handle(ProtocolCodec.encodeRequest(new BrokerRequest(
                    2, Command.JOIN_GROUP, "orders", -1, -1, -1, null, 0, group, member))));
            assertEquals(ErrorCode.NONE, response.errorCode());
            String logged = capture.list.stream().filter(e -> e.getFormattedMessage().contains("Request command="))
                    .findFirst().orElseThrow().getFormattedMessage();
            assertTrue(logged.contains("group=group\\r\\nFORGED member=member\\nFORGED"));
            assertFalse(logged.contains("\n")); assertFalse(logged.contains("\r"));
            // Escaping is confined to diagnostics; the original identity still addresses the membership.
            assertEquals(ErrorCode.NONE, broker.execute(new BrokerRequest(3, Command.HEARTBEAT, null,
                    -1, -1, -1, null, 0, group, member)).errorCode());
        } finally {
            logger.detachAppender(capture); logger.setLevel(previous); capture.stop();
        }
    }

    @Test
    void preservesFailureCauseAndRequestContextWithoutLoggingPayloadOrLeakingMdc() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(BrokerRequestHandler.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> capture = new ListAppender<>() {
            @Override protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                super.append(event);
            }
        };
        capture.start();
        logger.addAppender(capture);
        logger.setLevel(Level.DEBUG);
        MDC.put("correlationId", "outer-request");
        try {
            Broker broker = new Broker() {
                public BrokerResponse execute(BrokerRequest request) throws IOException {
                    throw new IOException("Disk unavailable", new IllegalStateException("Device failure"));
                }
                public void close() { }
            };
            BrokerRequest request = new BrokerRequest(17, Command.PRODUCE, "orders", 2, -1, -1,
                    "private-record-content".getBytes(java.nio.charset.StandardCharsets.UTF_8), 0, null, null);
            BrokerResponse response = ProtocolCodec.decodeResponse(new BrokerRequestHandler(broker)
                    .handle(ProtocolCodec.encodeRequest(request)));
            assertEquals(ErrorCode.INTERNAL_ERROR, response.errorCode());
            ILoggingEvent failure = capture.list.stream().filter(event -> event.getLevel() == Level.ERROR).findFirst().orElseThrow();
            assertTrue(failure.getFormattedMessage().contains("correlation=17 topic=orders partition=2"));
            assertEquals("Disk unavailable", failure.getThrowableProxy().getMessage());
            assertEquals("Device failure", failure.getThrowableProxy().getCause().getMessage());
            assertEquals("17", failure.getMDCPropertyMap().get("correlationId"));
            assertTrue(capture.list.stream().noneMatch(event -> event.getFormattedMessage().contains("private-record-content")));
            assertEquals("outer-request", MDC.get("correlationId"));
        } finally {
            MDC.remove("correlationId");
            logger.detachAppender(capture);
            logger.setLevel(previousLevel);
            capture.stop();
        }
    }
}
