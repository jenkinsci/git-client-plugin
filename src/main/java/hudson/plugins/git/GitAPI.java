package hudson.plugins.git;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.model.TaskListener;
import hudson.util.ArgumentListBuilder;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.jenkinsci.plugins.gitclient.CliGitAPIImpl;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Backward compatible class to match the one some plugins used to get from git-plugin
 *
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 * @deprecated
 */
public class GitAPI extends CliGitAPIImpl implements IGitAPI {

    private final File repository;

    @Deprecated
    public GitAPI(String gitExe, FilePath repository, TaskListener listener, EnvVars environment) {
        this(gitExe, new File(repository.getRemote()), listener, environment);
    }

    @Deprecated
    public GitAPI(String gitExe, FilePath repository, TaskListener listener, EnvVars environment, String reference) {
        this(gitExe, repository, listener, environment);
    }

    public GitAPI(String gitExe, File repository, TaskListener listener, EnvVars environment) {
        super(gitExe, repository, listener, environment);
        this.repository = repository;
    }
    
    @Deprecated
    public void checkoutBranch(String ref, String branch) throws GitException {
        if (branch == null)
            checkout(ref);
        else
            checkout(branch, ref);
    }

    @Deprecated
    public void merge(String revSpec) throws GitException {
        try {
            merge(getRepository().resolve(revSpec));
        } catch (IOException e) {
            throw new GitException("Failed to access repository", e);
        }
    }

    @Deprecated
    public boolean hasGitModules(String treeIsh) throws GitException {
        try {
            return new File(repository, ".gitmodules").exists();
        } catch (SecurityException ex) {
            throw new GitException(
                    "Security error when trying to check for .gitmodules. Are you sure you have correct permissions?",
                    ex);
        } catch (Exception e) {
            throw new GitException("Couldn't check for .gitmodules", e);
        }

    }

    @Deprecated
    public void setupSubmoduleUrls(String remote, TaskListener listener) throws GitException {
        // This is to make sure that we don't miss any new submodules or
        // changes in submodule origin paths...
        submoduleInit();
        submoduleSync();
        // This allows us to seamlessly use bare and non-bare superproject
        // repositories.
        fixSubmoduleUrls( remote, listener );
    }

    @Deprecated
    public void fetch(String repository, String refspec) throws GitException {
        fetch(repository, new RefSpec(refspec));
    }

    @Deprecated
    public void fetch(RemoteConfig remoteRepository) {
        // Assume there is only 1 URL / refspec for simplicity
        fetch(remoteRepository.getURIs().get(0).toPrivateString(), remoteRepository.getFetchRefSpecs().get(0).toString());
    }

    @Deprecated
    public void fetch() throws GitException {
        fetch(null, (RefSpec) null);
    }


    public void reset() throws GitException {
        reset(false);
    }

    @Deprecated
    public void push(RemoteConfig repository, String refspec) throws GitException {
        ArgumentListBuilder args = new ArgumentListBuilder();
        args.add("push", repository.getURIs().get(0).toPrivateString());

        if (refspec != null)
            args.add(refspec);

        launchCommand(args);
        // Ignore output for now as there's many different formats
        // That are possible.

    }

    @Deprecated
    public void clone(RemoteConfig source) throws GitException {
        clone(source, false);
    }

    @Deprecated
    public void clone(RemoteConfig rc, boolean useShallowClone) throws GitException {
        // Assume only 1 URL for this repository
        final String source = rc.getURIs().get(0).toPrivateString();
        clone(source, rc.getName(), useShallowClone, null);
    }

    @Deprecated
    public List<Branch> getBranchesContaining(String revspec) throws GitException {
        return parseBranches(launchCommand("branch", "-a", "--contains", revspec));
    }

    @Deprecated
    private List<Branch> parseBranches(String fos) throws GitException {
        // TODO: git branch -a -v --abbrev=0 would do this in one shot..
        List<Branch> tags = new ArrayList<Branch>();
        BufferedReader rdr = new BufferedReader(new StringReader(fos));
        String line;
        try {
            while ((line = rdr.readLine()) != null) {
                // Ignore the 1st
                line = line.substring(2);
                // Ignore '(no branch)' or anything with " -> ", since I think
                // that's just noise
                if ((!line.startsWith("("))
                        && (line.indexOf(" -> ") == -1)) {
                    tags.add(new Branch(line, revParse(line)));
                }
            }
        } catch (IOException e) {
            throw new GitException("Error parsing branches", e);
        }

        return tags;
    }

    @Deprecated
    public List<ObjectId> revListBranch(String branchId) throws GitException {
        return revList(branchId);
    }

    @Deprecated
    public List<String> showRevision(Revision r) throws GitException {
        return showRevision(null, r.getSha1());
    }


        @Deprecated
    public List<Tag> getTagsOnCommit(String revName) throws GitException, IOException {
        final Repository db = getRepository();
        final ObjectId commit = db.resolve(revName);
        final List<Tag> ret = new ArrayList<Tag>();

        for (final Map.Entry<String, Ref> tag : db.getTags().entrySet()) {
            final ObjectId tagId = tag.getValue().getObjectId();
            if (commit.equals(tagId))
                ret.add(new Tag(tag.getKey(), tagId));
        }
        return ret;
    }

    @Deprecated
    public ObjectId mergeBase(ObjectId id1, ObjectId id2) {
        try {
            String result;
            try {
                result = launchCommand("merge-base", id1.name(), id2.name());
            } catch (GitException ge) {
                return null;
            }


            BufferedReader rdr = new BufferedReader(new StringReader(result));
            String line;

            while ((line = rdr.readLine()) != null) {
                // Add the SHA1
                return ObjectId.fromString(line);
            }
        } catch (Exception e) {
            throw new GitException("Error parsing merge base", e);
        }

        return null;
    }

    @Deprecated
    public String getAllLogEntries(String branch) {
        return launchCommand("log", "--all", "--pretty=format:'%H#%ct'", branch);

    }

}
