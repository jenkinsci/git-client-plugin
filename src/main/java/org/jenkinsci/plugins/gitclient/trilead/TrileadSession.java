package org.jenkinsci.plugins.gitclient.trilead;

import com.trilead.ssh2.Connection;
import com.trilead.ssh2.Session;
import org.eclipse.jgit.transport.RemoteSession;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import static com.trilead.ssh2.ChannelCondition.*;

/**
 * TrileadSession class.
 *
 * @author Kohsuke Kawaguchi
 */
public class TrileadSession implements RemoteSession {
    protected final Connection con;

    /**
     * Constructor for TrileadSession.
     *
     * @param con a {@link com.trilead.ssh2.Connection} object for this session's connection.
     */
    public TrileadSession(Connection con) {
        this.con = con;
    }

    /** {@inheritDoc} */
    @Override
    public Process exec(String commandName, final int timeout) throws IOException {
        return new ProcessImpl(con, commandName, timeout);
    }

    private static class ProcessImpl extends Process {

        private final int timeout;
        private final Session s;

        public ProcessImpl(Connection con, String commandName, final int timeout) throws IOException {
            this.timeout = timeout;
            s = con.openSession();
            s.execCommand(commandName);
        }

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
            int r = s.waitForCondition(EXIT_STATUS, timeout * 1000L);
            if ((r&EXIT_STATUS)!=0)
                return exitValue();

            // not sure what exception jgit expects
            throw new InterruptedException("Timed out: " + r);
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
    }

    @Override
    public void disconnect() {
        con.close();
    }
}
