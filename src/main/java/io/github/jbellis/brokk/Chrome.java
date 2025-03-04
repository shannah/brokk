package io.github.jbellis.brokk;

import io.github.jbellis.brokk.gui.FileSelectionDialog;
import io.github.jbellis.brokk.ContextManager.OperationResult;
import io.github.jbellis.brokk.prompts.ArchitectPrompts;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

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

        // Initialize the unified context table
        contextTable = new JTable(new DefaultTableModel(new Object[]{"ID", "LOC", "Type", "Description", "Select"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 4; // Only checkbox column is editable
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return columnIndex == 4 ? Boolean.class : Object.class;
            }
        });
        contextTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        contextTable.setRowHeight(18);
        contextTable.setTableHeader(null);
        contextTable.setIntercellSpacing(new Dimension(10, 1));

        // Column widths similar to Lanterna layout
        contextTable.getColumnModel().getColumn(0).setPreferredWidth(30);   // ID
        contextTable.getColumnModel().getColumn(1).setPreferredWidth(50);   // LOC
        contextTable.getColumnModel().getColumn(2).setPreferredWidth(80);   // Type (editable/read-only)
        contextTable.getColumnModel().getColumn(3).setPreferredWidth(370);  // Description
        contextTable.getColumnModel().getColumn(4).setPreferredWidth(50);   // Select checkbox

        // Add listener to checkbox changes to update button states
        ((DefaultTableModel) contextTable.getModel()).addTableModelListener(e -> {
            if (e.getColumn() == 4) { // Checkbox column
                updateContextButtons();
            }
        });

        // Create the summary label for LOC/tokens with monospaced font
        locSummaryLabel = new JLabel(" ");
        locSummaryLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        locSummaryLabel.setBorder(new EmptyBorder(5, 5, 5, 5));

        // Buttons will be created by createContextButtonsPanel()

        // Set up the table panel with a single unified table
        JPanel tablePanel = new JPanel(new BorderLayout());
        tablePanel.add(new JScrollPane(contextTable), BorderLayout.CENTER);

        // Add the buttons panel to the right side
        JPanel buttonsPanel = createContextButtonsPanel();

        // Set up initial layout with table and buttons
        contextPanel.setLayout(new BorderLayout());
        contextPanel.add(tablePanel, BorderLayout.CENTER);
        contextPanel.add(buttonsPanel, BorderLayout.EAST);
        contextPanel.add(locSummaryLabel, BorderLayout.SOUTH);

        // initialize buttons to empty state
        updateContextButtons();

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
        String[] optsArr = options.chars().mapToObj(c -> String.valueOf((char) c)).toArray(String[]::new);
        String choice = (String) JOptionPane.showInputDialog(
                frame, msg, "Choose Option",
                JOptionPane.PLAIN_MESSAGE, null,
                optsArr, optsArr.length > 0 ? optsArr[0] : null
        );
        if (choice == null || choice.isEmpty()) {
            return options.toLowerCase().charAt(options.length() - 1);
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
     * Repopulate the unified context table from the given context.
     */
    public void updateContextTable(Context context) {
        // Reset the contextPanel
        contextPanel.removeAll();

        // Create table panel (even for empty context)
        JPanel tablePanel = new JPanel(new BorderLayout());
        tablePanel.add(new JScrollPane(contextTable), BorderLayout.CENTER);

        // Create buttons panel with fixed width based on longest button label
        JPanel buttonsPanel = createContextButtonsPanel();

        // Set up layout
        contextPanel.setLayout(new BorderLayout());
        contextPanel.add(tablePanel, BorderLayout.CENTER);
        contextPanel.add(buttonsPanel, BorderLayout.EAST);
        contextPanel.add(locSummaryLabel, BorderLayout.SOUTH);

        // Clear the table
        var tableModel = (DefaultTableModel) contextTable.getModel();
        tableModel.setRowCount(0);

        // Update button states based on empty context - using updateButtonStates helper method
        updateButtonStates(context);

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

        // Populate the table
        var allFragments = context.getAllFragmentsInDisplayOrder();
        int totalLines = 0;
        for (ContextFragment frag : allFragments) {
            int id = context.getPositionOfFragment(frag);
            int loc = countLinesSafe(frag);
            totalLines += loc;
            String desc = frag.description();

            boolean isEditable = (frag instanceof ContextFragment.RepoPathFragment)
                    && context.editableFiles().anyMatch(e -> e == frag);

            String type = isEditable ? "✏️ Editable" : "📄 Read-only";

            tableModel.addRow(new Object[]{id, loc, type, desc, false});
        }

        // approximate token count
        // (not strictly the same as old code, but an example)
        String fullText = "";
        // In real usage, you'd gather the text from DefaultPrompts or something
        // fullText = ...
        int approxTokens = Models.getApproximateTokens(fullText);

        locSummaryLabel.setText("Total LOC: " + totalLines + ", or about " + (approxTokens / 1000) + "k tokens");

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
    private void updateContextButtons() {
        boolean hasSelection = hasSelectedItems();

        // Update button labels based on selection and context state
        editButton.setText(hasSelection ? "Edit selected" : "Edit files");
        readOnlyButton.setText(hasSelection ? "Read selected" : "Read files");
        summarizeButton.setText(hasSelection ? "Summarize selected" : "Summarize files");

        // Drop and Copy buttons are special - always show "X all" or "X selected"
        dropButton.setText(hasSelection ? "Drop selected" : "Drop all");
        copyButton.setText(hasSelection ? "Copy selected" : "Copy all");

        // Update the enabled state
        updateButtonStates(contextManager == null ? null : contextManager.currentContext());
    }

    /**
     * Updates button enabled states based on context
     */
    private void updateButtonStates(Context context) {
        boolean hasContext = context != null && !context.isEmpty();

        // Drop and Copy buttons are only enabled if there is context
        dropButton.setEnabled(hasContext);
        copyButton.setEnabled(hasContext);
    }

    /**
     * Creates the panel with context action buttons
     */
    private JPanel createContextButtonsPanel() {
        // Use BoxLayout for natural button sizes stacked vertically
        JPanel buttonsPanel = new JPanel();
        buttonsPanel.setLayout(new BoxLayout(buttonsPanel, BoxLayout.Y_AXIS));
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

        if (copyButton == null) {
            copyButton = new JButton("Copy All");
        }
        copyButton.setMnemonic(KeyEvent.VK_C);
        copyButton.addActionListener(e -> performContextAction("copy"));

        // Create consistent button sizes based on the longest potential label text
        // Using prototype strings that match the longest possible button text
        JButton prototypeButton = new JButton("Summarize selected");
        Dimension buttonSize = prototypeButton.getPreferredSize();

        // Set fixed width based on prototype, but allow height to be determined by look & feel
        Dimension preferredSize = new Dimension(
                buttonSize.width,
                editButton.getPreferredSize().height
        );

        // Apply the same fixed width to all buttons
        editButton.setPreferredSize(preferredSize);
        readOnlyButton.setPreferredSize(preferredSize);
        summarizeButton.setPreferredSize(preferredSize);
        dropButton.setPreferredSize(preferredSize);
        copyButton.setPreferredSize(preferredSize);

        // Also set maximum size to prevent stretching in BoxLayout
        editButton.setMaximumSize(new Dimension(preferredSize.width, preferredSize.height));
        readOnlyButton.setMaximumSize(new Dimension(preferredSize.width, preferredSize.height));
        summarizeButton.setMaximumSize(new Dimension(preferredSize.width, preferredSize.height));
        dropButton.setMaximumSize(new Dimension(preferredSize.width, preferredSize.height));
        copyButton.setMaximumSize(new Dimension(preferredSize.width, preferredSize.height));

        // Add buttons to panel with spacing between
        buttonsPanel.add(editButton);
        buttonsPanel.add(Box.createRigidArea(new Dimension(0, 5)));
        buttonsPanel.add(readOnlyButton);
        buttonsPanel.add(Box.createRigidArea(new Dimension(0, 5)));
        buttonsPanel.add(summarizeButton);
        buttonsPanel.add(Box.createRigidArea(new Dimension(0, 5)));
        buttonsPanel.add(dropButton);
        buttonsPanel.add(Box.createRigidArea(new Dimension(0, 5)));
        buttonsPanel.add(copyButton);

        return buttonsPanel;
    }

    /**
     * Check if any items are selected in either table
     */
    private boolean hasSelectedItems() {
        if (contextTable.getModel().getRowCount() == 0) {
            return false;
        }

        // Check for any true value in checkbox column
        DefaultTableModel tableModel = (DefaultTableModel) contextTable.getModel();

        for (int i = 0; i < tableModel.getRowCount(); i++) {
            if (Boolean.TRUE.equals(tableModel.getValueAt(i, 4))) { // 4 is the checkbox column
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
        DefaultTableModel tableModel = (DefaultTableModel) contextTable.getModel();

        // Collect indices from the unified table
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            if (Boolean.TRUE.equals(tableModel.getValueAt(i, 4))) { // 4 is the checkbox column
                indices.add(Integer.parseInt(tableModel.getValueAt(i, 0).toString()));
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
                case "copy" -> {
                    // Copy all content
                    if (contextManager.currentContext().isEmpty()) {
                        result = ContextManager.OperationResult.error("No context to copy");
                    } else {
                        result = copyToClipboard(null);
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
                case "copy" -> {
                    // Copy selected fragments
                    result = copyToClipboard(selectedIndices);
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
     * Copies content to the clipboard.
     * If indices is null, copies all content, otherwise copies selected fragments.
     */
    private OperationResult copyToClipboard(List<Integer> indices) {
        String content;

        try {
            if (indices == null || indices.isEmpty()) {
                // Copy all content - get from context manager
                var msgs = ArchitectPrompts.instance.collectMessages(contextManager);
                var combined = new StringBuilder();
                msgs.forEach(m -> {
                    if (!(m instanceof dev.langchain4j.data.message.AiMessage)) {
                        combined.append(Models.getText(m)).append("\n\n");
                    }
                });
                combined.append("\n<goal>\n\n</goal>");
                content = combined.toString();
            } else {
                // Copy selected fragments
                var context = contextManager.currentContext();
                var allFrags = context.getAllFragmentsInDisplayOrder();
                var selectedContent = new StringBuilder();

                for (int idx : indices) {
                    if (idx >= 0 && idx < allFrags.size()) {
                        var frag = allFrags.get(idx);
                        try {
                            selectedContent.append(frag.text()).append("\n\n");
                        } catch (Exception e) {
                            contextManager.removeBadFragment(frag, new java.io.IOException(e));
                            return OperationResult.error("Error reading fragment: " + e.getMessage());
                        }
                    }
                }
                content = selectedContent.toString();
            }

            try {
                var sel = new java.awt.datatransfer.StringSelection(content);
                var cb = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
                cb.setContents(sel, sel);
                return OperationResult.success("Content copied to clipboard");
            } catch (Exception e) {
                return OperationResult.error("Failed to copy: " + e.getMessage());
            }
        } catch (Exception e) {
            return OperationResult.error("Failed to gather content: " + e.getMessage());
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
