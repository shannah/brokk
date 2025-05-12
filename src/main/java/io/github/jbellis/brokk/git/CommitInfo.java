package io.github.jbellis.brokk.git;

import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * A record to hold commit details
 */
public final class CommitInfo implements ICommitInfo {
    private final GitRepo repo;
    private final String id;
    private final String message;
    private final String author;
    private final Date date;

    /**
     *
     */
    public CommitInfo(GitRepo repo, String id, String message, String author, Date date) {
        this.repo = repo;
        this.id = id;
        this.message = message;
        this.author = author;
        this.date = date;
    }

    /**
     * Lists files changed in this commit compared to its primary parent.
     * For an initial commit, lists all files in that commit.
     * This method fetches data on demand.
     *
     * @return A list of relative file paths changed in this commit.
     * @throws GitAPIException if there's an error accessing Git data.
     */
    @Override
    public List<ProjectFile> changedFiles() throws GitAPIException {
        return repo.listFilesChangedInCommit(this.id);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String message() {
        return message;
    }

    @Override
    public String author() {
        return author;
    }

    @Override
    public Date date() {
        return date;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (CommitInfo) obj;
        return Objects.equals(this.id, that.id) &&
                Objects.equals(this.message, that.message) &&
                Objects.equals(this.author, that.author) &&
                Objects.equals(this.date, that.date);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, message, author, date);
    }

    @Override
    public String toString() {
        return "CommitInfo[" +
                "id=" + id + ", " +
                "message=" + message + ", " +
                "author=" + author + ", " +
                "date=" + date + ']';
    }
}
