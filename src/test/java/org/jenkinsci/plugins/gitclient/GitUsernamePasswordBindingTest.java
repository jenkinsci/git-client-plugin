package org.jenkinsci.plugins.gitclient;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import hudson.model.TaskListener;
import hudson.model.FreeStyleBuild;
import hudson.plugins.git.GitTool;
import hudson.tasks.BatchFile;
import hudson.tasks.Shell;

import hudson.tools.ToolProperty;
import jenkins.model.Jenkins;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FilenameUtils;
import org.jenkinsci.plugins.credentialsbinding.MultiBinding;
import org.jenkinsci.plugins.credentialsbinding.impl.SecretBuildWrapper;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Collections;

import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;

@RunWith(Parameterized.class)
public class GitUsernamePasswordBindingTest {
    @Parameterized.Parameters(name = "User {0}: Password {1}: GitToolName {2}: GitToolInstance {3}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{
                {"randomName", "special%%_342@**", new GitTool("git", "git", null)},
                {"a", "here's-a-quote", new JGitTool()},
                {"b", "He said \"Hello\", then left.", new JGitApacheTool()},
                {"many-words-in-a-user-name-because-we-can", "&Ampersand&", new JGitApacheTool()},
                {"user_name", "colon:inside;outside", createToolInstance("DEFAULT", "git", null)}
        });
    }

    @Rule
    public final TemporaryFolder tempFolder = new TemporaryFolder();

    @Rule
    public JenkinsRule r = new JenkinsRule();

    private final String username;

    private final String password;

    private final GitTool gitToolInstance;

    private final String credentialID = DigestUtils.sha256Hex(("Git Usernanme and Password Binding").getBytes(StandardCharsets.UTF_8));

    private File rootDir = null;
    private FilePath rootFilePath = null;
    private File gitRootRepo = null;
    private UsernamePasswordCredentialsImpl credentials = null;
    private GitUsernamePasswordBinding gitCredBind = null;

    public GitUsernamePasswordBindingTest(String username, String password, GitTool gitToolInstance) {
        this.username = username;
        this.password = password;
        this.gitToolInstance = gitToolInstance;
    }

    public static GitTool createToolInstance(String name, String home, List<? extends ToolProperty<?>> properties) {
        return new GitTool(name, home, properties);
    }

    @Before
    public void basicSetup() throws IOException {
        Jenkins.get();
        //File init
        rootDir = tempFolder.getRoot();
        rootFilePath = new FilePath(rootDir.getAbsoluteFile());
        gitRootRepo = tempFolder.newFolder();
        assertThat(gitRootRepo, is(notNullValue()));

        //Credential init
        credentials = new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, credentialID, "Git Username and Password Binding Test", this.username, this.password);
        CredentialsProvider.lookupStores(r.jenkins).iterator().next().addCredentials(Domain.global(), credentials);

        //GitUsernamePasswordBinding instance
        gitCredBind = new GitUsernamePasswordBinding(credentials.getId());
        assertThat(gitCredBind.type(), is(StandardUsernamePasswordCredentials.class));

        //Git
        Git git = Git.with(TaskListener.NULL, new EnvVars()).in(gitRootRepo).using(gitToolInstance.getGitExe());

        //Git Tool
        Jenkins.get().getDescriptorByType(GitTool.DescriptorImpl.class).getDefaultInstallers().clear();
        Jenkins.get().getDescriptorByType(GitTool.DescriptorImpl.class).setInstallations(gitToolInstance);
    }

    @Test
    public void test_GenerateGitScript_write() throws IOException, InterruptedException {
        GitUsernamePasswordBinding.GenerateGitScript tempGenScript = new GitUsernamePasswordBinding.GenerateGitScript(this.username, this.password, credentials.getId());
        assertThat(tempGenScript.type(), is(StandardUsernamePasswordCredentials.class));
        FilePath tempScriptFile = tempGenScript.write(credentials, rootFilePath);
        if (!isWindows()) {
            assertThat(tempScriptFile.mode(), is(0500));
            assertThat("File extension not sh", FilenameUtils.getExtension(tempScriptFile.getName()), is("sh"));
        } else {
            assertThat("File extension not bat", FilenameUtils.getExtension(tempScriptFile.getName()), is("bat"));
        }
        assertThat(tempScriptFile.readToString(), containsString(this.username));
        assertThat(tempScriptFile.readToString(), containsString(this.password));
    }

    //This test will pass as long as setKeyBindings(@NonNull StandardCredentials credentials) method
    //is executed before git tool type check, for all git tool implementations
    @Test
    public void test_FreeStyleProject() throws Exception {
        FreeStyleProject prj = r.createFreeStyleProject();
        prj.getBuildWrappersList().add(new SecretBuildWrapper(Collections.<MultiBinding<?>>
                singletonList(new GitUsernamePasswordBinding(credentialID))));
        if (isWindows()) {
            prj.getBuildersList().add(new BatchFile("set | findstr GIT_USERNAME > auth.txt & set | findstr GIT_PASSWORD >> auth.txt"));
        } else {
            prj.getBuildersList().add(new Shell("env | grep GIT_USERNAME > auth.txt; env | grep GIT_PASSWORD >> auth.txt"));
        }
        Map<JobPropertyDescriptor, JobProperty<? super FreeStyleProject>> p = prj.getProperties();
        r.configRoundtrip((Item) prj);
        SecretBuildWrapper wrapper = prj.getBuildWrappersList().get(SecretBuildWrapper.class);
        assertThat(wrapper, is(notNullValue()));
        List<? extends MultiBinding<?>> bindings = wrapper.getBindings();
        assertThat(bindings.size(), is(1));
        MultiBinding<?> binding = bindings.get(0);
        assertThat(binding.variables(), hasItem("GIT_USERNAME"));
        assertThat(binding.variables(), hasItem("GIT_PASSWORD"));
        FreeStyleBuild b = r.buildAndAssertSuccess(prj);
        r.assertLogNotContains(this.password, b);
        String fileContents = b.getWorkspace().child("auth.txt").readToString().trim();
        assertThat(fileContents, containsString("GIT_USERNAME=" + this.username));
        assertThat(fileContents, containsString("GIT_PASSWORD=" + this.password));
    }

    /**
     * inline ${@link hudson.Functions#isWindows()} to prevent a transient
     * remote classloader issue
     */
    private static boolean isWindows() {
        return File.pathSeparatorChar == ';';
    }
}
