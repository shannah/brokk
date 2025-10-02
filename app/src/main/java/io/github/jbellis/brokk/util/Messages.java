package io.github.jbellis.brokk.util;

import static java.util.Objects.requireNonNull;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Messages {
    private static final Logger logger = LogManager.getLogger(Messages.class);

    // Simple OpenAI token count estimator for approximate counting
    // It can remain static as it's stateless based on model ID
    private static final OpenAiTokenCountEstimator tokenCountEstimator = new OpenAiTokenCountEstimator("gpt-4o");

    public static void init() {
        // tokenizer is surprisingly heavyweigh to initialize, this is just to give a hook to force that early
        logger.debug("Messages helper initializing");
    }

    /**
     * We render these as "System" messages in the output. We don't use actual System messages since those are only
     * allowed at the very beginning for some models.
     */
    public static CustomMessage customSystem(String text) {
        return new CustomMessage(Map.of("text", text));
    }

    public static List<ChatMessage> forLlm(Collection<ChatMessage> messages) {
        return messages.stream()
                .map(m -> {
                    if (m instanceof CustomMessage) {
                        return new UserMessage(getText(m));
                    }
                    if (m instanceof UserMessage um && um.name() != null) {
                        // strip out the metadata we use for stashing UI action type
                        return new UserMessage(um.contents());
                    }
                    return m;
                })
                .toList();
    }

    /** Extracts text content from a ChatMessage. This logic is independent of Models state, so can remain static. */
    public static String getText(ChatMessage message) {
        return switch (message) {
            case SystemMessage sm -> sm.text();
            case AiMessage am -> {
                var text = am.text();
                if (text != null && !text.isBlank()) {
                    yield text;
                }
                var reasoning = am.reasoningContent();
                yield reasoning == null ? "" : reasoning;
            }
            case UserMessage um ->
                um.contents().stream()
                        .filter(c -> c instanceof TextContent)
                        .map(c -> ((TextContent) c).text())
                        .collect(Collectors.joining("\n"));
            case ToolExecutionResultMessage tr -> "%s -> %s".formatted(tr.toolName(), tr.text());
            case CustomMessage cm -> requireNonNull(cm.attributes().get("text")).toString();
            default ->
                throw new UnsupportedOperationException(message.getClass().toString());
        };
    }

    /** Helper method to create a ChatMessage of the specified type */
    public static ChatMessage create(String text, ChatMessageType type) {
        return create(text, type, false);
    }

    public static ChatMessage create(String text, ChatMessageType type, boolean isReasoning) {
        return switch (type) {
            case USER -> new UserMessage(text);
            case AI -> isReasoning ? new AiMessage("", text) : new AiMessage(text);
            case CUSTOM -> customSystem(text);
            // Add other cases as needed with appropriate implementations
            default -> {
                logger.warn("Unsupported message type: {}, using AiMessage as fallback", type);
                yield new AiMessage(text);
            }
        };
    }

    /** Primary difference from getText: 1. Includes tool requests 2. Includes placeholder for images */
    public static String getRepr(ChatMessage message) {
        return switch (message) {
            case SystemMessage sm -> sm.text();
            case CustomMessage cm -> requireNonNull(cm.attributes().get("text")).toString();
            case AiMessage am -> {
                var reasoning = am.reasoningContent();
                var text = am.text();
                var hasReasoning = reasoning != null && !reasoning.isBlank();
                var hasText = text != null && !text.isBlank();
                var hasTools = am.hasToolExecutionRequests();

                var parts = new java.util.ArrayList<String>();
                if (hasReasoning) {
                    parts.add("Reasoning:\n" + reasoning);
                    if (hasText) {
                        parts.add("Text:\n" + text);
                    }
                } else if (hasText) {
                    parts.add(text);
                }

                if (hasTools) {
                    var toolText = am.toolExecutionRequests().stream()
                            .map(Messages::getRepr)
                            .collect(Collectors.joining("\n"));
                    parts.add("Tool calls:\n" + toolText);
                }

                yield String.join("\n", parts);
            }
            case UserMessage um -> {
                yield um.contents().stream()
                        .map(c -> {
                            if (c instanceof TextContent textContent) {
                                return textContent.text();
                            } else if (c instanceof ImageContent) {
                                return "[Image]";
                            } else {
                                throw new UnsupportedOperationException(
                                        c.getClass().toString());
                            }
                        })
                        .collect(Collectors.joining("\n"));
            }
            case ToolExecutionResultMessage tr -> "%s -> %s".formatted(tr.toolName(), tr.text());
            default ->
                throw new UnsupportedOperationException(message.getClass().toString());
        };
    }

    public static String getRepr(ToolExecutionRequest tr) {
        return "%s(%s)".formatted(tr.name(), tr.arguments());
    }

    /**
     * Estimates the token count of a text string. This can remain static as it only depends on the static token count
     * estimator.
     */
    public static int getApproximateTokens(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        return tokenCountEstimator.encode(text).size();
    }

    public static int getApproximateTokens(Collection<ChatMessage> messages) {
        return getApproximateTokens(messages.stream().map(Messages::getText).collect(Collectors.joining("\n")));
    }

    public static boolean isReasoningMessage(ChatMessage message) {
        return message instanceof AiMessage aiMessage
                && aiMessage.reasoningContent() != null
                && !aiMessage.reasoningContent().isBlank();
    }
}
