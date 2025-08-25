package org.jenkinsci.plugins.gitclient;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LogHandlerTest {

    private LogHandler handler;

    @BeforeEach
    void setUp() {
        handler = new LogHandler();
    }

    @Test
    void testPublish() {
        String message = "testing publish";
        publishMessage(message);
    }

    private void publishMessage(String message) {
        LogRecord lr = new LogRecord(Level.INFO, message);
        handler.publish(lr);
        List<String> messages = handler.getMessages();
        assertEquals(message, messages.get(0));
        assertEquals(1, messages.size(), "Wrong size list of messages");
    }

    @Test
    void testFlush() {
        String message = "testing flush";
        publishMessage(message);
        handler.flush(); /* no-op */
    }

    @Test
    void testClose() {
        String message = "testing close";
        publishMessage(message);
        handler.close();
        List<String> messages = handler.getMessages();
        assertEquals(0, messages.size(), "Wrong size list of messages after close");
    }

    @Test
    void testGetMessages() {
        String message = "testing getMessages";
        publishMessage(message);
    }

    @Test
    void testContainsMessageSubstring() {
        String message = "testing containsMessageSubstring";
        publishMessage(message);
        assertTrue(handler.containsMessageSubstring("contains"), "Missing message 'contains'");
    }

    @Test
    void testContainsMessageSubstringFalse() {
        String message = "testing containsMessageSubstring";
        publishMessage(message);
        assertFalse(handler.containsMessageSubstring("Contains"), "Found message 'Contains'");
    }

    @Test
    void testGetTimeouts() {
        String message = "test timeout" + CliGitAPIImpl.TIMEOUT_LOG_PREFIX + "42";
        publishMessage(message);
        List<Integer> timeouts = handler.getTimeouts();
        assertEquals(Integer.valueOf(42), timeouts.get(0), "Wrong timeout");
        assertEquals(1, timeouts.size(), "Wrong size list");
    }

    @Test
    void testGetTimeoutsMultiple() {
        int timeout0 = 37;
        int timeout1 = 15;
        publishMessage("test timeout" + CliGitAPIImpl.TIMEOUT_LOG_PREFIX + timeout0);
        handler.publish(new LogRecord(Level.FINE, "no timeout in this message"));
        handler.publish(new LogRecord(Level.INFO, "Another timeout" + CliGitAPIImpl.TIMEOUT_LOG_PREFIX + timeout1));
        handler.publish(new LogRecord(Level.FINEST, "no timeout in this message either"));
        List<Integer> timeouts = handler.getTimeouts();
        assertEquals(timeout0, timeouts.get(0).intValue(), "Wrong timeout 0");
        assertEquals(timeout1, timeouts.get(1).intValue(), "Wrong timeout 1");
        assertEquals(2, timeouts.size(), "Wrong size list");
    }
}
