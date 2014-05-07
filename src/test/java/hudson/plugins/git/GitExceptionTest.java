package hudson.plugins.git;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import static org.hamcrest.CoreMatchers.is;

public class GitExceptionTest {

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Test
    public void throwsGitException() {
        String message = null;
        thrown.expect(GitException.class);
        thrown.expectMessage(is(message));
        throw new GitException();
    }

}
