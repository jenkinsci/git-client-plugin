package org.jenkinsci.plugins.gitclient;

import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.domains.HostnamePortRequirement;
import com.cloudbees.plugins.credentials.domains.HostnameRequirement;
import com.cloudbees.plugins.credentials.domains.PathRequirement;
import com.cloudbees.plugins.credentials.domains.SchemeRequirement;
import org.junit.Test;

import java.util.List;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * @author stephenc
 * @since 30/08/2013 15:05
 */
public class GitURIRequirementsBuilderTest {

    @Test
    public void smokes() throws Exception {
        List<DomainRequirement> list =
                GitURIRequirementsBuilder.fromUri("ssh://bob@foo.bar.com:8080/path/to/repo.git/").build();

        SchemeRequirement scheme = firstOrNull(list, SchemeRequirement.class);
        HostnameRequirement hostname = firstOrNull(list, HostnameRequirement.class);
        HostnamePortRequirement hostnamePort = firstOrNull(list, HostnamePortRequirement.class);
        PathRequirement path = firstOrNull(list, PathRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("ssh"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, notNullValue());
        assertThat(hostnamePort.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort.getPort(), is(8080));
        assertThat(path, notNullValue());
        assertThat(path.getPath(), is("/path/to/repo.git/"));

        list = GitURIRequirementsBuilder.fromUri("ssh://foo.bar.com:8080/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);
        path = firstOrNull(list, PathRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("ssh"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, notNullValue());
        assertThat(hostnamePort.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort.getPort(), is(8080));
        assertThat(path, notNullValue());
        assertThat(path.getPath(), is("/path/to/repo.git/"));

        list = GitURIRequirementsBuilder.fromUri("ssh://bob@foo.bar.com/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);
        path = firstOrNull(list, PathRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("ssh"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, nullValue());
        assertThat(path, notNullValue());
        assertThat(path.getPath(), is("/path/to/repo.git/"));

        list = GitURIRequirementsBuilder.fromUri("ssh://foo.bar.com/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);
        path = firstOrNull(list, PathRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("ssh"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, nullValue());
        assertThat(path, notNullValue());
        assertThat(path.getPath(), is("/path/to/repo.git/"));

        list = GitURIRequirementsBuilder.fromUri("git@foo.bar.com:/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);
        path = firstOrNull(list, PathRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("ssh"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort.getPort(), is(22));
        assertThat(path, notNullValue());
        assertThat(path.getPath(), is("/path/to/repo.git/"));

        /* Use an scp URL with relative path.
         * Git supports this style (command line and JGit), but the
         * PathRequirement incorrectly prefixes a slash to the front of the
         * relative URL. Unlikely to ever change that in PathRequirement due to
         * the many other locations likely to depend on that prefix addition.
         */
        list = GitURIRequirementsBuilder.fromUri("git@foo.bar.com:path/to/repo.git").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);
        path = firstOrNull(list, PathRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("ssh"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort.getPort(), is(22));
        assertThat(path, notNullValue());
        assertThat(path.getPath(), is("/path/to/repo.git")); // Should be "path/to/repo.git"

        list = GitURIRequirementsBuilder.fromUri("git://foo.bar.com:8080/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);
        path = firstOrNull(list, PathRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("git"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, notNullValue());
        assertThat(hostnamePort.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort.getPort(), is(8080));
        assertThat(path, notNullValue());
        assertThat(path.getPath(), is("/path/to/repo.git/"));

        list = GitURIRequirementsBuilder.fromUri("git://foo.bar.com/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);
        path = firstOrNull(list, PathRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("git"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, nullValue());
        assertThat(path, notNullValue());
        assertThat(path.getPath(), is("/path/to/repo.git/"));

        list = GitURIRequirementsBuilder.fromUri("http://bob:bobpass@foo.bar.com:8080/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);
        path = firstOrNull(list, PathRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("http"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, notNullValue());
        assertThat(hostnamePort.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort.getPort(), is(8080));
        assertThat(path, notNullValue());
        assertThat(path.getPath(), is("/path/to/repo.git/"));

        list = GitURIRequirementsBuilder.fromUri("http://foo.bar.com:8080/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);
        path = firstOrNull(list, PathRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("http"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, notNullValue());
        assertThat(hostnamePort.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort.getPort(), is(8080));
        assertThat(path, notNullValue());
        assertThat(path.getPath(), is("/path/to/repo.git/"));

        list = GitURIRequirementsBuilder.fromUri("http://bob@foo.bar.com/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);
        path = firstOrNull(list, PathRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("http"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, nullValue());
        assertThat(path, notNullValue());
        assertThat(path.getPath(), is("/path/to/repo.git/"));

        list = GitURIRequirementsBuilder.fromUri("http://foo.bar.com/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);
        path = firstOrNull(list, PathRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("http"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, nullValue());
        assertThat(path, notNullValue());
        assertThat(path.getPath(), is("/path/to/repo.git/"));

        list = GitURIRequirementsBuilder.fromUri("https://bob@foo.bar.com:8080/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);
        path = firstOrNull(list, PathRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("https"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, notNullValue());
        assertThat(hostnamePort.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort.getPort(), is(8080));
        assertThat(path, notNullValue());
        assertThat(path.getPath(), is("/path/to/repo.git/"));

        list = GitURIRequirementsBuilder.fromUri("https://foo.bar.com:8080/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);
        path = firstOrNull(list, PathRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("https"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, notNullValue());
        assertThat(hostnamePort.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort.getPort(), is(8080));
        assertThat(path, notNullValue());
        assertThat(path.getPath(), is("/path/to/repo.git/"));

        list = GitURIRequirementsBuilder.fromUri("https://bob@foo.bar.com/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);
        path = firstOrNull(list, PathRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("https"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, nullValue());
        assertThat(path, notNullValue());
        assertThat(path.getPath(), is("/path/to/repo.git/"));

        list = GitURIRequirementsBuilder.fromUri("https://foo.bar.com/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);
        path   = firstOrNull(list,PathRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("https"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, nullValue());
        assertThat(path, notNullValue());
        assertThat(path.getPath(), is("/path/to/repo.git/"));

        list = GitURIRequirementsBuilder.fromUri("ftp://bob@foo.bar.com:8080/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);
        path = firstOrNull(list, PathRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("ftp"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, notNullValue());
        assertThat(hostnamePort.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort.getPort(), is(8080));
        assertThat(path, notNullValue());
        assertThat(path.getPath(), is("/path/to/repo.git/"));

        list = GitURIRequirementsBuilder.fromUri("ftp://foo.bar.com:8080/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);
        path = firstOrNull(list, PathRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("ftp"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, notNullValue());
        assertThat(hostnamePort.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort.getPort(), is(8080));
        assertThat(path, notNullValue());
        assertThat(path.getPath(), is("/path/to/repo.git/"));

        list = GitURIRequirementsBuilder.fromUri("ftp://bob@foo.bar.com/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);
        path = firstOrNull(list, PathRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("ftp"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, nullValue());
        assertThat(path, notNullValue());
        assertThat(path.getPath(), is("/path/to/repo.git/"));

        list = GitURIRequirementsBuilder.fromUri("ftp://foo.bar.com/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);
        path = firstOrNull(list, PathRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("ftp"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, nullValue());
        assertThat(path, notNullValue());
        assertThat(path.getPath(), is("/path/to/repo.git/"));

        list = GitURIRequirementsBuilder.fromUri("ftps://bob@foo.bar.com:8080/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);
        path = firstOrNull(list, PathRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("ftps"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, notNullValue());
        assertThat(hostnamePort.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort.getPort(), is(8080));
        assertThat(path, notNullValue());
        assertThat(path.getPath(), is("/path/to/repo.git/"));

        list = GitURIRequirementsBuilder.fromUri("ftps://foo.bar.com:8080/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);
        path = firstOrNull(list, PathRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("ftps"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, notNullValue());
        assertThat(hostnamePort.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort.getPort(), is(8080));
        assertThat(path, notNullValue());
        assertThat(path.getPath(), is("/path/to/repo.git/"));

        list = GitURIRequirementsBuilder.fromUri("ftps://bob@foo.bar.com/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);
        path = firstOrNull(list, PathRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("ftps"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, nullValue());
        assertThat(path, notNullValue());
        assertThat(path.getPath(), is("/path/to/repo.git/"));

        list = GitURIRequirementsBuilder.fromUri("ftps://foo.bar.com/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);
        path = firstOrNull(list, PathRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("ftps"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, nullValue());
        assertThat(path, notNullValue());
        assertThat(path.getPath(), is("/path/to/repo.git/"));

        list = GitURIRequirementsBuilder.fromUri("rsync://foo.bar.com/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);
        path = firstOrNull(list, PathRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("rsync"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, nullValue());
        assertThat(path, notNullValue());
        assertThat(path.getPath(), is("/path/to/repo.git/"));

        list = GitURIRequirementsBuilder.fromUri("bob@foo.bar.com:/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);
        path = firstOrNull(list, PathRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("ssh"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, notNullValue());
        assertThat(hostnamePort.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort.getPort(), is(22));
        assertThat(path, notNullValue());
        assertThat(path.getPath(), is("/path/to/repo.git/"));

        list = GitURIRequirementsBuilder.fromUri("bob@foo.bar.com:path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);
        path = firstOrNull(list, PathRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("ssh"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, notNullValue());
        assertThat(hostnamePort.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort.getPort(), is(22));
        assertThat(path, notNullValue());
        assertThat(path.getPath(), is("/path/to/repo.git/"));

        list = GitURIRequirementsBuilder.fromUri("foo.bar.com:/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);
        path = firstOrNull(list, PathRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("ssh"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, notNullValue());
        assertThat(hostnamePort.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort.getPort(), is(22));
        assertThat(path, notNullValue());
        assertThat(path.getPath(), is("/path/to/repo.git/"));

        list = GitURIRequirementsBuilder.fromUri("foo.bar.com:path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);
        path = firstOrNull(list, PathRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("ssh"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, notNullValue());
        assertThat(hostnamePort.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort.getPort(), is(22));
        assertThat(path, notNullValue());
        assertThat(path.getPath(), is("/path/to/repo.git/"));

        list = GitURIRequirementsBuilder.fromUri("path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);
        path = firstOrNull(list, PathRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("file"));
        assertThat(hostname, nullValue());
        assertThat(hostnamePort, nullValue());
        assertThat(path, notNullValue());
        assertThat(path.getPath(), is("/path/to/repo.git/"));

        list = GitURIRequirementsBuilder.fromUri("/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);
        path = firstOrNull(list, PathRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("file"));
        assertThat(hostname, nullValue());
        assertThat(hostnamePort, nullValue());
        assertThat(path, notNullValue());
        assertThat(path.getPath(), is("/path/to/repo.git/"));

        list = GitURIRequirementsBuilder.fromUri("file:/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);
        path = firstOrNull(list, PathRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("file"));
        assertThat(hostname, nullValue());
        assertThat(hostnamePort, nullValue());
        assertThat(path, notNullValue());
        assertThat(path.getPath(), is("/path/to/repo.git/"));

        list = GitURIRequirementsBuilder.fromUri("file://path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);
        path = firstOrNull(list, PathRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("file"));
        assertThat(hostname, nullValue());
        assertThat(hostnamePort, nullValue());
        assertThat(path, notNullValue());
        assertThat(path.getPath(), is("/path/to/repo.git/"));

    }

    private static <T> T firstOrNull(List<? super T> list, Class<T> type) {
        for (Object i: list) {
            if (type.isInstance(i))
                return type.cast(i);
        }
        return null;
    }

}
