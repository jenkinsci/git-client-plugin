package hudson.plugins.git;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import hudson.model.TaskListener;
import org.apache.commons.io.output.NullPrintStream;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class TaskListenerLoggerTest {

    @Rule
    public JenkinsRule jenkinsRule = new JenkinsRule();

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
        listenerLoggerMock.printLogs(listener, false, "This message should not be printed.");
        verify(listenerLoggerMock).printLogs(listener, false, "This message should not be printed.");
    }

    @Test
    public void testPipeline() throws Exception {
        TaskListenerLogger listenerLoggerMock = mock(TaskListenerLogger.class);
        WorkflowJob pipeline = jenkinsRule.createProject(WorkflowJob.class);
        pipeline.setDefinition(new CpsFlowDefinition(
                "pipeline {" +
                        "    agent any" +
                        "    stages {" +
                        "        stage('Stage 1') {" +
                        "            steps {" +
                        "                echo 'Hello world!'" +
                        "            }" +
                        "        }" +
                        "    }" +
                        "}", true));
        jenkinsRule.assertBuildStatusSuccess(pipeline.scheduleBuild2(0).get());
        jenkinsRule.assertLogContains("This message should be printed.", pipeline.scheduleBuild2(0).get());
        listenerLoggerMock.printLogs(listener, true, "This message should be printed.");
        verify(listenerLoggerMock).printLogs(listener, true, "This message should be printed.");
        String log = pipeline.getLastBuild().getLog();
        assertTrue(log.contains("Hello, world!"));
        assertTrue(log.contains("This message should be printed."));
    }
}
