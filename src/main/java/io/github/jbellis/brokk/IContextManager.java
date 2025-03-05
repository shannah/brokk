package io.github.jbellis.brokk;

import dev.langchain4j.data.message.ChatMessage;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface IContextManager {
    RepoFile toFile(String relName);

    Set<RepoFile> getEditableFiles();

    default void addToHistory(List<ChatMessage> messages, Map<RepoFile, String> originalContents) {
    }

    default void addFiles(Collection<RepoFile> path) {
        throw new UnsupportedOperationException();
    }

    default Set<RepoFile> findMissingFileMentions(String text) {
        return Set.of();
    }

    default Project getProject() {
        return null;
    }
}
