package hudson.plugins.git;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import hudson.model.TaskListener;
import org.apache.commons.io.output.NullPrintStream;
import org.junit.Before;
import org.junit.Test;

public class TaskListenerLoggerTest {

    private TaskListener listener;

    @Before
    public void invokeTaskListener() {
        listener = NullPrintStream::new;
    }

    @Test
    public void shouldInvokeTheLoggerMethod() {
        TaskListenerLogger listenerLoggerMock = mock(TaskListenerLogger.class);
        listenerLoggerMock.printLogs(listener, true, "This message should be printed.");
        verify(listenerLoggerMock).printLogs(listener, true, "This message should be printed.");
    }

    @Test
    public void shouldNotInvokeTheLoggerMethod() {
        TaskListenerLogger listenerLoggerMock = mock(TaskListenerLogger.class);
        listenerLoggerMock.printLogs(listener, false, "This message should be printed.");
        verify(listenerLoggerMock).printLogs(listener, false, "This message should be printed.");
    }

}