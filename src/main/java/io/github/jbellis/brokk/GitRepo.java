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

public class GitRepo implements Closeable {

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

    @Override
    public void close() {
        git.close();
        repository.close();
    }
}
