package org.jenkinsci.plugins.gitclient;

import hudson.plugins.git.GitException;

/**
 * <p>This Exception is thrown if the result of an Git operation is ambiguous.</p>
 * <p>E.g. git prints a warning if a ref is ambiguous:<br/>
 * <tt>warning: refname 'xyz' is ambiguous.</tt></p>
 *  
 */
public class AmbiguousResultException extends GitException
{

    private static final long serialVersionUID = 1L;

    public AmbiguousResultException() {
        super();
    }

    public AmbiguousResultException(String message) {
        super(message);
    }

    public AmbiguousResultException(Throwable cause) {
        super(cause);
    }

    public AmbiguousResultException(String message, Throwable cause) {
        super(message, cause);
    }

    public AmbiguousResultException(String messageFormatString, Object...args) {
        super(String.format(messageFormatString, args));
    }
    
}
