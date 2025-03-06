package io.github.jbellis.brokk;

import io.github.jbellis.brokk.gui.SmartScroll;
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
import java.util.concurrent.Future;

/**
 * Chrome provides a Swing-based UI for Brokk, replacing the old Lanterna-based ConsoleIO.
 * It implements IConsoleIO so the rest of the code can call io.toolOutput(...), etc.
 *
 * It sets up a main JFrame with:
 *   1) A top RSyntaxTextArea for LLM output & shell output
 *   2) A single-line command input field
 *   3) A context panel showing read-only and editable files
 *   4) A command result label for showing success/error messages
 *   5) A background status label at the bottom to show spinners or tasks
 *
 * Updated as per request to:
 *   - Move action logic into ContextManager,
 *   - Schedule “Go/Ask/Search” tasks in an ExecutorService,
 *   - Add “Stop” button to cancel the current user-driven task,
 *   - Make sure all Swing updates happen in invokeLater calls,
 *   - Remove OperationResult usage entirely.
 */
public class Chrome implements AutoCloseable, IConsoleIO {
    private static final Logger logger = LogManager.getLogger(Chrome.class);
    private final int FRAGMENT_COLUMN = 3;
    private final String BGTASK_EMPTY = "No background tasks";

    // Dependencies:
    private ContextManager contextManager;
    private Project project;

    // Swing components:
    private final JFrame frame;
    private RSyntaxTextArea llmStreamArea;
    private JLabel commandResultLabel;
    private JTextArea commandInputField;
    private JLabel backgroundStatusLabel;
    
    // Context History Panel
    private JTable contextHistoryTable;
    private DefaultTableModel contextHistoryModel;

    // Context Panel & table:
    private JPanel contextPanel;
    private JTable contextTable;
    private JPanel locSummaryLabel;
    private JTable uncommittedFilesTable;
    private JButton suggestCommitButton;

    // Context action buttons:
    private JButton editButton;
    private JButton readOnlyButton;
    private JButton summarizeButton;
    private JButton dropButton;
    private JButton copyButton;
    private JButton pasteButton;
    private JButton symbolButton;

    // Buttons for the command input panel:
    private JButton codeButton;  // renamed from goButton
    private JButton askButton;
    private JButton searchButton;
    private JButton runButton;
    private JButton stopButton;  // cancels the current user-driven task

    // Track the currently running user-driven future (Code/Ask/Search/Run)
    private volatile Future<?> currentUserTask;
    private JScrollPane llmScrollPane;
    private final int CHECKBOX_COLUMN = 2;

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
        frame = new JFrame("Brokk - Swing Edition");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(800, 1200);  // Taller than wide
        frame.setLayout(new BorderLayout());

        // 3) Main panel (top area + bottom area)
        frame.add(buildMainPanel(), BorderLayout.CENTER);

        // 4) Build menu
        frame.setJMenuBar(buildMenuBar());

        // 5) Register global keyboard shortcuts
        registerGlobalKeyboardShortcuts();

        // 6) Load saved window size and position, then show window
        loadWindowSizeAndPosition();
        updateContextButtons();
        frame.setVisible(true);
        
        // Set focus to command input field on startup
        commandInputField.requestFocusInWindow();

        // Add listener to save window size and position when they change
        frame.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                if (project != null) {
                    project.saveMainWindowBounds(frame);
                }
            }

            @Override
            public void componentMoved(java.awt.event.ComponentEvent e) {
                if (project != null) {
                    project.saveMainWindowBounds(frame);
                }
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

        // Create a split pane to hold output and history
        var outputSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        outputSplitPane.setLeftComponent(outputPanel);
        outputSplitPane.setRightComponent(contextHistoryPanel);
        outputSplitPane.setResizeWeight(0.8); // 80% to output, 20% to history

        gbc.weighty = 1.0;
        gbc.gridy = 0;
        contentPanel.add(outputSplitPane, gbc);

        // Ensure the history panel width is correct immediately
        SwingUtilities.invokeLater(() -> {
            setInitialHistoryPanelWidth();
        });

        // 2. Command result label
        var resultLabel = buildCommandResultLabel();
        gbc.weighty = 0.0;
        gbc.gridy = 1;
        contentPanel.add(resultLabel, gbc);

        // 3. Command input with prompt
        var commandPanel = buildCommandInputPanel();
        gbc.gridy = 2;
        contentPanel.add(commandPanel, gbc);

        // 4. Context panel (with border title)
        var ctxPanel = buildContextPanel();
        gbc.weighty = 0.2;
        gbc.gridy = 3;
        contentPanel.add(ctxPanel, gbc);

        // 5. Background status label at bottom
        var statusLabel = buildBackgroundStatusLabel();
        gbc.weighty = 0.0;
        gbc.gridy = 4;
        contentPanel.add(statusLabel, gbc);

        panel.add(contentPanel, BorderLayout.CENTER);
        return panel;
    }

    /**
     * Creates the RSyntaxTextArea for the LLM stream, wrapped in a JScrollPane.
     */
    /**
     * Builds the Context History panel that shows past contexts
     */
    private JPanel buildContextHistoryPanel() {
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
        contextHistoryTable.setRowHeight(20);
        contextHistoryTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        
        // Remove table header
        contextHistoryTable.setTableHeader(null);

        // Set up column rendering for LLM conversation rows
        contextHistoryTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {

                Component c = super.getTableCellRendererComponent(
                        table, value, isSelected, hasFocus, row, column);

                if (contextManager != null && row < contextManager.contextHistory.size()) {
                    var ctx = contextManager.contextHistory.get(row);
                    if (ctx.textarea != null) {
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
                if (row >= 0 && contextManager != null && row < contextManager.contextHistory.size()) {
                    var ctx = contextManager.contextHistory.get(row);
                    previewContextFromHistory(ctx);
                }
            }
        });
        
        // Add double-click listener to restore context
        contextHistoryTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = contextHistoryTable.getSelectedRow();
                    if (row >= 0 && contextManager != null) {
                        restoreContextFromHistory(row);
                    }
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
        undoButton.addActionListener(e -> {
            if (contextManager != null) {
                disableUserActionButtons();
                disableContextActionButtons();
                currentUserTask = contextManager.undoContextAsync();
            }
        });

        var redoButton = new JButton("Redo");
        redoButton.setMnemonic(KeyEvent.VK_Y);
        redoButton.addActionListener(e -> {
            if (contextManager != null) {
                disableUserActionButtons();
                disableContextActionButtons();
                currentUserTask = contextManager.redoContextAsync();
            }
        });

        buttonPanel.add(undoButton);
        buttonPanel.add(redoButton);
        
        panel.add(scrollPane, BorderLayout.CENTER);
        panel.add(buttonPanel, BorderLayout.SOUTH);

        return panel;
    }
    
    /**
     * Previews a context from history without fully restoring it
     */
    private void previewContextFromHistory(Context ctx) {
        assert ctx != null;
        
        // update the context panel display
        updateContextTable(ctx);
        
        // If there's textarea content, restore it to the LLM output area
        if (ctx.textarea != null) {
            SwingUtilities.invokeLater(() -> {
                llmStreamArea.setText(ctx.textarea);
                llmStreamArea.setCaretPosition(0);
            });
        }
    }

    private void restoreContextFromHistory(int index) {
        // TODO
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
        // We'll treat the content as plain text
        llmStreamArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
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
        codeButton.addActionListener(e -> runCodeCommand());

        askButton = new JButton("Ask");
        askButton.setMnemonic(KeyEvent.VK_A);
        askButton.addActionListener(e -> runAskCommand());

        searchButton = new JButton("Search");
        searchButton.setMnemonic(KeyEvent.VK_S);
        searchButton.addActionListener(e -> runSearchCommand());

        runButton = new JButton("Run in Shell");
        runButton.setMnemonic(KeyEvent.VK_R);
        runButton.addActionListener(e -> runRunCommand());

        leftButtonsPanel.add(codeButton);
        leftButtonsPanel.add(askButton);
        leftButtonsPanel.add(searchButton);
        leftButtonsPanel.add(runButton);

        // Right side: stop
        var rightButtonsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        stopButton = new JButton("Stop");
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
        commandInputField.setText("");
        llmStreamArea.setText("");
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

        commandInputField.setText("");
        llmStreamArea.setText("");
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
        commandInputField.setText("");
        llmStreamArea.setText("");
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
        commandInputField.setText("");
        llmStreamArea.setText("");
        llmStreamArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);

        disableUserActionButtons();
        currentUserTask = contextManager.runSearchAsync(input);
    }

    /**
     * Disables “Go/Ask/Search” to prevent overlapping tasks, until re-enabled
     */
    private void disableUserActionButtons() {
        SwingUtilities.invokeLater(() -> {
            codeButton.setEnabled(false);
            askButton.setEnabled(false);
            searchButton.setEnabled(false);
            runButton.setEnabled(false);
            stopButton.setEnabled(true);
        });
    }

    /**
     * Re-enables “Go/Ask/Search” when the task completes or is canceled
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
        contextPanel = new JPanel(new BorderLayout());
        contextPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Context",
                javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
                javax.swing.border.TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)
        ));

        contextTable = new JTable(new DefaultTableModel(
                new Object[]{"LOC", "Description", "Select", "Fragment"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == CHECKBOX_COLUMN;
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return switch (columnIndex) {
                    case 0 -> Integer.class;
                    case 1 -> String.class;
                    case 2 -> Boolean.class;
                    case 3 -> ContextFragment.class;
                    default -> Object.class;
                };
            }
        });
        contextTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        // Add custom cell renderer for the description column to show italics for editable files
        contextTable.getColumnModel().getColumn(1).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            public java.awt.Component getTableCellRendererComponent(
                    JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {

                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

                if (value != null && value.toString().startsWith("✏️")) {
                    setFont(getFont().deriveFont(Font.ITALIC));
                } else {
                    setFont(getFont().deriveFont(Font.PLAIN));
                }

                return c;
            }
        });
        contextTable.setRowHeight(18);

        // Set up table header with custom column headers
        var tableHeader = contextTable.getTableHeader();
        tableHeader.setReorderingAllowed(false);
        tableHeader.setResizingAllowed(true);
        tableHeader.setFont(new Font(Font.DIALOG, Font.BOLD, 12));

        // Hide the header for the "Select" and "Fragment" columns
        contextTable.getColumnModel().getColumn(CHECKBOX_COLUMN).setHeaderValue("");
        contextTable.getColumnModel().getColumn(CHECKBOX_COLUMN).setMaxWidth(60);
        contextTable.getColumnModel().getColumn(FRAGMENT_COLUMN).setMinWidth(0);
        contextTable.getColumnModel().getColumn(FRAGMENT_COLUMN).setMaxWidth(0);
        contextTable.getColumnModel().getColumn(FRAGMENT_COLUMN).setWidth(0);

        contextTable.setIntercellSpacing(new Dimension(10, 1));

        // column widths
        contextTable.getColumnModel().getColumn(0).setPreferredWidth(50);
        contextTable.getColumnModel().getColumn(0).setMaxWidth(100);
        contextTable.getColumnModel().getColumn(1).setPreferredWidth(480);

        // Add double-click listener to open fragment preview
        contextTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = contextTable.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        var fragment = (ContextFragment) contextTable.getModel().getValueAt(row, FRAGMENT_COLUMN);
                        if (fragment != null) {
                            openFragmentPreview(fragment);
                        }
                    }
                }
            }
        });
        contextTable.getModel().addTableModelListener(e -> {
            if (e.getColumn() == CHECKBOX_COLUMN) {
                updateContextButtons();
            }
        });

        // Panel for context summary information at bottom
        var contextSummaryPanel = new JPanel();
        contextSummaryPanel.setLayout(new BorderLayout());

        locSummaryLabel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        JLabel innerLabel = new JLabel(" ");
        innerLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        innerLabel.setBorder(new EmptyBorder(5, 5, 5, 5));
        locSummaryLabel.add(innerLabel);
        locSummaryLabel.setBorder(BorderFactory.createEmptyBorder());

        // Create panel for uncommitted changes with a suggest commit button to the right
        var uncommittedPanel = new JPanel(new BorderLayout(10, 0));
        uncommittedPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Uncommitted Changes",
                javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
                javax.swing.border.TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)
        ));

        // Create table for uncommitted files - fixed to 3 rows with scrollbar
        uncommittedFilesTable = new JTable(new DefaultTableModel(
                new Object[]{"Filename", "Path"}, 0));
        uncommittedFilesTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        uncommittedFilesTable.setRowHeight(18);

        // Set column widths
        uncommittedFilesTable.getColumnModel().getColumn(0).setPreferredWidth(150);
        uncommittedFilesTable.getColumnModel().getColumn(1).setPreferredWidth(450);

        // Create a scroll pane with fixed height of 3 rows plus header and scrollbar
        JScrollPane uncommittedScrollPane = new JScrollPane(uncommittedFilesTable);
        int tableRowHeight = uncommittedFilesTable.getRowHeight();
        int headerHeight = 22; // Approximate header height
        int scrollbarHeight = 3; // Extra padding for scrollbar
        uncommittedScrollPane.setPreferredSize(new Dimension(600, (tableRowHeight * 3) + headerHeight + scrollbarHeight));
        
        // Create the suggest commit button panel on the right
        var commitButtonPanel = new JPanel(new BorderLayout());
        suggestCommitButton = new JButton("Suggest Commit");
        suggestCommitButton.setEnabled(false);
        suggestCommitButton.setMnemonic(KeyEvent.VK_C);
        suggestCommitButton.addActionListener(e -> {
            disableUserActionButtons();
            currentUserTask = contextManager.performCommitActionAsync();
        });
        
        // Make the button the same width as context panel buttons
        // We'll set this after we have the preferred size from the context buttons
        commitButtonPanel.add(suggestCommitButton, BorderLayout.NORTH);
        
        // Add table and button to the panel
        uncommittedPanel.add(uncommittedScrollPane, BorderLayout.CENTER);
        uncommittedPanel.add(commitButtonPanel, BorderLayout.EAST);
        
        contextSummaryPanel.add(locSummaryLabel, BorderLayout.NORTH);
        contextSummaryPanel.add(uncommittedPanel, BorderLayout.CENTER);

        // Table panel
        var tablePanel = new JPanel(new BorderLayout());
        JScrollPane tableScrollPane = new JScrollPane(contextTable,
                                                      JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                                                      JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        // Set a preferred size to maintain height even when empty (almost works)
        tableScrollPane.setPreferredSize(new Dimension(600, 150));
        tablePanel.add(tableScrollPane, BorderLayout.CENTER);

        // Buttons panel
        var buttonsPanel = createContextButtonsPanel();

        contextPanel.setLayout(new BorderLayout());
        contextPanel.add(tablePanel, BorderLayout.CENTER);
        contextPanel.add(buttonsPanel, BorderLayout.EAST);
        contextPanel.add(contextSummaryPanel, BorderLayout.SOUTH);
        contextPanel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                contextPanel.requestFocusInWindow();
            }
        });

        ((JLabel)locSummaryLabel.getComponent(0)).setText("No context - use Edit or Read or Summarize to add content");

        return contextPanel;
    }

    /**
     * Creates the panel with context action buttons: edit/read/summarize/drop/copy
     */
    private JPanel createContextButtonsPanel() {
        var buttonsPanel = new JPanel();
        buttonsPanel.setLayout(new BoxLayout(buttonsPanel, BoxLayout.Y_AXIS));
        buttonsPanel.setBorder(new EmptyBorder(5, 5, 5, 5));

        if (editButton == null) {
            editButton = new JButton("Edit All");
            editButton.setMnemonic(KeyEvent.VK_D);
            editButton.addActionListener(e -> {
                var selectedFragments = getSelectedFragments();
                currentUserTask = contextManager.performContextActionAsync("edit", selectedFragments);
            });
        }

        if (readOnlyButton == null) {
            readOnlyButton = new JButton("Read All");
            readOnlyButton.setMnemonic(KeyEvent.VK_R);
            readOnlyButton.addActionListener(e -> {
                var selectedFragments = getSelectedFragments();
                currentUserTask = contextManager.performContextActionAsync("read", selectedFragments);
            });
        }

        if (summarizeButton == null) {
            summarizeButton = new JButton("Summarize All");
            summarizeButton.setMnemonic(KeyEvent.VK_S);
            summarizeButton.addActionListener(e -> {
                var selectedFragments = getSelectedFragments();
                currentUserTask = contextManager.performContextActionAsync("summarize", selectedFragments);
            });
        }

        if (dropButton == null) {
            dropButton = new JButton("Drop All");
            dropButton.setMnemonic(KeyEvent.VK_P);  // Changed from VK_D to VK_P
            dropButton.addActionListener(e -> {
                disableContextActionButtons();
                var selectedFragments = getSelectedFragments();
                currentUserTask = contextManager.performContextActionAsync("drop", selectedFragments);
            });
        }

        if (copyButton == null) {
            copyButton = new JButton("Copy All");
            copyButton.addActionListener(e -> {
                var selectedFragments = getSelectedFragments();
                currentUserTask = contextManager.performContextActionAsync("copy", selectedFragments);
            });
        }

        if (pasteButton == null) {
            pasteButton = new JButton("Paste");
            pasteButton.addActionListener(e -> {
                currentUserTask = contextManager.performContextActionAsync("paste", List.of());
            });
        }

        // Create a prototype button to measure width
        var prototypeButton = new JButton("Summarize selected");
        var buttonSize = prototypeButton.getPreferredSize();
        var preferredSize = new Dimension(buttonSize.width, editButton.getPreferredSize().height);

        // Set sizes
        editButton.setPreferredSize(preferredSize);
        readOnlyButton.setPreferredSize(preferredSize);
        summarizeButton.setPreferredSize(preferredSize);
        dropButton.setPreferredSize(preferredSize);
        copyButton.setPreferredSize(preferredSize);
        pasteButton.setPreferredSize(preferredSize);

        editButton.setMaximumSize(new Dimension(preferredSize.width, preferredSize.height));
        readOnlyButton.setMaximumSize(new Dimension(preferredSize.width, preferredSize.height));
        summarizeButton.setMaximumSize(new Dimension(preferredSize.width, preferredSize.height));
        dropButton.setMaximumSize(new Dimension(preferredSize.width, preferredSize.height));
        copyButton.setMaximumSize(new Dimension(preferredSize.width, preferredSize.height));
        pasteButton.setMaximumSize(new Dimension(preferredSize.width, preferredSize.height));

        buttonsPanel.add(editButton);
        buttonsPanel.add(Box.createRigidArea(new Dimension(0, 5)));
        buttonsPanel.add(readOnlyButton);
        buttonsPanel.add(Box.createRigidArea(new Dimension(0, 5)));
        buttonsPanel.add(summarizeButton);
        buttonsPanel.add(Box.createRigidArea(new Dimension(0, 5)));
        
        // Add Symbol Usage button right after Summarize
        if (symbolButton == null) {
            symbolButton = new JButton("Symbol Usage");
            symbolButton.setMnemonic(KeyEvent.VK_Y);
            symbolButton.addActionListener(e -> {
                currentUserTask = contextManager.findSymbolUsageAsync();
            });
            symbolButton.setPreferredSize(preferredSize);
            symbolButton.setMaximumSize(new Dimension(preferredSize.width, preferredSize.height));
        }
        buttonsPanel.add(symbolButton);
        buttonsPanel.add(Box.createRigidArea(new Dimension(0, 5)));
        
        buttonsPanel.add(dropButton);
        buttonsPanel.add(Box.createVerticalGlue());  // Push remaining buttons to bottom
        buttonsPanel.add(Box.createRigidArea(new Dimension(0, 5)));
        buttonsPanel.add(copyButton);
        buttonsPanel.add(Box.createRigidArea(new Dimension(0, 5)));
        buttonsPanel.add(pasteButton);
        
        // Set the suggestCommitButton to match the width of these context buttons
        if (suggestCommitButton != null) {
            suggestCommitButton.setPreferredSize(preferredSize);
            suggestCommitButton.setMaximumSize(new Dimension(preferredSize.width, preferredSize.height));
        }

        return buttonsPanel;
    }

    /**
     * Disables the context action buttons while an action is in progress
     */
    private void disableContextActionButtons() {
        SwingUtilities.invokeLater(() -> {
            editButton.setEnabled(false);
            readOnlyButton.setEnabled(false);
            summarizeButton.setEnabled(false);
            dropButton.setEnabled(false);
            copyButton.setEnabled(false);
            pasteButton.setEnabled(false);
            symbolButton.setEnabled(false);
        });
    }

    /**
     * Re-enables context action buttons
     */
    public void enableContextActionButtons() {
        SwingUtilities.invokeLater(() -> {
            editButton.setEnabled(true);
            readOnlyButton.setEnabled(true);
            summarizeButton.setEnabled(true);
            dropButton.setEnabled(true);
            copyButton.setEnabled(true);
            pasteButton.setEnabled(true);
            symbolButton.setEnabled(true);
            updateContextButtons();
        });
    }

    /**
     * Check if any items are selected
     */
    private boolean hasSelectedItems() {
        if (contextTable.getModel().getRowCount() == 0) {
            return false;
        }
        var tableModel = (DefaultTableModel) contextTable.getModel();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            if (Boolean.TRUE.equals(tableModel.getValueAt(i, CHECKBOX_COLUMN))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the list of selected fragments
     */
    private List<ContextFragment> getSelectedFragments() {
        var fragments = new ArrayList<ContextFragment>();
        var tableModel = (DefaultTableModel) contextTable.getModel();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            if (Boolean.TRUE.equals(tableModel.getValueAt(i, CHECKBOX_COLUMN))) {
                fragments.add((ContextFragment) tableModel.getValueAt(i, FRAGMENT_COLUMN));
            }
        }
        return fragments;
    }

    /**
     * Update context action button labels
     */
    private void updateContextButtons() {
        var hasSelection = hasSelectedItems();
        editButton.setText(hasSelection ? "Edit Selected" : "Edit Files");
        readOnlyButton.setText(hasSelection ? "Read Selected" : "Read Files");
        summarizeButton.setText(hasSelection ? "Summarize Selected" : "Summarize Files");
        dropButton.setText(hasSelection ? "Drop Selected" : "Drop All");
        copyButton.setText(hasSelection ? "Copy Selected" : "Copy All");

        var ctx = (contextManager == null) ? null : contextManager.currentContext();
        var hasContext = (ctx != null && !ctx.isEmpty());
        dropButton.setEnabled(hasContext);
        copyButton.setEnabled(hasContext);

        updateSuggestCommitButton();
    }

    /**
     * Updates the uncommitted files table and the state of the suggest commit button
     */
    private void updateSuggestCommitButton() {
        assert contextManager != null;

        contextManager.submitBackgroundTask("Checking uncommitted files", () -> {
            List<String> uncommittedFiles = GitRepo.instance.getUncommittedFileNames();
            SwingUtilities.invokeLater(() -> {
                DefaultTableModel model = (DefaultTableModel) uncommittedFilesTable.getModel();
                model.setRowCount(0);
                
                if (uncommittedFiles.isEmpty()) {
                    suggestCommitButton.setEnabled(false);
                } else {
                    for (String filePath : uncommittedFiles) {
                        // Split into filename and path
                        int lastSlash = filePath.lastIndexOf('/');
                        String filename = (lastSlash >= 0) ? filePath.substring(lastSlash + 1) : filePath;
                        String path = (lastSlash >= 0) ? filePath.substring(0, lastSlash) : "";

                        model.addRow(new Object[]{filename, path});
                    }
                    suggestCommitButton.setEnabled(true);
                }
            });
            return null;
        });
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
                if (contextManager != null) {
                    disableUserActionButtons();
                    disableContextActionButtons();
                    currentUserTask = contextManager.undoContextAsync();
                }
            }
        });

        // Ctrl+Shift+Z => redo
        var redoKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Z,
                                                   InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(redoKeyStroke, "globalRedo");
        rootPane.getActionMap().put("globalRedo", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (contextManager != null) {
                    disableUserActionButtons();
                    disableContextActionButtons();
                    currentUserTask = contextManager.redoContextAsync();
                }
            }
        });

        // Ctrl+V => paste
        var pasteKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK);
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(pasteKeyStroke, "globalPaste");
        rootPane.getActionMap().put("globalPaste", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (contextManager != null) {
                    currentUserTask = contextManager.performContextActionAsync("paste", List.of());
                }
            }
        });
    }

    /**
     * Builds the menu bar
     */
    private JMenuBar buildMenuBar() {
        var menuBar = new JMenuBar();

        // File menu
        var fileMenu = new JMenu("File");
        fileMenu.setMnemonic(KeyEvent.VK_F);

        var editKeysItem = new JMenuItem("Edit secret keys");
        editKeysItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_K, InputEvent.ALT_DOWN_MASK));
        editKeysItem.addActionListener(e -> showSecretKeysDialog());
        fileMenu.add(editKeysItem);

        fileMenu.addSeparator();

        var setAutoContextItem = new JMenuItem("Set autocontext size");
        setAutoContextItem.addActionListener(e -> {
            // Simple spinner dialog
            var dialog = new JDialog(frame, "Set Autocontext Size", true);
            dialog.setLayout(new BorderLayout());

            var panel = new JPanel(new BorderLayout());
            panel.setBorder(new EmptyBorder(10, 10, 10, 10));

            var label = new JLabel("Enter autocontext size (0-100):");
            panel.add(label, BorderLayout.NORTH);

            var spinner = new JSpinner(new SpinnerNumberModel(
                    (contextManager != null) ? contextManager.currentContext().autoContextFileCount : 5,
                    0, 100, 1
            ));
            panel.add(spinner, BorderLayout.CENTER);

            var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            var okButton = new JButton("OK");
            var cancelButton = new JButton("Cancel");

            okButton.addActionListener(okEvent -> {
                var newSize = (int) spinner.getValue();
                contextManager.setAutoContextFiles(newSize);
                dialog.dispose();
            });

            cancelButton.addActionListener(cancelEvent -> dialog.dispose());
            buttonPanel.add(okButton);
            buttonPanel.add(cancelButton);
            panel.add(buttonPanel, BorderLayout.SOUTH);

            dialog.getRootPane().setDefaultButton(okButton);
            dialog.getRootPane().registerKeyboardAction(
                    evt -> dialog.dispose(),
                    KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                    JComponent.WHEN_IN_FOCUSED_WINDOW
            );

            dialog.add(panel);
            dialog.pack();
            dialog.setLocationRelativeTo(frame);
            dialog.setVisible(true);
        });
        fileMenu.add(setAutoContextItem);

        var refreshItem = new JMenuItem("Refresh Code Intelligence");
        refreshItem.addActionListener(e -> {
            contextManager.requestRebuild();
            toolOutput("Code intelligence will refresh in the background");
        });
        fileMenu.add(refreshItem);

        menuBar.add(fileMenu);

        // Edit menu
        var editMenu = new JMenu("Edit");
        editMenu.setMnemonic(KeyEvent.VK_E);

        var undoItem = new JMenuItem("Undo");
        undoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK));
        undoItem.addActionListener(e -> {
            disableUserActionButtons();
            disableContextActionButtons();
            currentUserTask = contextManager.undoContextAsync();
        });
        editMenu.add(undoItem);

        var redoItem = new JMenuItem("Redo");
        redoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z,
                                                       InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        redoItem.addActionListener(e -> {
            disableUserActionButtons();
            disableContextActionButtons();
            currentUserTask = contextManager.redoContextAsync();
        });
        editMenu.add(redoItem);

        editMenu.addSeparator();

        var copyMenuItem = new JMenuItem("Copy");
        copyMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK));
        copyMenuItem.addActionListener(e -> {
            var selectedFragments = getSelectedFragments();
            currentUserTask = contextManager.performContextActionAsync("copy", selectedFragments);
        });
        editMenu.add(copyMenuItem);

        var pasteMenuItem = new JMenuItem("Paste");
        pasteMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK));
        pasteMenuItem.addActionListener(e -> {
            currentUserTask = contextManager.performContextActionAsync("paste", List.of());
        });
        editMenu.add(pasteMenuItem);

        menuBar.add(editMenu);


        // Help menu
        var helpMenu = new JMenu("Help");
        helpMenu.setMnemonic(KeyEvent.VK_H);

        var aboutItem = new JMenuItem("About");
        aboutItem.addActionListener(e -> {
            JOptionPane.showMessageDialog(frame,
                                          "Brokk Swing UI\nVersion X\n...",
                                          "About Brokk",
                                          JOptionPane.INFORMATION_MESSAGE);
        });
        helpMenu.add(aboutItem);

        menuBar.add(helpMenu);

        return menuBar;
    }

    /**
     * Shows a dialog for editing LLM API secret keys.
     */
    private void showSecretKeysDialog() {
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
    public char askOptions(String msg, String options) {
        // e.g. (A)dd, (R)ead, etc.
        final char[] selected = new char[1];
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                selected[0] = internalAskOptions(msg, options);
            } else {
                SwingUtilities.invokeAndWait(() -> {
                    selected[0] = internalAskOptions(msg, options);
                });
            }
        } catch (Exception e) {
            logger.error("askOptions error", e);
            return options.toLowerCase().charAt(options.length() - 1);
        }
        return selected[0];
    }

    private char internalAskOptions(String msg, String options) {
        var optsArr = options.chars().mapToObj(c -> String.valueOf((char) c)).toArray(String[]::new);
        var choice = (String) JOptionPane.showInputDialog(
                frame, msg, "Choose Option",
                JOptionPane.PLAIN_MESSAGE, null,
                optsArr, (optsArr.length > 0) ? optsArr[0] : null
        );
        if (choice == null || choice.isEmpty()) {
            return options.toLowerCase().charAt(options.length() - 1);
        }
        return choice.toLowerCase().charAt(0);
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

    @Override
    public void clear() {
        SwingUtilities.invokeLater(() -> {
            llmStreamArea.setText("");
            commandResultLabel.setText("");
        });
    }

    /**
     * Repopulate the unified context table from the given context.
     */
    public void updateContextTable(Context context) {
        SwingUtilities.invokeLater(() -> {
            // Clear the existing table rows
            var tableModel = (DefaultTableModel) contextTable.getModel();
            tableModel.setRowCount(0);

            updateContextButtons();

            if (context.isEmpty()) {
                ((JLabel)locSummaryLabel.getComponent(0)).setText("No context - use Edit or Read or Summarize to add content");
                contextPanel.revalidate();
                contextPanel.repaint();
                return;
            }
            
            // Update context history table
            updateContextHistoryTable();

            // Fill the table with new data
            var allFragments = context.getAllFragmentsInDisplayOrder();
            int totalLines = 0;
            for (var frag : allFragments) {
                var loc = countLinesSafe(frag);
                totalLines += loc;
                var desc = frag.description();

                var isEditable = (frag instanceof ContextFragment.RepoPathFragment)
                        && context.editableFiles().anyMatch(e -> e == frag);

                if (isEditable) {
                    desc = "✏️ " + desc;  // Add pencil icon to editable files
                }

                tableModel.addRow(new Object[]{loc, desc, false, frag});
            }

            var fullText = "";  // no large merges needed
            var approxTokens = Models.getApproximateTokens(fullText);

            ((JLabel)locSummaryLabel.getComponent(0)).setText(
                    "Total: %,d LOC, or about %,dk tokens".formatted(totalLines, approxTokens / 1000)
            );

            // Just revalidate/repaint the panel to reflect the new rows
            contextPanel.revalidate();
            contextPanel.repaint();
        });
    }

    private int countLinesSafe(ContextFragment fragment) {
        try {
            var text = fragment.text();
            if (text.isEmpty()) return 0;
            return text.split("\\r?\\n", -1).length;
        } catch (Exception e) {
            toolErrorRaw("Error reading fragment: " + e.getMessage());
            return 0;
        }
    }

    @Override
    public void close() {
        logger.info("Closing Chrome UI");
        if (contextManager != null) {
            contextManager.shutdown();
        }
        if (frame != null) {
            frame.dispose();
        }
    }


    /**
     * Opens a preview window for a context fragment
     * @param fragment The fragment to preview
     */
    private void openFragmentPreview(ContextFragment fragment) {
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
            for (var ctx : contextManager.contextHistory) {
                contextHistoryModel.addRow(new Object[]{
                        ctx.getAction(),
                        ctx // We store the actual context object in hidden column
                });
            }

            // Auto-select the latest context
            if (contextHistoryModel.getRowCount() > 0) {
                int lastRow = contextHistoryModel.getRowCount() - 1;
                contextHistoryTable.setRowSelectionInterval(lastRow, lastRow);
            }
        });
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
     * Sets the initial width of the history panel based on the context buttons width
     */
    private void setInitialHistoryPanelWidth()
    {
        Container historyPanel = contextHistoryTable.getParent();
        while (historyPanel != null && !(historyPanel instanceof JPanel)) {
            historyPanel = historyPanel.getParent();
        }

        // Only proceed if we find the panel and the editButton is available
        if (historyPanel != null && editButton != null) {
            int buttonWidth = editButton.getPreferredSize().width;
            int newWidth = buttonWidth + 30; // Add padding

            // Set the panel's preferred width
            historyPanel.setPreferredSize(new Dimension(newWidth, historyPanel.getPreferredSize().height));

            // Force the split pane divider location
            Container parent = historyPanel.getParent();
            while (parent != null && !(parent instanceof JSplitPane)) {
                parent = parent.getParent();
            }
            if (parent instanceof JSplitPane splitPane) {
                splitPane.setResizeWeight(0.8);
                splitPane.setDividerLocation(frame.getWidth() - newWidth - splitPane.getDividerSize());
            }

            historyPanel.revalidate();
            historyPanel.repaint();
        }
    }

    JFrame getFrame() {
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

    /**
     * Helper row panel for editing secret keys in a single row
     */
    private static class KeyValueRowPanel extends JPanel {
        private final JComboBox<String> keyNameCombo;
        private final JTextField keyValueField;

        public KeyValueRowPanel(String[] defaultKeyNames) {
            this(defaultKeyNames, "", "");
        }

        public KeyValueRowPanel(String[] defaultKeyNames, String initialKey, String initialValue) {
            setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
            setBorder(new EmptyBorder(5, 0, 5, 0));

            keyNameCombo = new JComboBox<>(defaultKeyNames);
            keyNameCombo.setEditable(true);

            if (!initialKey.isEmpty()) {
                boolean found = false;
                for (int i = 0; i < defaultKeyNames.length; i++) {
                    if (defaultKeyNames[i].equals(initialKey)) {
                        keyNameCombo.setSelectedIndex(i);
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    keyNameCombo.setSelectedItem(initialKey);
                }
            }

            keyValueField = new JTextField(initialValue);

            keyNameCombo.setPreferredSize(new Dimension(150, 25));
            keyValueField.setPreferredSize(new Dimension(250, 25));

            keyNameCombo.setMaximumSize(new Dimension(150, 25));
            keyValueField.setMaximumSize(new Dimension(Short.MAX_VALUE, 25));

            add(new JLabel("Key: "));
            add(keyNameCombo);
            add(Box.createRigidArea(new Dimension(10, 0)));
            add(new JLabel("Value: "));
            add(keyValueField);
        }

        public String getKeyName() {
            var selected = keyNameCombo.getSelectedItem();
            return (selected != null) ? selected.toString().trim() : "";
        }

        public String getKeyValue() {
            return keyValueField.getText().trim();
        }
    }
}
