package io.github.jbellis.brokk.gui;

import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import io.github.jbellis.brokk.Models;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.List;

/**
 * The InstructionsPanel encapsulates the command input area, history dropdown,
 * mic button, model dropdown, and the action buttons.
 * It also includes the system messages and command result areas.
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
    private final JTextArea systemArea; // Moved from HistoryOutputPanel
    private final JScrollPane systemScrollPane; // Moved from HistoryOutputPanel
    private final JLabel commandResultLabel; // Moved from HistoryOutputPanel

    public InstructionsPanel(Chrome chrome) {
        super(new BorderLayout(2, 2)); // Main layout is BorderLayout
        setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(),
                                                   "Instructions",
                                                   TitledBorder.DEFAULT_JUSTIFICATION,
                                                   TitledBorder.DEFAULT_POSITION,
                                                   new Font(Font.DIALOG, Font.BOLD, 12)));

        this.chrome = chrome;

        // Initialize components
        commandInputField = buildCommandInputField();
        modelDropdown = new JComboBox<>();
        micButton = new VoiceInputButton(
                commandInputField,
                chrome.getContextManager(),
                () -> chrome.actionOutput("Recording"),
                chrome::toolError
        );
        systemArea = new JTextArea(); // Initialize moved component
        systemScrollPane = buildSystemMessagesArea(); // Initialize moved component
        commandResultLabel = buildCommandResultLabel(); // Initialize moved component

        // Initialize Buttons first
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

        stopButton = new JButton("Stop");
        stopButton.setToolTipText("Cancel the current operation");
        stopButton.addActionListener(e -> chrome.stopCurrentUserTask());

        // Top Bar (History, Model, Stop) (North)
        JPanel topBarPanel = buildTopBarPanel();
        add(topBarPanel, BorderLayout.NORTH);

        // Center Panel (Command Input + System/Result) (Center)
        JPanel centerPanel = buildCenterPanel();
        add(centerPanel, BorderLayout.CENTER);

        // Bottom Bar (Mic, Model, Actions) (South)
        JPanel bottomPanel = buildBottomPanel();
        add(bottomPanel, BorderLayout.SOUTH);

        SwingUtilities.invokeLater(() -> {
            if (chrome.getFrame() != null && chrome.getFrame().getRootPane() != null) {
                chrome.getFrame().getRootPane().setDefaultButton(codeButton);
            }
        });
    }

    private RSyntaxTextArea buildCommandInputField() {
        var area = new RSyntaxTextArea(3, 40);
        area.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        area.setHighlightCurrentLine(false);
        area.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.GRAY),
                BorderFactory.createEmptyBorder(2, 5, 2, 5)
        ));
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setRows(3); // Initial rows
        area.setMinimumSize(new Dimension(100, 80));
        area.setAutoIndentEnabled(false);

        // Add Ctrl+Enter shortcut to trigger the default button
        var ctrlEnter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, java.awt.event.InputEvent.CTRL_DOWN_MASK);
        area.getInputMap().put(ctrlEnter, "submitDefault");
        area.getActionMap().put("submitDefault", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                // If there's a default button, "click" it
                var rootPane = SwingUtilities.getRootPane(area);
                if (rootPane != null && rootPane.getDefaultButton() != null) {
                    rootPane.getDefaultButton().doClick();
                }
            }
        });

        return area;
    }

    private JPanel buildTopBarPanel() {
        JPanel topBarPanel = new JPanel(new BorderLayout(5, 0)); // Use BorderLayout
        topBarPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 2, 5)); // Add padding

        // Left Panel (Mic + History) (West)
        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        leftPanel.add(micButton);

        JButton historyButton = new JButton("History ▼");
        historyButton.setToolTipText("Select a previous instruction from history");
        historyButton.addActionListener(e -> showHistoryMenu(historyButton));
        leftPanel.add(historyButton);

        topBarPanel.add(leftPanel, BorderLayout.WEST);

        // Model Dropdown (Center)
        modelDropdown.setToolTipText("Select the AI model to use");
        topBarPanel.add(modelDropdown, BorderLayout.CENTER);

        // Stop Button (East)
        topBarPanel.add(stopButton, BorderLayout.EAST);

        return topBarPanel;
    }

    private JPanel buildCenterPanel() {
        JPanel centerPanel = new JPanel(new BorderLayout(0, 2)); // Vertical layout

        // Command Input Field (Top)
        JScrollPane commandScrollPane = new JScrollPane(commandInputField);
        commandScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        commandScrollPane.setPreferredSize(new Dimension(600, 80));
        commandScrollPane.setMinimumSize(new Dimension(100, 80));
        centerPanel.add(commandScrollPane, BorderLayout.CENTER);

        // System Messages + Command Result (Bottom)
        // Create a panel to hold system messages and the command result label
        var topInfoPanel = new JPanel();
        topInfoPanel.setLayout(new BoxLayout(topInfoPanel, BoxLayout.PAGE_AXIS)); // Use vertical BoxLayout
        topInfoPanel.add(commandResultLabel);
        topInfoPanel.add(systemScrollPane);
        centerPanel.add(topInfoPanel, BorderLayout.SOUTH);

        return centerPanel;
    }

    private JPanel buildBottomPanel() {
        JPanel bottomPanel = new JPanel();
        bottomPanel.setLayout(new BoxLayout(bottomPanel, BoxLayout.LINE_AXIS)); // Use horizontal BoxLayout
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2)); // Add padding

        // Action Button Bar
        JPanel actionButtonBar = buildActionBar();
        actionButtonBar.setAlignmentY(Component.CENTER_ALIGNMENT);
        bottomPanel.add(actionButtonBar);
        bottomPanel.add(Box.createHorizontalGlue()); // Pushes buttons to the left if needed

        return bottomPanel;
    }

    // Helper to build just the action buttons (Code, Ask, Search, Run)
    private JPanel buildActionBar() {
        JPanel actionButtonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        actionButtonsPanel.setBorder(BorderFactory.createEmptyBorder()); // No border needed
        actionButtonsPanel.add(codeButton);
        actionButtonsPanel.add(askButton);
        actionButtonsPanel.add(searchButton);
        actionButtonsPanel.add(runButton);
        return actionButtonsPanel;
    }

    /**
     * Builds the system messages area that appears below the command input area.
     * Moved from HistoryOutputPanel.
     */
    private JScrollPane buildSystemMessagesArea() {
        // Create text area for system messages
        systemArea.setEditable(false);
        systemArea.getCaret().setVisible(false); // Hide the edit caret
        systemArea.setLineWrap(true);
        systemArea.setWrapStyleWord(true);
        systemArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        systemArea.setRows(4);

        // Create scroll pane with border and title
        var scrollPane = new JScrollPane(systemArea);
        scrollPane.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "System Messages",
                TitledBorder.DEFAULT_JUSTIFICATION,
                TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)
        ));
        new SmartScroll(scrollPane);

        return scrollPane;
    }

    /**
     * Builds the command result label.
     * Moved from HistoryOutputPanel.
     */
    private JLabel buildCommandResultLabel() {
        var label = new JLabel(" "); // Start with a space to ensure height
        label.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        label.setBorder(new EmptyBorder(2, 5, 2, 5)); // Padding
        return label;
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

    // --- Public API ---

    public String getInputText() {
        return commandInputField.getText();
    }

    public void clearCommandInput() {
        commandInputField.setText("");
    }

    public void requestCommandInputFocus() {
        commandInputField.requestFocus();
    }

    /**
     * Sets the text of the command result label.
     * Moved from HistoryOutputPanel.
     */
    public void setCommandResultText(String text) {
        commandResultLabel.setText(text);
    }

    /**
     * Clears the text of the command result label.
     * Moved from HistoryOutputPanel.
     */
    public void clearCommandResultText() {
        commandResultLabel.setText(" "); // Set back to space to maintain height
    }

    /**
     * Appends text to the system output area with timestamp.
     * Moved from HistoryOutputPanel.
     */
    public void appendSystemOutput(String message) {
        // Format timestamp as HH:MM
        String timestamp = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));

        // Add newline if needed
        if (!systemArea.getText().isEmpty() && !systemArea.getText().endsWith("\n")) {
            systemArea.append("\n");
        }

        // Append timestamped message
        systemArea.append(timestamp + ": " + message);
        // Scroll to bottom
        SwingUtilities.invokeLater(() -> systemArea.setCaretPosition(systemArea.getDocument().getLength()));
    }

    // Initialization of model dropdown is now encapsulated here.
    public void initializeModels() {
        if (chrome.getContextManager() == null) {
            logger.warn("Cannot initialize models: ContextManager is null");
            modelDropdown.addItem("Error: No Context");
            modelDropdown.setEnabled(false);
            disableButtons();
            return;
        }

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
            var model = Models.get(selectedName);
            // Save the successfully selected model
            if (chrome.getProject() != null) {
                 // chrome.getProject().saveLastUsedModel(selectedName); // Method missing in Project class
            }
            return model;
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
        // Update the LLM output panel directly via Chrome
        chrome.llmOutput("# Please be patient\n\nBrokk makes multiple requests to the LLM while searching. Progress is logged in System Messages below.");
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
            micButton.setEnabled(false); // Also disable mic button
        });
    }

    public void enableButtons() {
        SwingUtilities.invokeLater(() -> {
            boolean modelsAvailable = modelDropdown.getItemCount() > 0 &&
                    !String.valueOf(modelDropdown.getSelectedItem()).startsWith("No Model") && // check selected value
                    !String.valueOf(modelDropdown.getSelectedItem()).startsWith("Error");
            modelDropdown.setEnabled(modelsAvailable);
            codeButton.setEnabled(modelsAvailable);
            askButton.setEnabled(modelsAvailable);
            searchButton.setEnabled(modelsAvailable);
            runButton.setEnabled(true);
            stopButton.setEnabled(false);
            micButton.setEnabled(true); // Enable mic button
        });
    }
}
