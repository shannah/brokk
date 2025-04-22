package io.github.jbellis.brokk.gui.mop;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.prompts.EditBlockParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import java.awt.*;

/**
 * Renderer for user messages, with styling specific to user input.
 */
public class UserMessageRenderer implements MessageComponentRenderer {
    private static final Logger logger = LogManager.getLogger(UserMessageRenderer.class);

    @Override
    public Component renderComponent(ChatMessage message, boolean isDarkTheme) {
        // Check if message is a UserMessage with a name (use this prop as sub-type)
        if (message instanceof UserMessage userMessage && userMessage.name() != null && !userMessage.name().isEmpty()) {
            try {
                IConsoleIO.MessageSubType.valueOf(userMessage.name());
                // Valid MessageSubType
            } catch (IllegalArgumentException e) {
                // Not a valid MessageSubType
                logger.debug("UserMessage name '{}' is not a valid MessageSubType", userMessage.name());
            }
            // Create a container panel with vertical BoxLayout
            JPanel containerPanel = new JPanel();
            containerPanel.setLayout(new BoxLayout(containerPanel, BoxLayout.Y_AXIS));
            containerPanel.setOpaque(false);
            containerPanel.setBackground(ThemeColors.getColor(isDarkTheme, "chat_background"));

            JPanel modePanel = new BaseChatMessagePanel(
                    null,
                    null,
                    new JLabel(userMessage.name().toUpperCase() + " MODE"),
                    isDarkTheme,
                    ThemeColors.getColor(isDarkTheme, "message_background")
            );

            // Create the regular message panel
            JPanel messagePanel = createMessagePanel(message, isDarkTheme);
            // Ensure the message panel is left-aligned
            messagePanel.setAlignmentX(Component.LEFT_ALIGNMENT);

            // Add both panels to the container
            containerPanel.add(modePanel);
            containerPanel.add(Box.createRigidArea(new Dimension(0, 5))); // Small gap between panels
            containerPanel.add(messagePanel);

            return containerPanel;
        } else {
            // Original implementation for regular user messages
            return createMessagePanel(message, isDarkTheme);
        }
    }
    
    /**
     * Creates a standard message panel for user messages.
     * 
     * @param message The chat message to render
     * @param isDarkTheme Whether dark theme is enabled
     * @return A configured BaseChatMessagePanel
     */
    private JPanel createMessagePanel(ChatMessage message, boolean isDarkTheme) {
        String content = MarkdownRenderUtil.getMessageContent(message);
        var contentPanel = MarkdownRenderUtil.renderMarkdownContent(content, isDarkTheme);
        contentPanel.setForeground(ThemeColors.getColor(isDarkTheme, "chat_text"));

        return new BaseChatMessagePanel(
            "You",
            "\uD83D\uDCBB", // Unicode for computer emoji
            contentPanel,
            isDarkTheme,
            ThemeColors.getColor(isDarkTheme, "message_border_user")
        );
    }
}
