package org.jboss.logmanager;

import org.jboss.logmanager.handlers.ConsoleHandler;
import org.jboss.logmanager.handlers.DelayedHandler;
import org.junit.jupiter.api.Test;

import java.util.logging.Handler;

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

    static <T> T[] array(T... vals) {
        return vals;
    }
}
