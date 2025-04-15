package io.github.jbellis.brokk.gui;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import io.github.jbellis.brokk.ArchitectAgent;
import io.github.jbellis.brokk.CodeAgent;
import io.github.jbellis.brokk.Context.ParsedOutput;
import io.github.jbellis.brokk.ContextFragment;
import io.github.jbellis.brokk.Models;
import io.github.jbellis.brokk.SearchAgent;
import io.github.jbellis.brokk.prompts.AskPrompts;
import io.github.jbellis.brokk.util.Environment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

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
    private final JButton agentButton;
    private final JButton codeButton;
    private final JButton askButton;
    private final JButton searchButton;
    private final JButton runButton;
    private final JButton stopButton;
    private final JTextArea systemArea; // Moved from HistoryOutputPanel
    private final JScrollPane systemScrollPane; // Moved from HistoryOutputPanel
    private final JLabel commandResultLabel; // Moved from HistoryOutputPanel

    public InstructionsPanel(Chrome chrome) {
        super(new BorderLayout(2, 2));
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
        agentButton = new JButton("Agent"); // Initialize the agent button
        agentButton.setMnemonic(KeyEvent.VK_G); // Mnemonic for Agent
        agentButton.setToolTipText("Run the multi-step agent to execute the current plan");
        agentButton.addActionListener(e -> runAgentCommand());

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
        JPanel topBarPanel = new JPanel(new BorderLayout(5, 0));
        topBarPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 2, 5));

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
        JPanel centerPanel = new JPanel(new BorderLayout(0, 2));

        // Command Input Field (Top)
        JScrollPane commandScrollPane = new JScrollPane(commandInputField);
        commandScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        commandScrollPane.setPreferredSize(new Dimension(600, 80));
        commandScrollPane.setMinimumSize(new Dimension(100, 80));
        centerPanel.add(commandScrollPane, BorderLayout.CENTER);

        // System Messages + Command Result (Bottom)
        // Create a panel to hold system messages and the command result label
        var topInfoPanel = new JPanel();
        topInfoPanel.setLayout(new BoxLayout(topInfoPanel, BoxLayout.PAGE_AXIS));
        topInfoPanel.add(commandResultLabel);
        topInfoPanel.add(systemScrollPane);
        centerPanel.add(topInfoPanel, BorderLayout.SOUTH);

        return centerPanel;
    }

    private JPanel buildBottomPanel() {
        JPanel bottomPanel = new JPanel();
        bottomPanel.setLayout(new BoxLayout(bottomPanel, BoxLayout.LINE_AXIS));
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

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
        actionButtonsPanel.setBorder(BorderFactory.createEmptyBorder());
        actionButtonsPanel.add(agentButton);
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
        label.setBorder(new EmptyBorder(2, 5, 2, 5));
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

        // Use the Models instance from ContextManager
        var models = chrome.getContextManager().getModels();
        // This method is already called via invokeLater, so we can update UI directly.
        // Fetch the available models (reads an in-memory map, safe for EDT)
        var modelLocationMap = models.getAvailableModels();

        // Update the dropdown UI
        modelDropdown.removeAllItems();
        modelLocationMap.forEach((k, v) -> logger.debug("Available modelName={} => location={}", k, v));
        if (modelLocationMap.isEmpty() || modelLocationMap.containsKey(Models.UNAVAILABLE)) {
            logger.error("No models discovered from LiteLLM or LiteLLM is unavailable.");
            modelDropdown.addItem("No Models Available");
            modelDropdown.setEnabled(false);
            disableButtons();
        } else {
            logger.debug("Populating dropdown with {} models.", modelLocationMap.size());
            // Populate dropdown with available models (excluding "-lite" ones)
            modelLocationMap.keySet().stream()
                    .filter(k -> !k.contains("-lite"))
                    .sorted()
                    .forEach(modelDropdown::addItem);
            modelDropdown.setEnabled(true);

            // Restore the last used model if possible
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
                    logger.warn("Last used model '{}' not found in available models (possibly due to policy change).", lastUsedModel);
                    // If the last used model isn't available (e.g., policy changed),
                    // select the first available item instead of leaving it blank.
                    if (modelDropdown.getItemCount() > 0) {
                        modelDropdown.setSelectedIndex(0);
                    }
                }
            } else {
                logger.debug("No last used model saved for this project.");
                // Select the first item if no previous selection exists
                 if (modelDropdown.getItemCount() > 0) {
                    modelDropdown.setSelectedIndex(0);
                }
            }
            enableButtons(); // Ensure buttons are enabled since models are available
        }
    }

    // --- Private Execution Logic ---

    /**
     * Executes the core logic for the "Code" command.
     * This runs inside the Runnable passed to contextManager.submitAction.
     */
    private void executeCodeCommand(StreamingChatLanguageModel model, String input) {
        var contextManager = chrome.getContextManager();
        var project = contextManager.getProject();
        project.pauseAnalyzerRebuilds();
        try {
            var result = CodeAgent.runSession(contextManager, model, input, true);
            contextManager.addToHistory(result, false);
        } finally {
            project.resumeAnalyzerRebuilds();
        }
    }

    /**
     * Executes the core logic for the "Ask" command.
     * This runs inside the Runnable passed to contextManager.submitAction.
     */
  private void executeAskCommand(StreamingChatLanguageModel model, String question) {
      try {
          var contextManager = chrome.getContextManager();
          if (question.isBlank()) {
              chrome.toolErrorRaw("Please provide a question");
              return;
          }
          // Provide the prompt messages
          var messages = new LinkedList<>(AskPrompts.instance.collectMessages(contextManager));
          messages.add(new UserMessage("<question>\n%s\n</question>".formatted(question.trim())));

          // stream from coder using the provided model
          var response = contextManager.getCoder(model, question).sendStreaming(messages, true);
          if (response.cancelled()) {
              chrome.systemOutput("Ask command cancelled!");
          } else if (response.error() != null) {
                 chrome.toolErrorRaw("Error during 'Ask': " + response.error().getMessage());
             } else if (response.chatResponse() != null && response.chatResponse().aiMessage() != null) {
                    var aiResponse = response.chatResponse().aiMessage();
                    // Check if the response is valid before adding to history
                    if (aiResponse.text() != null && !aiResponse.text().isBlank()) {
                        // Construct SessionResult for 'Ask'
                        var sessionResult = new CodeAgent.SessionResult(
                                "Ask: " + question, List.of(messages.getLast(), aiResponse),
                                Map.of(), // No original contents for Ask
                                chrome.getLlmOutputText(),
                                new CodeAgent.StopDetails(CodeAgent.StopReason.SUCCESS));
                        contextManager.addToHistory(sessionResult, false);
                    } else {
                        chrome.systemOutput("Ask command completed with an empty response.");
                    }
            } else {
                chrome.systemOutput("Ask command completed with no response data.");
            }
        } catch (CancellationException cex) {
             chrome.systemOutput("Ask command cancelled.");
         } catch (Exception e) {
             logger.error("Error during 'Ask' execution", e);
             chrome.toolErrorRaw("Internal error during ask command: " + e.getMessage());
         }
    }

    /**
     * Executes the core logic for the "Agent" command.
     * This runs inside the Runnable passed to contextManager.submitAction.
     * @param goal The initial user instruction passed to the agent.
     */
    private void executeAgentCommand(StreamingChatLanguageModel model, String goal) {
        var contextManager = chrome.getContextManager();
        try {
            // The plan existence check happens in runAgentCommand before submitting
            chrome.systemOutput("Architect engaged");
            var agent = new ArchitectAgent(contextManager, model, contextManager.getToolRegistry(), goal);
            agent.execute();
            chrome.systemOutput("Architect Agent finished executing"); // Final status on normal completion
        } catch (CancellationException cex) {
             chrome.systemOutput("Agent execution cancelled.");
        } catch (Exception e) {
             logger.error("Error during Agent execution", e);
             chrome.toolErrorRaw("Internal error during Agent command: " + e.getMessage());
        }
    }

    /**
     * Executes the core logic for the "Search" command.
     * This runs inside the Runnable passed to contextManager.submitAction.
     */
    private void executeSearchCommand(StreamingChatLanguageModel model, String query) {
         if (query.isBlank()) {
             chrome.toolErrorRaw("Please provide a search query");
             return;
         }
         try {
             var contextManager = chrome.getContextManager();
             // run a search agent, passing the specific model and tool registry
             // Pass chrome (IConsoleIO) instead of contextManager directly
             var agent = new SearchAgent(query, contextManager, model, contextManager.getToolRegistry());
             var result = agent.execute();
             if (result == null) {
                 // Agent execution was likely cancelled or errored, agent should log details
                 chrome.systemOutput("Search did not complete successfully.");
             } else {
                 chrome.clear();
                 String textResult = result.text();
                 chrome.llmOutput("# Query\n\n%s\n\n# Answer\n\n%s\n".formatted(query, textResult));
                 contextManager.addSearchFragment(result);
             }
         } catch (CancellationException cex) {
             chrome.systemOutput("Search command cancelled.");
         } catch (Exception e) {
             logger.error("Error during 'Search' execution", e);
             chrome.toolErrorRaw("Internal error during search command: " + e.getMessage());
         }
    }

    /**
     * Executes the core logic for the "Run in Shell" command.
     * This runs inside the Runnable passed to contextManager.submitAction.
     */
    private void executeRunCommand(String input) {
        var contextManager = chrome.getContextManager();
        var result = Environment.instance.captureShellCommand(input, contextManager.getRoot());
        String output = result.output().isBlank() ? "[operation completed with no output]" : result.output();
        chrome.llmOutput("\n```\n" + output + "\n```");

        var llmOutputText = chrome.getLlmOutputText();
        if (llmOutputText == null) {
            chrome.systemOutput("Interrupted!");
            return;
        }

        // Add to context history with the output text
        contextManager.pushContext(ctx -> {
            var runFrag = new ContextFragment.StringFragment(output, "Run " + input);
            var parsed = new ParsedOutput(llmOutputText, runFrag);
            return ctx.withParsedOutput(parsed, CompletableFuture.completedFuture("Run " + input));
        });
    }

    // --- Action Handlers ---

    public void runAgentCommand() {
        var selectedModel = getSelectedModel();
        if (selectedModel == null) {
            chrome.toolError("Please select a valid model from the dropdown.");
            return;
        }
        var contextManager = chrome.getContextManager();

        disableButtons();
        // Save model before submitting task
        var modelName = contextManager.getModels().nameOf(selectedModel);
        chrome.getProject().setLastUsedModel(modelName);
        // Get the goal from the input field
        var goal = commandInputField.getText();
        if (goal.isBlank()) {
            chrome.toolErrorRaw("Please provide an initial goal or instruction for the Agent.");
            enableButtons(); // Re-enable buttons since we are not proceeding
            return;
        }
        chrome.getProject().addToTextHistory(goal, 20);
        clearCommandInput();

        // Submit the action, calling the private execute method inside the lambda, passing the goal
        var future = contextManager.submitAction("Agent", "Executing project...", () -> executeAgentCommand(selectedModel, goal));
        chrome.setCurrentUserTask(future);
    }

    // Private helper to get the selected model.
    private StreamingChatLanguageModel getSelectedModel() {
        var selectedName = (String) modelDropdown.getSelectedItem();
        logger.debug("User selected model name from dropdown: {}", selectedName);
        if (selectedName == null || selectedName.startsWith("No Models") || selectedName.startsWith("Error")) {
            logger.warn("No valid model selected in dropdown.");
            return null;
        }
        var models = chrome.getContextManager().getModels();
        try {
            var model = models.get(selectedName); // Use instance method
            // Save the successfully selected model name
            if (chrome.getProject() != null && model != null) { // Check model is not null
                chrome.getProject().setLastUsedModel(selectedName); // Use the correct setter
            }
            return model;
        } catch (Exception e) {
            logger.error("Failed to get model instance for {}", selectedName, e);
            return null;
        }
    }

    // Methods for running commands. These prepare the input and model, then delegate
    // the core logic execution to contextManager.submitAction, which calls back
    // into the private execute* methods above.

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
        // Save model before submitting task
        var modelName = chrome.getContextManager().getModels().nameOf(selectedModel);
        chrome.getProject().setLastUsedModel(modelName);
        // Submit the action, calling the private execute method inside the lambda
        var future = chrome.getContextManager().submitAction("Code", input, () -> executeCodeCommand(selectedModel, input));
        chrome.setCurrentUserTask(future);
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
        // Save model before submitting task
        var modelName = chrome.getContextManager().getModels().nameOf(selectedModel);
        chrome.getProject().setLastUsedModel(modelName);
        // Submit the action, calling the private execute method inside the lambda
        var future = chrome.getContextManager().submitAction("Ask", input, () -> executeAskCommand(selectedModel, input));
        chrome.setCurrentUserTask(future);
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
        // Save model before submitting task
        var modelName = chrome.getContextManager().getModels().nameOf(selectedModel);
        chrome.getProject().setLastUsedModel(modelName);
        // Submit the action, calling the private execute method inside the lambda
        var future = chrome.getContextManager().submitAction("Search", input, () -> executeSearchCommand(selectedModel, input));
        chrome.setCurrentUserTask(future);
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
        // Submit the action, calling the private execute method inside the lambda
        var future = chrome.getContextManager().submitAction("Run", input, () -> executeRunCommand(input));
        chrome.setCurrentUserTask(future);
    }

    // Methods to disable and enable buttons.
     public void disableButtons() {
         SwingUtilities.invokeLater(() -> {
            agentButton.setEnabled(false); // Disable agent button
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
            agentButton.setEnabled(modelsAvailable); // Enable agent button based on model availability
             codeButton.setEnabled(modelsAvailable);
             askButton.setEnabled(modelsAvailable);
             searchButton.setEnabled(modelsAvailable);
            runButton.setEnabled(true);
            stopButton.setEnabled(false);
            micButton.setEnabled(true); // Enable mic button
        });
    }
}
