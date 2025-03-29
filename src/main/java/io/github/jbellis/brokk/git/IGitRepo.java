package io.github.jbellis.brokk.git;

import io.github.jbellis.brokk.analyzer.RepoFile;
import org.eclipse.jgit.lib.ObjectId;

import java.util.Set;

public interface IGitRepo {
    Set<RepoFile> getTrackedFiles();

    default String diff() {
        return "";
    }

    default void refresh() {
    }

    default ObjectId resolve(String s) {
        throw new UnsupportedOperationException();
    }
}
