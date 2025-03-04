package io.github.jbellis.brokk;

import io.github.jbellis.brokk.gui.FileSelectionDialog;
import io.github.jbellis.brokk.ContextManager.OperationResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.autocomplete.AutoCompletion;
import org.fife.ui.autocomplete.CompletionProvider;
import org.fife.ui.autocomplete.DefaultCompletionProvider;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;

/**
 * Chrome provides a Swing-based UI for Brokk, replacing the old Lanterna-based ConsoleIO.
 * It implements IConsoleIO so the rest of the code can call io.toolOutput(...), etc.
 *
 * It sets up a main JFrame with:
 *   1) A top RSyntaxTextArea for LLM output & shell output
 *   2) A single-line command input field with Emacs-style keybindings
 *   3) A context panel showing read-only and editable files
 *   4) A command result label for showing success/error messages
 *   5) A background status label at the bottom to show spinners or tasks
 *
 * This example includes corner-case handling:
 *   - Large LLM outputs, spinner updates, canceled tasks, etc.
 *   - Minimal confirmation dialogs using confirmAsk(...)
 *   - Minimal multi-option dialogs using askOptions(...)
 */

public class Chrome implements AutoCloseable, IConsoleIO {

    private static final Logger logger = LogManager.getLogger(Chrome.class);

    // Dependencies:
    private ContextManager contextManager;
    private Coder coder;
    private Commands commands;

    // Swing components:
    private JFrame frame;
    private RSyntaxTextArea llmStreamArea;
    private JLabel commandResultLabel;
    private JTextField commandInputField;
    private JLabel backgroundStatusLabel;

    // Context Panel & tables:
    private JPanel contextPanel;
    private JTable readOnlyTable;
    private JTable editableTable;
    private JLabel locSummaryLabel;
    
    // Context action buttons:
    private JButton editButton;
    private JButton readOnlyButton;
    private JButton summarizeButton;
    private JButton dropButton;

    // History:
    private final List<String> commandHistory = new ArrayList<>();
    private int historyIndex = -1;

    // For implementing "kill" / "yank" (Emacs-like)
    private String killBuffer = "";

    /**
     * Default constructor sets up the UI.
     * We call this from Brokk after creating contextManager, commands, etc.,
     * but before calling .resolveCircularReferences(...).
     */
    public Chrome() {
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

        // 5) Show window
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    /**
     * Finish wiring references to contextManager, commands, coder, etc.
     */
    public void resolveCircularReferences(ContextManager contextManager, Coder coder) {
        this.contextManager = contextManager;
        this.coder = coder;
        this.commands = new Commands(contextManager); // If needed, or you re-use the existing one
        // If you already have a `commands` reference, do:
        // this.commands = commands;

        // Now, also tell the commands object to use this as IConsoleIO:
        this.commands.resolveCircularReferences(this, coder);

        // If you want to load or unify command history from a file, etc. do that here
    }

    /**
     * Build the main panel that includes:
     *  - the LLM stream (top)
     *  - the command result label
     *  - the command input
     *  - the context panel
     *  - the background status label at bottom
     * This layout matches the old Lanterna ConsoleIO vertical arrangement.
     */
    private JPanel buildMainPanel() {
        // Create a main panel with BorderLayout
        JPanel panel = new JPanel(new BorderLayout());
        
        // Create a panel with GridBagLayout for precise control
        JPanel contentPanel = new JPanel(new GridBagLayout());
                GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 1.0;
        gbc.gridx = 0;
        gbc.insets = new Insets(2, 2, 2, 2);
        
        // 1. LLM streaming area (takes most of the space)
                JScrollPane llmScrollPane = buildLLMStreamScrollPane();
        gbc.weighty = 1.0;
        gbc.gridy = 0;
        contentPanel.add(llmScrollPane, gbc);
        
                // 2. Command result label
        JComponent resultLabel = buildCommandResultLabel();
        gbc.weighty = 0.0;
        gbc.gridy = 1;
        contentPanel.add(resultLabel, gbc);
        
        // 3. Command input with prompt
        JPanel commandPanel = buildCommandInputPanel();
        gbc.gridy = 2;
        contentPanel.add(commandPanel, gbc);
        
        // 4. Context panel (with border title)
        JPanel ctxPanel = buildContextPanel();
                gbc.weighty = 0.2;
        gbc.gridy = 3;
        contentPanel.add(ctxPanel, gbc);
        
        // 5. Background status label at the very bottom
                JComponent statusLabel = buildBackgroundStatusLabel();
        gbc.weighty = 0.0;
        gbc.gridy = 4;
        contentPanel.add(statusLabel, gbc);
        
        panel.add(contentPanel, BorderLayout.CENTER);
        return panel;
    }

    /**
     * Creates the RSyntaxTextArea for the LLM stream, wrapped in a JScrollPane.
     * This matches the main output area in the old Lanterna UI.
     */
    private JScrollPane buildLLMStreamScrollPane() {
        llmStreamArea = new RSyntaxTextArea();
        llmStreamArea.setEditable(false);
        // We'll treat the content as plain text or "none"
        llmStreamArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
        llmStreamArea.setAutoscrolls(true);
        llmStreamArea.setLineWrap(true); // Enable line wrapping like Lanterna
        llmStreamArea.setWrapStyleWord(true); // Wrap at word boundaries
        
        // Use a monospaced font like Lanterna terminal
        llmStreamArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        
        JScrollPane scrollPane = new JScrollPane(llmStreamArea);
        return scrollPane;
    }

    /**
     * Creates the command result label used to display messages from commands.
     * Matches the style of the Lanterna version.
     */
    private JComponent buildCommandResultLabel() {
        commandResultLabel = new JLabel(" ");
        commandResultLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        commandResultLabel.setBorder(new EmptyBorder(5, 10, 5, 10));
        // Make it visible with a subtle background
        commandResultLabel.setOpaque(true);
        commandResultLabel.setBackground(new Color(245, 245, 245));
        return commandResultLabel;
    }

    /**
     * Creates the bottom-most background status label
     * that shows "Working on: ..." or is blank when idle.
     * Matches the Lanterna status display.
     */
    private JComponent buildBackgroundStatusLabel() {
        backgroundStatusLabel = new JLabel(" ");
        backgroundStatusLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        backgroundStatusLabel.setBorder(new EmptyBorder(3, 10, 3, 10));
        backgroundStatusLabel.setOpaque(true);
        backgroundStatusLabel.setBackground(new Color(240, 240, 240));
        // Add a line border above to separate from other content
        backgroundStatusLabel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY),
            new EmptyBorder(5, 10, 5, 10)
        ));
        return backgroundStatusLabel;
    }

    /**
     * Creates a horizontal panel with a prompt and a single-line text field for commands.
     * This matches the command input area in Lanterna.
     */
    private JPanel buildCommandInputPanel() {
        // Use BorderLayout to make the text field expand horizontally
        JPanel panel = new JPanel(new BorderLayout());

        // Create a prompt label with fixed width
        JLabel promptLabel = new JLabel("> ");
        promptLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        promptLabel.setBorder(new EmptyBorder(2, 5, 2, 0));

        // Command input field takes remaining width
        commandInputField = new JTextField();
        commandInputField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));

        // Match the Lanterna look with a border
        commandInputField.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Color.GRAY),
            BorderFactory.createEmptyBorder(2, 5, 2, 5)
        ));

        // Keybindings for Emacs-like shortcuts
        bindEmacsKeys(commandInputField);

        // Basic approach: pressing Enter runs the command
        commandInputField.addActionListener(e -> {
            String text = commandInputField.getText();
            if (text != null && !text.isBlank()) {
                onUserCommand(text);
            }
        });

        // Add components to the panel - ensuring full width
        panel.add(promptLabel, BorderLayout.WEST);
        panel.add(commandInputField, BorderLayout.CENTER);

        // Create a wrapper panel with some padding
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBorder(new EmptyBorder(5, 5, 5, 5));
        wrapper.add(panel, BorderLayout.CENTER);

        return wrapper;
    }

    /**
     * Binds basic Emacs/readline-like keys to the given text field.
     */
    private void bindEmacsKeys(JTextField field) {
        // Example: ctrl-A => move cursor to start
        field.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK), "moveHome");
        field.getActionMap().put("moveHome", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                field.setCaretPosition(0);
            }
        });

        // Similarly: ctrl-E => move cursor to end
        field.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_E, InputEvent.CTRL_DOWN_MASK), "moveEnd");
        field.getActionMap().put("moveEnd", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                field.setCaretPosition(field.getText().length());
            }
        });

        // ctrl-B => move backward
        field.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_B, InputEvent.CTRL_DOWN_MASK), "moveLeft");
        field.getActionMap().put("moveLeft", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                int pos = field.getCaretPosition();
                if (pos > 0) {
                    field.setCaretPosition(pos - 1);
                }
            }
        });

        // ctrl-F => move forward
        field.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), "moveRight");
        field.getActionMap().put("moveRight", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                int pos = field.getCaretPosition();
                if (pos < field.getText().length()) {
                    field.setCaretPosition(pos + 1);
                }
            }
        });

        // ctrl-D => delete char at cursor
        field.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_D, InputEvent.CTRL_DOWN_MASK), "delChar");
        field.getActionMap().put("delChar", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                int pos = field.getCaretPosition();
                String text = field.getText();
                if (pos < text.length()) {
                    field.setText(text.substring(0, pos) + text.substring(pos + 1));
                    field.setCaretPosition(pos);
                }
            }
        });

        // ctrl-K => kill to end of line
        field.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_K, InputEvent.CTRL_DOWN_MASK), "killLine");
        field.getActionMap().put("killLine", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                int pos = field.getCaretPosition();
                String text = field.getText();
                if (pos < text.length()) {
                    killBuffer = text.substring(pos);
                    field.setText(text.substring(0, pos));
                } else {
                    killBuffer = "";
                }
            }
        });

        // ctrl-U => kill to beginning of line
        field.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_U, InputEvent.CTRL_DOWN_MASK), "killToStart");
        field.getActionMap().put("killToStart", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                int pos = field.getCaretPosition();
                if (pos > 0) {
                    killBuffer = field.getText().substring(0, pos);
                    field.setText(field.getText().substring(pos));
                    field.setCaretPosition(0);
                }
            }
        });

        // ctrl-Y => yank
        field.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK), "yank");
        field.getActionMap().put("yank", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (killBuffer != null && !killBuffer.isEmpty()) {
                    int pos = field.getCaretPosition();
                    String text = field.getText();
                    field.setText(text.substring(0, pos) + killBuffer + text.substring(pos));
                    field.setCaretPosition(pos + killBuffer.length());
                }
            }
        });

        // ctrl-L => clear LLM area
        field.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_L, InputEvent.CTRL_DOWN_MASK), "clearLLM");
        field.getActionMap().put("clearLLM", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                llmStreamArea.setText("");
            }
        });

        // ctrl-P => previous item in history
        field.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_P, InputEvent.CTRL_DOWN_MASK), "histPrev");
        field.getActionMap().put("histPrev", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                navigateHistory(-1);
            }
        });

        // ctrl-N => next item in history
        field.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK), "histNext");
        field.getActionMap().put("histNext", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                navigateHistory(1);
            }
        });

        // alt-B => move back one word
        field.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_B, InputEvent.ALT_DOWN_MASK), "moveLeftWord");
        field.getActionMap().put("moveLeftWord", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                int pos = field.getCaretPosition();
                String text = field.getText();
                while (pos > 0 && !Character.isWhitespace(text.charAt(pos - 1))) {
                    pos--;
                }
                field.setCaretPosition(pos);
            }
        });

        // alt-F => move forward one word
        field.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.ALT_DOWN_MASK), "moveRightWord");
        field.getActionMap().put("moveRightWord", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                int pos = field.getCaretPosition();
                String text = field.getText();
                while (pos < text.length() && !Character.isWhitespace(text.charAt(pos))) {
                    pos++;
                }
                field.setCaretPosition(pos);
            }
        });

        // alt-D => delete word forward
        field.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_D, InputEvent.ALT_DOWN_MASK), "delWord");
        field.getActionMap().put("delWord", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                int pos = field.getCaretPosition();
                String text = field.getText();
                int start = pos;
                while (pos < text.length() && !Character.isWhitespace(text.charAt(pos))) {
                    pos++;
                }
                if (pos > start) {
                    field.setText(text.substring(0, start) + text.substring(pos));
                    field.setCaretPosition(start);
                }
            }
        });
    }

    /**
     * Allows stepping up or down through the command history.
     * direction = -1 => previous
     * direction = +1 => next
     */
    private void navigateHistory(int direction) {
        if (commandHistory.isEmpty()) return;

        if (historyIndex < 0) {
            // The user might be at the 'end' of the history
            historyIndex = commandHistory.size();
        }
        historyIndex += direction;
        if (historyIndex < 0) {
            historyIndex = 0;
        } else if (historyIndex >= commandHistory.size()) {
            historyIndex = commandHistory.size();
            commandInputField.setText("");
            return;
        }
        commandInputField.setText(commandHistory.get(historyIndex));
        commandInputField.setCaretPosition(commandInputField.getText().length());
    }

    /**
     * Called when user presses Enter on the command input field.
     * We'll parse the input. If it starts with '$', run shell. Otherwise, pass to LLM.
     */
    private void onUserCommand(String input) {
        addToHistory(input);
        commandInputField.setText("");

        if (contextManager == null || coder == null) {
            toolErrorRaw("ContextManager/Coder not ready");
            return;
        }

        // For backward-compat: if starts with "/", we might still pass it to commands
        if (input.startsWith("/") || input.startsWith("$")) {
            var result = commands.handleCommand(input); // partial fallback
            showOperationResult(result);
        } else {
            // Just treat as user request to LLM
            LLM.runSession(coder, this, contextManager.getCurrentModel(coder.models), input);
        }
    }

    /**
     * Show the outcome of a slash-command or shell command in the commandResultLabel.
     */
    private void showOperationResult(ContextManager.OperationResult result) {
        if (result == null) return;
        switch (result.status()) {
            case ERROR -> {
                if (result.message() != null) {
                    toolErrorRaw(result.message());
                }
            }
            case SUCCESS -> {
                if (result.message() != null) {
                    toolOutput(result.message());
                }
            }
            case PREFILL -> {
                if (result.message() != null) {
                    commandInputField.setText(result.message());
                }
            }
            case SKIP_SHOW -> {
                // no op
            }
        }
    }

    /**
     * Persists the command in memory. For advanced usage, you can store it to .brokk/linereader.txt, etc.
     */
    private void addToHistory(String command) {
        if (commandHistory.isEmpty() || !command.equals(commandHistory.get(commandHistory.size() - 1))) {
            commandHistory.add(command);
        }
        historyIndex = commandHistory.size();
    }

    /**
     * Build the context panel for read-only + editable tables, and a summary label.
     * This matches the context display from the Lanterna UI.
     */
    private JPanel buildContextPanel() {
        // Create main context panel with border
        contextPanel = new JPanel(new BorderLayout());
        contextPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Context",
                javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
                javax.swing.border.TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)
        ));

        // Initialize the tables even though they may not be shown initially
        // Configure read-only table with monospaced font and checkbox column
        readOnlyTable = new JTable(new DefaultTableModel(new Object[]{"ID", "LOC", "Description", "Select"}, 0) {
            @Override 
            public boolean isCellEditable(int row, int column) {
                return column == 3; // Only checkbox column is editable
            }
            
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return columnIndex == 3 ? Boolean.class : Object.class;
            }
        });
        readOnlyTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        readOnlyTable.setRowHeight(18);
        readOnlyTable.setTableHeader(null);
        readOnlyTable.setIntercellSpacing(new Dimension(10, 1));
        // Column widths similar to Lanterna layout
        readOnlyTable.getColumnModel().getColumn(0).setPreferredWidth(30);
        readOnlyTable.getColumnModel().getColumn(1).setPreferredWidth(50);
        readOnlyTable.getColumnModel().getColumn(2).setPreferredWidth(450);
        readOnlyTable.getColumnModel().getColumn(3).setPreferredWidth(50);

        // Configure editable table with monospaced font and checkbox column
        editableTable = new JTable(new DefaultTableModel(new Object[]{"ID", "LOC", "Description", "Select"}, 0) {
            @Override 
            public boolean isCellEditable(int row, int column) {
                return column == 3; // Only checkbox column is editable
            }
            
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return columnIndex == 3 ? Boolean.class : Object.class;
            }
        });
        editableTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        editableTable.setRowHeight(18);
        editableTable.setTableHeader(null);
        editableTable.setIntercellSpacing(new Dimension(10, 1));
        // Column widths similar to Lanterna layout
        editableTable.getColumnModel().getColumn(0).setPreferredWidth(30);
        editableTable.getColumnModel().getColumn(1).setPreferredWidth(50);
        editableTable.getColumnModel().getColumn(2).setPreferredWidth(450);
        editableTable.getColumnModel().getColumn(3).setPreferredWidth(50);
        
        // Add listeners to checkbox changes to update button states
        ((DefaultTableModel)readOnlyTable.getModel()).addTableModelListener(e -> {
            if (e.getColumn() == 3) { // Checkbox column
                updateContextButtonLabels();
            }
        });
        
        ((DefaultTableModel)editableTable.getModel()).addTableModelListener(e -> {
            if (e.getColumn() == 3) { // Checkbox column
                updateContextButtonLabels();
            }
        });

        // Create the summary label for LOC/tokens with monospaced font
        locSummaryLabel = new JLabel(" ");
        locSummaryLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        locSummaryLabel.setBorder(new EmptyBorder(5, 5, 5, 5));
        
        // Buttons will be created by createContextButtonsPanel()

        // Set up initial tables with scroll panes (even if empty)
        JPanel tablesPanel = new JPanel(new GridLayout(2, 1, 0, 5));

        // Read-only panel with label and table
        JPanel readOnlyPanel = new JPanel(new BorderLayout());
        JLabel roLabel = new JLabel("Read-only");
                roLabel.setBorder(new EmptyBorder(2, 0, 2, 0));
        roLabel.setFont(new Font(Font.DIALOG, Font.BOLD, 11));
                readOnlyPanel.add(roLabel, BorderLayout.NORTH);
        readOnlyPanel.add(new JScrollPane(readOnlyTable), BorderLayout.CENTER);

        // Editable panel with label and table
        JPanel editablePanel = new JPanel(new BorderLayout());
        JLabel edLabel = new JLabel("Editable");
        edLabel.setBorder(new EmptyBorder(2, 0, 2, 0));
        edLabel.setFont(new Font(Font.DIALOG, Font.BOLD, 11));
        editablePanel.add(edLabel, BorderLayout.NORTH);
        editablePanel.add(new JScrollPane(editableTable), BorderLayout.CENTER);

        // Add both panels to the tables panel
        tablesPanel.add(readOnlyPanel);
        tablesPanel.add(editablePanel);

        // Add the buttons panel to the right side
        JPanel buttonsPanel = createContextButtonsPanel();
        
        // Set up initial layout with tables and buttons
        contextPanel.setLayout(new BorderLayout());
        contextPanel.add(tablesPanel, BorderLayout.CENTER);
        contextPanel.add(buttonsPanel, BorderLayout.EAST);
        contextPanel.add(locSummaryLabel, BorderLayout.SOUTH);
        
        // Initially disable drop button since we have no context
        dropButton.setEnabled(false);
        
        // Set initial message in summary label
        locSummaryLabel.setText("No context - use Edit new files or Read new files buttons to add content");

        return contextPanel;
    }

    /**
     * Builds the menu bar with items for application actions.
     * Context manipulation is now handled by direct buttons in the context panel.
     */
    private JMenuBar buildMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        // File menu
        JMenu fileMenu = new JMenu("File");
        fileMenu.setMnemonic(KeyEvent.VK_F);

        JMenuItem addItem = new JMenuItem("Add context");
        addItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.ALT_DOWN_MASK));
        addItem.addActionListener(e -> doAddContext());
        fileMenu.add(addItem);

        menuBar.add(fileMenu);

        // Edit menu
        JMenu editMenu = new JMenu("Edit");
        editMenu.setMnemonic(KeyEvent.VK_E);

        JMenuItem undoItem = new JMenuItem("Undo");
        undoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK));
        undoItem.addActionListener(e -> showOperationResult(contextManager.undoContext()));
        editMenu.add(undoItem);

        JMenuItem redoItem = new JMenuItem("Redo");
        redoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK));
        redoItem.addActionListener(e -> showOperationResult(contextManager.redoContext()));
        editMenu.add(redoItem);

        menuBar.add(editMenu);

        // Tools or "View" or "Actions" menu
        JMenu actionsMenu = new JMenu("Actions");
        actionsMenu.setMnemonic(KeyEvent.VK_T);

        JMenuItem refreshItem = new JMenuItem("Refresh Code Intelligence");
        refreshItem.addActionListener(e -> {
            contextManager.requestRebuild();
            toolOutput("Code intelligence will refresh in the background");
        });
        actionsMenu.add(refreshItem);

        menuBar.add(actionsMenu);

        // Help
        JMenu helpMenu = new JMenu("Help");
        helpMenu.setMnemonic(KeyEvent.VK_H);

        JMenuItem aboutItem = new JMenuItem("About");
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

    private List<RepoFile> showFileSelectionDialog(String title) {
        var dialog = new FileSelectionDialog(frame, contextManager.getRoot(), title);
        dialog.setVisible(true);
        if (dialog.isConfirmed()) {
            return dialog.getSelectedFiles();
        }
        return List.of();
    }

    /**
     * Simplistic example that pops up a file chooser to add files to the context manager.
     */
    private void doAddContext() {
        if (contextManager == null) {
            toolErrorRaw("Cannot add context, no manager");
            return;
        }
        var files = showFileSelectionDialog("Add Context");
        if (!files.isEmpty()) {
            contextManager.addFiles(files);
            toolOutput("Added: " + files);
        }
    }

    /**
     * Similar approach to add read-only context.
     */
    private void doReadContext() {
        if (contextManager == null) {
            toolErrorRaw("Cannot read context, no manager");
            return;
        }
        JFileChooser chooser = new JFileChooser(contextManager.getRoot().toFile());
        chooser.setMultiSelectionEnabled(true);
        int result = chooser.showOpenDialog(frame);
        if (result == JFileChooser.APPROVE_OPTION) {
            var files = chooser.getSelectedFiles();
            if (files.length == 0) {
                toolOutput("No files selected");
                return;
            }
            var repoFiles = new ArrayList<RepoFile>();
            for (var f : files) {
                var rel = contextManager.getRoot().relativize(f.toPath()).toString();
                repoFiles.add(contextManager.toFile(rel));
            }
            contextManager.addReadOnlyFiles(repoFiles);
            toolOutput("Added read-only " + repoFiles);
        }
    }

    /**
     * Drops all context from the ContextManager.
     */
    private void doDropAll() {
        if (contextManager == null) {
            toolErrorRaw("Cannot drop context, no manager");
            return;
        }
        var res = contextManager.dropAll();
        showOperationResult(res);
    }

    /**
     * For the IConsoleIO interface, we set the text in commandResultLabel.
     */
    @Override
    public void toolOutput(String msg) {
        commandResultLabel.setText(msg);
        logger.info(msg);
    }

    @Override
    public void toolErrorRaw(String msg) {
        commandResultLabel.setText("[ERROR] " + msg);
        logger.warn(msg);
    }

    @Override
    public void llmOutput(String token) {
        llmStreamArea.append(token);
        // auto-scroll to bottom
        llmStreamArea.setCaretPosition(llmStreamArea.getDocument().getLength());
    }

    @Override
    public boolean confirmAsk(String msg) {
        int resp = JOptionPane.showConfirmDialog(frame, msg, "Confirm", JOptionPane.YES_NO_OPTION);
        return (resp == JOptionPane.YES_OPTION);
    }

    public char askOptions(String msg, String options) {
        // e.g. "Action for file X? (A)dd, (R)ead, (S)ummarize, (I)gnore"
        // Implement a simple input dialog or combo selection:
        String[] optsArr = options.chars().mapToObj(c -> String.valueOf((char)c)).toArray(String[]::new);
        String choice = (String) JOptionPane.showInputDialog(
                frame, msg, "Choose Option",
                JOptionPane.PLAIN_MESSAGE, null,
                optsArr, optsArr.length > 0 ? optsArr[0] : null
        );
        if (choice == null || choice.isEmpty()) {
            return options.toLowerCase().charAt(options.length()-1);
        }
        return choice.toLowerCase().charAt(0);
    }

    @Override
    public void spin(String message) {
        backgroundStatusLabel.setText("Working on: " + message);
    }

    @Override
    public void spinComplete() {
        backgroundStatusLabel.setText("");
    }

    @Override
    public boolean isSpinning() {
        return !backgroundStatusLabel.getText().isBlank();
    }

    public String getRawInput() {
        // Not used in the same way with Swing, but you could:
        return commandInputField.getText();
    }

    public void clear() {
        llmStreamArea.setText("");
        commandResultLabel.setText("");
    }

    /**
     * Repopulate the readOnlyTable / editableTable from the given context.
     */
    public void updateContextTable(Context context) {
        // Reset the contextPanel
        contextPanel.removeAll();
        
        // Create tables panel (even for empty context)
        JPanel tablesPanel = new JPanel(new GridLayout(2, 1, 0, 5));

        // Read-only panel with label and table
        JPanel readOnlyPanel = new JPanel(new BorderLayout());
        JLabel roLabel = new JLabel("Read-only");
        roLabel.setBorder(new EmptyBorder(2, 0, 2, 0));
        roLabel.setFont(new Font(Font.DIALOG, Font.BOLD, 11));
                    readOnlyPanel.add(roLabel, BorderLayout.NORTH);
        readOnlyPanel.add(new JScrollPane(readOnlyTable), BorderLayout.CENTER);

        // Editable panel with label and table
                    JPanel editablePanel = new JPanel(new BorderLayout());
        JLabel edLabel = new JLabel("Editable");
        edLabel.setBorder(new EmptyBorder(2, 0, 2, 0));
        edLabel.setFont(new Font(Font.DIALOG, Font.BOLD, 11));
        editablePanel.add(edLabel, BorderLayout.NORTH);
        editablePanel.add(new JScrollPane(editableTable), BorderLayout.CENTER);

        // Add both panels to the tables panel
        tablesPanel.add(readOnlyPanel);
        tablesPanel.add(editablePanel);

        // Create buttons panel
        JPanel buttonsPanel = createContextButtonsPanel();

        // Set up layout
        contextPanel.setLayout(new BorderLayout());
        contextPanel.add(tablesPanel, BorderLayout.CENTER);
        contextPanel.add(buttonsPanel, BorderLayout.EAST);
        contextPanel.add(locSummaryLabel, BorderLayout.SOUTH);

        // Clear the tables
        var roModel = (DefaultTableModel) readOnlyTable.getModel();
        var edModel = (DefaultTableModel) editableTable.getModel();
        roModel.setRowCount(0);
        edModel.setRowCount(0);

        // Update button states based on empty context
        dropButton.setEnabled(!context.isEmpty());
        updateContextButtonLabels();

        // If context is empty, show message in summary label and return
        if (context.isEmpty()) {
            locSummaryLabel.setText("No context - use Edit new files or Read new files buttons to add content");
            
            // Refresh the UI
            contextPanel.revalidate();
            contextPanel.repaint();
            return;
        }
        
        // Enable drop button since we have context
        dropButton.setEnabled(true);

        // Populate the tables
        var allFragments = context.getAllFragmentsInDisplayOrder();
        int totalLines = 0;
        for (ContextFragment frag : allFragments) {
            int id = context.getPositionOfFragment(frag);
            int loc = countLinesSafe(frag);
            totalLines += loc;
            String desc = frag.description();

            boolean isEditable = (frag instanceof ContextFragment.RepoPathFragment)
                    && context.editableFiles().anyMatch(e -> e == frag);

            if (isEditable) {
                edModel.addRow(new Object[]{id, loc, desc, false});
            } else {
                roModel.addRow(new Object[]{id, loc, desc, false});
            }
        }

        // approximate token count
        // (not strictly the same as old code, but an example)
        String fullText = "";
        // In real usage, you'd gather the text from DefaultPrompts or something
        // fullText = ...
        int approxTokens = Models.getApproximateTokens(fullText);

        locSummaryLabel.setText("Total LOC: " + totalLines + ", or about " + (approxTokens/1000) + "k tokens");
        
        // Refresh the UI
        contextPanel.revalidate();
        contextPanel.repaint();
    }

    /**
     * Safe line count helper
     */
    private int countLinesSafe(ContextFragment fragment) {
        try {
            String text = fragment.text();
            if (text.isEmpty()) return 0;
            return text.split("\\r?\\n", -1).length;
        } catch (Exception e) {
            toolErrorRaw("Error reading fragment: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Updates button labels based on whether items are selected in the tables
     * and whether we have context
     */
    private void updateContextButtonLabels() {
                boolean hasSelection = hasSelectedItems();
        boolean hasContext = contextManager != null && !contextManager.currentContext().isEmpty();

        if (hasContext) {
            editButton.setText(hasSelection ? "Edit Selected" : "Edit All");
            readOnlyButton.setText(hasSelection ? "Read Selected" : "Read All");
            summarizeButton.setText(hasSelection ? "Summarize Selected" : "Summarize All");
            dropButton.setText(hasSelection ? "Drop Selected" : "Drop All");
        } else {
            editButton.setText("Edit new files");
            readOnlyButton.setText("Read new files");
            summarizeButton.setText("Summarize new files");
            dropButton.setText("Drop All");
        }
    }
    
    /**
     * Creates the panel with context action buttons
     */
    private JPanel createContextButtonsPanel() {
        JPanel buttonsPanel = new JPanel(new GridLayout(4, 1, 5, 5));
        buttonsPanel.setBorder(new EmptyBorder(5, 5, 5, 5));

        // Initialize buttons if they don't exist yet
        if (editButton == null) {
            editButton = new JButton("Edit All");
            editButton.setMnemonic(KeyEvent.VK_E);
            editButton.addActionListener(e -> performContextAction("edit"));
                }
        
        if (readOnlyButton == null) {
            readOnlyButton = new JButton("Read All");
            readOnlyButton.setMnemonic(KeyEvent.VK_R);
            readOnlyButton.addActionListener(e -> performContextAction("read"));
        }
        
        if (summarizeButton == null) {
            summarizeButton = new JButton("Summarize All");
            summarizeButton.setMnemonic(KeyEvent.VK_S);
            summarizeButton.addActionListener(e -> performContextAction("summarize"));
        }
        
        if (dropButton == null) {
            dropButton = new JButton("Drop All");
            dropButton.setMnemonic(KeyEvent.VK_D);
            dropButton.addActionListener(e -> performContextAction("drop"));
        }

        // Add buttons to panel
        buttonsPanel.add(editButton);
        buttonsPanel.add(readOnlyButton);
        buttonsPanel.add(summarizeButton);
        buttonsPanel.add(dropButton);

        return buttonsPanel;
    }
    
    /**
     * Check if any items are selected in either table
     */
    private boolean hasSelectedItems() {
        if (readOnlyTable.getModel().getRowCount() == 0 && editableTable.getModel().getRowCount() == 0) {
            return false;
        }
        
        // Check for any true value in checkbox column of either table
        DefaultTableModel roModel = (DefaultTableModel) readOnlyTable.getModel();
        DefaultTableModel edModel = (DefaultTableModel) editableTable.getModel();
        
        for (int i = 0; i < roModel.getRowCount(); i++) {
            if (Boolean.TRUE.equals(roModel.getValueAt(i, 3))) {
                return true;
            }
        }
        
        for (int i = 0; i < edModel.getRowCount(); i++) {
            if (Boolean.TRUE.equals(edModel.getValueAt(i, 3))) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Get the list of selected fragment indices from both tables
     */
    private List<Integer> getSelectedFragmentIndices() {
        List<Integer> indices = new ArrayList<>();
        
        DefaultTableModel roModel = (DefaultTableModel) readOnlyTable.getModel();
        DefaultTableModel edModel = (DefaultTableModel) editableTable.getModel();
        
        // Collect indices from read-only table
        for (int i = 0; i < roModel.getRowCount(); i++) {
            if (Boolean.TRUE.equals(roModel.getValueAt(i, 3))) {
                indices.add(Integer.parseInt(roModel.getValueAt(i, 0).toString()));
            }
        }
        
        // Collect indices from editable table
        for (int i = 0; i < edModel.getRowCount(); i++) {
            if (Boolean.TRUE.equals(edModel.getValueAt(i, 3))) {
                indices.add(Integer.parseInt(edModel.getValueAt(i, 0).toString()));
            }
        }
        
        return indices;
    }
    
    /**
     * Perform the requested action on selected context items (or all if none selected)
     */
    private void performContextAction(String action) {
        if (contextManager == null) {
            toolErrorRaw("Context manager not ready");
            return;
        }
        
        List<Integer> selectedIndices = getSelectedFragmentIndices();
        ContextManager.OperationResult result = null;
        
        if (selectedIndices.isEmpty()) {
            // Act on all context or new files
            switch (action) {
                case "edit" -> doAddContext();
                case "read" -> doReadContext();
                case "drop" -> {
                    if (contextManager.currentContext().isEmpty()) {
                        result = ContextManager.OperationResult.error("No context to drop");
                    } else {
                        result = contextManager.dropAll();
                    }
                }
                case "summarize" -> {
                    // Summarize all eligible files in context
                    var context = contextManager.currentContext();
                    var fragments = context.getAllFragmentsInDisplayOrder().stream()
                        .filter(ContextFragment::isEligibleForAutoContext)
                        .collect(java.util.stream.Collectors.toSet());
                    
                    if (fragments.isEmpty()) {
                        result = ContextManager.OperationResult.error("No eligible items to summarize");
                    } else {
                        var sources = new java.util.HashSet<CodeUnit>();
                        for (var frag : fragments) {
                            sources.addAll(frag.sources(contextManager.getAnalyzer()));
                        }
                        
                        boolean success = contextManager.summarizeClasses(sources);
                        result = success ? 
                            ContextManager.OperationResult.success("Summarized " + sources.size() + " classes") : 
                            ContextManager.OperationResult.error("Failed to summarize classes");
                    }
                }
            }
        } else {
            // Act on selected items
            switch (action) {
                case "edit" -> {
                    try {
                        var files = new java.util.HashSet<RepoFile>();
                        for (int idx : selectedIndices) {
                            var resolved = contextManager.getFilesFromFragmentIndex(idx);
                            files.addAll(resolved);
                        }
                        contextManager.addFiles(files);
                        result = ContextManager.OperationResult.success("Converted " + files.size() + " files to editable");
                    } catch (Exception e) {
                        result = ContextManager.OperationResult.error("Error: " + e.getMessage());
                    }
                }
                case "read" -> {
                    try {
                        var files = new java.util.HashSet<RepoFile>();
                        for (int idx : selectedIndices) {
                            var resolved = contextManager.getFilesFromFragmentIndex(idx);
                            files.addAll(resolved);
                        }
                        contextManager.addReadOnlyFiles(files);
                        result = ContextManager.OperationResult.success("Added " + files.size() + " read-only files");
                    } catch (Exception e) {
                        result = ContextManager.OperationResult.error("Error: " + e.getMessage());
                    }
                }
                case "drop" -> {
                    // Build the command args string from indices
                    var indices = selectedIndices.stream()
                        .map(Object::toString)
                        .collect(java.util.stream.Collectors.joining(" "));
                    
                    // Convert to fraglist and drop them
                    var context = contextManager.currentContext();
                    var pathFragsToRemove = new ArrayList<ContextFragment.PathFragment>();
                    var virtualToRemove = new ArrayList<ContextFragment.VirtualFragment>();
                    
                    for (int idx : selectedIndices) {
                        var allFrags = context.getAllFragmentsInDisplayOrder();
                        if (idx >= 0 && idx < allFrags.size()) {
                            var frag = allFrags.get(idx);
                            if (frag instanceof ContextFragment.PathFragment pf) {
                                pathFragsToRemove.add(pf);
                            } else if (frag instanceof ContextFragment.VirtualFragment vf) {
                                virtualToRemove.add(vf);
                            }
                        }
                    }
                    
                    contextManager.drop(pathFragsToRemove, virtualToRemove);
                    result = ContextManager.OperationResult.success("Dropped " + selectedIndices.size() + " items");
                }
                case "summarize" -> {
                    // Get fragment at each index
                    var fragments = new java.util.HashSet<ContextFragment>();
                    var context = contextManager.currentContext();
                    var allFrags = context.getAllFragmentsInDisplayOrder();
                    
                    for (int idx : selectedIndices) {
                        if (idx >= 0 && idx < allFrags.size()) {
                            fragments.add(allFrags.get(idx));
                        }
                    }
                    
                    if (fragments.isEmpty()) {
                        result = ContextManager.OperationResult.error("No items to summarize");
                    } else {
                        var sources = new java.util.HashSet<CodeUnit>();
                        for (var frag : fragments) {
                            sources.addAll(frag.sources(contextManager.getAnalyzer()));
                        }
                        
                        boolean success = contextManager.summarizeClasses(sources);
                        result = success ? 
                            ContextManager.OperationResult.success("Summarized from " + fragments.size() + " fragments") : 
                            ContextManager.OperationResult.error("Failed to summarize classes");
                    }
                }
            }
        }
        
        if (result != null) {
            showOperationResult(result);
        }
    }

    /**
     * If we have resources to close, do it here. Typically no-op for Swing.
     */
    @Override
    public void close() {
        logger.info("Closing Chrome UI");
        // we can dispose the frame:
        if (frame != null) {
            frame.dispose();
        }
    }

    // -------------- If you want a custom CompletionProvider ---------------
    private CompletionProvider createCompletionProvider() {
        // Example only. For commands:
        var provider = new DefaultCompletionProvider();
        // Just add some example completions:
        provider.addCompletion(new org.fife.ui.autocomplete.BasicCompletion(provider, "/add"));
        provider.addCompletion(new org.fife.ui.autocomplete.BasicCompletion(provider, "/read"));
        // etc.
        return provider;
    }

    /**
     * Outputs shell command results to the LLM stream area,
     * similar to the Lanterna UI's behavior.
     */
    public void shellOutput(String st) {
        // Add a newline before the output to separate it from previous content
        if (llmStreamArea.getText().length() > 0 && !llmStreamArea.getText().endsWith("\n\n")) {
            llmStreamArea.append("\n");
        }
        llmStreamArea.append(st);
        // auto-scroll to bottom
        llmStreamArea.setCaretPosition(llmStreamArea.getDocument().getLength());
    }
}
