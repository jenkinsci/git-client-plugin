package hudson.plugins.git;

import hudson.model.TaskListener;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.RemoteConfig;
import org.jenkinsci.plugins.gitclient.GitClient;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * @deprecated methods here are deprecated until proven useful by a plugin
 */
public interface IGitAPI extends GitClient {

    boolean hasGitModules( String treeIsh ) throws GitException;
    String getRemoteUrl(String name, String GIT_DIR) throws GitException;
    void setRemoteUrl(String name, String url, String GIT_DIR) throws GitException;
    String getDefaultRemote( String _default_ ) throws GitException;
    boolean isBareRepository() throws GitException;
    boolean isBareRepository(String GIT_DIR) throws GitException;
    void submoduleInit()  throws GitException;
    void submoduleSync() throws GitException;
    String getSubmoduleUrl(String name) throws GitException;
    void setSubmoduleUrl(String name, String url) throws GitException;
    void fixSubmoduleUrls( String remote, TaskListener listener ) throws GitException;
    void setupSubmoduleUrls( String remote, TaskListener listener ) throws GitException;
    public void fetch(String repository, String refspec) throws GitException;
    void fetch(RemoteConfig remoteRepository);
    void fetch() throws GitException;
    void reset(boolean hard) throws GitException;
    void reset() throws GitException;
    void push(RemoteConfig repository, String revspec) throws GitException;
    void merge(String revSpec) throws GitException;
    void clone(RemoteConfig source) throws GitException;
    void clone(RemoteConfig rc, boolean useShallowClone) throws GitException;
    List<Branch> getBranchesContaining(String revspec) throws GitException;
    List<IndexEntry> lsTree(String treeIsh) throws GitException;
    List<ObjectId> revListBranch(String branchId) throws GitException;
    String describe(String commitIsh) throws GitException;
    List<Tag> getTagsOnCommit(String revName) throws GitException, IOException;
    void changelog(String revFrom, String revTo, OutputStream fos) throws GitException;
    void checkoutBranch(String branch, String commitish) throws GitException;
    ObjectId mergeBase(ObjectId sha1, ObjectId sha12);
    String getAllLogEntries(String branch);
}
