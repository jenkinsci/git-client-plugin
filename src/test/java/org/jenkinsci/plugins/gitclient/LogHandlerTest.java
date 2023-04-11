package org.jenkinsci.plugins.gitclient;

import static org.junit.Assert.*;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.Before;
import org.junit.Test;

public class LogHandlerTest {

    private LogHandler handler;

    @Before
    public void setUp() {
        handler = new LogHandler();
    }

    @Test
    public void testPublish() {
        String message = "testing publish";
        publishMessage(message);
    }

    private void publishMessage(String message) {
        LogRecord lr = new LogRecord(Level.INFO, message);
        handler.publish(lr);
        List<String> messages = handler.getMessages();
        assertEquals(message, messages.get(0));
        assertEquals("Wrong size list of messages", 1, messages.size());
    }

    @Test
    public void testFlush() {
        String message = "testing flush";
        publishMessage(message);
        handler.flush(); /* no-op */
    }

    @Test
    public void testClose() {
        String message = "testing close";
        publishMessage(message);
        handler.close();
        List<String> messages = handler.getMessages();
        assertEquals("Wrong size list of messages after close", 0, messages.size());
    }

    @Test
    public void testGetMessages() {
        String message = "testing getMessages";
        publishMessage(message);
    }

    @Test
    public void testContainsMessageSubstring() {
        String message = "testing containsMessageSubstring";
        publishMessage(message);
        assertTrue("Missing message 'contains'", handler.containsMessageSubstring("contains"));
    }

    @Test
    public void testContainsMessageSubstringFalse() {
        String message = "testing containsMessageSubstring";
        publishMessage(message);
        assertFalse("Found message 'Contains'", handler.containsMessageSubstring("Contains"));
    }

    @Test
    public void testGetTimeouts() {
        String message = "test timeout" + CliGitAPIImpl.TIMEOUT_LOG_PREFIX + "42";
        publishMessage(message);
        List<Integer> timeouts = handler.getTimeouts();
        assertEquals("Wrong timeout", Integer.valueOf(42), timeouts.get(0));
        assertEquals("Wrong size list", 1, timeouts.size());
    }

    @Test
    public void testGetTimeoutsMultiple() {
        int timeout0 = 37;
        int timeout1 = 15;
        publishMessage("test timeout" + CliGitAPIImpl.TIMEOUT_LOG_PREFIX + timeout0);
        handler.publish(new LogRecord(Level.FINE, "no timeout in this message"));
        handler.publish(new LogRecord(Level.INFO, "Another timeout" + CliGitAPIImpl.TIMEOUT_LOG_PREFIX + timeout1));
        handler.publish(new LogRecord(Level.FINEST, "no timeout in this message either"));
        List<Integer> timeouts = handler.getTimeouts();
        assertEquals("Wrong timeout 0", timeout0, timeouts.get(0).intValue());
        assertEquals("Wrong timeout 1", timeout1, timeouts.get(1).intValue());
        assertEquals("Wrong size list", 2, timeouts.size());
    }
}
