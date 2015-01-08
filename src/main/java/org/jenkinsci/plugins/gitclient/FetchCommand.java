package org.jenkinsci.plugins.gitclient;

import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;

import java.util.List;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public interface FetchCommand extends GitCommand {

    FetchCommand from(URIish remote, List<RefSpec> refspecs);

    FetchCommand prune();

    FetchCommand shallow(boolean shallow);
    
    FetchCommand timeout(Integer timeout);

    FetchCommand tags(boolean tags);
}
