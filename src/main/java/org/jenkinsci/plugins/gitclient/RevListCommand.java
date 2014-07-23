/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.jenkinsci.plugins.gitclient;

import java.util.List;
import org.eclipse.jgit.lib.ObjectId;

/**
 *
 * @author <a href="mailto:m.zahnlecker@gmail.com">Marc Zahnlecker
 */
public interface RevListCommand extends GitCommand {
    RevListCommand all();

    RevListCommand firstParent();

    RevListCommand to(List<ObjectId> revs);

    RevListCommand reference(String reference);
}
