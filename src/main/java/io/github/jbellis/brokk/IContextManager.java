package io.github.jbellis.brokk;

import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import io.github.jbellis.brokk.analyzer.BrokkFile;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.IGitRepo;
import io.github.jbellis.brokk.tools.ToolRegistry;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * Interface for context manager functionality
 */
public interface IContextManager {

    /**
     * Listener interface for context change events.
     */
    interface ContextListener {
        /**
         * Called when the context has changed.
         *
         * @param newCtx The new context state.
         * @param source The object that initiated the context change. Can be null.
         */
        void contextChanged(Context newCtx);
    }

    /**
     * Adds a listener that will be notified when the context changes.
     *
     * @param listener The listener to add. Must not be null.
     */
    default void addContextListener(ContextListener listener) {
        Objects.requireNonNull(listener);
        // Default implementation does nothing
    }

    default ProjectFile toFile(String relName) {
        throw new UnsupportedOperationException();
    }

    default Set<ProjectFile> getEditableFiles() {
        throw new UnsupportedOperationException();
    }

    default Set<BrokkFile> getReadonlyFiles() {
        throw new UnsupportedOperationException();
    }

    default <T> Future<T> submitBackgroundTask(String taskDescription, Callable<T> task) {
        try {
            return CompletableFuture.completedFuture(task.call());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    default List<ProjectFile> getTestFiles() {
        throw new UnsupportedOperationException();
    }

    default void replaceContext(Context newContext, Context replacement) {
        // no-op
    }

    default AnalyzerWrapper getAnalyzerWrapper() {
        throw new UnsupportedOperationException();
    }

    default IAnalyzer getAnalyzer() throws InterruptedException {
        throw new UnsupportedOperationException();
    }

    default IAnalyzer getAnalyzerUninterrupted() {
        throw new UnsupportedOperationException();
    }

    default void requestRebuild() {}

    default IGitRepo getRepo() {
        return getProject().getRepo();
    }

    default Models getModels() {
        throw new UnsupportedOperationException();
    }

    default void editFiles(Collection<ProjectFile> path) {
        throw new UnsupportedOperationException();
    }

    default IProject getProject() {
        return new IProject() {};
    }

    default IConsoleIO getIo() {
        throw new UnsupportedOperationException();
    }

    default ToolRegistry getToolRegistry() {
        throw new UnsupportedOperationException();
    }

    default Llm getLlm(StreamingChatLanguageModel model, String taskDescription) {
        return new Llm(model, taskDescription, this, getProject().getDataRetentionPolicy() == Project.DataRetentionPolicy.IMPROVE_BROKK);
    }
}
