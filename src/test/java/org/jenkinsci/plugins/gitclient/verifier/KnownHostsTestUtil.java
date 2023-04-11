package org.jenkinsci.plugins.gitclient.verifier;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.rules.TemporaryFolder;

public class KnownHostsTestUtil {

    private final TemporaryFolder testFolder;

    public KnownHostsTestUtil(TemporaryFolder testFolder) {
        this.testFolder = testFolder;
    }

    public File createFakeSSHDir(String dir) throws IOException {
        // Create a fake directory for use with a known_hosts file
        return testFolder.newFolder(dir);
    }

    public File createFakeKnownHosts(String dir, String name) throws IOException {
        // Create fake known hosts file
        File fakeSSHDir = createFakeSSHDir(dir);
        return new File(fakeSSHDir, name);
    }

    public File createFakeKnownHosts(String dir, String name, String fileContent) throws IOException {
        File fakeKnownHosts = createFakeKnownHosts(dir, name);
        byte[] fakeKnownHostsBytes = fileContent.getBytes(StandardCharsets.UTF_8);
        Files.write(fakeKnownHosts.toPath(), fakeKnownHostsBytes);
        return fakeKnownHosts;
    }

    public File createFakeKnownHosts(String fileContent) throws IOException {
        File fakeKnownHosts = createFakeKnownHosts("fake.ssh", "known_hosts_fake");
        byte[] fakeKnownHostsBytes = fileContent.getBytes(StandardCharsets.UTF_8);
        Files.write(fakeKnownHosts.toPath(), fakeKnownHostsBytes);
        return fakeKnownHosts;
    }

    public List<String> getKnownHostsContent(File file) throws IOException {
        return Files.readAllLines(file.toPath());
    }
}
