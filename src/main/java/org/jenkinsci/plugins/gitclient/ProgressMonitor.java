package org.jenkinsci.plugins.gitclient;

import hudson.model.TaskListener;

import java.io.PrintStream;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class ProgressMonitor implements org.eclipse.jgit.lib.ProgressMonitor {

    private final PrintStream log;
    private int totalTasks;
    private int completed;

    public ProgressMonitor(TaskListener listener) {
        this.log = listener.getLogger();
    }

    public void start(int totalTasks) {
        // not set ?
        this.totalTasks = totalTasks;
    }

    public void beginTask(String title, int totalWork) {
        log.println(title);
    }

    public void update(int completed) {
        this.completed += completed;
    }

    public void endTask() {
    }

    public boolean isCancelled() {
        return Thread.currentThread().isInterrupted();
    }
}
