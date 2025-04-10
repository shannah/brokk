package io.github.jbellis.brokk;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.UserMessage;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents a single task interaction, including the user request ("job") and the full conversation log.
 * The log can be compressed for storage.
 * This record is serializable, using langchain4j's JSON serialization for ChatMessage lists.
 *
 * @param sequence      A unique sequence number for ordering tasks.
 * @param description   A short description of the user's request for this task.
 * @param log           The uncompressed list of chat messages for this task. Null if compressed.
 * @param summary The compressed representation of the chat messages (summary). Null if uncompressed.
 */
public record TaskEntry(int sequence, String description, List<ChatMessage> log, String summary) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Initial version
    private static final System.Logger logger = System.getLogger(TaskEntry.class.getName());

    /** Enforce that exactly one of log or summary is non-null */
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

    public static TaskEntry fromCompressed(int sequence, String compressedLog) {
        return new TaskEntry(sequence, null, null, compressedLog);
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

    // --- Custom Serialization using Proxy Pattern ---

    /**
     * Replace this TaskEntry instance with a SerializationProxy during serialization.
     * This allows us to convert the non-serializable ChatMessage list to JSON.
     */
    @Serial
    private Object writeReplace() {
        return new SerializationProxy(this);
    }

    /**
     * Prevent direct deserialization of TaskEntry; must go through the proxy.
     */
    @Serial
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        throw new java.io.NotSerializableException("TaskEntry must be serialized via SerializationProxy");
    }

    /**
     * A helper class to handle the serialization and deserialization of TaskEntry.
     * It stores the ChatMessage list as a JSON string.
     */
    private static class SerializationProxy implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        private final int sequence;
        private final String description;
        private final String serializedLog; // Store log as JSON string
        private final String summary;

        SerializationProxy(TaskEntry taskEntry) {
            this.sequence = taskEntry.sequence();
            this.description = taskEntry.description();
            this.summary = taskEntry.summary();
            // Serialize the log to JSON if it exists
            this.serializedLog = taskEntry.log() != null
                    ? ChatMessageSerializer.messagesToJson(taskEntry.log())
                    : null;
        }

        /**
         * Reconstruct the TaskEntry instance after the SerializationProxy is deserialized.
         */
        @Serial
        private Object readResolve() {
            if (serializedLog != null) {
                // Deserialize log from JSON
                List<ChatMessage> deserializedLog = ChatMessageDeserializer.messagesFromJson(serializedLog);
                return new TaskEntry(sequence, description, deserializedLog, null);
            } else {
                // Entry was compressed or had no log originally
                return new TaskEntry(sequence, description, null, summary);
            }
        }
    }
}
