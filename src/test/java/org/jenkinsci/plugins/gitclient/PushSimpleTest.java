package org.jenkinsci.plugins.gitclient;

import hudson.plugins.git.GitException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.eclipse.jgit.transport.URIish;
import org.junit.Test;
import org.junit.runners.Parameterized;

import static org.junit.Assert.assertThrows;

public class PushSimpleTest extends PushTest {

    public PushSimpleTest(String gitImpl, String branchName, String refSpec, Class<Throwable> expectedException) {
        super(gitImpl, branchName, refSpec, expectedException);
    }

    @Test
    public void pushNonFastForwardThrows() throws IOException, GitException, InterruptedException, URISyntaxException {
        checkoutOldBranchAndCommitFile(); // Old branch can't be pushed without force()
        assertThrows(GitException.class,
                     () -> {
                         workingGitClient.push().to(bareURI).ref(refSpec).timeout(1).execute();
                     });
    }

    @Test
    public void pushBadURIThrows() throws IOException, GitException, InterruptedException, URISyntaxException {
        checkoutBranchAndCommitFile();
        URIish bad = new URIish(bareURI.toString() + "-bad");
        assertThrows(GitException.class,
                     () -> {
                         workingGitClient.push().to(bad).ref(refSpec).execute();
                     });
    }

    @Parameterized.Parameters(name = "{0} with {1}")
    public static Collection pushParameters() {
        List<Object[]> parameters = new ArrayList<>();
        Object[] gitParameter = {"git", "master", "master", null};
        parameters.add(gitParameter);
        Object[] jgitParameter = {"jgit", "master", "master", null};
        parameters.add(jgitParameter);
        return parameters;
    }
}
