package org.jenkinsci.plugins.gitclient;

import hudson.remoting.RemoteOutputStream;
import org.kohsuke.stapler.framework.io.WriterOutputStream;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Serializable;
import java.io.Writer;
import java.nio.charset.Charset;

/**
 * {@link Writer} version of {@link RemoteOutputStream}.
 *
 * TODO: move this to remoting
 *
 * @author Kohsuke Kawaguchi
 */
class RemoteWriter extends Writer implements Serializable {
    private OutputStream ros;
    private transient Writer w;

    RemoteWriter(Writer w) {
        ros = new RemoteOutputStream(new WriterOutputStream(w, UTF8));
        w = new OutputStreamWriter(ros, UTF8);
    }

    private void readObject(ObjectInputStream stream) throws IOException, ClassNotFoundException {
        stream.defaultReadObject();
        w = new OutputStreamWriter(ros,UTF8);
    }

    @Override
    public void write(int c) throws IOException {
        w.write(c);
    }

    @Override
    public void write(char[] cbuf, int off, int len) throws IOException {
        w.write(cbuf, off, len);
    }

    @Override
    public void write(String str) throws IOException {
        w.write(str);
    }

    @Override
    public void write(String str, int off, int len) throws IOException {
        w.write(str, off, len);
    }

    @Override
    public Writer append(CharSequence csq, int start, int end) throws IOException {
        return w.append(csq, start, end);
    }

    @Override
    public void flush() throws IOException {
        w.flush();
    }

    @Override
    public void close() throws IOException {
        w.close();
    }

    private static final long serialVersionUID = 1L;

    private static final Charset UTF8 = Charset.forName("UTF-8");
}
