package io.github.jbellis.brokk.gui;

import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import io.github.jbellis.brokk.Models;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import javax.swing.*;
import javax.swing.border.EtchedBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.List;

/**
 * The InstructionsPanel encapsulates the command input area, history dropdown,
 * mic button, model dropdown, and the action buttons.
 * All initialization and action code related to these components has been moved here.
 */
public class InstructionsPanel extends JPanel {
    private static final Logger logger = LogManager.getLogger(InstructionsPanel.class);

    private static final int DROPDOWN_MENU_WIDTH = 1000; // Pixels
    private static final int TRUNCATION_LENGTH = 100;    // Characters

    private final Chrome chrome;
    private final RSyntaxTextArea commandInputField;
    private final JComboBox<String> modelDropdown;
    private final VoiceInputButton micButton;
    private final JButton codeButton;
    private final JButton askButton;
    private final JButton searchButton;
    private final JButton runButton;
    private final JButton stopButton;

    public InstructionsPanel(Chrome chrome) {
        super(new GridBagLayout());
        setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(),
                                                   "Instructions",
                                                   TitledBorder.DEFAULT_JUSTIFICATION,
                                                   TitledBorder.DEFAULT_POSITION,
                                                   new Font(Font.DIALOG, Font.BOLD, 12)));

        this.chrome = chrome;
        var gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 1.0;
        gbc.gridx = 0;
        gbc.insets = new Insets(2, 2, 2, 2);

        // Row 0: History Dropdown spanning columns 1-2
        gbc.gridx = 1;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(0, 0, 5, 0);
        JPanel historyPanel = buildHistoryDropdown();
        add(historyPanel, gbc);
        gbc.insets = new Insets(0, 0, 0, 0);

        // Row 1 & 2: Mic Button, Command Input, Model Dropdown, Buttons
        // Mic button (Col 0, Rows 1-2)
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.gridwidth = 1;
        gbc.gridheight = 2;
        gbc.weightx = 0;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.VERTICAL;
        gbc.anchor = GridBagConstraints.CENTER;
        gbc.insets = new Insets(2, 2, 2, 8);
        commandInputField = new RSyntaxTextArea(3, 40);
        commandInputField.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
        commandInputField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        commandInputField.setHighlightCurrentLine(false);
        commandInputField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.GRAY),
                BorderFactory.createEmptyBorder(2, 5, 2, 5)
        ));
        commandInputField.setLineWrap(true);
        commandInputField.setWrapStyleWord(true);
        commandInputField.setRows(3);
        commandInputField.setMinimumSize(new Dimension(100, 80));
        commandInputField.setAutoIndentEnabled(false);
        micButton = new VoiceInputButton(
                commandInputField,
                chrome.getContextManager(),
                () -> chrome.actionOutput("Recording"),
                chrome::toolError
        );
        add(micButton, gbc);
        gbc.insets = new Insets(0, 0, 0, 0);
        gbc.gridheight = 1;
        gbc.weighty = 0;
        gbc.fill = GridBagConstraints.BOTH;

        // Command input field (Row 1, Columns 1-2)
        gbc.gridx = 1;
        gbc.gridy = 1;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.insets = new Insets(0, 0, 5, 0);
        JScrollPane commandScrollPane = new JScrollPane(commandInputField);
        commandScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        commandScrollPane.setPreferredSize(new Dimension(600, 80));
        commandScrollPane.setMinimumSize(new Dimension(100, 80));
        add(commandScrollPane, gbc);
        gbc.insets = new Insets(0, 0, 0, 0);

        // Model dropdown (Row 2, Column 1)
        gbc.gridx = 1;
        gbc.gridy = 2;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(0, 0, 0, 5);
        modelDropdown = new JComboBox<>();
        modelDropdown.setToolTipText("Select the AI model to use");
        add(modelDropdown, gbc);
        gbc.insets = new Insets(0, 0, 0, 0);

        // Buttons holder panel (Row 2, Column 2)
        gbc.gridx = 2;
        gbc.gridy = 2;
        gbc.gridwidth = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.EAST;
        JPanel buttonsHolder = new JPanel(new BorderLayout());
        // Left buttons panel
        JPanel leftButtonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        leftButtonsPanel.setBorder(BorderFactory.createEmptyBorder());
        codeButton = new JButton("Code");
        codeButton.setMnemonic(KeyEvent.VK_C);
        codeButton.setToolTipText("Tell the LLM to write code to solve this problem using the current context");
        codeButton.addActionListener(e -> runCodeCommand());
        askButton = new JButton("Ask");
        askButton.setMnemonic(KeyEvent.VK_A);
        askButton.setToolTipText("Ask the LLM a question about the current context");
        askButton.addActionListener(e -> runAskCommand());
        searchButton = new JButton("Search");
        searchButton.setMnemonic(KeyEvent.VK_S);
        searchButton.setToolTipText("Explore the codebase beyond the current context");
        searchButton.addActionListener(e -> runSearchCommand());
        runButton = new JButton("Run in Shell");
        runButton.setMnemonic(KeyEvent.VK_N);
        runButton.setToolTipText("Execute the current instructions in a shell");
        runButton.addActionListener(e -> runRunCommand());
        leftButtonsPanel.add(codeButton);
        leftButtonsPanel.add(askButton);
        leftButtonsPanel.add(searchButton);
        leftButtonsPanel.add(runButton);
        // Right buttons panel
        JPanel rightButtonsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        rightButtonsPanel.setBorder(BorderFactory.createEmptyBorder());
        stopButton = new JButton("Stop");
        stopButton.setToolTipText("Cancel the current operation");
        stopButton.addActionListener(e -> chrome.stopCurrentUserTask());
        rightButtonsPanel.add(stopButton);
        buttonsHolder.add(leftButtonsPanel, BorderLayout.WEST);
        buttonsHolder.add(rightButtonsPanel, BorderLayout.EAST);
        add(buttonsHolder, gbc);

        SwingUtilities.invokeLater(() -> {
            if (chrome.getFrame() != null && chrome.getFrame().getRootPane() != null) {
                chrome.getFrame().getRootPane().setDefaultButton(codeButton);
            }
        });
    }

    private JPanel buildHistoryDropdown() {
        JPanel historyPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        JButton historyButton = new JButton("History ▼");
        historyButton.setToolTipText("Select a previous instruction from history");
        historyPanel.add(historyButton);
        historyButton.addActionListener(e -> showHistoryMenu(historyButton));
        return historyPanel;
    }

    private void showHistoryMenu(Component invoker) {
        logger.debug("Showing history menu");
        JPopupMenu historyMenu = new JPopupMenu();
        var project = chrome.getProject();
        if (project == null) {
            logger.warn("Cannot show history menu: project is null");
            JMenuItem errorItem = new JMenuItem("Project not loaded");
            errorItem.setEnabled(false);
            historyMenu.add(errorItem);
        } else {
            List<String> historyItems = project.loadTextHistory();
            logger.debug("History items loaded: {}", historyItems.size());
            if (historyItems.isEmpty()) {
                JMenuItem emptyItem = new JMenuItem("(No history items)");
                emptyItem.setEnabled(false);
                historyMenu.add(emptyItem);
            } else {
                for (int i = historyItems.size() - 1; i >= 0; i--) {
                    String item = historyItems.get(i);
                    String itemWithoutNewlines = item.replace('\n', ' ');
                    String displayText = itemWithoutNewlines.length() > TRUNCATION_LENGTH
                            ? itemWithoutNewlines.substring(0, TRUNCATION_LENGTH) + "..."
                            : itemWithoutNewlines;
                    String escapedItem = item.replace("&", "&amp;")
                            .replace("<", "&lt;")
                            .replace(">", "&gt;")
                            .replace("\"", "&quot;");
                    JMenuItem menuItem = new JMenuItem(displayText);
                    menuItem.setToolTipText("<html><pre>" + escapedItem + "</pre></html>");
                    menuItem.addActionListener(event -> {
                        commandInputField.setText(item);
                        commandInputField.requestFocusInWindow();
                    });
                    historyMenu.add(menuItem);
                }
            }
        }
        if (chrome.themeManager != null) {
            chrome.themeManager.registerPopupMenu(historyMenu);
        }
        historyMenu.setMinimumSize(new Dimension(DROPDOWN_MENU_WIDTH, 0));
        historyMenu.setPreferredSize(new Dimension(DROPDOWN_MENU_WIDTH, historyMenu.getPreferredSize().height));
        historyMenu.pack();
        logger.debug("Showing history menu with preferred width: {}", DROPDOWN_MENU_WIDTH);
        historyMenu.show(invoker, 0, invoker.getHeight());
    }

    public String getInputText() {
        return commandInputField.getText();
    }

    public void clearCommandInput() {
        commandInputField.setText("");
    }

    public void requestCommandInputFocus() {
        commandInputField.requestFocus();
    }

    // Initialization of model dropdown is now encapsulated here.
    public void initializeModels() {
        chrome.getContextManager().submitBackgroundTask("Fetching available models", () -> {
            var modelLocationMap = Models.getAvailableModels();
            SwingUtilities.invokeLater(() -> {
                modelDropdown.removeAllItems();
                modelLocationMap.forEach((k, v) -> logger.debug("Available modelName={} => location={}", k, v));
                if (modelLocationMap.isEmpty()) {
                    logger.error("No models discovered from LiteLLM.");
                    modelDropdown.addItem("No Models Available");
                    modelDropdown.setEnabled(false);
                    disableButtons();
                } else {
                    logger.debug("Populating dropdown with {} models.", modelLocationMap.size());
                    modelLocationMap.keySet().stream()
                            .filter(k -> !k.contains("-lite"))
                            .sorted()
                            .forEach(modelDropdown::addItem);
                    modelDropdown.setEnabled(true);
                    var lastUsedModel = chrome.getProject().getLastUsedModel();
                    if (lastUsedModel != null) {
                        boolean found = false;
                        for (int i = 0; i < modelDropdown.getItemCount(); i++) {
                            if (modelDropdown.getItemAt(i).equals(lastUsedModel)) {
                                modelDropdown.setSelectedItem(lastUsedModel);
                                found = true;
                                logger.debug("Restored last used model: {}", lastUsedModel);
                                break;
                            }
                        }
                        if (!found) {
                            logger.warn("Last used model '{}' not found in available models.", lastUsedModel);
                        }
                    } else {
                        logger.debug("No last used model saved for this project.");
                    }
                    enableButtons();
                }
            });
            return null;
        });
    }

    // Private helper to get the selected model.
    private StreamingChatLanguageModel getSelectedModel() {
        var selectedName = (String) modelDropdown.getSelectedItem();
        logger.debug("User selected model name from dropdown: {}", selectedName);
        if (selectedName == null || selectedName.startsWith("No Models") || selectedName.startsWith("Error")) {
            logger.warn("No valid model selected in dropdown.");
            return null;
        }
        try {
            return Models.get(selectedName);
        } catch (Exception e) {
            logger.error("Failed to get model instance for {}", selectedName, e);
            return null;
        }
    }

    // Methods for running commands.
    public void runCodeCommand() {
        var input = commandInputField.getText();
        if (input.isBlank()) {
            chrome.toolErrorRaw("Please enter a command or text");
            return;
        }
        var selectedModel = getSelectedModel();
        if (selectedModel == null) {
            chrome.toolError("Please select a valid model from the dropdown.");
            return;
        }
        chrome.getProject().addToTextHistory(input, 20);
        chrome.historyOutputPane.setLlmOutput("# Code\n" + input + "\n\n# Response\n");
        clearCommandInput();
        disableButtons();
        chrome.setCurrentUserTask(chrome.getContextManager().runCodeCommandAsync(selectedModel, input));
    }

    public void runAskCommand() {
        var input = commandInputField.getText();
        if (input.isBlank()) {
            chrome.toolErrorRaw("Please enter a question");
            return;
        }
        var selectedModel = getSelectedModel();
        if (selectedModel == null) {
            chrome.toolError("Please select a valid model from the dropdown.");
            return;
        }
        chrome.getProject().addToTextHistory(input, 20);
        chrome.historyOutputPane.setLlmOutput("# Ask\n" + input + "\n\n# Response\n");
        clearCommandInput();
        disableButtons();
        chrome.setCurrentUserTask(chrome.getContextManager().runAskAsync(selectedModel, input));
    }

    public void runSearchCommand() {
        var input = commandInputField.getText();
        if (input.isBlank()) {
            chrome.toolErrorRaw("Please provide a search query");
            return;
        }
        var selectedModel = getSelectedModel();
        if (selectedModel == null) {
            chrome.toolError("Please select a valid model from the dropdown.");
            return;
        }
        chrome.getProject().addToTextHistory(input, 20);
        chrome.historyOutputPane.setLlmOutput("# Search\n" + input + "\n\n");
        chrome.historyOutputPane.appendLlmOutput("# Please be patient\n\nBrokk makes multiple requests to the LLM while searching. Progress is logged in System Messages below.");
        clearCommandInput();
        disableButtons();
        chrome.setCurrentUserTask(chrome.getContextManager().runSearchAsync(selectedModel, input));
    }

    public void runRunCommand() {
        var input = commandInputField.getText();
        if (input.isBlank()) {
            chrome.toolError("Please enter a command to run");
            return;
        }
        chrome.getProject().addToTextHistory(input, 20);
        chrome.historyOutputPane.setLlmOutput("# Run\n" + input + "\n\n# Output\n");
        clearCommandInput();
        disableButtons();
        chrome.setCurrentUserTask(chrome.getContextManager().runRunCommandAsync(input));
    }

    // Methods to disable and enable buttons.
    public void disableButtons() {
        SwingUtilities.invokeLater(() -> {
            codeButton.setEnabled(false);
            askButton.setEnabled(false);
            searchButton.setEnabled(false);
            runButton.setEnabled(false);
            stopButton.setEnabled(true);
            modelDropdown.setEnabled(false);
        });
    }

    public void enableButtons() {
        SwingUtilities.invokeLater(() -> {
            codeButton.setEnabled(true);
            boolean modelsAvailable = modelDropdown.getItemCount() > 0 &&
                    !modelDropdown.getItemAt(0).startsWith("No Model");
            modelDropdown.setEnabled(modelsAvailable);
            codeButton.setEnabled(modelsAvailable);
            askButton.setEnabled(modelsAvailable);
            searchButton.setEnabled(modelsAvailable);
            runButton.setEnabled(true);
            stopButton.setEnabled(false);
        });
    }
}
