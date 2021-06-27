/*
 * The MIT License
 *
 * Copyright 2020 Mark Waite.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkinsci.plugins.gitclient;

import com.cloudbees.plugins.credentials.CredentialsDescriptor;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class UnsupportedCommandTest {

    private final UnsupportedCommand unsupportedCommand;

    public UnsupportedCommandTest() {
        unsupportedCommand = new UnsupportedCommand();
    }

    @Test
    public void testSparseCheckoutPathsNull() {
        unsupportedCommand.sparseCheckoutPaths(null);
        assertTrue(unsupportedCommand.determineSupportForJGit());
    }

    @Test
    public void testSparseCheckoutPathsEmptyList() {
        List<String> emptyList = new ArrayList<>();
        unsupportedCommand.sparseCheckoutPaths(emptyList);
        assertTrue(unsupportedCommand.determineSupportForJGit());
    }

    @Test
    public void testSparseCheckoutPaths() {
        List<String> sparseList = new ArrayList<>();
        sparseList.add("a-file-for-sparse-checkout");
        unsupportedCommand.sparseCheckoutPaths(sparseList);
        assertFalse(unsupportedCommand.determineSupportForJGit());
    }

    @Test
    public void testTimeoutNull() {
        unsupportedCommand.timeout(null);
        assertTrue(unsupportedCommand.determineSupportForJGit());
    }

    @Test
    public void testTimeout() {
        Integer five = 5;
        unsupportedCommand.timeout(five);
        assertFalse(unsupportedCommand.determineSupportForJGit());
    }

    @Test
    public void testLfsRemoteNull() {
        unsupportedCommand.lfsRemote(null);
        assertTrue(unsupportedCommand.determineSupportForJGit());
    }

    @Test
    public void testLfsRemote() {
        unsupportedCommand.lfsRemote("https://github.com/MarkEWaite/docker-lfs");
        assertFalse(unsupportedCommand.determineSupportForJGit());
    }

    @Test
    public void testLfsCredentialsNull() {
        unsupportedCommand.lfsCredentials(null);
        assertTrue(unsupportedCommand.determineSupportForJGit());
    }

    @Test
    public void testLfsCredentials() {
        FakeCredentials credentials = new FakeCredentials();
        unsupportedCommand.lfsCredentials(credentials);
        assertFalse(unsupportedCommand.determineSupportForJGit());
    }

    @Test
    public void testNotShallow() {
        unsupportedCommand.shallow(false);
        assertTrue(unsupportedCommand.determineSupportForJGit());
    }

    @Test
    public void testShallow() {
        unsupportedCommand.shallow(true);
        assertFalse(unsupportedCommand.determineSupportForJGit());
    }

    @Test
    public void testDepthNull() {
        unsupportedCommand.depth(null);
        assertTrue(unsupportedCommand.determineSupportForJGit());
    }

    @Test
    public void testDepthNegative() {
        unsupportedCommand.depth(-1); // Surprising, but acceptable
        assertFalse(unsupportedCommand.determineSupportForJGit());
    }

    @Test
    public void testDepth() {
        unsupportedCommand.depth(1);
        assertFalse(unsupportedCommand.determineSupportForJGit());
    }

    @Test
    public void testNotFirstParent() {
        unsupportedCommand.firstParent(false);
        assertTrue(unsupportedCommand.determineSupportForJGit());
    }

    @Test
    public void testFirstParent() {
        unsupportedCommand.firstParent(true);
        assertFalse(unsupportedCommand.determineSupportForJGit());
    }

    @Test
    public void testThreadsNull() {
        unsupportedCommand.threads(null);
        assertTrue(unsupportedCommand.determineSupportForJGit());
    }

    @Test
    public void testThreadsZero() {
        unsupportedCommand.threads(0);
        assertTrue(unsupportedCommand.determineSupportForJGit());
    }

    @Test
    public void testThreads() {
        unsupportedCommand.threads(42);
        assertFalse(unsupportedCommand.determineSupportForJGit());
    }

    @Test
    public void testNotRemoteTracking() {
        unsupportedCommand.remoteTracking(false);
        assertTrue(unsupportedCommand.determineSupportForJGit());
    }

    @Test
    public void testRemoteTracking() {
        unsupportedCommand.remoteTracking(true);
        assertFalse(unsupportedCommand.determineSupportForJGit());
    }

    @Test
    public void testRefNull() {
        unsupportedCommand.ref(null);
        assertTrue(unsupportedCommand.determineSupportForJGit());
    }

    @Test
    public void testRefEmpty() {
        unsupportedCommand.ref("");
        assertTrue(unsupportedCommand.determineSupportForJGit());
    }

    @Test
    public void testRef() {
        unsupportedCommand.ref("beadeddeededcededadded");
        assertFalse(unsupportedCommand.determineSupportForJGit());
    }

    @Test
    public void testNotParentCredentials() {
        unsupportedCommand.parentCredentials(false);
        assertTrue(unsupportedCommand.determineSupportForJGit());
    }

    @Test
    public void testParentCredentials() {
        unsupportedCommand.remoteTracking(true);
        assertFalse(unsupportedCommand.determineSupportForJGit());
    }

    @Test
    public void testUseBranchNull() {
        unsupportedCommand.useBranch(null, null);
        assertTrue(unsupportedCommand.determineSupportForJGit());
    }

    @Test
    public void testUseBranchNullBranchName() {
        unsupportedCommand.useBranch("some-submodule", null);
        assertFalse(unsupportedCommand.determineSupportForJGit());
    }

    @Test
    public void testUseBranchNullSubmodule() {
        unsupportedCommand.useBranch(null, "some-branch");
        assertFalse(unsupportedCommand.determineSupportForJGit());
    }

    @Test
    public void testUseBranch() {
        unsupportedCommand.useBranch("some-submodule", "some-branch");
        assertFalse(unsupportedCommand.determineSupportForJGit());
    }

    @Test
    public void testGitPublisherDisabled() {
        /* Disabled git publisher is allowed to use JGit */
        unsupportedCommand.gitPublisher(false);
        assertTrue(unsupportedCommand.determineSupportForJGit());
    }

    @Test
    public void testGitPublisher() {
        /* Enabled git publisher must not use JGit */
        unsupportedCommand.gitPublisher(true);
        assertFalse(unsupportedCommand.determineSupportForJGit());
    }

    @Test
    public void testDetermineSupportForJGit() {
        /* Confirm default is true */
        assertTrue(unsupportedCommand.determineSupportForJGit());
    }

    private static class FakeCredentials implements StandardCredentials {

        public FakeCredentials() {
        }

        @Override
        public String getDescription() {
            throw new UnsupportedOperationException("Unsupported");
        }

        @Override
        public String getId() {
            throw new UnsupportedOperationException("Unsupported");
        }

        @Override
        public CredentialsScope getScope() {
            throw new UnsupportedOperationException("Unsupported");
        }

        @Override
        public CredentialsDescriptor getDescriptor() {
            throw new UnsupportedOperationException("Unsupported");
        }
    }
}
