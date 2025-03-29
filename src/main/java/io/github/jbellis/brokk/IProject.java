package io.github.jbellis.brokk;

import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.RepoFile;
import io.github.jbellis.brokk.git.IGitRepo;

import java.nio.file.Path;
import java.util.Set;

public interface IProject {
    IAnalyzer getAnalyzer();

    IGitRepo getRepo();

    Path getRoot();

    Set<RepoFile> getFiles();
}
