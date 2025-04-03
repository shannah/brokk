package io.github.jbellis.brokk.git;

import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.eclipse.jgit.lib.ObjectId;

import java.io.IOException;
import java.util.List;
import java.util.Set;

public interface IGitRepo {
    Set<ProjectFile> getTrackedFiles();

    default String diff() {
        return "";
    }

    default void refresh() {
    }

    default ObjectId resolve(String s) {
        throw new UnsupportedOperationException();
    }

    default void add(List<ProjectFile> files) throws IOException {
        throw new UnsupportedOperationException();
    }
}
