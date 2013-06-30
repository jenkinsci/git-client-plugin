package org.jenkinsci.plugins.gitclient.trilead;

import com.trilead.ssh2.Connection;
import com.trilead.ssh2.Session;
import org.eclipse.jgit.transport.RemoteSession;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import static com.trilead.ssh2.ChannelCondition.*;

/**
 * @author Kohsuke Kawaguchi
 */
public class TrileadSession implements RemoteSession {
    protected final Connection con;

    public TrileadSession(Connection con) {
        this.con = con;
    }

    public Process exec(String commandName, final int timeout) throws IOException {
        final Session s = con.openSession();
        s.execCommand(commandName);

        return new Process() {
            @Override
            public OutputStream getOutputStream() {
                return s.getStdin();
            }

            @Override
            public InputStream getInputStream() {
                return s.getStdout();
            }

            @Override
            public InputStream getErrorStream() {
                return s.getStderr();
            }

            @Override
            public int waitFor() throws InterruptedException {
                int r = s.waitForCondition(EXIT_STATUS, timeout * 1000);
                if ((r&EXIT_STATUS)!=0)
                    return exitValue();

                // not sure what exception jgit expects
                throw new InterruptedException("Timed out: "+r);
            }

            @Override
            public int exitValue() {
                Integer i = s.getExitStatus();
                if (i==null)    throw new IllegalThreadStateException(); // hasn't finished
                return i;
            }

            @Override
            public void destroy() {
                s.close();
            }
        };
    }

    public void disconnect() {
        con.close();
    }
}
