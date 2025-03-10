package io.github.jbellis.brokk;

public interface IProject {
    Analyzer getAnalyzer();

    Analyzer getAnalyzerNonBlocking();

    GitRepo getRepo();
}
