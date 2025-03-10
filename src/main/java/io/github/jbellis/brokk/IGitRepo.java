package io.github.jbellis.brokk;

import java.nio.file.Path;
import java.util.List;

public interface IGitRepo {
    Path getRoot();

    List<RepoFile> getTrackedFiles();
}
