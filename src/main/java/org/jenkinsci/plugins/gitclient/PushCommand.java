package org.jenkinsci.plugins.gitclient;

import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;

import java.util.List;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public interface PushCommand extends GitCommand {

    PushCommand to(URIish remote);

    PushCommand ref(String refspec);

    PushCommand force();
    
    PushCommand timeout(Integer timeout);
}
