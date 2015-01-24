package org.jenkinsci.plugins.gitclient;

import hudson.model.TaskListener;

import java.io.PrintStream;

/**
 * JGitProgressMonitor class.
 *
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class JGitProgressMonitor implements org.eclipse.jgit.lib.ProgressMonitor {

    private final PrintStream log;
    private int completed;

    /**
     * Constructor for JGitProgressMonitor.
     *
     * @param listener a {@link hudson.model.TaskListener} object.
     */
    public JGitProgressMonitor(TaskListener listener) {
        this.log = listener.getLogger();
    }

    /** {@inheritDoc} */
    public void start(int totalTasks) {
    }

    /** {@inheritDoc} */
    public void beginTask(String title, int totalWork) {
        log.println(title);
    }

    /** {@inheritDoc} */
    public void update(int completed) {
        this.completed += completed;
    }

    /**
     * endTask.
     */
    public void endTask() {
    }

    /**
     * isCancelled.
     *
     * @return true if this progress monitor has been interrupted
     */
    public boolean isCancelled() {
        return Thread.currentThread().isInterrupted();
    }
}
