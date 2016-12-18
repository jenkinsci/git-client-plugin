package org.jenkinsci.plugins.gitclient;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.CharArrayWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import static org.junit.Assert.*;
import static org.hamcrest.Matchers.*;



@RunWith(Parameterized.class)
public class ChangelogCommandTest extends MergedRepositoryTest {

    public ChangelogCommandTest(String implementation) {
        super(implementation);
    }
    ChangelogCommand changeCmd;
    CharArrayWriter writer;

    @Before
    public void setup() throws InterruptedException {
        changeCmd = git.changelog();
        writer = new CharArrayWriter();
        changeCmd.to(writer);
        mergeCmd.setRevisionToMerge(commit1Branch).execute();
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection gitImplementations() {
        List<Object[]> args = new ArrayList<>();
        String[] implementations = new String[]{"git", "jgit"};
        for (String implementation : implementations) {
            Object[] gitImpl = {implementation};
            args.add(gitImpl);
        }
        return args;
    }

    @Test
    public void changeCmd_withMergesEnabled_ExpectMergeCommitIncluded() throws InterruptedException {
        changeCmd.withMerges();
        changeCmd.execute();
        System.out.println(writer.toString());
        assertThat(writer.toString(), containsString(" Merge commit '" + commit1Branch.name() + "'"));
    }

    @Test
    public void changeCmd_withMergesDisabled_ExpectMergeCommitsExcluded() throws InterruptedException {
        changeCmd.execute();
        assertThat(writer.toString(), not(containsString(" Merge commit '" + commit1Branch.name() + "'")));
    }
}
