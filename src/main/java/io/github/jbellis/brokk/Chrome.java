package io.github.jbellis.brokk;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
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
public class Chrome implements AutoCloseable, IConsoleIO
{
    private static final Logger logger = LogManager.getLogger(Chrome.class);

    // Dependencies:
    private ContextManager contextManager;
    private Coder coder;
    private Commands commands;
    private Project project;

    // Swing components:
    private JFrame frame;
    private RSyntaxTextArea llmStreamArea;
    private JLabel commandResultLabel;
    private JTextField commandInputField;
    private JLabel backgroundStatusLabel;

    // Context Panel & table:
    private JPanel contextPanel;
    private JTable contextTable;
    private JLabel locSummaryLabel;

    // Context action buttons:
    private JButton editButton;
    private JButton readOnlyButton;
    private JButton summarizeButton;
    private JButton dropButton;
    private JButton copyButton;
    private JButton pasteButton;

    // Buttons for the command input panel:
    private JButton goButton;
    private JButton askButton;
    private JButton searchButton;
    private JButton stopButton;  // cancels the current user-driven task

    // History:
    private final List<String> commandHistory = new ArrayList<>();
    private int historyIndex = -1;

    // For implementing "kill" / "yank" (Emacs-like)
    private String killBuffer = "";

    // Track the currently running user-driven future (Go/Ask/Search)
    private volatile Future<?> currentUserTask;

    /**
     * Default constructor sets up the UI.
     * We call this from Brokk after creating contextManager, commands, etc.,
     * but before calling .resolveCircularReferences(...).
     */
    public Chrome()
    {
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

        // 6) Show window
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    /**
     * Finish wiring references to contextManager, commands, coder, etc.
     */
    public void resolveCircularReferences(ContextManager contextManager, Coder coder)
    {
        this.contextManager = contextManager;
        this.coder = coder;
        this.project = contextManager.getProject();
        this.commands = new Commands(contextManager);

        // Now, also tell the commands object to use this as IConsoleIO:
        this.commands.resolveCircularReferences(this, coder);

        // The contextManager also needs a reference to this Chrome
        this.contextManager.setChrome(this);
    }

    /**
     * Build the main panel that includes:
     *  - the LLM stream (top)
     *  - the command result label
     *  - the command input
     *  - the context panel
     *  - the background status label at bottom
     */
    private JPanel buildMainPanel()
    {
        var panel = new JPanel(new BorderLayout());

        var contentPanel = new JPanel(new GridBagLayout());
        var gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 1.0;
        gbc.gridx = 0;
        gbc.insets = new Insets(2,2,2,2);

        // 1. LLM streaming area
        var llmScrollPane = buildLLMStreamScrollPane();
        gbc.weighty = 1.0;
        gbc.gridy = 0;
        contentPanel.add(llmScrollPane, gbc);

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
    private JScrollPane buildLLMStreamScrollPane()
    {
        llmStreamArea = new RSyntaxTextArea();
        llmStreamArea.setEditable(false);
        // We'll treat the content as plain text
        llmStreamArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
        var caret = (DefaultCaret) llmStreamArea.getCaret();
        caret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        llmStreamArea.setLineWrap(true);
        llmStreamArea.setWrapStyleWord(true);
        llmStreamArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));

        JScrollPane scrollPane = new JScrollPane(llmStreamArea);
        
        // Add scroll listener to detect manual scrolling
        scrollPane.getVerticalScrollBar().addAdjustmentListener(e -> {
            JScrollBar sb = scrollPane.getVerticalScrollBar();
            int value = sb.getValue();
            int extent = sb.getModel().getExtent();
            int max = sb.getMaximum();

            // If the user is near the bottom, re-enable autoscroll. Otherwise disable it.
            userHasManuallyScrolled = (value + extent < max - 1);
            System.out.println("Manually scrolled: " + userHasManuallyScrolled);
        });

        return scrollPane;
    }

    /**
     * Creates the command result label used to display messages.
     */
    private JComponent buildCommandResultLabel()
    {
        commandResultLabel = new JLabel(" ");
        commandResultLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        commandResultLabel.setBorder(new EmptyBorder(5,10,5,10));
        commandResultLabel.setOpaque(true);
        commandResultLabel.setBackground(new Color(245,245,245));
        return commandResultLabel;
    }

    /**
     * Creates the bottom-most background status label that shows "Working on: ..." or is blank when idle.
     */
    private JComponent buildBackgroundStatusLabel()
    {
        backgroundStatusLabel = new JLabel(" ");
        backgroundStatusLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        backgroundStatusLabel.setBorder(new EmptyBorder(3,10,3,10));
        backgroundStatusLabel.setOpaque(true);
        backgroundStatusLabel.setBackground(new Color(240,240,240));
        // Add a line border above
        backgroundStatusLabel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1,0,0,0,Color.LIGHT_GRAY),
                new EmptyBorder(5,10,5,10)
        ));
        return backgroundStatusLabel;
    }

    /**
     * Creates a panel with a single-line text field for commands, plus “Go / Ask / Search” on the left and a “Stop” button on the right.
     * The panel is titled "Instructions".
     */
    private JPanel buildCommandInputPanel()
    {
        var wrapper = new JPanel(new BorderLayout());
        wrapper.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(
                        BorderFactory.createEtchedBorder(),
                        "Instructions",
                        javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
                        javax.swing.border.TitledBorder.DEFAULT_POSITION,
                        new Font(Font.DIALOG, Font.BOLD, 12)
                ),
                new EmptyBorder(5,5,5,5)
        ));

        commandInputField = new JTextField();
        commandInputField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        commandInputField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.GRAY),
                BorderFactory.createEmptyBorder(2,5,2,5)
        ));

        // Basic approach: pressing Enter runs "Go"
        commandInputField.addActionListener(e -> runGoCommand());

        // Emacs-like keybindings
        bindEmacsKeys(commandInputField);

        // Left side: go/ask/search
        var leftButtonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        goButton = new JButton("Go");
        goButton.addActionListener(e -> runGoCommand());

        askButton = new JButton("Ask");
        askButton.addActionListener(e -> runAskCommand());

        searchButton = new JButton("Search");
        searchButton.addActionListener(e -> runSearchCommand());

        leftButtonsPanel.add(goButton);
        leftButtonsPanel.add(askButton);
        leftButtonsPanel.add(searchButton);

        // Right side: stop
        var rightButtonsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        stopButton = new JButton("Stop");
        stopButton.addActionListener(e -> stopCurrentUserTask());
        rightButtonsPanel.add(stopButton);

        // We'll place left and right panels in a horizontal layout
        var buttonsHolder = new JPanel(new BorderLayout());
        buttonsHolder.add(leftButtonsPanel, BorderLayout.WEST);
        buttonsHolder.add(rightButtonsPanel, BorderLayout.EAST);

        // Add text field at top, buttons panel below
        wrapper.add(commandInputField, BorderLayout.CENTER);
        wrapper.add(buttonsHolder, BorderLayout.SOUTH);

        // Set "Go" as default
        frame.getRootPane().setDefaultButton(goButton);

        return wrapper;
    }

    /**
     * Invoked on "Go" button or pressing Enter in the command input field.
     */
    private void runGoCommand()
    {
        var input = commandInputField.getText();
        if (input.isBlank()) {
            toolErrorRaw("Please enter a command or text");
            return;
        }
        addToHistory(input);
        commandInputField.setText("");

        disableUserActionButtons();
        // schedule in ContextManager
        currentUserTask = contextManager.runGoCommandAsync(input);
    }

    /**
     * Invoked on "Ask" button
     */
    private void runAskCommand()
    {
        var input = commandInputField.getText();
        if (input.isBlank()) {
            toolErrorRaw("Please enter a question");
            return;
        }
        addToHistory(input);
        commandInputField.setText("");

        disableUserActionButtons();
        currentUserTask = contextManager.runAskAsync(input);
    }

    /**
     * Invoked on "Search" button
     */
    private void runSearchCommand()
    {
        var input = commandInputField.getText();
        if (input.isBlank()) {
            toolErrorRaw("Please provide a search query");
            return;
        }
        addToHistory(input);
        commandInputField.setText("");

        disableUserActionButtons();
        currentUserTask = contextManager.runSearchAsync(input);
    }

    /**
     * Disables “Go/Ask/Search” to prevent overlapping tasks, until re-enabled
     */
    private void disableUserActionButtons()
    {
        SwingUtilities.invokeLater(() -> {
            goButton.setEnabled(false);
            askButton.setEnabled(false);
            searchButton.setEnabled(false);
            stopButton.setEnabled(true);
            // Reset scroll tracking when starting a new command
            userHasManuallyScrolled = false;
        });
    }

    /**
     * Re-enables “Go/Ask/Search” when the task completes or is canceled
     */
    public void enableUserActionButtons()
    {
        SwingUtilities.invokeLater(() -> {
            goButton.setEnabled(true);
            askButton.setEnabled(true);
            searchButton.setEnabled(true);
            stopButton.setEnabled(false);
        });
    }

    /**
     * Cancels the currently running user-driven Future (Go/Ask/Search), if any
     */
    private void stopCurrentUserTask()
    {
        if (currentUserTask != null && !currentUserTask.isDone()) {
            currentUserTask.cancel(true);
        }
    }

    /**
     * Binds Emacs/readline-like keys to the given text field.
     */
    private void bindEmacsKeys(JTextField field)
    {
        field.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_K, InputEvent.CTRL_DOWN_MASK), "killLine");
        field.getActionMap().put("killLine", new AbstractAction()
        {
            @Override
            public void actionPerformed(ActionEvent e) {
                var pos = field.getCaretPosition();
                var text = field.getText();
                if (pos < text.length()) {
                    killBuffer = text.substring(pos);
                    field.setText(text.substring(0, pos));
                } else {
                    killBuffer = "";
                }
            }
        });

        field.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_U, InputEvent.CTRL_DOWN_MASK), "killToStart");
        field.getActionMap().put("killToStart", new AbstractAction()
        {
            @Override
            public void actionPerformed(ActionEvent e) {
                var pos = field.getCaretPosition();
                if (pos > 0) {
                    killBuffer = field.getText().substring(0, pos);
                    field.setText(field.getText().substring(pos));
                    field.setCaretPosition(0);
                }
            }
        });

        field.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK), "yank");
        field.getActionMap().put("yank", new AbstractAction()
        {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (killBuffer != null && !killBuffer.isEmpty()) {
                    var pos = field.getCaretPosition();
                    var text = field.getText();
                    field.setText(text.substring(0, pos) + killBuffer + text.substring(pos));
                    field.setCaretPosition(pos + killBuffer.length());
                }
            }
        });
    }

    /**
     * Adds the command to history if not duplicate of last
     */
    private void addToHistory(String command)
    {
        if (commandHistory.isEmpty() ||
                !command.equals(commandHistory.get(commandHistory.size() - 1)))
        {
            commandHistory.add(command);
        }
        historyIndex = commandHistory.size();
    }

    /**
     * Build the context panel (unified table + action buttons).
     */
    private JPanel buildContextPanel()
    {
        contextPanel = new JPanel(new BorderLayout());
        contextPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Context",
                javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
                javax.swing.border.TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)
        ));
        
        contextTable = new JTable(new DefaultTableModel(
                new Object[]{"ID", "LOC", "Description", "Select"}, 0)
        {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 3; // Only the checkbox column
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return (columnIndex == 3) ? Boolean.class : Object.class;
            }
        });
        contextTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        
        // Add custom cell renderer for the description column to show italics for editable files
        contextTable.getColumnModel().getColumn(2).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
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

        // Hide the header for the "Select" column
        contextTable.getColumnModel().getColumn(3).setHeaderValue("");
        contextTable.getColumnModel().getColumn(3).setMaxWidth(60);

        contextTable.setIntercellSpacing(new Dimension(10,1));

        // column widths
        contextTable.getColumnModel().getColumn(0).setPreferredWidth(30);
        contextTable.getColumnModel().getColumn(1).setPreferredWidth(50);
        contextTable.getColumnModel().getColumn(2).setPreferredWidth(450);
        contextTable.getColumnModel().getColumn(3).setPreferredWidth(50);

        ((DefaultTableModel) contextTable.getModel()).addTableModelListener(e -> {
            if (e.getColumn() == 3) { // checkbox column changed
                updateContextButtons();
            }
        });

        locSummaryLabel = new JLabel(" ");
        locSummaryLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        locSummaryLabel.setBorder(new EmptyBorder(5,5,5,5));

        // Table panel
        var tablePanel = new JPanel(new BorderLayout());
        JScrollPane tableScrollPane = new JScrollPane(contextTable);
        // Set a preferred size to maintain height even when empty (almost works)
        tableScrollPane.setPreferredSize(new Dimension(600, 150));
        tablePanel.add(tableScrollPane, BorderLayout.CENTER);

        // Buttons panel
        var buttonsPanel = createContextButtonsPanel();

        contextPanel.setLayout(new BorderLayout());
        contextPanel.add(tablePanel, BorderLayout.CENTER);
        contextPanel.add(buttonsPanel, BorderLayout.EAST);
        contextPanel.add(locSummaryLabel, BorderLayout.SOUTH);
        contextPanel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                contextPanel.requestFocusInWindow();
            }
        });

        updateContextButtons();  // initialize
        locSummaryLabel.setText("No context - use Edit or Read or Summarize to add content");

        return contextPanel;
    }

    /**
     * Creates the panel with context action buttons: edit/read/summarize/drop/copy
     */
    private JPanel createContextButtonsPanel()
    {
        var buttonsPanel = new JPanel();
        buttonsPanel.setLayout(new BoxLayout(buttonsPanel, BoxLayout.Y_AXIS));
        buttonsPanel.setBorder(new EmptyBorder(5,5,5,5));

        if (editButton == null) {
            editButton = new JButton("Edit All");
            editButton.addActionListener(e -> {
                var selectedIndices = getSelectedFragmentIndices();
                currentUserTask = contextManager.performContextActionAsync("edit", selectedIndices);
            });
        }

        if (readOnlyButton == null) {
            readOnlyButton = new JButton("Read All");
            readOnlyButton.addActionListener(e -> {
                var selectedIndices = getSelectedFragmentIndices();
                currentUserTask = contextManager.performContextActionAsync("read", selectedIndices);
            });
        }

        if (summarizeButton == null) {
            summarizeButton = new JButton("Summarize All");
            summarizeButton.addActionListener(e -> {
                var selectedIndices = getSelectedFragmentIndices();
                currentUserTask = contextManager.performContextActionAsync("summarize", selectedIndices);
            });
        }

        if (dropButton == null) {
            dropButton = new JButton("Drop All");
            dropButton.addActionListener(e -> {
                var selectedIndices = getSelectedFragmentIndices();
                disableContextActionButtons();
                currentUserTask = contextManager.performContextActionAsync("drop", selectedIndices);
            });
        }

        if (copyButton == null) {
            copyButton = new JButton("Copy All");
            copyButton.addActionListener(e -> {
                var selectedIndices = getSelectedFragmentIndices();
                currentUserTask = contextManager.performContextActionAsync("copy", selectedIndices);
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
        buttonsPanel.add(Box.createRigidArea(new Dimension(0,5)));
        buttonsPanel.add(readOnlyButton);
        buttonsPanel.add(Box.createRigidArea(new Dimension(0,5)));
        buttonsPanel.add(summarizeButton);
        buttonsPanel.add(Box.createRigidArea(new Dimension(0,5)));
        buttonsPanel.add(dropButton);
        buttonsPanel.add(Box.createVerticalGlue());  // Push remaining buttons to bottom
        buttonsPanel.add(Box.createRigidArea(new Dimension(0,5)));
        buttonsPanel.add(copyButton);
        buttonsPanel.add(Box.createRigidArea(new Dimension(0,5)));
        buttonsPanel.add(pasteButton);

        return buttonsPanel;
    }

    /**
     * Disables the context action buttons while an action is in progress
     */
    private void disableContextActionButtons()
    {
        SwingUtilities.invokeLater(() -> {
            editButton.setEnabled(false);
            readOnlyButton.setEnabled(false);
            summarizeButton.setEnabled(false);
            dropButton.setEnabled(false);
            copyButton.setEnabled(false);
            pasteButton.setEnabled(false);
        });
    }

    /**
     * Re-enables context action buttons
     */
    public void enableContextActionButtons()
    {
        SwingUtilities.invokeLater(() -> {
            editButton.setEnabled(true);
            readOnlyButton.setEnabled(true);
            summarizeButton.setEnabled(true);
            dropButton.setEnabled(true);
            copyButton.setEnabled(true);
            pasteButton.setEnabled(true);
            updateContextButtons();
        });
    }

    /**
     * Check if any items are selected
     */
    private boolean hasSelectedItems()
    {
        if (contextTable.getModel().getRowCount() == 0) {
            return false;
        }
        var tableModel = (DefaultTableModel) contextTable.getModel();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            if (Boolean.TRUE.equals(tableModel.getValueAt(i, 3))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the list of selected fragment indices
     */
    private List<Integer> getSelectedFragmentIndices()
    {
        var indices = new ArrayList<Integer>();
        var tableModel = (DefaultTableModel) contextTable.getModel();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            if (Boolean.TRUE.equals(tableModel.getValueAt(i, 3))) {
                indices.add(Integer.parseInt(tableModel.getValueAt(i, 0).toString()));
            }
        }
        return indices;
    }

    /**
     * Update context action button labels
     */
    private void updateContextButtons()
    {
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
    }

    /**
     * Registers global keyboard shortcuts for undo/redo
     */
    private void registerGlobalKeyboardShortcuts()
    {
        var rootPane = frame.getRootPane();

        // Ctrl+Z => undo
        var undoKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK);
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(undoKeyStroke, "globalUndo");
        rootPane.getActionMap().put("globalUndo", new AbstractAction()
        {
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
        rootPane.getActionMap().put("globalRedo", new AbstractAction()
        {
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
        rootPane.getActionMap().put("globalPaste", new AbstractAction()
        {
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
    private JMenuBar buildMenuBar()
    {
        var menuBar = new JMenuBar();

        // File menu
        var fileMenu = new JMenu("File");
        fileMenu.setMnemonic(KeyEvent.VK_F);

        var addItem = new JMenuItem("Add context");
        addItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.ALT_DOWN_MASK));
        addItem.addActionListener(e -> {
            disableContextActionButtons();
            disableUserActionButtons();
            currentUserTask = contextManager.addContextViaDialogAsync();
        });
        fileMenu.add(addItem);

        var editKeysItem = new JMenuItem("Edit secret keys");
        editKeysItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_K, InputEvent.ALT_DOWN_MASK));
        editKeysItem.addActionListener(e -> showSecretKeysDialog());
        fileMenu.add(editKeysItem);

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

        var pasteMenuItem = new JMenuItem("Paste");
        pasteMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK));
        pasteMenuItem.addActionListener(e -> {
            currentUserTask = contextManager.performContextActionAsync("paste", List.of());
        });
        editMenu.add(pasteMenuItem);

        menuBar.add(editMenu);

        // Actions menu
        var actionsMenu = new JMenu("Actions");
        actionsMenu.setMnemonic(KeyEvent.VK_T);

        var setAutoContextItem = new JMenuItem("Set autocontext size");
        setAutoContextItem.addActionListener(e -> {
            // Simple spinner dialog
            var dialog = new JDialog(frame, "Set Autocontext Size", true);
            dialog.setLayout(new BorderLayout());

            var panel = new JPanel(new BorderLayout());
            panel.setBorder(new EmptyBorder(10,10,10,10));

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
                    KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE,0),
                    JComponent.WHEN_IN_FOCUSED_WINDOW
            );

            dialog.add(panel);
            dialog.pack();
            dialog.setLocationRelativeTo(frame);
            dialog.setVisible(true);
        });
        actionsMenu.add(setAutoContextItem);

        var refreshItem = new JMenuItem("Refresh Code Intelligence");
        refreshItem.addActionListener(e -> {
            contextManager.requestRebuild();
            toolOutput("Code intelligence will refresh in the background");
        });
        actionsMenu.add(refreshItem);

        menuBar.add(actionsMenu);

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
    private void showSecretKeysDialog()
    {
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
        mainPanel.setBorder(new EmptyBorder(10,10,10,10));

        var existingKeys = project.getLlmKeys();
        List<KeyValueRowPanel> keyRows = new ArrayList<>();

        var defaultKeyNames = coder.models.defaultKeyNames;

        // if empty, add one row
        if (existingKeys.isEmpty()) {
            var row = new KeyValueRowPanel(defaultKeyNames);
            keyRows.add(row);
            mainPanel.add(row);
        }

        // Actually, we want multiple rows in a vertical layout
        var keysPanel = new JPanel();
        keysPanel.setLayout(new BoxLayout(keysPanel, BoxLayout.Y_AXIS));

        if (!existingKeys.isEmpty()) {
            for (var entry : existingKeys.entrySet()) {
                var row = new KeyValueRowPanel(defaultKeyNames, entry.getKey(), entry.getValue());
                keyRows.add(row);
                keysPanel.add(row);
            }
        } else {
            var row = new KeyValueRowPanel(defaultKeyNames, "", "");
            keyRows.add(row);
            keysPanel.add(row);
        }

        var scrollPane = new JScrollPane(keysPanel);
        scrollPane.setPreferredSize(new Dimension((int)(frame.getWidth()*0.9), 250));
        mainPanel.add(scrollPane, BorderLayout.CENTER);

        // Add/Remove
        var addRemovePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        var addButton = new JButton("Add Key");
        addButton.addActionListener(ev -> {
            var newRow = new KeyValueRowPanel(defaultKeyNames);
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
            var newKeys = new java.util.HashMap<String,String>();
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
    public void toolOutput(String msg)
    {
        SwingUtilities.invokeLater(() -> {
            commandResultLabel.setText(msg);
            logger.info(msg);
        });
    }

    @Override
    public void toolErrorRaw(String msg)
    {
        SwingUtilities.invokeLater(() -> {
            commandResultLabel.setText("[ERROR] " + msg);
            logger.warn(msg);
        });
    }

    // Track if user has manually scrolled
    private boolean userHasManuallyScrolled = false;
    
    @Override
    public void llmOutput(String token)
    {
        SwingUtilities.invokeLater(() -> {
            // Append the text
            llmStreamArea.append(token);

            // Only auto-scroll if user hasn't scrolled manually
            if (!userHasManuallyScrolled) {
                llmStreamArea.setCaretPosition(llmStreamArea.getDocument().getLength());
            }
        });
    }

    @Override
    public boolean confirmAsk(String msg)
    {
        // Must block on EDT, so we use invokeAndWait
        final boolean[] result = new boolean[1];
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                int resp = JOptionPane.showConfirmDialog(frame, msg, "Confirm", JOptionPane.YES_NO_OPTION);
                result[0] = (resp == JOptionPane.YES_OPTION);
            } else {
                SwingUtilities.invokeAndWait(() -> {
                    int resp = JOptionPane.showConfirmDialog(frame, msg, "Confirm", JOptionPane.YES_NO_OPTION);
                    result[0] = (resp == JOptionPane.YES_OPTION);
                });
            }
        } catch (Exception e) {
            logger.error("confirmAsk error", e);
            return false;
        }
        return result[0];
    }

    @Override
    public char askOptions(String msg, String options)
    {
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
            return options.toLowerCase().charAt(options.length()-1);
        }
        return selected[0];
    }

    private char internalAskOptions(String msg, String options)
    {
        var optsArr = options.chars().mapToObj(c -> String.valueOf((char)c)).toArray(String[]::new);
        var choice = (String) JOptionPane.showInputDialog(
                frame, msg, "Choose Option",
                JOptionPane.PLAIN_MESSAGE, null,
                optsArr, (optsArr.length > 0) ? optsArr[0] : null
        );
        if (choice == null || choice.isEmpty()) {
            return options.toLowerCase().charAt(options.length()-1);
        }
        return choice.toLowerCase().charAt(0);
    }

    @Override
    public void spin(String message)
    {
        SwingUtilities.invokeLater(() -> backgroundStatusLabel.setText("Working on: " + message));
    }

    @Override
    public void spinComplete()
    {
        SwingUtilities.invokeLater(() -> backgroundStatusLabel.setText(""));
    }

    @Override
    public boolean isSpinning()
    {
        return !backgroundStatusLabel.getText().isBlank();
    }

    @Override
    public void clear()
    {
        SwingUtilities.invokeLater(() -> {
            llmStreamArea.setText("");
            commandResultLabel.setText("");
        });
    }

    /**
     * Repopulate the unified context table from the given context.
     */
    public void updateContextTable(Context context)
    {
        SwingUtilities.invokeLater(() -> {
            // Clear the existing table rows
            var tableModel = (DefaultTableModel) contextTable.getModel();
            tableModel.setRowCount(0);

            updateContextButtons();
            
            if (context.isEmpty()) {
                locSummaryLabel.setText("No context - use Edit or Read or Summarize to add content");
                contextPanel.revalidate();
                contextPanel.repaint();
                return;
            }
            
            // Fill the table with new data
            var allFragments = context.getAllFragmentsInDisplayOrder();
            int totalLines = 0;
            for (var frag : allFragments) {
                var id = context.getPositionOfFragment(frag);
                var loc = countLinesSafe(frag);
                totalLines += loc;
                var desc = frag.description();

                var isEditable = (frag instanceof ContextFragment.RepoPathFragment)
                        && context.editableFiles().anyMatch(e -> e == frag);

                // Create a custom cell renderer for italicizing editable entries
                if (isEditable) {
                    desc = "✏️ " + desc;  // Add pencil icon to editable files
                }

                tableModel.addRow(new Object[]{id, loc, desc, false});
            }
            
            var fullText = "";  // no large merges needed
            var approxTokens = Models.getApproximateTokens(fullText);
            
            locSummaryLabel.setText(
                    "Total: %,d LOC, or about %,dk tokens".formatted(totalLines, approxTokens / 1000)
            );
            
            // Just revalidate/repaint the panel to reflect the new rows
            contextPanel.revalidate();
            contextPanel.repaint();
        });
    }

    private int countLinesSafe(ContextFragment fragment)
    {
        try {
            var text = fragment.text();
            if (text.isEmpty()) return 0;
            return text.split("\\r?\\n", -1).length;
        } catch (Exception e) {
            toolErrorRaw("Error reading fragment: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Outputs shell command results to the LLM stream area
     */
    public void shellOutput(String st)
    {
        SwingUtilities.invokeLater(() -> {
            if (!llmStreamArea.getText().endsWith("\n\n")) {
                llmStreamArea.append("\n");
            }
            llmStreamArea.append(st);
            if (!userHasManuallyScrolled) {
                llmStreamArea.setCaretPosition(llmStreamArea.getDocument().getLength());
            }
        });
    }

    @Override
    public void close()
    {
        logger.info("Closing Chrome UI");
        if (frame != null) {
            frame.dispose();
        }
    }

    JFrame getFrame() {
        assert SwingUtilities.isEventDispatchThread() : "Not on EDT";
        return frame;
    }

    /**
     * Helper row panel for editing secret keys in a single row
     */
    private static class KeyValueRowPanel extends JPanel
    {
        private final JComboBox<String> keyNameCombo;
        private final JTextField keyValueField;

        public KeyValueRowPanel(String[] defaultKeyNames)
        {
            this(defaultKeyNames, "", "");
        }

        public KeyValueRowPanel(String[] defaultKeyNames, String initialKey, String initialValue)
        {
            setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
            setBorder(new EmptyBorder(5,0,5,0));

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

            keyNameCombo.setPreferredSize(new Dimension(150,25));
            keyValueField.setPreferredSize(new Dimension(250,25));

            keyNameCombo.setMaximumSize(new Dimension(150,25));
            keyValueField.setMaximumSize(new Dimension(Short.MAX_VALUE,25));

            add(new JLabel("Key: "));
            add(keyNameCombo);
            add(Box.createRigidArea(new Dimension(10,0)));
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
