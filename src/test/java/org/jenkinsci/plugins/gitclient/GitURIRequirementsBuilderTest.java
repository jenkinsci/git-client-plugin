package org.jenkinsci.plugins.gitclient;

import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.domains.HostnamePortRequirement;
import com.cloudbees.plugins.credentials.domains.HostnameRequirement;
import com.cloudbees.plugins.credentials.domains.SchemeRequirement;
import org.junit.Test;

import java.util.List;

import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.assertThat;

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

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("ssh"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, notNullValue());
        assertThat(hostnamePort.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort.getPort(), is(8080));
        
        list = GitURIRequirementsBuilder.fromUri("ssh://foo.bar.com:8080/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("ssh"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, notNullValue());
        assertThat(hostnamePort.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort.getPort(), is(8080));
        
        list = GitURIRequirementsBuilder.fromUri("ssh://bob@foo.bar.com/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("ssh"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, nullValue());

        list = GitURIRequirementsBuilder.fromUri("ssh://foo.bar.com/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("ssh"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, nullValue());

        list = GitURIRequirementsBuilder.fromUri("git://foo.bar.com:8080/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("git"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, notNullValue());
        assertThat(hostnamePort.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort.getPort(), is(8080));

        list = GitURIRequirementsBuilder.fromUri("git://foo.bar.com/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("git"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, nullValue());

        list = GitURIRequirementsBuilder.fromUri("http://bob@foo.bar.com:8080/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("http"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, notNullValue());
        assertThat(hostnamePort.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort.getPort(), is(8080));
        
        list = GitURIRequirementsBuilder.fromUri("http://foo.bar.com:8080/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("http"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, notNullValue());
        assertThat(hostnamePort.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort.getPort(), is(8080));
        
        list = GitURIRequirementsBuilder.fromUri("http://bob@foo.bar.com/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("http"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, nullValue());
        
        list = GitURIRequirementsBuilder.fromUri("http://foo.bar.com/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("http"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, nullValue());
        
        list = GitURIRequirementsBuilder.fromUri("https://bob@foo.bar.com:8080/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("https"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, notNullValue());
        assertThat(hostnamePort.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort.getPort(), is(8080));
        
        list = GitURIRequirementsBuilder.fromUri("https://foo.bar.com:8080/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("https"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, notNullValue());
        assertThat(hostnamePort.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort.getPort(), is(8080));
        
        list = GitURIRequirementsBuilder.fromUri("https://bob@foo.bar.com/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("https"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, nullValue());
        
        list = GitURIRequirementsBuilder.fromUri("https://foo.bar.com/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("https"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, nullValue());
        
        list = GitURIRequirementsBuilder.fromUri("ftp://bob@foo.bar.com:8080/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("ftp"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, notNullValue());
        assertThat(hostnamePort.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort.getPort(), is(8080));
        
        list = GitURIRequirementsBuilder.fromUri("ftp://foo.bar.com:8080/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("ftp"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, notNullValue());
        assertThat(hostnamePort.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort.getPort(), is(8080));
        
        list = GitURIRequirementsBuilder.fromUri("ftp://bob@foo.bar.com/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("ftp"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, nullValue());
        
        list = GitURIRequirementsBuilder.fromUri("ftp://foo.bar.com/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("ftp"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, nullValue());
        
        list = GitURIRequirementsBuilder.fromUri("ftps://bob@foo.bar.com:8080/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("ftps"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, notNullValue());
        assertThat(hostnamePort.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort.getPort(), is(8080));
        
        list = GitURIRequirementsBuilder.fromUri("ftps://foo.bar.com:8080/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("ftps"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, notNullValue());
        assertThat(hostnamePort.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort.getPort(), is(8080));
        
        list = GitURIRequirementsBuilder.fromUri("ftps://bob@foo.bar.com/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("ftps"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, nullValue());
        
        list = GitURIRequirementsBuilder.fromUri("ftps://foo.bar.com/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("ftps"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, nullValue());
        
        list = GitURIRequirementsBuilder.fromUri("rsync://foo.bar.com/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("rsync"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, nullValue());

        list = GitURIRequirementsBuilder.fromUri("bob@foo.bar.com:/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("ssh"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, notNullValue());
        assertThat(hostnamePort.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort.getPort(), is(22));

        list = GitURIRequirementsBuilder.fromUri("bob@foo.bar.com:path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("ssh"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, notNullValue());
        assertThat(hostnamePort.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort.getPort(), is(22));

        list = GitURIRequirementsBuilder.fromUri("foo.bar.com:/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("ssh"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, notNullValue());
        assertThat(hostnamePort.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort.getPort(), is(22));

        list = GitURIRequirementsBuilder.fromUri("foo.bar.com:path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("ssh"));
        assertThat(hostname, notNullValue());
        assertThat(hostname.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort, notNullValue());
        assertThat(hostnamePort.getHostname(), is("foo.bar.com"));
        assertThat(hostnamePort.getPort(), is(22));

        list = GitURIRequirementsBuilder.fromUri("path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("file"));
        assertThat(hostname, nullValue());
        assertThat(hostnamePort, nullValue());

        list = GitURIRequirementsBuilder.fromUri("/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("file"));
        assertThat(hostname, nullValue());
        assertThat(hostnamePort, nullValue());

        list = GitURIRequirementsBuilder.fromUri("file:/path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("file"));
        assertThat(hostname, nullValue());
        assertThat(hostnamePort, nullValue());

        list = GitURIRequirementsBuilder.fromUri("file://path/to/repo.git/").build();

        scheme = firstOrNull(list, SchemeRequirement.class);
        hostname = firstOrNull(list, HostnameRequirement.class);
        hostnamePort = firstOrNull(list, HostnamePortRequirement.class);

        assertThat(scheme, notNullValue());
        assertThat(scheme.getScheme(), is("file"));
        assertThat(hostname, nullValue());
        assertThat(hostnamePort, nullValue());

    }
    
    <T> T firstOrNull(List<? super T> list, Class<T> type) {
        for (Object i: list) {
            if (type.isInstance(i))
                return type.cast(i);
        }
        return null;
    }

}
