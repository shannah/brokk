package io.github.jbellis.brokk;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class GitRepo implements Closeable, IGitRepo {

    private final Path root;
    private final Repository repository;
    private final Git git;
    private List<RepoFile> trackedFilesCache = null;

    /**
     * Returns true if the directory has a .git folder.
     */
    static boolean hasGitRepo(Path dir) {
        assert dir != null;
        return dir.resolve(".git").toFile().isDirectory();
    }

    /**
     * Get the JGit instance for direct API access
     */
    public Git getGit() {
        return git;
    }

    public GitRepo(Path root) {
        this.root = root;
        if (root == null) {
            throw new IllegalStateException("No git repository found");
        }
        try {
            repository = new FileRepositoryBuilder()
                    .setGitDir(root.resolve(".git").toFile())
                    .build();
            git = new Git(repository);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to open repository", e);
        }
    }


    @Override
    public Path getRoot() {
        return root;
    }

    public synchronized void refresh() {
        repository.getRefDatabase().refresh();
        trackedFilesCache = null;
    }

    public synchronized void add(String relName) throws IOException {
        try {
            git.add().addFilepattern(relName).call();
        } catch (GitAPIException e) {
            throw new IOException("Unable to add file %s to git: %s".formatted(relName, e.getMessage()));
        }
    }

    /**
     * Returns a list of RepoFile objects representing all tracked files in the repository,
     * including unchanged files from HEAD and any files with staged or unstaged modifications
     * (changed, modified, added, removed) from the working directory.
     */
    @Override
    public synchronized List<RepoFile> getTrackedFiles() {
        if (trackedFilesCache != null) {
            return trackedFilesCache;
        }
        var trackedPaths = new HashSet<String>();
        try {
            // HEAD (unchanged) files
            var headTreeId = repository.resolve("HEAD^{tree}");
            if (headTreeId != null) {
                try (var revWalk = new RevWalk(repository);
                     var treeWalk = new TreeWalk(repository))
                {
                    var headTree = revWalk.parseTree(headTreeId);
                    treeWalk.addTree(headTree);
                    treeWalk.setRecursive(true);
                    while (treeWalk.next()) {
                        trackedPaths.add(treeWalk.getPathString());
                    }
                }
            }
            // Staged/modified/added/removed
            var status = git.status().call();
            trackedPaths.addAll(status.getChanged());
            trackedPaths.addAll(status.getModified());
            trackedPaths.addAll(status.getAdded());
            trackedPaths.addAll(status.getRemoved());
        } catch (IOException | GitAPIException e) {
            throw new UncheckedIOException(new IOException(e));
        }
        trackedFilesCache = trackedPaths.stream()
                .map(path -> new RepoFile(root, path))
                .collect(Collectors.toList());
        return trackedFilesCache;
    }

    /**
     * Get the current commit ID (HEAD)
     * @return The full commit hash of HEAD
     * @throws IOException If there's an error getting the commit ID
     */
    public String getCurrentCommitId() throws IOException {
        var head = repository.resolve("HEAD");
        return head != null ? head.getName() : "";
    }

    /**
     * Get diff for specific files only
     * @param filePaths List of file paths to include in the diff
     * @return String containing the diff output
     */
    public synchronized String diffFiles(List<String> filePaths) {
        try (var out = new ByteArrayOutputStream()) {
            // Create path filters for the specified files
            var filters = new ArrayList<PathFilter>();
            for (String path : filePaths) {
                filters.add(PathFilter.create(path));
            }
            var filterGroup = PathFilterGroup.create(filters);

            // 1) staged changes for specified files
            git.diff()
                    .setCached(true)
                    .setShowNameAndStatusOnly(false)
                    .setPathFilter(filterGroup)
                    .setOutputStream(out)
                    .call();
            String staged = out.toString(StandardCharsets.UTF_8);
            out.reset();

            // 2) unstaged changes for specified files
            git.diff()
                    .setCached(false)
                    .setShowNameAndStatusOnly(false)
                    .setPathFilter(filterGroup)
                    .setOutputStream(out)
                    .call();
            String unstaged = out.toString(StandardCharsets.UTF_8);

            if (!staged.isEmpty() && !unstaged.isEmpty()) {
                return staged + "\n" + unstaged;
            }
            return staged + unstaged;
        } catch (IOException | GitAPIException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }

    public synchronized String diff() {
        try (var out = new ByteArrayOutputStream()) {
            var status = git.status().call();
            var trackedPaths = new HashSet<String>();
            trackedPaths.addAll(status.getModified());
            trackedPaths.addAll(status.getChanged());
            trackedPaths.addAll(status.getAdded());
            trackedPaths.addAll(status.getRemoved());
            trackedPaths.addAll(status.getMissing());
            
            // If no changed files, return empty string early
            if (trackedPaths.isEmpty()) {
                return "";
            }

            var filters = new ArrayList<PathFilter>();
            for (String path : trackedPaths) {
                filters.add(PathFilter.create(path));
            }
            var filterGroup = PathFilterGroup.create(filters);

            // 1) staged changes
            git.diff()
                    .setCached(true)
                    .setShowNameAndStatusOnly(false)
                    .setPathFilter(filterGroup)
                    .setOutputStream(out)
                    .call();
            String staged = out.toString(StandardCharsets.UTF_8);
            out.reset();

            // 2) unstaged changes
            git.diff()
                    .setCached(false)
                    .setShowNameAndStatusOnly(false)
                    .setPathFilter(filterGroup)
                    .setOutputStream(out)
                    .call();
            String unstaged = out.toString(StandardCharsets.UTF_8);

            if (!staged.isEmpty() && !unstaged.isEmpty()) {
                return staged + "\n" + unstaged;
            }
            return staged + unstaged;
        } catch (IOException | GitAPIException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }

    /**
     * Gets a list of uncommitted file paths
     * @return List of file paths relative to git root
     */
    public List<String> getUncommittedFileNames() {
        String diffSt = diff();
        if (diffSt.isEmpty()) {
            return List.of();
        }

        // Simple parsing to extract filepaths from git diff output
        Set<String> filePaths = new HashSet<>();
        for (String line : diffSt.split("\n")) {
            line = line.trim();
            if (line.startsWith("diff --git")) {
                // Extract full path from diff --git a/path/to/file b/path/to/file
                String[] parts = line.split(" ");
                if (parts.length >= 4) {
                    String path = parts[3].substring(2); // skip "b/"
                    filePaths.add(path);
                }
            }
        }
        return new ArrayList<>(filePaths);
    }

    /**
     * Commit a specific list of files
     */
    /**
     * Commit a specific list of files
     * @return The commit ID of the new commit
     */
    public String commitFiles(List<String> filePatterns, String message) throws IOException {
        try {
            // Stage each file pattern
            for (String pattern : filePatterns) {
                git.add().addFilepattern(pattern).call();
            }

            // Commit the staged changes
            var commitResult = git.commit().setMessage(message).call();
            String commitId = commitResult.getId().getName();

            // Refresh the repository state
            refresh();
            
            return commitId;
        } catch (GitAPIException e) {
            throw new IOException("Failed to commit files: " + e.getMessage(), e);
        }
    }
    
    /**
     * Push the committed changes to the remote repository
     */
    public void push() throws IOException {
        try {
            git.push().call();
        } catch (GitAPIException e) {
            throw new IOException("Failed to push changes: " + e.getMessage(), e);
        }
    }
    
    /**
     * Get a set of commit IDs that exist in the local branch but not in its remote tracking branch
     * 
     * @param branchName Name of the local branch to check
     * @return Set of commit IDs that haven't been pushed
     * @throws IOException If there's a problem accessing the repository
     */
    public Set<String> getUnpushedCommitIds(String branchName) throws IOException {
        try {
            Set<String> unpushedCommits = new HashSet<>();
            
            // Get the tracking branch name
            String trackingBranch = getTrackingBranch(branchName);
            if (trackingBranch == null) {
                // No tracking branch, so all commits are considered unpushed
                // However, for consistency we'll return an empty set in this case
                return unpushedCommits;
            }
            
            // Get commits that are in local but not in remote
            var branchRef = "refs/heads/" + branchName;
            var trackingRef = "refs/remotes/" + trackingBranch;
            
            var localObjectId = repository.resolve(branchRef);
            var remoteObjectId = repository.resolve(trackingRef);
            
            if (localObjectId == null || remoteObjectId == null) {
                return unpushedCommits;
            }
            
            // Find commits that are in local but not in remote
            var revWalk = new RevWalk(repository);
            revWalk.markStart(revWalk.parseCommit(localObjectId));
            revWalk.markUninteresting(revWalk.parseCommit(remoteObjectId));
            
            revWalk.forEach(commit -> unpushedCommits.add(commit.getId().getName()));
            revWalk.dispose();
            
            return unpushedCommits;
        } catch (IOException e) {
            throw new IOException("Failed to get unpushed commits: " + e.getMessage(), e);
        }
    }
    
    /**
     * Check if a local branch has a configured upstream (tracking) branch
     * 
     * @param branchName Name of the local branch to check
     * @return true if the branch has an upstream, false otherwise
     */
    public boolean hasUpstreamBranch(String branchName) {
        return getTrackingBranch(branchName) != null;
    }
    
    /**
     * Get the tracking branch name for a local branch
     * 
     * @param branchName Name of the local branch
     * @return The full name of the tracking branch (e.g., "origin/main") or null if none
     */
    private String getTrackingBranch(String branchName) {
        try {
            var config = repository.getConfig();
            String trackingBranch = config.getString("branch", branchName, "remote");
            String remoteBranch = config.getString("branch", branchName, "merge");
            
            if (trackingBranch != null && remoteBranch != null) {
                // Convert from ref format to branch name
                if (remoteBranch.startsWith("refs/heads/")) {
                    remoteBranch = remoteBranch.substring("refs/heads/".length());
                }
                return trackingBranch + "/" + remoteBranch;
            }
            return null;
        } catch (Exception e) {
            // If anything goes wrong, assume no tracking branch
            return null;
        }
    }
    
    /**
     * List all local branches
     */
    public List<String> listLocalBranches() {
        try {
            var branches = new ArrayList<String>();
            for (var ref : git.branchList().call()) {
                branches.add(ref.getName().replaceFirst("^refs/heads/", ""));
            }
            return branches;
        } catch (GitAPIException e) {
            throw new UncheckedIOException(new IOException("Failed to list local branches", e));
        }
    }
    
    /**
     * List all remote branches
     */
    public List<String> listRemoteBranches() {
        try {
            var branches = new ArrayList<String>();
            for (var ref : git.branchList().setListMode(org.eclipse.jgit.api.ListBranchCommand.ListMode.REMOTE).call()) {
                branches.add(ref.getName().replaceFirst("^refs/remotes/", ""));
            }
            return branches;
        } catch (GitAPIException e) {
            throw new UncheckedIOException(new IOException("Failed to list remote branches", e));
        }
    }
    
    /**
     * Checkout a specific branch
     */
    public void checkout(String branchName) throws IOException {
        try {
            git.checkout().setName(branchName).call();
            refresh();
        } catch (GitAPIException e) {
            throw new IOException("Failed to checkout branch: " + e.getMessage(), e);
        }
    }
    
    /**
     * Checkout a remote branch, creating a local tracking branch
     * Similar to "git switch -c branch origin/branch"
     */
    public void checkoutRemoteBranch(String remoteBranchName) throws IOException {
        try {
            // Extract the branch name without remote prefix
            String branchName;
            if (remoteBranchName.contains("/")) {
                branchName = remoteBranchName.substring(remoteBranchName.indexOf('/') + 1);
            } else {
                branchName = remoteBranchName;
            }
            
            // Check if local branch with same name already exists
            if (listLocalBranches().contains(branchName)) {
                throw new IOException("Local branch '" + branchName + "' already exists. Choose a different name.");
            }
            
            // Create and checkout the branch, setting up tracking
            git.checkout()
                .setCreateBranch(true)
                .setName(branchName)
                .setStartPoint(remoteBranchName)
                .setUpstreamMode(org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode.TRACK)
                .call();
            
            refresh();
        } catch (GitAPIException e) {
            throw new IOException("Failed to checkout remote branch: " + e.getMessage(), e);
        }
    }
    
    /**
     * Rename a branch
     */
    public void renameBranch(String oldName, String newName) throws IOException {
        try {
            git.branchRename().setOldName(oldName).setNewName(newName).call();
        } catch (GitAPIException e) {
            throw new IOException("Failed to rename branch: " + e.getMessage(), e);
        }
    }
    
    /**
     * Check if a branch is fully merged into HEAD
     */
    public boolean isBranchMerged(String branchName) throws IOException {
        try {
            // Check if the branch is merged using the branchList command with merged option
            List<org.eclipse.jgit.lib.Ref> mergedBranches = git.branchList()
                .setListMode(org.eclipse.jgit.api.ListBranchCommand.ListMode.ALL)
                .setContains("HEAD")
                .call();
            
            for (org.eclipse.jgit.lib.Ref ref : mergedBranches) {
                String name = ref.getName().replaceFirst("^refs/heads/", "");
                if (name.equals(branchName)) {
                    return true;
                }
            }
            return false;
        } catch (GitAPIException e) {
            throw new IOException("Failed to check if branch is merged: " + e.getMessage(), e);
        }
    }

    /**
     * Delete a branch
     */
    public void deleteBranch(String branchName) throws IOException {
        try {
            git.branchDelete().setBranchNames(branchName).call();
        } catch (GitAPIException e) {
            throw new IOException("Failed to delete branch: " + e.getMessage(), e);
        }
    }
    
    /**
     * Force delete a branch even if it's not fully merged
     */
    public void forceDeleteBranch(String branchName) throws IOException {
        try {
            git.branchDelete().setBranchNames(branchName).setForce(true).call();
        } catch (GitAPIException e) {
            throw new IOException("Failed to force delete branch: " + e.getMessage(), e);
        }
    }
    
    /**
     * Merge a branch into HEAD
     */
    public void mergeIntoHead(String branchName) throws IOException {
        try {
            git.merge().include(repository.resolve(branchName)).call();
            refresh();
        } catch (GitAPIException e) {
            throw new IOException("Failed to merge branch: " + e.getMessage(), e);
        }
    }
    
    /**
     * Revert a specific commit
     */
    public void revertCommit(String commitId) throws IOException {
        try {
            git.revert().include(repository.resolve(commitId)).call();
            refresh();
        } catch (GitAPIException e) {
            throw new IOException("Failed to revert commit: " + e.getMessage(), e);
        }
    }
    
    /**
     * List commits
     * @return List of commit information (simplified for now)
     */
    public List<String> listCommits() {
        try {
            var commits = new ArrayList<String>();
            for (var commit : git.log().call()) {
                commits.add(commit.getName() + ": " + commit.getShortMessage());
            }
            return commits;
        } catch (GitAPIException e) {
            throw new UncheckedIOException(new IOException("Failed to list commits", e));
        }
    }
    
    /**
     * Get current branch name
     * @return The current branch name
     */
    public String getCurrentBranch() {
        try {
            return repository.getBranch();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to get current branch", e);
        }
    }
    
    /**
     * A record to hold commit details
     */
    public record CommitInfo(String id, String message, String author, String date) {}
    
    /**
     * List commits with detailed information for a specific branch
     * @param branchName The branch to list commits for
     * @return List of detailed commit information
     */
    public List<CommitInfo> listCommitsDetailed(String branchName) {
        try {
            var commits = new ArrayList<CommitInfo>();
            var logCommand = git.log();
            
            // Set the branch if specified
            if (branchName != null && !branchName.isEmpty()) {
                logCommand.add(repository.resolve(branchName));
            }
            
            for (var commit : logCommand.call()) {
                String id = commit.getName();
                String message = commit.getShortMessage();
                String author = commit.getAuthorIdent().getName();
                String date = commit.getAuthorIdent().getWhen().toString();
                
                commits.add(new CommitInfo(id, message, author, date));
            }
            return commits;
        } catch (GitAPIException | IOException e) {
            throw new UncheckedIOException(new IOException("Failed to list commits", e));
        }
    }
    
    /**
     * List changed files in a specific commit
     */
    public List<String> listChangedFilesInCommit(String commitId) {
        try {
            var commitObj = repository.resolve(commitId);
            try (var revWalk = new RevWalk(repository)) {
                var commit = revWalk.parseCommit(commitObj);
                var parentCommit = commit.getParentCount() > 0 ? commit.getParent(0) : null;

                if (parentCommit == null) {
                    // For the first commit, list all files
                    try (var treeWalk = new TreeWalk(repository)) {
                        treeWalk.addTree(commit.getTree());
                        treeWalk.setRecursive(true);

                        var files = new ArrayList<String>();
                        while (treeWalk.next()) {
                            files.add(treeWalk.getPathString());
                        }
                        return files;
                    }
                } else {
                    // For subsequent commits, show diff with parent
                    parentCommit = revWalk.parseCommit(parentCommit.getId());
                    try (var diffFormatter = new org.eclipse.jgit.diff.DiffFormatter(new ByteArrayOutputStream())) {
                        diffFormatter.setRepository(repository);
                        var files = new ArrayList<String>();
                        
                        // Get the differences between this commit and its parent
                        var diffs = diffFormatter.scan(parentCommit.getTree(), commit.getTree());
                        for (var diff : diffs) {
                            if (diff.getNewPath() != null && !diff.getNewPath().equals("/dev/null")) {
                                files.add(diff.getNewPath());
                            }
                            if (diff.getOldPath() != null && !diff.getOldPath().equals("/dev/null") && 
                                !files.contains(diff.getOldPath())) {
                                files.add(diff.getOldPath());
                            }
                        }
                        return files;
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list changed files in commit", e);
        }
    }
    
    /**
     * Show diff between two commits
     * Special handling for when HEAD is used as a reference in the working directory
     */
    public String showDiff(String commitIdA, String commitIdB) {
        try (var out = new ByteArrayOutputStream()) {
            // Special case when comparing with HEAD (working directory)
            if ("HEAD".equals(commitIdA)) {
                // Get the diff from commitB to current working tree
                git.diff()
                    .setOldTree(prepareTreeParser(commitIdB))
                    .setOutputStream(out)
                    .call();
            } else {
                // Normal diff between two commits
                git.diff()
                    .setOldTree(prepareTreeParser(commitIdA))
                    .setNewTree(prepareTreeParser(commitIdB))
                    .setOutputStream(out)
                    .call();
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException | GitAPIException e) {
            throw new UncheckedIOException(new IOException("Failed to show diff", e));
        }
    }
    
    /**
     * Show diff for a specific file between two commits
     * Special handling for when HEAD is used as a reference in the working directory
     */
    public String showFileDiff(String commitIdA, String commitIdB, String filePath) {
        try (var out = new ByteArrayOutputStream()) {
            // Special case when comparing with HEAD (working directory)
            if ("HEAD".equals(commitIdA)) {
                // Get the diff from commitB to current working tree
                git.diff()
                    .setOldTree(prepareTreeParser(commitIdB))
                    .setPathFilter(PathFilter.create(filePath))
                    .setOutputStream(out)
                    .call();
            } else {
                // Normal diff between two commits
                git.diff()
                    .setOldTree(prepareTreeParser(commitIdA))
                    .setNewTree(prepareTreeParser(commitIdB))
                    .setPathFilter(PathFilter.create(filePath))
                    .setOutputStream(out)
                    .call();
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException | GitAPIException e) {
            throw new UncheckedIOException(new IOException("Failed to show file diff", e));
        }
    }
    
    /**
     * Helper method to prepare a tree parser for diff operations
     */
    private org.eclipse.jgit.treewalk.AbstractTreeIterator prepareTreeParser(String objectId) throws IOException {
        var objId = repository.resolve(objectId);
        try (var revWalk = new RevWalk(repository)) {
            var commit = revWalk.parseCommit(objId);
            var treeId = commit.getTree().getId();
            
            try (var reader = repository.newObjectReader()) {
                return new org.eclipse.jgit.treewalk.CanonicalTreeParser(null, reader, treeId);
            }
        }
    }

    /**
     * Create a stash from the current changes
     * @param message Optional message to describe the stash (can be null)
     * @throws IOException If there's an error creating the stash
     */
    public void createStash(String message) throws IOException {
        try {
            git.stashCreate()
               .setIncludeUntracked(true)
               .setWorkingDirectoryMessage(message != null ? message : "Stash created via Brokk")
               .call();
            refresh();
        } catch (GitAPIException e) {
            throw new IOException("Failed to create stash: " + e.getMessage(), e);
        }
    }

    /**
     * Lists all stashes in the repository
     * @return List of stash entries with their info
     * @throws IOException If there's an error accessing stashes
     */
    public List<StashInfo> listStashes() throws IOException {
        try {
            var stashes = new ArrayList<StashInfo>();
            var collection = git.stashList().call();
            int index = 0;
            
            for (var stash : collection) {
                String id = stash.getName();
                String message = stash.getShortMessage();
                if (message.startsWith("WIP on ")) {
                    message = message.substring(7); // Remove "WIP on " prefix
                } else if (message.startsWith("On ")) {
                    int colonPos = message.indexOf(':', 3);
                    if (colonPos > 3) {
                        message = message.substring(colonPos + 2);
                    }
                }
                String author = stash.getAuthorIdent().getName();
                String date = stash.getAuthorIdent().getWhen().toString();
                
                stashes.add(new StashInfo(id, message, author, date, index));
                index++;
            }
            return stashes;
        } catch (GitAPIException e) {
            throw new IOException("Failed to list stashes: " + e.getMessage(), e);
        }
    }
    
    /**
     * Apply a stash to the working directory without removing it from the stash list
     * 
     * @param stashIndex The index of the stash to apply (0 is the most recent)
     * @throws IOException If there's an error applying the stash
     */
    public void applyStash(int stashIndex) throws IOException {
        try {
            git.stashApply()
               .setStashRef("stash@{" + stashIndex + "}")
               .call();
            refresh();
        } catch (GitAPIException e) {
            throw new IOException("Failed to apply stash: " + e.getMessage(), e);
        }
    }
    
    /**
     * Pop a stash - apply it to the working directory and remove it from the stash list
     * 
     * @param stashIndex The index of the stash to pop (0 is the most recent)
     * @throws IOException If there's an error popping the stash
     */
    public void popStash(int stashIndex) throws IOException {
        try {
            git.stashApply()
               .setStashRef("stash@{" + stashIndex + "}")
               .call();
            
            git.stashDrop()
               .setStashRef(stashIndex)
               .call();
               
            refresh();
        } catch (GitAPIException e) {
            throw new IOException("Failed to pop stash: " + e.getMessage(), e);
        }
    }
    
    /**
     * Drop a stash without applying it
     * 
     * @param stashIndex The index of the stash to drop (0 is the most recent)
     * @throws IOException If there's an error dropping the stash
     */
    public void dropStash(int stashIndex) throws IOException {
        try {
            git.stashDrop()
               .setStashRef(stashIndex)
               .call();
            refresh();
        } catch (GitAPIException e) {
            throw new IOException("Failed to drop stash: " + e.getMessage(), e);
        }
    }
    
    /**
     * A record to hold stash details
     */
    public record StashInfo(String id, String message, String author, String date, int index) {}

    /**
     * Search commits
     * @param query Text to search for in commit messages, author names, and email addresses
     * @return List of detailed commit information matching the search
     */
    public List<CommitInfo> searchCommits(String query) {
        try {
            var commits = new ArrayList<CommitInfo>();

            for (var commit : git.log().call()) {
                // Search in commit message, author name and email
                if (commit.getFullMessage().toLowerCase().contains(query.toLowerCase()) ||
                    commit.getAuthorIdent().getName().toLowerCase().contains(query.toLowerCase()) ||
                    commit.getAuthorIdent().getEmailAddress().toLowerCase().contains(query.toLowerCase())) {
                    
                    String id = commit.getName();
                    String message = commit.getShortMessage();
                    String author = commit.getAuthorIdent().getName();
                    String date = commit.getAuthorIdent().getWhen().toString();
                    
                    commits.add(new CommitInfo(id, message, author, date));
                }
            }
            return commits;
        } catch (GitAPIException e) {
            throw new UncheckedIOException(new IOException("Failed to search commits", e));
        }
    }

    @Override
    public void close() {
        git.close();
        repository.close();
    }
}
