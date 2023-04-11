package org.jenkinsci.plugins.gitclient;

import hudson.model.TaskListener;
import java.io.PrintStream;

/**
 * Jenkins implementation of the JGit progress monitoring interface.
 * Reports progress of JGit operations like fetch and clone to the
 * Jenkins TaskListener passed to the constructor.
 *
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class JGitProgressMonitor implements org.eclipse.jgit.lib.ProgressMonitor {

    private final PrintStream log;
    private int completed;

    /**
     * Constructor for JGitProgressMonitor.
     *
     * @param listener task listener that will receive progress messages during JGit operations
     */
    public JGitProgressMonitor(TaskListener listener) {
        this.log = listener.getLogger();
    }

    /** {@inheritDoc} */
    @Override
    public void start(int totalTasks) {}

    /** {@inheritDoc} */
    @Override
    public void beginTask(String title, int totalWork) {
        log.println(title);
    }

    /** {@inheritDoc} */
    @Override
    public void update(int completed) {
        this.completed += completed;
    }

    /** {@inheritDoc} */
    @Override
    public void endTask() {}

    /** {@inheritDoc} */
    @Override
    public void showDuration(boolean enabled) {}

    /** {@inheritDoc} */
    @Override
    public boolean isCancelled() {
        return Thread.currentThread().isInterrupted();
    }
}
