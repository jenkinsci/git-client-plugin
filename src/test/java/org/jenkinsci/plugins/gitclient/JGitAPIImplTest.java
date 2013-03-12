package org.jenkinsci.plugins.gitclient;

import hudson.plugins.git.IGitAPI;
import junit.framework.Test;
import junit.framework.TestSuite;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class JGitAPIImplTest extends GitAPITestCase {
    @Override
    protected GitClient setupGitAPI() {
        return Git.with(listener, env).in(repo).using("jgit").getClient();
    }

    public static Test suite() {
        TestSuite suite =
                new TestSuite("JGitAPIImplTest, only covers implemented methods");
        suite.addTest(TestSuite.createTest(JGitAPIImplTest.class, "test_initialize_repository"));
        suite.addTest(TestSuite.createTest(JGitAPIImplTest.class, "test_detect_commit_in_repo"));
        suite.addTest(TestSuite.createTest(JGitAPIImplTest.class, "test_getRemoteURL"));
        suite.addTest(TestSuite.createTest(JGitAPIImplTest.class, "test_setRemoteURL"));

        suite.addTest(TestSuite.createTest(JGitAPIImplTest.class, "test_clean"));
        suite.addTest(TestSuite.createTest(JGitAPIImplTest.class, "test_fetch"));
        suite.addTest(TestSuite.createTest(JGitAPIImplTest.class, "test_fetch_with_updated_tag"));
        suite.addTest(TestSuite.createTest(JGitAPIImplTest.class, "test_create_branch"));
        suite.addTest(TestSuite.createTest(JGitAPIImplTest.class, "test_list_branches"));
        suite.addTest(TestSuite.createTest(JGitAPIImplTest.class, "test_list_remote_branches"));
        suite.addTest(TestSuite.createTest(JGitAPIImplTest.class, "test_list_branches_containing_ref"));
        suite.addTest(TestSuite.createTest(JGitAPIImplTest.class, "test_delete_branch"));
        suite.addTest(TestSuite.createTest(JGitAPIImplTest.class, "test_create_tag"));
        suite.addTest(TestSuite.createTest(JGitAPIImplTest.class, "test_delete_tag"));
        suite.addTest(TestSuite.createTest(JGitAPIImplTest.class, "test_list_tags_with_filter"));
        suite.addTest(TestSuite.createTest(JGitAPIImplTest.class, "test_tag_exists"));
        suite.addTest(TestSuite.createTest(JGitAPIImplTest.class, "test_get_tag_message"));
        //suite.addTest(TestSuite.createTest(JGitAPIImplTest.class, "test_get_HEAD_revision"));
        suite.addTest(TestSuite.createTest(JGitAPIImplTest.class, "test_revparse_sha1_HEAD_or_tag"));
        suite.addTest(TestSuite.createTest(JGitAPIImplTest.class, "test_hasGitRepo_without_git_directory"));
        suite.addTest(TestSuite.createTest(JGitAPIImplTest.class, "test_hasGitRepo_with_invalid_git_repo"));
        suite.addTest(TestSuite.createTest(JGitAPIImplTest.class, "test_hasGitRepo_with_valid_git_repo"));

        return suite;
    }

}
