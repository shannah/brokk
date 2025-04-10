package io.github.jbellis.brokk;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents a single task interaction, including the user request ("job") and the full conversation log.
 * The log can be compressed for storage.
 *
 * @param sequence      A unique sequence number for ordering tasks.
 * @param description   A short description of the user's request for this task.
 * @param log           The uncompressed list of chat messages for this task. Null if compressed.
 * @param summary The compressed representation of the chat messages (summary). Null if uncompressed.
 */
public record TaskEntry(int sequence, String description, List<ChatMessage> log, String summary) {
    private static final System.Logger logger = System.getLogger(TaskEntry.class.getName());

    /** Enforce that exactly one of log or compressedLog is non-null */
    public TaskEntry {
        assert (description == null) == (log == null);
        assert (log == null) != (summary == null) : "Exactly one of log or summary must be non-null";
        assert log == null || !log.isEmpty();
        assert summary == null || !summary.isEmpty();
    }

    /**
     * Creates a TaskHistory instance from a list of ChatMessages representing a session.
     * Creates a TaskEntry instance from a list of ChatMessages representing a full session interaction.
     * The first message *must* be a UserMessage, its content is stored as the `description`.
     * The remaining messages (AI responses, tool calls/results) are stored in the `log`.
     * The TaskEntry starts uncompressed.
     *
     * @param sequence The sequence number for this task.
     * @param sessionMessages The full list of messages from the session, starting with the user's request.
     * @return A new TaskEntry instance.
     */
    public static TaskEntry fromSession(int sequence, List<ChatMessage> sessionMessages) {
        assert sessionMessages != null && !sessionMessages.isEmpty();

        ChatMessage firstMessage = sessionMessages.getFirst();
        assert firstMessage instanceof UserMessage : firstMessage;
        String job = Models.getText(firstMessage);
        return new TaskEntry(sequence, job, sessionMessages.subList(1, sessionMessages.size()), null);
    }

    public static TaskEntry fromCompressed(int sequence, String description, String compressedLog) {
        return new TaskEntry(sequence, description, null, compressedLog);
    }

    /**
     * Returns true if this TaskEntry holds a compressed summary instead of the full message log.
     * @return true if compressed, false otherwise.
     */
    public boolean isCompressed() {
        return summary != null;
    }

    /**
     * Provides a string representation suitable for logging or context display.
     */
    @Override
    public String toString() {
        if (isCompressed()) {
            return """
                <task sequence=%s summarized=true>
                %s
                </task>
                """.formatted(sequence, summary.indent(2).stripTrailing()).stripIndent();
        }

        var logText = log.stream()
                .map(message -> {
                    var text = Models.getText(message);
                    return """
                    <message type=%s>
                    %s
                    </message>
                    """.formatted(message.type().name().toLowerCase(), text.indent(2).stripTrailing()).stripIndent();
                })
                .collect(Collectors.joining("\n"));
        return """
        <task sequence=%s>
        %s
        %s
        </task>
        """.formatted(sequence,
                      description.indent(2).stripTrailing(),
                      logText.indent(2).stripTrailing())
                .stripIndent();
    }
}
