package io.github.jbellis.brokk;

import io.github.jbellis.brokk.analyzer.DisabledAnalyzer;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.IGitRepo;

import java.nio.file.Path;
import java.util.Set;

public interface IProject {
    default IAnalyzer getAnalyzer() {
        return new DisabledAnalyzer();
    }

    default IGitRepo getRepo() {
        return Set::of;
    }

    default Path getRoot() {
        return null;
    }

    default Set<ProjectFile> getFiles() {
        return Set.of();
    }

    /**
     * Gets the structured build details inferred by the BuildAgent.
     * @return BuildDetails record, potentially BuildDetails.EMPTY if not found or on error.
     */
    default BuildAgent.BuildDetails getBuildDetails() {
        return BuildAgent.BuildDetails.EMPTY; // Default implementation returns empty
    }
}
