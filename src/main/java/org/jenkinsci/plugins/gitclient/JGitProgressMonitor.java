package org.jenkinsci.plugins.gitclient;

import hudson.model.TaskListener;

import java.io.PrintStream;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class JGitProgressMonitor implements org.eclipse.jgit.lib.ProgressMonitor {

    private final PrintStream log;
    private int completed;

    public JGitProgressMonitor(TaskListener listener) {
        this.log = listener.getLogger();
    }

    public void start(int totalTasks) {
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
