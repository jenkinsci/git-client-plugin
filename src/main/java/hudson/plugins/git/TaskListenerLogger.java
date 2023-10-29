package hudson.plugins.git;

import hudson.model.TaskListener;

/**
 * This class is an addition to task listener that will print logs based on flags.
 */
public class TaskListenerLogger {
    /**
     * Prints the log if printDetailedLogs is true.
     *
     * @param listener the task listener that will print log.
     * @param printDetailedLogs is a boolean value. If it is true the log is printed.
     * @param msg the message
     *           to be printed in the log.
     */
    public void printLogs(TaskListener listener, boolean printDetailedLogs, String msg) {
        if (printDetailedLogs) {
            listener.getLogger().print(msg);
        }
    }
}
