package org.jenkinsci.plugins.gitclient;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.FilePath;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(Parameterized.class)
public class GitUsernamePasswordBindTest {
    @Parameterized.Parameters
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{
                {"randomName", "special%%_342@**"}, {"here's-a-quote", "&Ampersand"}, {"semi;colon", "colon:inside"}
        });
    }

    @Rule public final TemporaryFolder tempFolder = new TemporaryFolder();

    @Rule public JenkinsRule r = new JenkinsRule();


    private final String username;

    private final String password;

    public GitUsernamePasswordBindTest(String username, String password){
        this.username = username;
        this.password = password;
    }

    private File rootDir = null;
    private FilePath rootFilePath = null;
    private UsernamePasswordCredentialsImpl credentials=null;
    private GitUsernamePasswordBind gitCredBind=null;

    @Before
    public void basicSetup() throws IOException {
        //File init
        rootDir = tempFolder.getRoot();
        rootFilePath = new FilePath(rootDir.getAbsoluteFile());

        //Credential init
        credentials = new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, "80y7g2fjekru8h9237ywes2", "GIt Username and Password Binding Test", this.username, this.password);
        CredentialsProvider.lookupStores(r.jenkins).iterator().next().addCredentials(Domain.global(), credentials);
        gitCredBind = new GitUsernamePasswordBind(credentials.getId());
    }

    @Test
    public void test_GenerateGitScript_write() throws IOException, InterruptedException {
        GitUsernamePasswordBind.GenerateGitScript tempGenScript = new GitUsernamePasswordBind.GenerateGitScript(this.username,this.password,credentials.getId());
        FilePath tempSriptFile = tempGenScript.write(credentials,rootFilePath);
        assertNotNull(tempGenScript);
        assertTrue("Username not found",tempSriptFile.readToString().contains(this.username));
        assertTrue("Password not found",tempSriptFile.readToString().contains(this.password));
    }
}
