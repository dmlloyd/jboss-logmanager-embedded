package org.jboss.logmanager;

import org.jboss.logmanager.handlers.ConsoleHandler;
import org.jboss.logmanager.handlers.DelayedHandler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.logging.Handler;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 *
 */
public class LoggerNodeTestCase {

    @Test
    public void testHandlers() {
        LogContext ctxt = new LogContext();
        LoggerNode node = new LoggerNode(ctxt);
        DelayedHandler handler = new DelayedHandler();
        node.setHandlers(array(handler));
        node.removeHandler(handler);
        node.addHandler(handler);
        node.addHandler(handler);
        node.removeHandler(handler);
        Handler[] handlers = node.getHandlers();
        assertEquals(handlers.length, 1);
        assertEquals(handlers[0], handler);
        node.removeHandler(new ConsoleHandler());
    }

    @Test
    public void testExtend() {
        LogContext ctxt = new LogContext();
        LoggerNode node = new LoggerNode(ctxt);
        Logger.AttachmentKey<Object> k0 = new Logger.AttachmentKey<>();
        Logger.AttachmentKey<Object> k1 = new Logger.AttachmentKey<>();
        Logger.AttachmentKey<Object> k2 = new Logger.AttachmentKey<>();
        Logger.AttachmentKey<Object> k3 = new Logger.AttachmentKey<>();
        Logger.AttachmentKey<Object> k4 = new Logger.AttachmentKey<>();
        Logger.AttachmentKey<Object> k5 = new Logger.AttachmentKey<>();
        Logger.AttachmentKey<Object> k6 = new Logger.AttachmentKey<>();
        Assertions.assertEquals("[null]", toString(node.getAttArray()));
        Assertions.assertEquals(null, node.attach(k0, "0"));
        Assertions.assertEquals("[0]", toString(node.getAttArray()));
        Assertions.assertEquals(null, node.attach(k1, "1"));
        Assertions.assertEquals("[0, 1]", toString(node.getAttArray()));
        Assertions.assertEquals(null, node.attach(k4, "4"));
        Assertions.assertEquals("[0, 1, null, null, 4]", toString(node.getAttArray()));
        Assertions.assertEquals(null, node.attach(k3, "3"));
        Assertions.assertEquals("[0, 1, null, 3, 4]", toString(node.getAttArray()));
        Assertions.assertEquals("1", node.detach(k1));
        Assertions.assertEquals("[0, null, null, 3, 4]", toString(node.getAttArray()));
        Assertions.assertEquals(null, node.detach(k1));
        Assertions.assertEquals("[0, null, null, 3, 4]", toString(node.getAttArray()));
        Assertions.assertEquals("4", node.detach(k4));
        Assertions.assertEquals("[0, null, null, 3, null]", toString(node.getAttArray()));
        Assertions.assertEquals(null, node.detach(k5));
        Assertions.assertEquals(null, node.detach(k6));

        Assertions.assertEquals("0", node.getAttachment(k0));
        Assertions.assertEquals("3", node.getAttachment(k3));
        Assertions.assertEquals(null, node.getAttachment(k4));
        Assertions.assertEquals(null, node.getAttachment(k5));
        Assertions.assertEquals(null, node.getAttachment(k6));

        node = new LoggerNode(ctxt);
        Assertions.assertEquals(null, node.attach(k4, "4"));
        Assertions.assertEquals("[null, null, null, null, 4]", toString(node.getAttArray()));
    }

    private String toString(Object[] array) {
        return "[" + Arrays.stream(array).map(String::valueOf).collect(Collectors.joining(", ")) + "]";
    }

    static <T> T[] array(T... vals) {
        return vals;
    }
}
