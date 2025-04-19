package io.github.jbellis.brokk.gui.MOP;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
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
        // Check if message is a UserMessage with a name
        if (message instanceof UserMessage userMessage && userMessage.name() != null && !userMessage.name().isEmpty()) {
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
                    ThemeColors.getColor(isDarkTheme, "message_border_user")
            );

            // 2. Create the regular message panel
            String content = MarkdownRenderUtil.getMessageContent(message);
            var contentPanel = MarkdownRenderUtil.renderMarkdownContent(content, isDarkTheme);
            contentPanel.setForeground(ThemeColors.getColor(isDarkTheme, "chat_text"));

            var messagePanel = new BaseChatMessagePanel(
                "You",
                "\uD83D\uDCBB", // Unicode for computer emoji
                contentPanel,
                isDarkTheme,
                ThemeColors.getColor(isDarkTheme, "message_border_user")
            );
            // Ensure the message panel is left-aligned
            messagePanel.setAlignmentX(Component.LEFT_ALIGNMENT);

            // Add both panels to the container
            containerPanel.add(modePanel);
            containerPanel.add(Box.createRigidArea(new Dimension(0, 5))); // Small gap between panels
            containerPanel.add(messagePanel);

            return containerPanel;
        } else {
            // Original implementation for regular user messages
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
}
