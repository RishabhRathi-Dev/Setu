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
