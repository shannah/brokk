package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.analyzer.RepoFile;
import io.github.jbellis.brokk.analyzer.CodeUnit;

/**
 * Represents a file reference with metadata for context menu usage.
 */
public class FileReferenceData {
    private final String fileName;
    private final String fullPath;
    private final RepoFile repoFile; // Optional, if available
    private final CodeUnit codeUnit; // Optional, if available

    public FileReferenceData(String fileName, String fullPath, RepoFile repoFile, CodeUnit codeUnit) {
        this.fileName = fileName;
        this.fullPath = fullPath;
        this.repoFile = repoFile;
        this.codeUnit = codeUnit;
    }

    // Getters
    public String getFileName() { return fileName; }
    public String getFullPath() { return fullPath; }
    public RepoFile getRepoFile() { return repoFile; }
    public CodeUnit getCodeUnit() { return codeUnit; }

    @Override
    public String toString() {
        return fileName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FileReferenceData that = (FileReferenceData) o;
        return fullPath.equals(that.fullPath);
    }

    @Override
    public int hashCode() {
        return fullPath.hashCode();
    }
}
