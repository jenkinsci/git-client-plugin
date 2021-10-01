package org.jenkinsci.plugins.gitclient;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.plugins.git.GitException;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.http.auth.Credentials;
import org.apache.http.auth.UsernamePasswordCredentials;

class Netrc {
    private static final Pattern NETRC_TOKEN = Pattern.compile("(\\S+)");

    private enum ParseState {
        START, REQ_KEY, REQ_VALUE, MACHINE, LOGIN, PASSWORD, MACDEF, END
    }


    private File netrc;
    private long lastModified;
    private Map<String,UsernamePasswordCredentials> hosts = new HashMap<>();



    /**
     * getInstance.
     *
     * @return a {@link org.jenkinsci.plugins.gitclient.Netrc} object.
     */
    public static Netrc getInstance() {
        File netrc = getDefaultFile();
        return getInstance(netrc);
    }

    /**
     * getInstance.
     *
     * @param netrcPath a {@link java.lang.String} object.
     * @return a {@link org.jenkinsci.plugins.gitclient.Netrc} object.
     */
    public static Netrc getInstance(@NonNull String netrcPath) {
        File netrc = new File(netrcPath);
        return netrc.exists() ? getInstance(new File(netrcPath)) : null;
    }

    /**
     * getInstance.
     *
     * @param netrc a {@link java.io.File} object.
     * @return a {@link org.jenkinsci.plugins.gitclient.Netrc} object.
     */
    public static Netrc getInstance(File netrc) {
        return new Netrc(netrc).parse();
    }

    private static File getDefaultFile() {
        File home = new File(System.getProperty("user.home"));
        File netrc = new File(home, ".netrc");
        if (!netrc.exists()) netrc = new File(home, "_netrc"); // windows variant
        return netrc;
    }


    /**
     * getCredentials.
     *
     * @param host a {@link java.lang.String} object.
     * @return a {@link org.apache.http.auth.Credentials} object.
     */
    public synchronized Credentials getCredentials(String host) {
        if (!this.netrc.exists()) return null;
        if (this.lastModified != this.netrc.lastModified()) parse();
        return this.hosts.get(host);
    }

    private Netrc(File netrc) {
        this.netrc = netrc;
    }

    synchronized private Netrc parse() {
        if (!netrc.exists()) return this;

        this.hosts.clear();
        this.lastModified = this.netrc.lastModified();

        try (BufferedReader r = new BufferedReader(new InputStreamReader(Files.newInputStream(netrc.toPath()), Charset.defaultCharset()))) {
            String line;
            String machine = null;
            String login = null;
            String password = null;

            ParseState state = ParseState.START;
            Matcher matcher = NETRC_TOKEN.matcher("");
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    if (state == ParseState.MACDEF) {
                        state = ParseState.REQ_KEY;
                    }
                    continue;
                }

                matcher.reset(line);
                while (matcher.find()) {
                    String match = matcher.group();
                    switch (state) {
                    case START:
                        if ("machine".equals(match)) {
                            state = ParseState.MACHINE;
                        }
                        break;

                    case REQ_KEY:
                        if (null == match) {
                            state = ParseState.REQ_VALUE;
                        } else switch (match) {
                            case "login":
                                state = ParseState.LOGIN;
                                break;
                            case "password":
                                state = ParseState.PASSWORD;
                                break;
                            case "macdef":
                                state = ParseState.MACDEF;
                                break;
                            case "machine":
                                state = ParseState.MACHINE;
                                break;
                            default:
                                state = ParseState.REQ_VALUE;
                                break;
                        }
                        break;

                    case REQ_VALUE:
                        state = ParseState.REQ_KEY;
                        break;

                    case MACHINE:
                        if (machine != null && login != null && password != null) {
                            this.hosts.put(machine, new UsernamePasswordCredentials(login, password));
                        }
                        machine = match;
                        login = null;
                        password = null;
                        state = ParseState.REQ_KEY;
                        break;

                    case LOGIN:
                        login = match;
                        state = ParseState.REQ_KEY;
                        break;

                    case PASSWORD:
                        password = match;
                        state = ParseState.REQ_KEY;
                        break;

                    case MACDEF:
                        // Only way out is an empty line, handled before the find() loop.
                        break;
                    }
                }
            }
            if (machine != null) {
                if (login != null && password != null) {
                    this.hosts.put(machine, new UsernamePasswordCredentials(login, password));
                }
            }

        } catch (IOException e) {
            throw new GitException("Invalid netrc file: '" + this.netrc.getAbsolutePath() + "'", e);
        }

        return this;
    }

}
