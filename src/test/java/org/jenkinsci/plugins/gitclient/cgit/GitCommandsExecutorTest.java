package org.jenkinsci.plugins.gitclient.cgit;

import static java.util.Arrays.asList;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.theInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

import hudson.model.TaskListener;
import hudson.plugins.git.GitException;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class GitCommandsExecutorTest {

    private final int threads;
    private final TaskListener listener;

    public GitCommandsExecutorTest(int threads) {
        this.threads = threads;
        this.listener = mockTaskListener();
    }

    @After
    public void verifyCorrectExecutorServiceShutdown() {
        verifyNoInteractions(listener);
    }

    @Parameters(name = "threads={0}")
    public static Iterable<Integer> threadsParameter() {
        return asList(1, 2, 100);
    }

    @Test
    public void allCommandsSucceed() throws Exception {
        List<Callable<String>> commands = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            commands.add(successfulCommand("some value"));
        }

        new GitCommandsExecutor(threads, listener).invokeAll(commands);

        for (Callable<String> command : commands) {
            verify(command).call();
        }
    }

    @Test
    public void allCommandsFail() throws Exception {
        List<Callable<String>> commands = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            commands.add(erroneousCommand(new RuntimeException("some error")));
        }

        try {
            new GitCommandsExecutor(threads, listener).invokeAll(commands);
            fail("Expected an exception but none was thrown");
        } catch (Exception e) {
            assertThat(e, instanceOf(GitException.class));
            assertThat(e.getMessage(), is("java.lang.RuntimeException: some error"));
            assertThat(e.getCause(), instanceOf(RuntimeException.class));
        }
    }

    @Test
    public void firstCommandFails() throws Exception {
        long commandExecutionTime = 60_000;
        List<Callable<String>> commands = asList(
                erroneousCommand(new RuntimeException("some error")),
                successfulCommand("some value", commandExecutionTime),
                successfulCommand("some value", commandExecutionTime)
        );

        long executionStartMillis = System.currentTimeMillis();
        try {
            new GitCommandsExecutor(threads, listener).invokeAll(commands);
            fail("Expected an exception but none was thrown");
        } catch (Exception e) {
            assertThat(e, instanceOf(GitException.class));
            assertThat(e.getMessage(), is("java.lang.RuntimeException: some error"));
            assertThat(e.getCause(), instanceOf(RuntimeException.class));
        }
        long executionStopMillis = System.currentTimeMillis();

        for (Callable<String> command : commands) {
            if (commands.indexOf(command) < threads) {
                verify(command).call();
            } else {
                verifyNoInteractions(command);
            }
        }
        assertThat(executionStopMillis - executionStartMillis, is(lessThan(commandExecutionTime)));
    }

    @Test
    public void lastCommandFails() throws Exception {
        List<Callable<String>> commands = asList(
                successfulCommand("some value"),
                successfulCommand("some value"),
                erroneousCommand(new RuntimeException("some error"))
        );

        try {
            new GitCommandsExecutor(threads, listener).invokeAll(commands);
            fail("Expected an exception but none was thrown");
        } catch (Exception e) {
            assertThat(e, instanceOf(GitException.class));
            assertThat(e.getMessage(), is("java.lang.RuntimeException: some error"));
            assertThat(e.getCause(), instanceOf(RuntimeException.class));
        }

        for (Callable<String> command : commands) {
            verify(command).call();
        }
    }

    @Test
    public void moreCommandsThanThreads() throws Exception {
        List<Callable<String>> commands = new ArrayList<>();
        for (int i = 0; i < threads + 1; i++) {
            commands.add(successfulCommand("some value"));
        }

        new GitCommandsExecutor(threads, listener).invokeAll(commands);

        for (Callable<String> command : commands) {
            verify(command).call();
        }
    }

    @Test
    public void lessCommandsThanThreads() throws Exception {
        List<Callable<String>> commands = new ArrayList<>();
        for (int i = 0; i < threads - 1; i++) {
            commands.add(successfulCommand("some value"));
        }

        new GitCommandsExecutor(threads, listener).invokeAll(commands);

        for (Callable<String> command : commands) {
            verify(command).call();
        }
    }

    @Test
    public void callerThreadWasInterrupted() throws Exception {
        long commandExecutionTime = 60_000;
        List<Callable<String>> commands = new ArrayList<>();
        for (int i = 0; i < threads + 1; i++) {
            commands.add(successfulCommand("some value", commandExecutionTime));
        }

        AtomicBoolean isCallerInterrupted = new AtomicBoolean(false);

        Thread caller = new Thread(() -> {
            try {
                new GitCommandsExecutor(threads, listener).invokeAll(commands);
            } catch (InterruptedException e) {
                isCallerInterrupted.set(true);
            }
        });

        long callerStartMillis = System.currentTimeMillis();
        caller.start();
        caller.interrupt();
        caller.join();
        long callerStopMillis = System.currentTimeMillis();

        for (Callable<String> command : commands) {
            if (commands.indexOf(command) < threads) {
                verify(command).call();
            } else {
                verifyNoInteractions(command);
            }
        }
        assertThat(callerStopMillis - callerStartMillis, is(lessThan(commandExecutionTime)));
        assertThat(isCallerInterrupted.get(), is(true));
    }

    @Test
    public void commandWasInterrupted() throws Exception {
        Exception commandException = new InterruptedException("some interrupt");
        List<Callable<String>> commands = asList(erroneousCommand(commandException));

        try {
            new GitCommandsExecutor(threads, listener).invokeAll(commands);
            fail("Expected an exception but none was thrown");
        } catch (Exception e) {
            assertThat(e, instanceOf(InterruptedException.class));
            assertThat(e.getMessage(), is(nullValue()));
            assertThat(e.getCause(), is(theInstance(commandException)));
        }
    }

    @Test
    public void commandHadGitProblem() throws Exception {
        Exception commandException = new GitException("some error");
        List<Callable<String>> commands = asList(erroneousCommand(commandException));

        try {
            new GitCommandsExecutor(threads, listener).invokeAll(commands);
            fail("Expected an exception but none was thrown");
        } catch (Exception e) {
            assertThat(e, instanceOf(GitException.class));
            assertThat(e.getMessage(), is("hudson.plugins.git.GitException: some error"));
            assertThat(e.getCause(), is(theInstance(commandException)));
        }
    }

    @Test
    public void commandHadUnknownProblem() throws Exception {
        Exception commandException = new RuntimeException("some error");
        List<Callable<String>> commands = asList(erroneousCommand(commandException));

        try {
            new GitCommandsExecutor(threads, listener).invokeAll(commands);
            fail("Expected an exception but none was thrown");
        } catch (Exception e) {
            assertThat(e, instanceOf(GitException.class));
            assertThat(e.getMessage(), is("java.lang.RuntimeException: some error"));
            assertThat(e.getCause(), is(theInstance(commandException)));
        }
    }

    private TaskListener mockTaskListener() {
        TaskListener listener = mock(TaskListener.class);
        when(listener.getLogger()).thenReturn(mock(PrintStream.class));
        return listener;
    }

    private Callable<String> successfulCommand(String value) throws Exception {
        Callable<String> command = mock(Callable.class);
        when(command.call()).thenReturn(value);
        return command;
    }

    private Callable<String> successfulCommand(String value, long commandExecutionTime) throws Exception {
        Callable<String> command = mock(Callable.class);
        when(command.call()).then(invocation -> {
            Thread.sleep(commandExecutionTime);
            return value;
        });
        return command;
    }

    private Callable<String> erroneousCommand(Exception exception) throws Exception {
        Callable<String> command = mock(Callable.class);
        when(command.call()).thenThrow(exception);
        return command;
    }
}
