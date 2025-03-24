package io.github.jbellis.brokk;

import io.github.jbellis.brokk.analyzer.IAnalyzer;

public interface IProject {
    IAnalyzer getAnalyzer();

    IGitRepo getRepo();
}
