package io.github.jbellis.brokk;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents a single task interaction for the Task History, including the user request ("description") and the full LLM message log.
 * The log can be compressed to save context space while retaining the most relevant information.
 * This record is serializable, using langchain4j's JSON serialization for ChatMessage lists.
 *
 * @param sequence      A unique sequence number for ordering tasks.
 * @param description   A short description of the user's request for this task.
 * @param log           The uncompressed list of chat messages for this task. Null if compressed.
 * @param summary The compressed representation of the chat messages (summary). Null if uncompressed.
 */
public record TaskMessages(int sequence, String description, List<ChatMessage> log, String summary) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Initial version
    private static final System.Logger logger = System.getLogger(TaskMessages.class.getName());

    /** Enforce that exactly one of log or summary is non-null */
    public TaskMessages {
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
     *                        (So, NOT including system messages, workspace messages, example edit messages, etc.)
     * @return A new TaskEntry instance.
     */
    public static TaskMessages fromSession(int sequence, SessionResult result) {
        assert result != null;
        return new TaskMessages(sequence, result.actionDescription(), result.messages(), null);
    }

    public static TaskMessages fromCompressed(int sequence, String compressedLog) {
        return new TaskMessages(sequence, null, null, compressedLog);
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
              """.stripIndent().formatted(sequence, summary.indent(2).stripTrailing());
        }

        var logText = formatMessages(log);
        return """
          <task sequence=%s>
          %s
          %s
          </task>
          """.stripIndent().formatted(sequence,
                        description.indent(2).stripTrailing(),
                        logText.indent(2).stripTrailing());
    }

    public static @NotNull String formatMessages(List<ChatMessage> messages) {
        return messages.stream()
                  .map(message -> {
                      var text = Models.getRepr(message);
                      return """
                      <message type=%s>
                      %s
                      </message>
                      """.stripIndent().formatted(message.type().name().toLowerCase(), text.indent(2).stripTrailing());
                  })
                  .collect(Collectors.joining("\n"));
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

        SerializationProxy(TaskMessages taskMessages) {
            this.sequence = taskMessages.sequence();
            this.description = taskMessages.description();
            this.summary = taskMessages.summary();
            // Serialize the log to JSON if it exists
            this.serializedLog = taskMessages.log() != null
                    ? ChatMessageSerializer.messagesToJson(taskMessages.log())
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
                return new TaskMessages(sequence, description, deserializedLog, null);
            } else {
                // Entry was compressed or had no log originally
                return new TaskMessages(sequence, description, null, summary);
            }
        }
    }
}
