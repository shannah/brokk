package io.github.jbellis.brokk;

import dev.langchain4j.data.message.ChatMessage;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;

public interface IContextManager {
    RepoFile toFile(String relName);

    Set<RepoFile> getEditableFiles();

    default void addToHistory(List<ChatMessage> messages, Map<RepoFile, String> originalContents, String action) {
    }

    default void addToHistory(List<ChatMessage> messages, Map<RepoFile, String> originalContents, Future<String> action) {
    }

    default void editFiles(Collection<RepoFile> path) {
        throw new UnsupportedOperationException();
    }

    default Project getProject() {
        return null;
    }

    default void addToGit(String string) throws IOException {}
}
