/*
 * The MIT License
 *
 * Copyright 2015 CloudBees, Inc.
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

import java.io.File;
import jenkins.scm.impl.mock.AbstractSampleDVCSRepoRule;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Manages a sample Git repository.
 */
public class GitClientSampleRepoRule extends AbstractSampleDVCSRepoRule
        implements BeforeEachCallback, AfterEachCallback {

    public void git(String... cmds) throws Exception {
        run("git", cmds);
    }

    @Override
    public void init() throws Exception {
        run(true, tmp.getRoot(), "git", "version");
        git("init");
        write("file", "");
        git("add", "file");
        git("config", "--local", "user.name", "Git SampleRepoRule");
        git("config", "--local", "user.email", "gits@mplereporule");
        git("commit", "--message=init");
    }

    public File getRoot() {
        return this.sampleRepo;
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        try {
            this.before();
        } catch (Throwable t) {
            throw new ExtensionConfigurationException(t.getMessage(), t);
        }
    }

    @Override
    public void afterEach(ExtensionContext context) {
        this.after();
    }
}
