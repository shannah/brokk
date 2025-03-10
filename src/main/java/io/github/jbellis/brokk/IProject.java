package io.github.jbellis.brokk;

public interface IProject {
    IAnalyzer getAnalyzer();

    IAnalyzer getAnalyzerNonBlocking();

    IGitRepo getRepo();
}
