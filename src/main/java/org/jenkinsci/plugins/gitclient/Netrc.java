package org.jenkinsci.plugins.gitclient;

import hudson.plugins.git.GitException;
import hudson.util.IOUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.httpclient.Credentials;
import org.apache.commons.httpclient.UsernamePasswordCredentials;


class Netrc {
    private static final Pattern NETRC_TOKEN = Pattern.compile("(\\S+)");

    private enum ParseState {
        START, REQ_KEY, REQ_VALUE, MACHINE, LOGIN, PASSWORD, MACDEF, END;
    };


    private File netrc;
    private long lastModified;
    private Map<String,UsernamePasswordCredentials> hosts = new HashMap<String,UsernamePasswordCredentials>();



    public static Netrc getInstance(String netrcPath)
    {
        File netrc = getFile(netrcPath);
        if (netrc == null) return null;
        return new Netrc(netrc).parse();
    }


    public static Netrc getInstance(File netrc)
    {
        if (netrc == null) netrc = getFile(null);
        if (netrc == null) return null;
        return new Netrc(netrc).parse();
    }



    private static File getFile(String netrcPath)
    {
        File netrc = null;

        if (netrcPath == null) {
            File home = new File(System.getProperty("user.home"));
            netrc = new File(home, ".netrc");
            if (!netrc.exists()) netrc = new File(home, "_netrc"); // windows variant
        }
        else {
            netrc = new File(netrcPath);
        }

        return (netrc.exists() ? netrc : null);
    }





    public Credentials getCredentials(String host) {

        if (!this.netrc.exists()) return null;

        if (this.lastModified != this.netrc.lastModified()) parse();

        return this.hosts.get(host);
    }



    private Netrc(File netrc)
    {
        this.netrc = netrc;
    }



    synchronized private Netrc parse()
    {
        if (!netrc.exists()) return null;

        this.hosts.clear();
        this.lastModified = this.netrc.lastModified();


        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(netrc));
            String line = null;
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
                        if ("login".equals(match)) {
                            state = ParseState.LOGIN;
                        }
                        else if ("password".equals(match)) {
                            state = ParseState.PASSWORD;
                        }
                        else if ("macdef".equals(match)) {
                            state = ParseState.MACDEF;
                        }
                        else if ("machine".equals(match)) {
                            state = ParseState.MACHINE;
                        }
                        else {
                            state = ParseState.REQ_VALUE;
                        }
                        break;

                    case REQ_VALUE:
                        state = ParseState.REQ_KEY;
                        break;

                    case MACHINE:
                        if (machine != null) {
                            if (login != null && password != null) {
                                this.hosts.put(machine, new UsernamePasswordCredentials(login, password));
                            }
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
        } finally {
            IOUtils.closeQuietly(r);
        }

        return this;
    }

}
