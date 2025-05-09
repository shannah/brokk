package io.github.jbellis.brokk.util;

import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import io.github.jbellis.brokk.Models;
import io.github.jbellis.brokk.Project;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class ModelsWrapper {
    private volatile CompletableFuture<Models> future;

    public void reinit(Project project) {
        future = CompletableFuture.supplyAsync(() -> new Models(project));
    }

    public Models get() {
        try {
            return future.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    public StreamingChatLanguageModel get(String modelName, Project.ReasoningLevel reasoning) {
        return get().get(modelName, reasoning);
    }

    public StreamingChatLanguageModel quickModel() {
        return get().quickModel();
    }

    public String nameOf(StreamingChatLanguageModel model) {
        return get().nameOf(model);
    }

    public StreamingChatLanguageModel quickestModel() {
        return get().quickestModel();
    }
}
