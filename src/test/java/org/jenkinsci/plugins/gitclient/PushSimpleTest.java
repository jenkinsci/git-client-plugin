package org.jenkinsci.plugins.gitclient;

import static org.junit.jupiter.api.Assertions.assertThrows;

import hudson.plugins.git.GitException;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@ParameterizedClass(name = "{0} with {1}")
@MethodSource("pushParameters")
class PushSimpleTest extends PushTest {

    @Test
    void pushNonFastForwardThrows() throws Exception {
        checkoutOldBranchAndCommitFile(); // Old branch can't be pushed without force()
        assertThrows(GitException.class, () -> workingGitClient
                .push()
                .to(bareURI)
                .ref(refSpec)
                .timeout(1)
                .execute());
    }

    @Test
    void pushBadURIThrows() throws Exception {
        checkoutBranchAndCommitFile();
        URIish bad = new URIish(bareURI.toString() + "-bad");
        assertThrows(
                GitException.class,
                () -> workingGitClient.push().to(bad).ref(refSpec).execute());
    }

    static List<Arguments> pushParameters() {
        List<Arguments> parameters = new ArrayList<>();
        Arguments gitParameter = Arguments.of("git", "master", "master", null);
        parameters.add(gitParameter);
        Arguments jgitParameter = Arguments.of("jgit", "master", "master", null);
        parameters.add(jgitParameter);
        return parameters;
    }
}
