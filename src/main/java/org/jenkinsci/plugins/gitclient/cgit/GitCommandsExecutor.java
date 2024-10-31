package org.jenkinsci.plugins.gitclient.cgit;

import com.google.common.util.concurrent.MoreExecutors;
import hudson.model.TaskListener;
import hudson.plugins.git.GitException;
import hudson.util.DaemonThreadFactory;
import hudson.util.ExceptionCatchingThreadFactory;
import hudson.util.NamingThreadFactory;
import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * This executor can invoke multiple git commands in parallel using threads.
 * <p>
 * If threads = 1 the caller thread is used.
 * If a git command fails, invocation of all running and not yet started commands is stopped.
 */
public class GitCommandsExecutor {

    private final int threads;
    private final TaskListener listener;

    public GitCommandsExecutor(int threads, TaskListener listener) {
        this.threads = Math.max(1, threads);
        this.listener = listener;
    }

    public <T> void invokeAll(Collection<Callable<T>> commands) throws GitException, InterruptedException {
        ExecutorService executorService = null;
        try {
            if (threads == 1) {
                executorService = MoreExecutors.newDirectExecutorService();
            } else {
                ThreadFactory threadFactory = new ExceptionCatchingThreadFactory(
                        new NamingThreadFactory(new DaemonThreadFactory(), GitCommandsExecutor.class.getSimpleName()));
                executorService = Executors.newFixedThreadPool(threads, threadFactory);
            }
            invokeAll(executorService, commands);
        } finally {
            if (executorService != null) {
                executorService.shutdownNow();
                if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                    listener.getLogger().println("[WARNING] Threads did not terminate properly");
                }
            }
        }
    }

    private <T> void invokeAll(ExecutorService executorService, Collection<Callable<T>> commands)
            throws GitException, InterruptedException {
        CompletionService<T> completionService = new ExecutorCompletionService<>(executorService);
        Iterator<Callable<T>> remainingCommands = commands.iterator();
        int nCommands = commands.size();

        for (int i = 0; i < threads && i < nCommands; i++) {
            submitRemainingCommand(completionService, remainingCommands);
        }

        for (int i = 0; i < nCommands; i++) {
            checkResult(completionService.take());
            submitRemainingCommand(completionService, remainingCommands);
        }
    }

    private <T> void submitRemainingCommand(
            CompletionService<T> completionService, Iterator<Callable<T>> remainingCommands) {
        if (remainingCommands.hasNext()) {
            completionService.submit(remainingCommands.next());
        }
    }

    private <T> void checkResult(Future<T> result) throws GitException, InterruptedException {
        try {
            result.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof InterruptedException) {
                throw (InterruptedException) new InterruptedException().initCause(cause);
            } else {
                throw new GitException(cause);
            }
        }
    }
}
