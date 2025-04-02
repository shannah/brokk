package io.github.jbellis.brokk;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static java.lang.Math.min;
import static org.junit.jupiter.api.Assertions.*;

public class CoderTest {

    // Dummy ConsoleIO for testing purposes
    static class NoOpConsoleIO implements IConsoleIO {
        @Override public void actionOutput(String msg) {}
        @Override public void toolError(String msg) {}
        @Override public void toolErrorRaw(String msg) {
            System.out.println("Tool error: " + msg);
        }
        @Override public void llmOutput(String token) {}
        @Override public void systemOutput(String message) {}
    }

    private static Coder coder;

    @TempDir
    static Path tempDir; // JUnit Jupiter provides a temporary directory

    @BeforeAll
    static void setUp() {
        // Initialize models once for all tests in this class
        // This relies on Models.init() finding models via LiteLLM
        Models.init();

        // Create a Coder instance using the temp directory and dummy IO/ContextManager
        var consoleIO = new NoOpConsoleIO();
        var contextManager = new IContextManager() {};
        coder = new Coder(consoleIO, tempDir, contextManager);
    }

    // uncomment when you need it, this makes live API calls
//    @Test
    void testModels() {
        var availableModels = Models.getAvailableModels();
        Assumptions.assumeFalse(availableModels.isEmpty(), "No models available via LiteLLM, skipping testModels test.");

        var messages = List.<ChatMessage>of(new UserMessage("hello world"));
        Map<String, Throwable> failures = new ConcurrentHashMap<>();

        availableModels.keySet().parallelStream().forEach(modelName -> {
            try {
                System.out.println("Testing model: " + modelName);
                StreamingChatLanguageModel model = Models.get(modelName);
                assertNotNull(model, "Failed to get model instance for: " + modelName);

                // Use the non-streaming sendMessage variant for simplicity in testing basic connectivity
                // Note: This uses the internal retry logic of Coder.sendMessage
                var result = coder.sendMessage(model, messages);

                assertNotNull(result, "Result should not be null for model: " + modelName);
                assertFalse(result.cancelled(), "Request should not be cancelled for model: " + modelName);
                if (result.error() != null) {
                    // Capture the error directly instead of asserting null
                    throw new AssertionError("Request resulted in an error for model: " + modelName, result.error());
                }

                var chatResponse = result.chatResponse();
                assertNotNull(chatResponse, "ChatResponse should not be null for model: " + modelName);
                assertNotNull(chatResponse.aiMessage(), "AI message should not be null for model: " + modelName);
                assertNotNull(chatResponse.aiMessage().text(), "AI message text should not be null for model: " + modelName);
                assertFalse(chatResponse.aiMessage().text().isBlank(), "AI message text should not be blank for model: " + modelName);

                var firstLine = chatResponse.aiMessage().text().lines().findFirst().orElse("");
                System.out.println("Response from " + modelName + ": " + firstLine.substring(0, min(firstLine.length(), 50)) + "...");
            } catch (Throwable t) {
                // Catch assertion errors or any other exceptions during the test for this model
                failures.put(modelName, t);
                System.err.println("Failure testing model " + modelName + ": " + t.getMessage());
            }
        });

        if (!failures.isEmpty()) {
            String failureSummary = failures.entrySet().stream()
                .map(entry -> "Model '" + entry.getKey() + "' failed: " + entry.getValue().getMessage() +
                              (entry.getValue().getCause() != null ? " (Cause: " + entry.getValue().getCause().getMessage() + ")" : ""))
                .collect(Collectors.joining("\n"));
            fail("One or more models failed the test:\n" + failureSummary);
        }
    }
}
