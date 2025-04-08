package io.github.jbellis.brokk;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import java.util.Objects;

/**
 * Represents the result of executing a tool, providing more context than
 * the standard ToolExecutionResultMessage.
 * It includes the execution status, a classification of the action type,
 * the textual result (or error message), and the original request.
 */
public record ToolExecutionResult(
        ToolExecutionRequest request,
        Status status,
        ActionType actionType,
        String resultText // Contains the primary output on SUCCESS, or error message on FAILURE.
) {
    /**
     * Overall status of the tool execution.
     */
    public enum Status {
        /** The tool executed successfully and produced its intended outcome. */
        SUCCESS,
        /** The tool failed to execute due to an error. */
        FAILURE
    }

    /**
     * General classification of the action performed by the tool.
     * Helps agents understand the *kind* of operation that occurred.
     */
    public enum ActionType {
        /** Read-only operations (e.g., searchSymbols, getSources, readFile). */
        QUERY,
        /** Modifying existing resources (e.g., replaceLines, replaceFunction). */
        MODIFY,
        /** Creating new resources (e.g., replaceFile for a new file). */
        CREATE,
        /** Removing resources (e.g., removeFile). */
        DELETE,
        /** Agent control/explanation (e.g., explain, answer, abort). */
        CONTROL,
        /** Other action types not covered above. */
        OTHER
    }

    // --- Constructor ---

    /**
     * Primary constructor. Ensures non-null fields.
     */
    public ToolExecutionResult {
        Objects.requireNonNull(request, "request cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        Objects.requireNonNull(actionType, "actionType cannot be null");
        Objects.requireNonNull(resultText, "resultText cannot be null"); // Store error message here on failure
    }

    // --- Factory Methods ---

    /**
     * Creates a success result.
     *
     * @param request The original request.
     * @param actionType The type of action performed.
     * @param resultText The textual result for the LLM (can be null, will default to "Success").
     * @return A new ToolExecutionResult instance.
     */
    public static ToolExecutionResult success(ToolExecutionRequest request, ActionType actionType, String resultText) {
        String finalText = (resultText == null || resultText.isBlank()) ? "Success" : resultText;
        return new ToolExecutionResult(request, Status.SUCCESS, actionType, finalText);
    }

    /**
     * Creates a failure result.
     *
     * @param request The original request.
     * @param actionType The type of action attempted.
     * @param errorMessage The error message describing the failure.
     * @return A new ToolExecutionResult instance.
     */
    public static ToolExecutionResult failure(ToolExecutionRequest request, ActionType actionType, String errorMessage) {
        String finalError = (errorMessage == null || errorMessage.isBlank()) ? "Unknown error" : errorMessage;
        // Store the error message in the resultText field for simplicity
        return new ToolExecutionResult(request, Status.FAILURE, actionType, finalError);
    }

    // --- Convenience Accessors ---

    public String toolName() {
        return request.name();
    }

    public String toolId() {
        return request.id();
    }

    public String arguments() {
        return request.arguments();
    }

    // --- Conversion ---

    /**
     * Converts this extended result into the standard LangChain4j
     * ToolExecutionResultMessage suitable for sending back to the LLM.
     *
     * @return A ToolExecutionResultMessage.
     */
    public ToolExecutionResultMessage toExecutionResultMessage() {
        String text;
        if (status == Status.SUCCESS) {
            text = resultText; // Already handled null/blank in factory
        } else {
            // For failure, the resultText field holds the error message (set by the factory)
            text = "Error: " + resultText;
        }
        // Use the ID from the original request if available, otherwise default? LC4J seems okay with null ID here.
        return new ToolExecutionResultMessage(toolId(), toolName(), text);
    }
}
