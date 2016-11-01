package org.jenkinsci.plugins.gitclient;

import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.plugins.git.GitTool;
import hudson.slaves.DumbSlave;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.jenkinsci.plugins.gitclient.CliGitAPIImpl.TIMEOUT_LOG_PREFIX;

/**
 * Created by a165807 on 2016-11-01.
 */
public class CliGitAPIImplWithJenkinsRuleTest {
    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void test_global_config_timeout() throws Exception {
        GitTool.onLoaded();
        GitTool gitTool = GitTool.getDefaultInstallation();
        Assert.assertNotNull(gitTool);
        Assert.assertNotNull(gitTool.getDescriptor());
        gitTool.getDescriptor().setGitDefaultTimeout(999);
        DumbSlave slave = j.createSlave();
        slave.setMode(Node.Mode.EXCLUSIVE);
        hudson.EnvVars env = new hudson.EnvVars();

        LogHandler handler = new LogHandler();
        handler.setLevel(Level.ALL);
        Logger logger = Logger.getLogger(this.getClass().getName());
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);

        TaskListener listener = new hudson.util.LogTaskListener(logger, Level.ALL);

        CliGitAPIImpl git = new CliGitAPIImpl("git", new File("."), listener, env);
        git.launchCommand("--version");

        Assert.assertTrue(handler.containsMessageSubstring(TIMEOUT_LOG_PREFIX + 999));
    }
}
