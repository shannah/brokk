package io.github.jbellis.brokk.git;

import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.util.Date;
import java.util.List;

public interface ICommitInfo {
    List<ProjectFile> changedFiles() throws GitAPIException;

    String id();

    String message();

    String author();

    Date date();

    class CommitInfoStub implements ICommitInfo {
        private final String message;

        public CommitInfoStub(String message) {
            this.message = message;
        }

        @Override
        public List<ProjectFile> changedFiles() {
            return List.of();
        }

        @Override
        public String id() {
            return "";
        }

        @Override
        public String message() {
            return message;
        }

        @Override
        public String author() {
            return "";
        }

        @Override
        public Date date() {
            return null;
        }
    }
}
