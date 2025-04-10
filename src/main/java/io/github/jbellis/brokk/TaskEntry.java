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
 * @param compressedLog The compressed representation of the chat messages. Null if uncompressed.
 */
public record TaskEntry(int sequence, String description, List<ChatMessage> log, String compressedLog) {
    private static final System.Logger logger = System.getLogger(TaskEntry.class.getName());

    /** Enforce that exactly one of log or compressedLog is non-null */
    public TaskEntry {
        assert description != null && !description.isBlank();
        assert (log == null) != (compressedLog == null) : "Exactly one of log or compressedLog must be non-null";
        assert log == null || !log.isEmpty();
        assert compressedLog == null || !compressedLog.isEmpty();
    }

    /**
     * Creates a TaskHistory instance from a list of ChatMessages representing a session.
     * Assumes the first message is a UserMessage and extracts its text as the job.
     * The TaskHistory always starts uncompressed.
     *
     * @param sequence The sequence number for this task.
     * @param sessionMessages The list of messages from the session. Must not be empty and the first message must be a UserMessage.
     * @return A new TaskHistory instance.
     */
    public static TaskEntry fromSession(int sequence, List<ChatMessage> sessionMessages) {
        assert sessionMessages != null && !sessionMessages.isEmpty();

        ChatMessage firstMessage = sessionMessages.getFirst();
        assert firstMessage instanceof UserMessage : firstMessage;
        String job = Models.getText(firstMessage);
        return new TaskEntry(sequence, job, sessionMessages.subList(1, sessionMessages.size()), null);
    }

    // TODO: Implement compress() method
    // public TaskHistory compress() { ... }

    /**
     * Provides a string representation suitable for logging or context display.
     *
     * @return A formatted string representing the task.
     */
    @Override
    public String toString() {
        String logText;
        if (log == null) {
            logText = "  <summary>%s</message>".formatted(this.compressedLog);
        }
        else {
            logText = log.stream()
                    .map(message -> {
                        var text = Models.getText(message);
                        return """
                        <message type=%s>
                        %s
                        </message>
                        """.formatted(message.type().name().toLowerCase(), text.indent(2).stripTrailing()).stripIndent();
                    })
                    .collect(Collectors.joining("\n  "));
        }
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
