package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.Analyzer;
import io.github.jbellis.brokk.CodeUnit;
import io.github.jbellis.brokk.Context;
import io.github.jbellis.brokk.ContextFragment;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.Models;
import io.github.jbellis.brokk.Project;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;

public class Chrome implements AutoCloseable, IConsoleIO {
    private static final Logger logger = LogManager.getLogger(Chrome.class);
    private final String BGTASK_EMPTY = "No background tasks";

    // Dependencies:
    final ContextManager contextManager;
    private final Project project;

    // Swing components:
    final JFrame frame;
    private RSyntaxTextArea llmStreamArea;
    private JLabel commandResultLabel;
    private JTextArea commandInputField;
    private JLabel backgroundStatusLabel;
    
    // Context History Panel
    private JTable contextHistoryTable;
    private DefaultTableModel contextHistoryModel;
    
    // Track the horizontal split that holds the history panel
    private JSplitPane historySplitPane;
    private JSplitPane verticalSplitPane;

    // Capture panel buttons
    private JButton captureTextButton;
    private JButton editReferencesButton;

    // Context Panel:
    private ContextPanel contextPanel;

    // Buttons for the command input panel:
    private JButton codeButton;  // renamed from goButton
    private JButton askButton;
    private JButton searchButton;
    private JButton runButton;
    private JButton stopButton;  // cancels the current user-driven task

    // Track the currently running user-driven future (Code/Ask/Search/Run)
    volatile Future<?> currentUserTask;
    private JScrollPane llmScrollPane;
    JTextArea captureDescriptionArea;

    /**
     * Enum representing the different types of context actions that can be performed.
     * This replaces the use of magic strings when calling performContextActionAsync.
     */
    public enum ContextAction {
        EDIT, READ, SUMMARIZE, DROP, COPY, PASTE
    }

    /**
     * Default constructor sets up the UI.
     * We call this from Brokk after creating contextManager, commands, etc.,
     * but before calling .resolveCircularReferences(...).
     */
    public Chrome(ContextManager contextManager) {
        this.contextManager = contextManager;
        this.project = contextManager.getProject();

        // 1) Set FlatLaf Look & Feel
        try {
            com.formdev.flatlaf.FlatLightLaf.setup();
        } catch (Exception e) {
            logger.warn("Failed to set LAF, using default", e);
        }

        // 2) Build main window
        frame = new JFrame("Brokk: Code Intelligence for AI");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(800, 1200);  // Taller than wide
        frame.setLayout(new BorderLayout());

        // 3) Main panel (top area + bottom area)
        frame.add(buildMainPanel(), BorderLayout.CENTER);

        // 4) Build menu
        frame.setJMenuBar(MenuBar.buildMenuBar(this));

        // 5) Register global keyboard shortcuts
        registerGlobalKeyboardShortcuts();

        // 6) Load saved window size and position, then show window
        loadWindowSizeAndPosition();

        frame.setVisible(true);
        // this gets it to respect the minimum size on buttons panel, fuck it
        frame.validate();
        frame.repaint();

        // Set focus to command input field on startup
        commandInputField.requestFocusInWindow();

        // Add listener to save window size and position when they change
        frame.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                project.saveMainWindowBounds(frame);
            }

            @Override
            public void componentMoved(java.awt.event.ComponentEvent e) {
                project.saveMainWindowBounds(frame);
            }
        });
    }

    /**
     * Build the main panel that includes:
     *  - the LLM stream (top)
     *  - the command result label
     *  - the command input
     *  - the context panel
     *  - the background status label at bottom
     */
    private JPanel buildMainPanel() {
        var panel = new JPanel(new BorderLayout());

        var contentPanel = new JPanel(new GridBagLayout());
        var gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 1.0;
        gbc.gridx = 0;
        gbc.insets = new Insets(2, 2, 2, 2);

        // 1. LLM streaming area
        // LLM streaming area in titled panel
        var outputPanel = new JPanel(new BorderLayout());
        outputPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Output",
                javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
                javax.swing.border.TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)
        ));

        llmScrollPane = buildLLMStreamScrollPane();
        outputPanel.add(llmScrollPane, BorderLayout.CENTER);

        // Build the history panel, but don't add it to the split pane yet
        // We'll do this after we know the button size
        var contextHistoryPanel = buildContextHistoryPanel();

        // Store this horizontal split in our class field
        this.historySplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        historySplitPane.setLeftComponent(outputPanel);
        historySplitPane.setRightComponent(contextHistoryPanel);
        historySplitPane.setResizeWeight(0.8); // 80% to output, 20% to history

        // Create a split pane with output+history in top and command+context+status in bottom
        verticalSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        verticalSplitPane.setTopComponent(historySplitPane);
        
        // Create a panel for everything below the output area
        var bottomPanel = new JPanel(new GridBagLayout());
        var bottomGbc = new GridBagConstraints();
        bottomGbc.fill = GridBagConstraints.BOTH;
        bottomGbc.weightx = 1.0;
        bottomGbc.gridx = 0;
        bottomGbc.insets = new Insets(2, 2, 2, 2);
        
        // We will size the history panel after the frame is actually displayed
        SwingUtilities.invokeLater(this::setInitialHistoryPanelWidth);

        // 2. Command result label
        var resultLabel = buildCommandResultLabel();
        bottomGbc.weighty = 0.0;
        bottomGbc.gridy = 0;
        bottomPanel.add(resultLabel, bottomGbc);

        // 3. Command input with prompt
        var commandPanel = buildCommandInputPanel();
        bottomGbc.gridy = 1;
        bottomPanel.add(commandPanel, bottomGbc);

        // 4. Context panel (with border title)
        var ctxPanel = buildContextPanel();
        bottomGbc.weighty = 0.2;
        bottomGbc.gridy = 2;
        bottomPanel.add(ctxPanel, bottomGbc);

        // 5. Background status label at bottom
        var statusLabel = buildBackgroundStatusLabel();
        bottomGbc.weighty = 0.0;
        bottomGbc.gridy = 3;
        bottomPanel.add(statusLabel, bottomGbc);
        
        verticalSplitPane.setBottomComponent(bottomPanel);
        
        // Add the vertical split pane to the content panel
        gbc.weighty = 1.0;
        gbc.gridy = 0;
        contentPanel.add(verticalSplitPane, gbc);

        // Set initial divider position - 60% for output area, 40% for bottom
        SwingUtilities.invokeLater(() -> {
            int height = frame.getHeight();
            verticalSplitPane.setDividerLocation((int)(height * 0.6));
        });

        panel.add(contentPanel, BorderLayout.CENTER);
        return panel;
    }

    /**
     * Builds the Context History panel that shows past contexts
     */
    private JPanel buildContextHistoryPanel() {
        // Create a parent panel to contain both history and capture panels
        var parentPanel = new JPanel(new BorderLayout());
        
        // Create history panel
        var panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Context History",
                javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
                javax.swing.border.TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)
        ));

        // Create table model with columns - just one visible column
        contextHistoryModel = new DefaultTableModel(
                new Object[]{"", "Context"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        contextHistoryTable = new JTable(contextHistoryModel);
        contextHistoryTable.setFont(new Font(Font.DIALOG, Font.PLAIN, 12));
        contextHistoryTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        
        // Remove table header
        contextHistoryTable.setTableHeader(null);
        
        // Set up multi-line cell renderer for the first column
        contextHistoryTable.getColumnModel().getColumn(0).setCellRenderer(new MultiLineCellRenderer(this));
        
        // Set up column rendering for LLM conversation rows
        contextHistoryTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {

                Component c = super.getTableCellRendererComponent(
                        table, value, isSelected, hasFocus, row, column);

                if (row < contextManager.getContextHistory().size()) {
                    var ctx = contextManager.getContextHistory().get(row);
                    if (ctx.getTextAreaContents() != null) {
                    // LLM conversation - use dark background
                    if (!isSelected) {
                        c.setBackground(new Color(50, 50, 50));
                        c.setForeground(new Color(220, 220, 220));
                    }
                } else {
                    // Regular context - use normal colors
                    if (!isSelected) {
                        c.setBackground(table.getBackground());
                        c.setForeground(table.getForeground());
                    }
                }
                }

                return c;
            }
        });

        // Add selection listener to preview context
        contextHistoryTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int row = contextHistoryTable.getSelectedRow();
                if (row >= 0 && row < contextManager.getContextHistory().size()) {
                    var ctx = contextManager.getContextHistory().get(row);
                    previewContextFromHistory(ctx);
                }
            }
        });
        
        // Add right-click context menu for history operations
        contextHistoryTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showContextHistoryPopupMenu(e);
                }
            }
            
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showContextHistoryPopupMenu(e);
                }
            }
        });

        // Adjust column widths - hide the context object column
        contextHistoryTable.getColumnModel().getColumn(0).setPreferredWidth(150);
        contextHistoryTable.getColumnModel().getColumn(1).setMinWidth(0);
        contextHistoryTable.getColumnModel().getColumn(1).setMaxWidth(0);
        contextHistoryTable.getColumnModel().getColumn(1).setWidth(0);

        // Add table to scroll pane
        var scrollPane = new JScrollPane(contextHistoryTable);

        // Add undo/redo buttons at the bottom
        var buttonPanel = new JPanel(new GridLayout(2, 1, 0, 5));
        buttonPanel.setBorder(new EmptyBorder(5, 5, 5, 5));

        var undoButton = new JButton("Undo");
        undoButton.setMnemonic(KeyEvent.VK_Z);
        undoButton.setToolTipText("Undo the most recent history entry");
        undoButton.addActionListener(e -> {
            disableUserActionButtons();
            disableContextActionButtons();
            currentUserTask = contextManager.undoContextAsync();
        });

        var redoButton = new JButton("Redo");
        redoButton.setMnemonic(KeyEvent.VK_Y);
        redoButton.setToolTipText("Redo the most recently undone entry");
        redoButton.addActionListener(e -> {
            disableUserActionButtons();
            disableContextActionButtons();
            currentUserTask = contextManager.redoContextAsync();
        });

        buttonPanel.add(undoButton);
        buttonPanel.add(redoButton);
        
        panel.add(scrollPane, BorderLayout.CENTER);
        panel.add(buttonPanel, BorderLayout.SOUTH);

        // Create capture output panel
        var capturePanel = buildCaptureOutputPanel();

        // Add both panels to parent with a vertical split
        var splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        splitPane.setTopComponent(panel);
        splitPane.setBottomComponent(capturePanel);
        splitPane.setResizeWeight(0.7); // 70% to history, 30% to capture

        parentPanel.add(splitPane, BorderLayout.CENTER);

        return parentPanel;
    }
    
    /**
     * Previews a context from history without fully restoring it
     */
    private void previewContextFromHistory(Context ctx) {
        assert ctx != null;
        loadContext(ctx);
    }

    /**
     * Lightweight method to preview a context without updating history
     * Only updates the LLM text area and context panel display
     */
    public void loadContext(Context ctx) {
        assert ctx != null;

        SwingUtilities.invokeLater(() -> {
            // Don't clear history selection when previewing
            contextPanel.populateContextTable(ctx);

            // If there's textarea content, restore it to the LLM output area
            if (ctx.getTextAreaContents() == null) {
                llmStreamArea.setText("");
            } else {
                llmStreamArea.setText(ctx.getTextAreaContents());
                llmStreamArea.setCaretPosition(0);
                if (ctx.getTextAreaContents().startsWith("Code:")) {
                    llmStreamArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JAVA);
                }
                // Ensure the scroll pane displays from the top
                SwingUtilities.invokeLater(() -> {
                    llmScrollPane.getVerticalScrollBar().setValue(0);
                });
            }

            updateCaptureButtons(ctx);
        });
    }

    /**
     * Shows the context menu for the context history table
     */
    private void showContextHistoryPopupMenu(MouseEvent e) {
        int row = contextHistoryTable.rowAtPoint(e.getPoint());
        if (row < 0) return;
        
        // Select the row under the cursor
        contextHistoryTable.setRowSelectionInterval(row, row);
        
        // Create popup menu
        JPopupMenu popup = new JPopupMenu();
        JMenuItem undoToHereItem = new JMenuItem("Undo to here");
        undoToHereItem.addActionListener(event -> restoreContextFromHistory(row));
        popup.add(undoToHereItem);
        
        // Show popup menu
        popup.show(contextHistoryTable, e.getX(), e.getY());
    }
    
    /**
     * Restore context to a specific point in history
     */
    private void restoreContextFromHistory(int index) {
        int currentIndex = contextManager.getContextHistory().size() - 1;
        if (index < currentIndex) {
            disableUserActionButtons();
            disableContextActionButtons();
            int stepsToUndo = currentIndex - index;
            currentUserTask = contextManager.undoContextAsync(stepsToUndo);
        }
    }

    /**
     * Gets the current text from the LLM output area
     */
    public String getLlmOutputText() {
        return llmStreamArea.getText();
    }

    private JScrollPane buildLLMStreamScrollPane() {
        llmStreamArea = new RSyntaxTextArea();
        llmStreamArea.setEditable(false);
        // Initial welcome message is Markdown
        llmStreamArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_MARKDOWN);
        var caret = (DefaultCaret) llmStreamArea.getCaret();
        caret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        llmStreamArea.setLineWrap(true);
        llmStreamArea.setWrapStyleWord(true);
        llmStreamArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));

        var jsp = new JScrollPane(llmStreamArea);
        new SmartScroll(jsp);
        return jsp;
    }

    /**
     * Creates the command result label used to display messages.
     */
    private JComponent buildCommandResultLabel() {
        commandResultLabel = new JLabel(" ");
        commandResultLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        commandResultLabel.setBorder(new EmptyBorder(5, 10, 5, 10));
        commandResultLabel.setOpaque(true);
        commandResultLabel.setBackground(new Color(245, 245, 245));
        return commandResultLabel;
    }

    /**
     * Creates the bottom-most background status label that shows "Working on: ..." or is blank when idle.
     */
    private JComponent buildBackgroundStatusLabel() {
        backgroundStatusLabel = new JLabel(BGTASK_EMPTY);
        backgroundStatusLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        backgroundStatusLabel.setBorder(new EmptyBorder(3, 10, 3, 10));
        backgroundStatusLabel.setOpaque(true);
        backgroundStatusLabel.setBackground(new Color(240, 240, 240));
        // Add a line border above
        backgroundStatusLabel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY),
                new EmptyBorder(5, 10, 5, 10)
        ));
        return backgroundStatusLabel;
    }

    /**
     * Creates a panel with a single-line text field for commands, plus “Go / Ask / Search” on the left and a “Stop” button on the right.
     * The panel is titled "Instructions".
     */
    private JPanel buildCommandInputPanel() {
        var wrapper = new JPanel(new BorderLayout());
        wrapper.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(
                        BorderFactory.createEtchedBorder(),
                        "Instructions",
                        javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
                        javax.swing.border.TitledBorder.DEFAULT_POSITION,
                        new Font(Font.DIALOG, Font.BOLD, 12)
                ),
                new EmptyBorder(5, 5, 5, 5)
        ));

        commandInputField = new JTextArea(3, 40);
        commandInputField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        commandInputField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.GRAY),
                BorderFactory.createEmptyBorder(2, 5, 2, 5)
        ));
        commandInputField.setLineWrap(true);
        commandInputField.setWrapStyleWord(true);
        commandInputField.setRows(3);
        commandInputField.setMinimumSize(new Dimension(100, 80));

        // Create a JScrollPane for the text area
        JScrollPane commandScrollPane = new JScrollPane(commandInputField);
        commandScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        commandScrollPane.setPreferredSize(new Dimension(600, 80)); // Set preferred height for 3 lines
        commandScrollPane.setMinimumSize(new Dimension(100, 80));

        // Emacs-like keybindings
        wrapper.add(commandScrollPane, BorderLayout.CENTER);

        // Left side: code/ask/search/run
        var leftButtonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
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
        searchButton.setToolTipText("Explore the codebase to find answers that are NOT in the current context");
        searchButton.addActionListener(e -> runSearchCommand());

        runButton = new JButton("Run in Shell");
        runButton.setMnemonic(KeyEvent.VK_R);
        runButton.setToolTipText("Execute the current instructions as a shell command");
        runButton.addActionListener(e -> runRunCommand());

        leftButtonsPanel.add(codeButton);
        leftButtonsPanel.add(askButton);
        leftButtonsPanel.add(searchButton);
        leftButtonsPanel.add(runButton);

        // Right side: stop
        var rightButtonsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        stopButton = new JButton("Stop");
        stopButton.setToolTipText("Cancel the current operation");
        stopButton.addActionListener(e -> stopCurrentUserTask());
        rightButtonsPanel.add(stopButton);

        // We'll place left and right panels in a horizontal layout
        var buttonsHolder = new JPanel(new BorderLayout());
        buttonsHolder.add(leftButtonsPanel, BorderLayout.WEST);
        buttonsHolder.add(rightButtonsPanel, BorderLayout.EAST);

        // Add text area at top, buttons panel below
        wrapper.add(commandScrollPane, BorderLayout.CENTER);
        wrapper.add(buttonsHolder, BorderLayout.SOUTH);

        // Set "Go" as default
        frame.getRootPane().setDefaultButton(codeButton);

        return wrapper;
    }

    /**
     * Invoked on "Code" button or pressing Enter in the command input field.
     */
    private void runCodeCommand() {
        var input = commandInputField.getText();
        if (input.isBlank()) {
            toolErrorRaw("Please enter a command or text");
            return;
        }

        // Check if LLM is available
        if (!contextManager.getCoder().isLlmAvailable()) {
            toolError("No LLM available (missing API keys)");
            return;
        }

        llmStreamArea.setText("Code: " + commandInputField.getText() + "\n\n");
        commandInputField.setText("");
        llmStreamArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JAVA);

        disableUserActionButtons();
        // schedule in ContextManager
        currentUserTask = contextManager.runCodeCommandAsync(input);
    }

    /**
     * Invoked on "Run" button
     */
    private void runRunCommand() {
        var input = commandInputField.getText();
        if (input.isBlank()) {
            toolError("Please enter a command to run");
        }

        llmStreamArea.setText("Run: " + commandInputField.getText() + "\n\n");
        commandInputField.setText("");
        llmStreamArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);

        disableUserActionButtons();
        currentUserTask = contextManager.runRunCommandAsync(input);
    }

    /**
     * Invoked on "Ask" button
     */
    private void runAskCommand() {
        var input = commandInputField.getText();
        if (input.isBlank()) {
            toolErrorRaw("Please enter a question");
            return;
        }
        
        // Check if LLM is available
        if (!contextManager.getCoder().isLlmAvailable()) {
            toolError("No LLM available (missing API keys)");
            return;
        }
        
        llmStreamArea.setText("Ask: " + commandInputField.getText() + "\n\n");
        commandInputField.setText("");
        llmStreamArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);

        disableUserActionButtons();
        currentUserTask = contextManager.runAskAsync(input);
    }

    /**
     * Invoked on "Search" button
     */
    private void runSearchCommand() {
        var input = commandInputField.getText();
        if (input.isBlank()) {
            toolErrorRaw("Please provide a search query");
            return;
        }
        
        // Check if LLM is available
        if (!contextManager.getCoder().isLlmAvailable()) {
            toolError("No LLM available (missing API keys)");
            return;
        }
        
        llmStreamArea.setText("Search: " + commandInputField.getText() + "\n\n");
        commandInputField.setText("");
        llmStreamArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);

        disableUserActionButtons();
        currentUserTask = contextManager.runSearchAsync(input);
    }

    public void setOutputSyntax(String syntaxType) {
        SwingUtilities.invokeLater(() -> {
            llmStreamArea.setSyntaxEditingStyle(syntaxType);
        });
    }

    @Override
    public void clear() {
        llmStreamArea.setText("");
    }

    /**
     * Disables "Go/Ask/Search" to prevent overlapping tasks, until re-enabled
     */
    void disableUserActionButtons() {
        SwingUtilities.invokeLater(() -> {
            codeButton.setEnabled(false);
            askButton.setEnabled(false);
            searchButton.setEnabled(false);
            runButton.setEnabled(false);
            stopButton.setEnabled(true);
        });
    }

    /**
     * Re-enables "Go/Ask/Search" when the task completes or is canceled
     */
    public void enableUserActionButtons() {
        SwingUtilities.invokeLater(() -> {
            codeButton.setEnabled(true);
            askButton.setEnabled(true);
            searchButton.setEnabled(true);
            runButton.setEnabled(true);
            stopButton.setEnabled(false);
            updateSuggestCommitButton();
        });
    }

    /**
     * Disables the context action buttons while an action is in progress
     */
    void disableContextActionButtons() {
        contextPanel.disableContextActionButtons();
    }

    /**
     * Re-enables context action buttons
     */
    public void enableContextActionButtons() {
        contextPanel.enableContextActionButtons();
    }

    /**
     * Cancels the currently running user-driven Future (Go/Ask/Search), if any
     */
    private void stopCurrentUserTask() {
        if (currentUserTask != null && !currentUserTask.isDone()) {
            currentUserTask.cancel(true);
        }
    }

    /**
     * Build the context panel (unified table + action buttons).
     */
    private JPanel buildContextPanel() {
        contextPanel = new ContextPanel(this, contextManager);
        return contextPanel;
    }

    // Moved to ContextPanel class

    // Moved to ContextPanel class

    /**
     * Updates the uncommitted files table and the state of the suggest commit button
     */
    private void updateSuggestCommitButton() {
        contextPanel.updateSuggestCommitButton();
    }

    /**
     * Registers global keyboard shortcuts for undo/redo
     */
    private void registerGlobalKeyboardShortcuts() {
        var rootPane = frame.getRootPane();

        // Ctrl+Z => undo
        var undoKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK);
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(undoKeyStroke, "globalUndo");
        rootPane.getActionMap().put("globalUndo", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                disableUserActionButtons();
                disableContextActionButtons();
                currentUserTask = contextManager.undoContextAsync();
            }
        });

        // Ctrl+Shift+Z => redo
        var redoKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Z,
                                                   InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(redoKeyStroke, "globalRedo");
        rootPane.getActionMap().put("globalRedo", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                disableUserActionButtons();
                disableContextActionButtons();
                currentUserTask = contextManager.redoContextAsync();
            }
        });

        // Ctrl+V => paste
        var pasteKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK);
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(pasteKeyStroke, "globalPaste");
        rootPane.getActionMap().put("globalPaste", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                currentUserTask = contextManager.performContextActionAsync(ContextAction.PASTE, List.of());
            }
        });
    }

    /**
     * Shows a dialog for editing LLM API secret keys.
     */
    void showSecretKeysDialog() {
        if (project == null) {
            toolErrorRaw("Project not available");
            return;
        }

        // Reuse the existing code for editing keys
        // unchanged from original example
        JDialog dialog = new JDialog(frame, "Edit LLM API Keys", true);
        dialog.setLayout(new BorderLayout());

        // main panel
        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        var existingKeys = project.getLlmKeys();
        List<KeyValueRowPanel> keyRows = new ArrayList<>();

        // if empty, add one row
        if (existingKeys.isEmpty()) {
            var row = new KeyValueRowPanel(Models.defaultKeyNames);
            keyRows.add(row);
            mainPanel.add(row);
        }

        // Actually, we want multiple rows in a vertical layout
        var keysPanel = new JPanel();
        keysPanel.setLayout(new BoxLayout(keysPanel, BoxLayout.Y_AXIS));

        if (!existingKeys.isEmpty()) {
            for (var entry : existingKeys.entrySet()) {
                var row = new KeyValueRowPanel(Models.defaultKeyNames, entry.getKey(), entry.getValue());
                keyRows.add(row);
                keysPanel.add(row);
            }
        } else {
            var row = new KeyValueRowPanel(Models.defaultKeyNames, "", "");
            keyRows.add(row);
            keysPanel.add(row);
        }

        var scrollPane = new JScrollPane(keysPanel);
        scrollPane.setPreferredSize(new Dimension((int) (frame.getWidth() * 0.9), 250));
        mainPanel.add(scrollPane, BorderLayout.CENTER);

        // Add/Remove
        var addRemovePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        var addButton = new JButton("Add Key");
        addButton.addActionListener(ev -> {
            var newRow = new KeyValueRowPanel(Models.defaultKeyNames);
            keyRows.add(newRow);
            keysPanel.add(newRow);
            keysPanel.revalidate();
            keysPanel.repaint();
        });

        var removeButton = new JButton("Remove Last Key");
        removeButton.addActionListener(ev -> {
            if (!keyRows.isEmpty()) {
                var last = keyRows.remove(keyRows.size() - 1);
                keysPanel.remove(last);
                keysPanel.revalidate();
                keysPanel.repaint();
            }
        });
        addRemovePanel.add(addButton);
        addRemovePanel.add(removeButton);
        mainPanel.add(addRemovePanel, BorderLayout.NORTH);

        // OK/Cancel
        var actionButtonsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        var okButton = new JButton("OK");
        var cancelButton = new JButton("Cancel");

        okButton.addActionListener(ev -> {
            var newKeys = new java.util.HashMap<String, String>();
            boolean hasEmptyKey = false;

            for (var row : keyRows) {
                var key = row.getKeyName();
                var value = row.getKeyValue();
                if (key.isBlank() && value.isBlank()) {
                    continue;
                }
                if (key.isBlank()) {
                    hasEmptyKey = true;
                    continue;
                }
                newKeys.put(key, value);
            }

            if (hasEmptyKey) {
                JOptionPane.showMessageDialog(dialog,
                                              "Some keys have empty names and will be skipped.",
                                              "Warning",
                                              JOptionPane.WARNING_MESSAGE);
            }

            project.saveLlmKeys(newKeys);
            toolOutput("Saved " + newKeys.size() + " API keys");
            dialog.dispose();
        });

        cancelButton.addActionListener(ev -> dialog.dispose());

        actionButtonsPanel.add(okButton);
        actionButtonsPanel.add(cancelButton);
        mainPanel.add(actionButtonsPanel, BorderLayout.SOUTH);

        dialog.add(mainPanel);
        dialog.getRootPane().setDefaultButton(okButton);
        dialog.getRootPane().registerKeyboardAction(
                event -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW
        );
        dialog.pack();
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
    }

    /**
     * For the IConsoleIO interface, sets the text in commandResultLabel. Safe to call from any thread.
     */
    @Override
    public void toolOutput(String msg) {
        SwingUtilities.invokeLater(() -> {
            commandResultLabel.setText(msg);
            logger.info(msg);
        });
    }

    @Override
    public void toolErrorRaw(String msg) {
        SwingUtilities.invokeLater(() -> {
            commandResultLabel.setText(msg);
            logger.warn(msg);
        });
    }

    @Override
    public void llmOutput(String token) {
        SwingUtilities.invokeLater(() -> {
            llmStreamArea.append(token);
        });
    }

    @Override
    public void shellOutput(String message) {
        if (!llmStreamArea.getText().endsWith("\n\n")) {
            llmStreamArea.append("\n");
        }
        llmStreamArea.append(message);
    }

    @Override
    public void spin(String message) {
        SwingUtilities.invokeLater(() -> backgroundStatusLabel.setText(message));
    }

    @Override
    public void spinComplete() {
        SwingUtilities.invokeLater(() -> backgroundStatusLabel.setText(BGTASK_EMPTY));
    }

    @Override
    public boolean isSpinning() {
        return !backgroundStatusLabel.getText().equals(BGTASK_EMPTY);
    }

    /**
     * Repopulate the unified context table from the given context.
     */
    public void setContext(Context context) {
        SwingUtilities.invokeLater(() -> {
            contextPanel.populateContextTable(context);
            clearContextHistorySelection();
            updateContextHistoryTable();
            updateSuggestCommitButton();
        });
    }

    @Override
    public void close() {
        logger.info("Closing Chrome UI");
        contextManager.shutdown();
        if (frame != null) {
            frame.dispose();
        }
    }


    /**
     * Opens a preview window for a context fragment
     * @param fragment The fragment to preview
     */
    public void openFragmentPreview(ContextFragment fragment) {
        String text;
        try {
            text = fragment.text();
        } catch (IOException e) {
            contextManager.removeBadFragment(fragment, e);
            return;
        }
        SwingUtilities.invokeLater(() -> {
            // Create a new window
            var previewFrame = new JFrame(fragment.description());
            previewFrame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

            // Create syntax text area
            var textArea = new RSyntaxTextArea();
            textArea.setText(text);
            textArea.setEditable(false);
            textArea.setCodeFoldingEnabled(true);
            textArea.setSyntaxEditingStyle(getSyntaxStyleForFragment(fragment));

            try {
                Theme.load(getClass().getResourceAsStream(
                                "/org/fife/ui/rsyntaxtextarea/themes/default.xml"))
                        .apply(textArea);
            } catch (Exception e) {
                logger.warn("Could not load theme for preview", e);
            }

            var scrollPane = new JScrollPane(textArea);
            previewFrame.add(scrollPane);

            // Ensure text area starts scrolled to the top
            textArea.setCaretPosition(0);

            // Set window size from saved properties
            Rectangle bounds = project.getPreviewWindowBounds();
            previewFrame.setSize(bounds.width, bounds.height);

            // Position the window, checking if position is valid
            if (bounds.x >= 0 && bounds.y >= 0 && isPositionOnScreen(bounds.x, bounds.y)) {
                previewFrame.setLocation(bounds.x, bounds.y);
            } else {
                previewFrame.setLocationRelativeTo(frame);
            }

            // Add component listener to save position
            previewFrame.addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent e) {
                    project.savePreviewWindowBounds(previewFrame);
                }

                @Override
                public void componentMoved(java.awt.event.ComponentEvent e) {
                    project.savePreviewWindowBounds(previewFrame);
                }
            });

            // Add Escape key to close
            previewFrame.getRootPane().registerKeyboardAction(
                    e -> previewFrame.dispose(),
                    KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                    JComponent.WHEN_IN_FOCUSED_WINDOW
            );

            previewFrame.setVisible(true);
        });
    }

    /**
     * Determines the appropriate syntax style for a fragment
     */
    private String getSyntaxStyleForFragment(ContextFragment fragment) {
        // Default to Java
        var style = SyntaxConstants.SYNTAX_STYLE_JAVA;

        // Check fragment path if it's a file
        if (fragment instanceof ContextFragment.RepoPathFragment) {
            var path = ((ContextFragment.RepoPathFragment) fragment).file().getFileName().toLowerCase();

            if (path.endsWith(".md") || path.endsWith(".markdown")) {
                style = SyntaxConstants.SYNTAX_STYLE_MARKDOWN;
            } else if (path.endsWith(".py")) {
                style = SyntaxConstants.SYNTAX_STYLE_PYTHON;
            } else if (path.endsWith(".js")) {
                style = SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT;
            } else if (path.endsWith(".html") || path.endsWith(".htm")) {
                style = SyntaxConstants.SYNTAX_STYLE_HTML;
            } else if (path.endsWith(".xml")) {
                style = SyntaxConstants.SYNTAX_STYLE_XML;
            } else if (path.endsWith(".json")) {
                style = SyntaxConstants.SYNTAX_STYLE_JSON;
            } else if (path.endsWith(".css")) {
                style = SyntaxConstants.SYNTAX_STYLE_CSS;
            } else if (path.endsWith(".sql")) {
                style = SyntaxConstants.SYNTAX_STYLE_SQL;
            } else if (path.endsWith(".sh") || path.endsWith(".bash")) {
                style = SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL;
            } else if (path.endsWith(".c") || path.endsWith(".h")) {
                style = SyntaxConstants.SYNTAX_STYLE_C;
            } else if (path.endsWith(".cpp") || path.endsWith(".hpp") ||
                    path.endsWith(".cc") || path.endsWith(".hh")) {
                style = SyntaxConstants.SYNTAX_STYLE_CPLUSPLUS;
            } else if (path.endsWith(".properties")) {
                style = SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE;
            } else if (path.endsWith(".kt")) {
                style = SyntaxConstants.SYNTAX_STYLE_KOTLIN;
            }
        }

        return style;
    }

    /**
     * Loads window size and position from project properties
     */
    private void loadWindowSizeAndPosition() {
        assert project != null;
        Rectangle bounds = project.getMainWindowBounds();

        // Only apply saved values if they're valid
        if (bounds.width > 0 && bounds.height > 0) {
            frame.setSize(bounds.width, bounds.height);

            // Only use the position if it was actually set (not -1)
            if (bounds.x >= 0 && bounds.y >= 0 && isPositionOnScreen(bounds.x, bounds.y)) {
                frame.setLocation(bounds.x, bounds.y);
            } else {
                // If not on a visible screen, center the window
                frame.setLocationRelativeTo(null);
            }
        } else {
            // If no valid size is saved, center the window
            frame.setLocationRelativeTo(null);
        }
    }

    /**
     * Updates the context history table with the current context history
     */
    public void updateContextHistoryTable() {
        SwingUtilities.invokeLater(() -> {
            contextHistoryModel.setRowCount(0);

            // Add rows for each context in history
            for (var ctx : contextManager.getContextHistory()) {
                contextHistoryModel.addRow(new Object[]{
                        ctx.getAction(),
                        ctx // We store the actual context object in hidden column
                });
            }
            
            // Update row heights based on content
            for (int row = 0; row < contextHistoryTable.getRowCount(); row++) {
                adjustRowHeight(row);
            }
        });
    }
    
    /**
     * Adjusts the height of a row based on its content
     */
    private void adjustRowHeight(int row) {
        if (row >= contextHistoryTable.getRowCount()) return;
        
        // Get the cell renderer component for the visible column
        var renderer = contextHistoryTable.getCellRenderer(row, 0);
        var comp = contextHistoryTable.prepareRenderer(renderer, row, 0);
        
        // Calculate the preferred height
        int preferredHeight = comp.getPreferredSize().height;
        preferredHeight = Math.max(preferredHeight, 20); // Minimum height
        
        // Set the row height if it differs from current height
        if (contextHistoryTable.getRowHeight(row) != preferredHeight) {
            contextHistoryTable.setRowHeight(row, preferredHeight);
        }
    }

    public void clearContextHistorySelection() {
        SwingUtilities.invokeLater(() -> {
            contextHistoryTable.clearSelection();
        });
    }
    
    /**
     * Gets the context history table for selection checks
     */
    public JTable getContextHistoryTable() {
        assert SwingUtilities.isEventDispatchThread() : "Not on EDT";
        return contextHistoryTable;
    }

    /**
     * Checks if a position is on any available screen
     */
    private boolean isPositionOnScreen(int x, int y) {
        for (var screen : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            for (var config : screen.getConfigurations()) {
                if (config.getBounds().contains(x, y)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Builds the "Capture Output" panel with a 5-line references area
     * and two full-width buttons stacked at the bottom.
     */
    private JPanel buildCaptureOutputPanel() {
        var panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Capture Output",
                javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
                javax.swing.border.TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)
        ));

        // 1) Multiline references text area (5 rows).
        //    Wrap it in a scroll pane so if references exceed 5 lines, a scrollbar appears.
        captureDescriptionArea = new JTextArea("Files referenced: None", 5, 50);
        captureDescriptionArea.setEditable(false);
        captureDescriptionArea.setLineWrap(true);
        captureDescriptionArea.setWrapStyleWord(true);
        captureDescriptionArea.setFont(new Font(Font.DIALOG, Font.PLAIN, 12));

        // Create scroll pane for references area
        var referencesScrollPane = new JScrollPane(captureDescriptionArea,
                                                   JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                                                   JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        // Make it expand to full width
        referencesScrollPane.setAlignmentX(Component.CENTER_ALIGNMENT);
        referencesScrollPane.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                                                          captureDescriptionArea.getPreferredSize().height));

        // 2) Add the references area at the top
        panel.add(referencesScrollPane);

        // 3) Add "glue" so everything below is pushed to the bottom
        panel.add(Box.createVerticalGlue());

        // 4) "Capture Text" button, full width
        captureTextButton = new JButton("Capture Text");
        captureTextButton.setMnemonic(KeyEvent.VK_T);
        captureTextButton.setToolTipText("Capture the output as context");
        captureTextButton.addActionListener(e -> {
            contextManager.captureTextFromContextAsync();
        });
        captureTextButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        captureTextButton.setMaximumSize(
                new Dimension(Integer.MAX_VALUE, captureTextButton.getPreferredSize().height)
        );

        // 5) "Edit References" button, full width
        editReferencesButton = new JButton("Edit References");
        editReferencesButton.setToolTipText("Edit the files referenced by the output");
        editReferencesButton.setMnemonic(KeyEvent.VK_F);
        editReferencesButton.setEnabled(false);
        editReferencesButton.addActionListener(e -> {
            contextManager.editFilesFromContextAsync();
        });
        editReferencesButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        editReferencesButton.setMaximumSize(
                new Dimension(Integer.MAX_VALUE, editReferencesButton.getPreferredSize().height)
        );

        // 6) Stack both buttons at the bottom
        panel.add(captureTextButton);
        panel.add(Box.createVerticalStrut(5));  // small gap
        panel.add(editReferencesButton);

        // 7) Add a DocumentListener to the main llmStreamArea so these buttons
        //    update when that text changes
        llmStreamArea.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) { updateCaptureButtons(null); }
            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) { updateCaptureButtons(null); }
            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) { updateCaptureButtons(null); }
        });

        return panel;
    }

    /**
     * Updates the state of capture buttons based on textarea content
     */
    public void updateCaptureButtons(Context ctx) {
        String text = llmStreamArea.getText();
        boolean hasText = !text.isBlank();

        SwingUtilities.invokeLater(() -> {
            captureTextButton.setEnabled(hasText);
            var analyzer = contextManager.getAnalyzerNonBlocking();

            // Check for sources only if there's text
            if (hasText && analyzer != null) {
                // Use the sources method directly instead of a static method
                ContextFragment.VirtualFragment fragment;
                fragment = ctx == null
                        ? new ContextFragment.StringFragment(text, "temp")
                        : ctx.getParsedOutput().parsedFragment();
                Set<CodeUnit> sources = fragment.sources(project);
                editReferencesButton.setEnabled(!sources.isEmpty());

                // Update description with file names
                contextPanel.updateFilesDescriptionLabel(sources, analyzer);
            } else {
                editReferencesButton.setEnabled(false);
                contextPanel.updateFilesDescriptionLabel(Set.of(), analyzer);
            }
        });
    }
    
    /**
     * Updates the description label with file names
     */
    public void updateFilesDescriptionLabel(Set<CodeUnit> sources, Analyzer analyzer) {
        contextPanel.updateFilesDescriptionLabel(sources, analyzer);
    }

    public Context getSelectedContext() {
        var selected = SwingUtil.runOnEDT(() -> contextHistoryTable.getSelectedRow(), -1);
        if (selected < 0) {
            return contextManager.currentContext();
        }
        return SwingUtil.runOnEDT(() -> (Context) contextHistoryTable.getModel().getValueAt(selected, 1), null);
    }
    
    private void setInitialHistoryPanelWidth()
    {
        // Safety checks
        if (historySplitPane == null) {
            return;
        }

        // We measure the edit button's width and add padding
        var editButton = contextPanel.getEditButton();
        if (editButton == null) {
            return;
        }
        
        int buttonWidth = editButton.getPreferredSize().width;
        int newWidth = buttonWidth + 30;

        // Now set the divider location on the horizontal split
        // so that the right side is newWidth.
        // Frame width minus newWidth minus the divider size =>
        // left side gets the remainder, right side is about newWidth.

        int dividerPos = frame.getWidth() - newWidth - historySplitPane.getDividerSize();
        // If the frame isn't shown, or is smaller than newWidth, we clamp to a min of e.g. 100
        if (dividerPos < 100) {
            dividerPos = 100;
        }

        historySplitPane.setResizeWeight(0.0); // left side can shrink/grow
        historySplitPane.setDividerLocation(dividerPos);

        // Re-validate to ensure the UI picks up changes
        historySplitPane.revalidate();
        historySplitPane.repaint();
    }

    public JFrame getFrame() {
        assert SwingUtilities.isEventDispatchThread() : "Not on EDT";
        return frame;
    }

    public void focusInput() {
        SwingUtilities.invokeLater(() -> {
            this.commandInputField.requestFocus();
        });
    }

    /**
     * Prefills the command input field with the given text
     */
    public void prefillCommand(String command) {
        SwingUtilities.invokeLater(() -> {
            commandInputField.setText(command);
            runButton.requestFocus();
        });
    }

    public void updateContextTable() {
        contextPanel.updateContextTable();
    }

    public ContextManager getContextManager() {
        return contextManager;
    }

    /**
     * Get the list of selected fragments
     */
    public List<ContextFragment> getSelectedFragments() {
        return contextPanel.getSelectedFragments();
    }
}
