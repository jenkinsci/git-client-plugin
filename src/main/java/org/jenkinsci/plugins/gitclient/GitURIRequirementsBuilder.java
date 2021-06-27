package org.jenkinsci.plugins.gitclient;

import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.domains.HostnamePortRequirement;
import com.cloudbees.plugins.credentials.domains.HostnameRequirement;
import com.cloudbees.plugins.credentials.domains.PathRequirement;
import com.cloudbees.plugins.credentials.domains.SchemeRequirement;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A builder to help creating requirements from GIT URIs.
 *
 * @author stephenc
 * @since 1.2.0
 */
public class GitURIRequirementsBuilder {

    /**
     * Part of a pattern which matches the scheme part (git, http, ...) of an
     * URI. Defines one capturing group containing the scheme without the
     * trailing colon and slashes
     */
    private static final String SCHEME_P = "([a-z][a-z0-9+-]+)://"; //$NON-NLS-1$

    /**
     * Part of a pattern which matches the optional user/password part (e.g.
     * root:pwd@ in git://root:pwd@host.xyz/a.git) of URIs. Defines two
     * capturing groups: the first containing the user and the second containing
     * the password
     */
    private static final String OPT_USER_PWD_P = "(?:([^/:@]+)(?::([^\\\\/]+))?@)?"; //$NON-NLS-1$

    /**
     * Part of a pattern which matches the host part of URIs. Defines one
     * capturing group containing the host name.
     */
    private static final String HOST_P = "((?:[^\\\\/:]+)|(?:\\[[0-9a-f:]+\\]))";

    /**
     * Part of a pattern which matches the optional port part of URIs. Defines
     * one capturing group containing the port without the preceding colon.
     */
    private static final String OPT_PORT_P = "(?::(\\d+))?"; //$NON-NLS-1$

    /**
     * Part of a pattern which matches the ~username part (e.g. /~root in
     * git://host.xyz/~root/a.git) of URIs. Defines no capturing group.
     */
    private static final String USER_HOME_P = "(?:/~(?:[^\\\\/]+))"; //$NON-NLS-1$

    /**
     * Part of a pattern which matches the optional drive letter in paths (e.g.
     * D: in file:///D:/a.txt). Defines no capturing group.
     */
    private static final String OPT_DRIVE_LETTER_P = "(?:[A-Za-z]:)?"; //$NON-NLS-1$

    /**
     * Part of a pattern which matches a relative path. Relative paths don't
     * start with slash or drive letters. Defines no capturing group.
     */
    private static final String RELATIVE_PATH_P = "(?:(?:[^\\\\/]+[\\\\/]+)*[^\\\\/]+[\\\\/]*)"; //$NON-NLS-1$

    /**
     * Part of a pattern which matches a relative or absolute path. Defines no
     * capturing group.
     */
    private static final String PATH_P = "(" + OPT_DRIVE_LETTER_P + "[\\\\/]?" //$NON-NLS-1$ //$NON-NLS-2$
            + RELATIVE_PATH_P + ")"; //$NON-NLS-1$

    private static final long serialVersionUID = 1L;

    /**
     * A pattern matching standard URI: </br>
     * <code>scheme "://" user_password? hostname? portnumber? path</code>
     */
    private static final Pattern FULL_URI = Pattern.compile("^" // //$NON-NLS-1$
            + SCHEME_P //
            + "(?:" // start a group containing hostname and all options only //$NON-NLS-1$
            // availabe when a hostname is there
            + OPT_USER_PWD_P //
            + HOST_P //
            + OPT_PORT_P //
            + "(" // open a catpuring group the the user-home-dir part //$NON-NLS-1$
            + (USER_HOME_P + "?") // //$NON-NLS-1$
            + "[\\\\/])" // //$NON-NLS-1$
            + ")?" // close the optional group containing hostname //$NON-NLS-1$
            + "(.+)?" // //$NON-NLS-1$
            + "$"); //$NON-NLS-1$

    /**
     * A pattern matching the reference to a local file. This may be an absolute
     * path (maybe even containing windows drive-letters) or a relative path.
     */
    private static final Pattern LOCAL_FILE = Pattern.compile("^" // //$NON-NLS-1$
            + "([\\\\/]?" + PATH_P + ")" // //$NON-NLS-1$ //$NON-NLS-2$
            + "$"); //$NON-NLS-1$

    /**
     * A pattern matching a URI for the scheme 'file' which has only ':/' as
     * separator between scheme and path. Standard file URIs have '://' as
     * separator, but java.io.File.toURI() constructs those URIs.
     */
    private static final Pattern SINGLE_SLASH_FILE_URI = Pattern.compile("^" // //$NON-NLS-1$
            + "(file):([\\\\/](?![\\\\/])" // //$NON-NLS-1$
            + PATH_P //
            + ")$"); //$NON-NLS-1$

    /**
     * A pattern matching a SCP URI's of the form user@host:path/to/repo.git
     */
    private static final Pattern RELATIVE_SCP_URI = Pattern.compile("^" // //$NON-NLS-1$
            + OPT_USER_PWD_P //
            + HOST_P //
            + ":(" // //$NON-NLS-1$
            + ("(?:" + USER_HOME_P + "[\\\\/])?") // //$NON-NLS-1$ //$NON-NLS-2$
            + RELATIVE_PATH_P //
            + ")$"); //$NON-NLS-1$

    /**
     * A pattern matching a SCP URI's of the form user@host:/path/to/repo.git
     */
    private static final Pattern ABSOLUTE_SCP_URI = Pattern.compile("^" // //$NON-NLS-1$
            + OPT_USER_PWD_P //
            + "([^\\\\/:]{2,})" // //$NON-NLS-1$
            + ":(" // //$NON-NLS-1$
            + "[\\\\/]" + RELATIVE_PATH_P // //$NON-NLS-1$
            + ")$"); //$NON-NLS-1$

    /**
     * The list of requirements.
     */
    @NonNull
    private final List<DomainRequirement> requirements;

    /**
     * Private constructor.
     *
     * @param requirements the list of requirements.
     */
    private GitURIRequirementsBuilder(@NonNull List<DomainRequirement> requirements) {
        this.requirements = new ArrayList<>(requirements);
    }

    /**
     * Creates an empty builder.
     *
     * @return a new empty builder.
     */
    @NonNull
    public static GitURIRequirementsBuilder create() {
        return new GitURIRequirementsBuilder(Collections.emptyList());
    }

    /**
     * Creates a new builder with the same requirements as this builder.
     *
     * @return a new builder with the same requirements as this builder.
     */
    @NonNull
    public GitURIRequirementsBuilder duplicate() {
        return new GitURIRequirementsBuilder(requirements);
    }

    /**
     * Creates a new builder using the supplied URI.
     *
     * @param uri the URI to create the requirements of.
     * @return a new builder with the requirements of the supplied URI.
     */
    @NonNull
    public static GitURIRequirementsBuilder fromUri(@CheckForNull String uri) {
        return create().withUri(uri);
    }

    /**
     * Replaces the requirements with those of the supplied URI.
     *
     * @param uri the URI.
     * @return {@code this}.
     */
    @NonNull
    public GitURIRequirementsBuilder withUri(@CheckForNull String uri) {
        if (uri != null) {
            Matcher matcher = SINGLE_SLASH_FILE_URI.matcher(uri);
            if (matcher.matches()) {
                return withScheme("file").withPath(matcher.group(2)).withoutHostname().withoutHostnamePort();
            }
            matcher = FULL_URI.matcher(uri);
            if (matcher.matches()) {
                withScheme(matcher.group(1));
                if (!"file".equals(matcher.group(1)) && matcher.group(4) != null) {
                    withPath(matcher.group(7));
                    if (matcher.group(5) != null) {
                        withHostnamePort(matcher.group(4), Integer.parseInt(matcher.group(5)));
                    } else {
                        withHostname(matcher.group(4)).withoutHostnamePort();
                    }
                } else {
                    withPath(matcher.group(4)+"/"+matcher.group(7));
                }
                return this;
            }
            matcher = RELATIVE_SCP_URI.matcher(uri);
            if (matcher.matches()) {
                return withScheme("ssh").withPath(matcher.group(4)).withHostnamePort(matcher.group(3),22);
            }
            matcher = ABSOLUTE_SCP_URI.matcher(uri);
            if (matcher.matches()) {
                return withScheme("ssh").withPath(matcher.group(4)).withHostnamePort(matcher.group(3),22);
            }
            matcher = LOCAL_FILE.matcher(uri);
            if (matcher.matches()) {
                return withScheme("file").withPath(matcher.group(2)).withoutHostname().withoutHostnamePort();
            }
        }
        return withoutScheme().withoutPath().withoutHostname().withoutHostnamePort();
    }

    /**
     * Removes any scheme requirements.
     *
     * @return {@code this}.
     */
    @NonNull
    public GitURIRequirementsBuilder withoutScheme() {
        for (Iterator<DomainRequirement> iterator = requirements.iterator(); iterator.hasNext(); ) {
            DomainRequirement r = iterator.next();
            if (r instanceof SchemeRequirement) {
                iterator.remove();
            }
        }
        return this;
    }

    /**
     * Removes any path requirements.
     *
     * @return {@code this}.
     */
    @NonNull
    public GitURIRequirementsBuilder withoutPath() {
        for (Iterator<DomainRequirement> iterator = requirements.iterator(); iterator.hasNext(); ) {
            DomainRequirement r = iterator.next();
            if (r instanceof PathRequirement) {
                iterator.remove();
            }
        }
        return this;
    }

    /**
     * Removes any hostname or hostname:port requirements.
     *
     * @return {@code this}.
     */
    @NonNull
    public GitURIRequirementsBuilder withoutHostname() {
        for (Iterator<DomainRequirement> iterator = requirements.iterator(); iterator.hasNext(); ) {
            DomainRequirement r = iterator.next();
            if (r instanceof HostnameRequirement) {
                iterator.remove();
            }
        }
        return this;
    }

    /**
     * Removes any hostname:port requirements.
     *
     * @return {@code this}.
     */
    @NonNull
    public GitURIRequirementsBuilder withoutHostnamePort() {
        for (Iterator<DomainRequirement> iterator = requirements.iterator(); iterator.hasNext(); ) {
            DomainRequirement r = iterator.next();
            if (r instanceof HostnamePortRequirement) {
                iterator.remove();
            }
        }
        return this;
    }

    /**
     * Replace any scheme requirements with the supplied scheme.
     *
     * @param scheme the scheme to use as a requirement
     * @return {@code this}.
     */
    @NonNull
    public GitURIRequirementsBuilder withScheme(@CheckForNull String scheme) {
        withoutScheme();
        if (scheme != null) {
            requirements.add(new SchemeRequirement(scheme));
        }
        return this;
    }

    /**
     * Replace any path requirements with the supplied path.
     *
     * @param path to use as a requirement
     * @return {@code this}.
     */
    @NonNull
    public GitURIRequirementsBuilder withPath(@CheckForNull String path) {
        withoutPath();
        if (path != null) {
            requirements.add(new PathRequirement(path));
        }
        return this;
    }

    /**
     * Replace any hostname requirements with the supplied hostname.
     *
     * @param hostname the hostname to use as a requirement
     * @return {@code this}.
     */
    @NonNull
    public GitURIRequirementsBuilder withHostname(@CheckForNull String hostname) {
        return withHostnamePort(hostname, -1);
    }

    /**
     * Replace any hostname or hostname:port requirements with the supplied hostname and port.
     *
     * @param hostname the hostname to use as a requirement or (@code null} to not add any requirement
     * @param port     the port or {@code -1} to not add {@link com.cloudbees.plugins.credentials.domains.HostnamePortRequirement}s
     * @return {@code this}.
     */
    @NonNull
    public GitURIRequirementsBuilder withHostnamePort(@CheckForNull String hostname, int port) {
        withoutHostname();
        withoutHostnamePort();
        if (hostname != null) {
            requirements.add(new HostnameRequirement(hostname));
            if (port != -1) {
                requirements.add(new HostnamePortRequirement(hostname, port));
            }
        }
        return this;
    }

    /**
     * Builds the list of requirements.
     *
     * @return the list of requirements.
     */
    @NonNull
    public List<DomainRequirement> build() {
        return new ArrayList<>(requirements);
    }

}
