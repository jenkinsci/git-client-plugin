package org.jenkinsci.plugins.gitclient;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

/**
 * Recording log handler to allow assertions on logging. Not intended for use
 * outside this package. Not intended for use outside tests.
 *
 * @author <a href="mailto:mark.earl.waite@gmail.com">Mark Waite</a>
 */
public class LogHandler extends Handler {

    private List<String> messages = new ArrayList<>();

    @Override
    public void publish(LogRecord lr) {
        messages.add(lr.getMessage());
    }

    @Override
    public void flush() {}

    @Override
    public void close() throws SecurityException {
        messages = new ArrayList<>();
    }

    List<String> getMessages() {
        return messages;
    }

    boolean containsMessageSubstring(String messageSubstring) {
        return messages.stream().anyMatch(message -> message.contains(messageSubstring));
    }

    List<Integer> getTimeouts() {
        List<Integer> timeouts = new ArrayList<>();
        for (String message : getMessages()) {
            int start = message.indexOf(CliGitAPIImpl.TIMEOUT_LOG_PREFIX);
            if (start >= 0) {
                String timeoutStr = message.substring(start + CliGitAPIImpl.TIMEOUT_LOG_PREFIX.length());
                timeouts.add(Integer.parseInt(timeoutStr));
            }
        }
        return timeouts;
    }
}
