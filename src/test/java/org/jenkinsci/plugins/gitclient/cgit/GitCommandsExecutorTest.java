package org.jenkinsci.plugins.gitclient.cgit;

import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.theInstance;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class GitCommandsExecutorTest {

    private final int threads;
    private final TaskListener listener;
    private final ByteArrayOutputStream logStream;

    @Rule
    public TestName testName = new TestName();

    public GitCommandsExecutorTest(int threads) {
        this.threads = threads;
        this.logStream = new ByteArrayOutputStream();
        this.listener = new StreamTaskListener(new PrintStream(logStream), StandardCharsets.UTF_8);
    }

    @After
    public void verifyCorrectExecutorServiceShutdown() {
        loggedNoOutput();
    }

    private String value = null;
    private String error = null;

    @Before
    public void defineValues() {
        value = "some value " + testName.getMethodName();
        error = "some error " + testName.getMethodName();
    }

    @Parameters(name = "threads={0}")
    public static Iterable<Integer> threadsParameter() {
        return asList(1, 2, 100);
    }

    @Test
    public void allCommandsSucceed() throws Exception {
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
    public void allCommandsFail() throws Exception {
        List<Callable<String>> commands = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            commands.add(new BadCommand(new RuntimeException(error)));
        }

        Exception e = assertThrows(GitException.class, () -> {
            new GitCommandsExecutor(threads, listener).invokeAll(commands);
        });
        assertThat(e.getMessage(), is("java.lang.RuntimeException: " + error));
        assertThat(e.getCause(), instanceOf(RuntimeException.class));
    }

    @Test
    public void firstCommandFails() throws Exception {
        long commandExecutionTime = 60_000;
        List<Callable<String>> commands = asList(
                new BadCommand(new RuntimeException(error)),
                new GoodCommand(value, commandExecutionTime),
                new GoodCommand(value, commandExecutionTime));

        long executionStartMillis = System.currentTimeMillis();
        Exception e = assertThrows(GitException.class, () -> {
            new GitCommandsExecutor(threads, listener).invokeAll(commands);
        });
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
    public void lastCommandFails() throws Exception {
        List<Callable<String>> commands =
                asList(new GoodCommand(value), new GoodCommand(value), new BadCommand(new RuntimeException(error)));

        Exception e = assertThrows(GitException.class, () -> {
            new GitCommandsExecutor(threads, listener).invokeAll(commands);
        });
        assertThat(e.getMessage(), is("java.lang.RuntimeException: " + error));
        assertThat(e.getCause(), instanceOf(RuntimeException.class));

        for (Callable<String> command : commands) {
            wasCalled(command);
        }
    }

    @Test
    public void moreCommandsThanThreads() throws Exception {
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
    public void lessCommandsThanThreads() throws Exception {
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
    public void callerThreadWasInterrupted() throws Exception {
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
    public void commandWasInterrupted() throws Exception {
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
    public void commandHadGitProblem() throws Exception {
        Exception commandException = new GitException(error);
        List<Callable<String>> commands = Collections.singletonList(new BadCommand(commandException));

        Exception e = assertThrows(GitException.class, () -> {
            new GitCommandsExecutor(threads, listener).invokeAll(commands);
        });
        assertThat(e.getMessage(), is("hudson.plugins.git.GitException: " + error));
        assertThat(e.getCause(), is(theInstance(commandException)));
    }

    @Test
    public void commandHadUnknownProblem() throws Exception {
        Exception commandException = new RuntimeException(error);
        List<Callable<String>> commands = Collections.singletonList(new BadCommand(commandException));

        Exception e = assertThrows(GitException.class, () -> {
            new GitCommandsExecutor(threads, listener).invokeAll(commands);
        });
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
    private class GoodCommand implements Callable {

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
        public Object call() throws Exception {
            called = true;
            if (commandExecutionTime > 0) {
                Thread.sleep(commandExecutionTime);
            }
            return value;
        }
    }

    /* A callable that always throws an exception */
    private class BadCommand implements Callable {

        private final Exception exception;
        private boolean called = false;

        BadCommand(Exception exceptionToThrow) {
            exception = exceptionToThrow;
        }

        boolean wasCalled() {
            return called;
        }

        @Override
        public Object call() throws Exception {
            called = true;
            throw exception;
        }
    }
}
