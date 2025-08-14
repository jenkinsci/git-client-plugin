package org.jenkinsci.plugins.gitclient.cgit;

import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.theInstance;
import static org.junit.jupiter.api.Assertions.*;

import hudson.model.TaskListener;
import hudson.plugins.git.GitException;
import hudson.util.StreamTaskListener;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

@ParameterizedClass(name = "threads={0}")
@MethodSource("threadsParameter")
class GitCommandsExecutorTest {

    private final ByteArrayOutputStream logStream = new ByteArrayOutputStream();
    private final TaskListener listener = new StreamTaskListener(new PrintStream(logStream), StandardCharsets.UTF_8);

    @Parameter(0)
    private int threads;

    private String value = null;
    private String error = null;

    static List<Integer> threadsParameter() {
        return List.of(1, 2, 100);
    }

    @BeforeEach
    void defineValues(TestInfo testInfo) {
        value = "some value " + testInfo.getTestMethod().orElseThrow().getName();
        error = "some error " + testInfo.getTestMethod().orElseThrow().getName();
    }

    @AfterEach
    void verifyCorrectExecutorServiceShutdown() {
        loggedNoOutput();
    }

    @Test
    void allCommandsSucceed() throws Exception {
        List<Callable<String>> commands = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            commands.add(new GoodCommand(value));
        }

        new GitCommandsExecutor(threads, listener).invokeAll(commands);

        for (Callable<String> command : commands) {
            wasCalled(command);
        }
    }

    @Test
    void allCommandsFail() {
        List<Callable<String>> commands = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            commands.add(new BadCommand(new RuntimeException(error)));
        }

        Exception e =
                assertThrows(GitException.class, () -> new GitCommandsExecutor(threads, listener).invokeAll(commands));
        assertThat(e.getMessage(), is("java.lang.RuntimeException: " + error));
        assertThat(e.getCause(), instanceOf(RuntimeException.class));
    }

    @Test
    void firstCommandFails() {
        long commandExecutionTime = 60_000;
        List<Callable<String>> commands = asList(
                new BadCommand(new RuntimeException(error)),
                new GoodCommand(value, commandExecutionTime),
                new GoodCommand(value, commandExecutionTime));

        long executionStartMillis = System.currentTimeMillis();
        Exception e =
                assertThrows(GitException.class, () -> new GitCommandsExecutor(threads, listener).invokeAll(commands));
        assertThat(e.getMessage(), is("java.lang.RuntimeException: " + error));
        assertThat(e.getCause(), instanceOf(RuntimeException.class));
        long executionStopMillis = System.currentTimeMillis();

        for (Callable<String> command : commands) {
            if (commands.indexOf(command) < threads) {
                wasCalled(command);
            }
        }
        assertThat(executionStopMillis - executionStartMillis, is(lessThan(commandExecutionTime)));
    }

    @Test
    void lastCommandFails() {
        List<Callable<String>> commands =
                asList(new GoodCommand(value), new GoodCommand(value), new BadCommand(new RuntimeException(error)));

        Exception e =
                assertThrows(GitException.class, () -> new GitCommandsExecutor(threads, listener).invokeAll(commands));
        assertThat(e.getMessage(), is("java.lang.RuntimeException: " + error));
        assertThat(e.getCause(), instanceOf(RuntimeException.class));

        for (Callable<String> command : commands) {
            wasCalled(command);
        }
    }

    @Test
    void moreCommandsThanThreads() throws Exception {
        List<Callable<String>> commands = new ArrayList<>();
        for (int i = 0; i < threads + 1; i++) {
            commands.add(new GoodCommand(value));
        }

        new GitCommandsExecutor(threads, listener).invokeAll(commands);

        for (Callable<String> command : commands) {
            wasCalled(command);
        }
    }

    @Test
    void lessCommandsThanThreads() throws Exception {
        List<Callable<String>> commands = new ArrayList<>();
        for (int i = 0; i < threads - 1; i++) {
            commands.add(new GoodCommand(value));
        }

        new GitCommandsExecutor(threads, listener).invokeAll(commands);

        for (Callable<String> command : commands) {
            wasCalled(command);
        }
    }

    @Test
    void callerThreadWasInterrupted() throws Exception {
        long commandExecutionTime = 60_000;
        List<Callable<String>> commands = new ArrayList<>();
        for (int i = 0; i < threads + 1; i++) {
            commands.add(new GoodCommand(value, commandExecutionTime));
        }

        AtomicBoolean isCallerInterrupted = new AtomicBoolean(false);

        Thread caller = new Thread(() -> {
            try {
                new GitCommandsExecutor(threads, listener).invokeAll(commands);
            } catch (InterruptedException e) {
                isCallerInterrupted.set(true);
            } catch (GitException x) {
                throw new AssertionError(x);
            }
        });

        long callerStartMillis = System.currentTimeMillis();
        caller.start();
        caller.interrupt();
        caller.join();
        long callerStopMillis = System.currentTimeMillis();

        for (Callable<String> command : commands) {
            if (commands.indexOf(command) < threads) {
                wasCalled(command);
            } else {
                loggedNoOutput();
            }
        }
        assertThat(callerStopMillis - callerStartMillis, is(lessThan(commandExecutionTime)));
        assertThat(isCallerInterrupted.get(), is(true));
    }

    @Test
    void commandWasInterrupted() {
        Exception commandException = new InterruptedException("some interrupt");
        List<Callable<String>> commands = Collections.singletonList(new BadCommand(commandException));
        Exception e = assertThrows(
                InterruptedException.class, () -> new GitCommandsExecutor(threads, listener).invokeAll(commands));
        assertThat(e.getMessage(), is(nullValue()));
        if (threads == 1) {
            assertThat(e.getCause(), is(nullValue()));
        } else {
            assertThat(e.getCause(), is(theInstance(commandException)));
        }
    }

    @Test
    void commandHadGitProblem() {
        Exception commandException = new GitException(error);
        List<Callable<String>> commands = Collections.singletonList(new BadCommand(commandException));

        Exception e =
                assertThrows(GitException.class, () -> new GitCommandsExecutor(threads, listener).invokeAll(commands));
        assertThat(e.getMessage(), is("hudson.plugins.git.GitException: " + error));
        assertThat(e.getCause(), is(theInstance(commandException)));
    }

    @Test
    void commandHadUnknownProblem() {
        Exception commandException = new RuntimeException(error);
        List<Callable<String>> commands = Collections.singletonList(new BadCommand(commandException));

        Exception e =
                assertThrows(GitException.class, () -> new GitCommandsExecutor(threads, listener).invokeAll(commands));
        assertThat(e.getMessage(), is("java.lang.RuntimeException: " + error));
        assertThat(e.getCause(), is(theInstance(commandException)));
    }

    private void loggedNoOutput() {
        String loggedOutput = logStream.toString();
        assertThat(loggedOutput, is(emptyString()));
    }

    private void wasCalled(Callable<String> command) {
        if (command instanceof GoodCommand good) {
            assertTrue(good.wasCalled());
        } else if (command instanceof BadCommand bad) {
            assertTrue(bad.wasCalled());
        } else {
            fail("Unexpected command type " + command);
        }
    }

    /* A callable that always returns a value, sometimes after a delay */
    private static class GoodCommand implements Callable<String> {

        private final String value;
        private final long commandExecutionTime;
        private boolean called = false;

        GoodCommand(String value) {
            this(value, -1);
        }

        GoodCommand(String value, long commandExecutionTime) {
            this.value = value;
            this.commandExecutionTime = commandExecutionTime;
        }

        boolean wasCalled() {
            return called;
        }

        @Override
        public String call() throws Exception {
            called = true;
            if (commandExecutionTime > 0) {
                Thread.sleep(commandExecutionTime);
            }
            return value;
        }
    }

    /* A callable that always throws an exception */
    private static class BadCommand implements Callable<String> {

        private final Exception exception;
        private boolean called = false;

        BadCommand(Exception exceptionToThrow) {
            exception = exceptionToThrow;
        }

        boolean wasCalled() {
            return called;
        }

        @Override
        public String call() throws Exception {
            called = true;
            throw exception;
        }
    }
}
