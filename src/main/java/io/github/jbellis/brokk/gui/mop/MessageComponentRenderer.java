package io.github.jbellis.brokk.gui.mop;

import dev.langchain4j.data.message.ChatMessage;

import java.awt.*;

/**
 * Interface for rendering chat message components.
 * Each implementation handles the rendering of a specific message type.
 */
public interface MessageComponentRenderer {
    /**
     * Renders a chat message as a Swing component.
     *
     * @param message The message to render
     * @param isDarkTheme Whether the dark theme is active
     * @return A Component representing the rendered message
     */
    Component renderComponent(ChatMessage message, boolean isDarkTheme);
}
