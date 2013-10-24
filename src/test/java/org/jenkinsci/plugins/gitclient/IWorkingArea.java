package org.jenkinsci.plugins.gitclient;

import hudson.plugins.git.IGitAPI;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.ObjectId;

import java.io.File;
import java.io.IOException;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public interface IWorkingArea {
    String cmd(String args) throws IOException, InterruptedException;

    String launchCommand(String... args) throws IOException, InterruptedException;

    String repoPath();

    WorkingArea init() throws IOException, InterruptedException;

    void add(String path) throws IOException, InterruptedException;

    void tag(String tag) throws IOException, InterruptedException;

    void commit(String msg) throws IOException, InterruptedException;

    File file(String path);

    boolean exists(String path);

    void touch(String path) throws IOException;

    File touch(String path, String content) throws IOException;

    void rm(String path);

    String contentOf(String path) throws IOException;

    CliGitAPIImpl cgit() throws Exception;

    FileRepository repo() throws IOException;

    void checkout(String branch) throws IOException, InterruptedException;

    ObjectId head() throws IOException, InterruptedException;

    ObjectId revParse(String commit) throws IOException, InterruptedException;

    IGitAPI igit();
}
