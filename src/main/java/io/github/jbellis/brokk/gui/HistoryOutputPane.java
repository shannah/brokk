package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.Context;
import io.github.jbellis.brokk.ContextFragment;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.analyzer.RepoFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Set;

/**
 * A component that combines the context history panel with the output panel in a horizontal split pane.
 */
public class HistoryOutputPane extends JSplitPane {
    private static final Logger logger = LogManager.getLogger(HistoryOutputPane.class);

    private final Chrome chrome;
    private final ContextManager contextManager;
    private JTable historyTable;
    private DefaultTableModel historyModel;
    
    // Output components
    private MarkdownOutputPanel llmStreamArea;
    private JScrollPane llmScrollPane;
    private JTextArea systemArea;
    private JScrollPane systemScrollPane;
    private JTextArea captureDescriptionArea;
    private JButton copyTextButton;

    /**
     * Constructs a new HistoryOutputPane.
     *
     * @param chrome The parent Chrome instance
     * @param contextManager The context manager
     */
    public HistoryOutputPane(Chrome chrome, ContextManager contextManager) {
        super(JSplitPane.HORIZONTAL_SPLIT);
        this.chrome = chrome;
        this.contextManager = contextManager;

        // Build left side - context history panel
        var historyPanel = buildContextHistoryPanel();
        setLeftComponent(historyPanel);

        // Build LLM streaming area
        llmScrollPane = buildLLMStreamScrollPane();
        
        // Build capture output panel
        var capturePanel = buildCaptureOutputPanel();
        
        // Build system messages area
        systemScrollPane = buildSystemMessagesArea();

        // Build right side - output panel
        var rightPanel = new JPanel(new BorderLayout());

        // Output panel with LLM stream
        var outputPanel = new JPanel(new BorderLayout());
        outputPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Output",
                javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
                javax.swing.border.TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)
        ));
        outputPanel.add(llmScrollPane, BorderLayout.CENTER);

        // Add capture panel below LLM output
        outputPanel.add(capturePanel, BorderLayout.SOUTH);

        // Add output panel and system messages to right side
        rightPanel.add(outputPanel, BorderLayout.CENTER);
        rightPanel.add(systemScrollPane, BorderLayout.SOUTH);

        setRightComponent(rightPanel);
        
        // Configure the split pane
        setResizeWeight(0.2); // 80% to output, 20% to history
        setContinuousLayout(true);
        
        // Set minimum sizes
        getLeftComponent().setMinimumSize(new Dimension(100, 0));
        getRightComponent().setMinimumSize(new Dimension(200, 0));
    }
    
    /**
     * Builds the Context History panel that shows past contexts
     */
    private JPanel buildContextHistoryPanel() {
        // Create history panel
        var panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Context History",
                javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
                javax.swing.border.TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)
        ));

        // Create table model with columns - first two columns are visible, third is hidden
        historyModel = new DefaultTableModel(
                new Object[]{"", "Action", "Context"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        historyTable = new JTable(historyModel);
        historyTable.setFont(new Font(Font.DIALOG, Font.PLAIN, 12));
        historyTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // Remove table header
        historyTable.setTableHeader(null);

        // Set up tooltip renderer for description column (index 1)
        historyTable.getColumnModel().getColumn(1).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                                                          boolean isSelected, boolean hasFocus, int row, int column) {
                JLabel label = (JLabel)super.getTableCellRendererComponent(
                        table, value, isSelected, hasFocus, row, column);

                // Set the tooltip to show the full text
                if (value != null) {
                    label.setToolTipText(value.toString());
                }

                return label;
            }
        });

        // Set up emoji renderer for first column
        historyTable.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                                                          boolean isSelected, boolean hasFocus, int row, int column) {
                JLabel label = (JLabel)super.getTableCellRendererComponent(
                        table, value, isSelected, hasFocus, row, column);

                // Center-align the emoji
                label.setHorizontalAlignment(JLabel.CENTER);

                return label;
            }
        });

        // Add selection listener to preview context
        historyTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int row = historyTable.getSelectedRow();
                if (row >= 0 && row < historyTable.getRowCount()) {
                    // Get the context object from the hidden third column
                    var ctx = (Context)historyModel.getValueAt(row, 2);
                    contextManager.setSelectedContext(ctx);
                    chrome.loadContext(ctx);
                }
            }
        });

        // Add right-click context menu for history operations
        historyTable.addMouseListener(new MouseAdapter() {
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

        // Adjust column widths - set emoji column width and hide the context object column
        historyTable.getColumnModel().getColumn(0).setPreferredWidth(30);
        historyTable.getColumnModel().getColumn(0).setMinWidth(30);
        historyTable.getColumnModel().getColumn(0).setMaxWidth(30);
        historyTable.getColumnModel().getColumn(1).setPreferredWidth(150);
        historyTable.getColumnModel().getColumn(2).setMinWidth(0);
        historyTable.getColumnModel().getColumn(2).setMaxWidth(0);
        historyTable.getColumnModel().getColumn(2).setWidth(0);

        // Add table to scroll pane with SmartScroll
        var scrollPane = new JScrollPane(historyTable);
        scrollPane.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        new SmartScroll(scrollPane);

        // Add undo/redo buttons at the bottom
        var buttonPanel = new JPanel(new GridLayout(2, 1, 0, 5));
        buttonPanel.setBorder(new EmptyBorder(5, 5, 5, 5));

        var undoButton = new JButton("Undo");
        undoButton.setMnemonic(KeyEvent.VK_Z);
        undoButton.setToolTipText("Undo the most recent history entry");
        undoButton.addActionListener(e -> {
            chrome.disableUserActionButtons();
            chrome.disableContextActionButtons();
            chrome.currentUserTask = contextManager.undoContextAsync();
        });

        var redoButton = new JButton("Redo");
        redoButton.setMnemonic(KeyEvent.VK_Y);
        redoButton.setToolTipText("Redo the most recently undone entry");
        redoButton.addActionListener(e -> {
            chrome.disableUserActionButtons();
            chrome.disableContextActionButtons();
            chrome.currentUserTask = contextManager.redoContextAsync();
        });

        buttonPanel.add(undoButton);
        buttonPanel.add(redoButton);

        panel.add(scrollPane, BorderLayout.CENTER);
        panel.add(buttonPanel, BorderLayout.SOUTH);

        return panel;
    }

    /**
     * Shows the context menu for the context history table
     */
    private void showContextHistoryPopupMenu(MouseEvent e) {
        int row = historyTable.rowAtPoint(e.getPoint());
        if (row < 0) return;

        // Select the row under the cursor
        historyTable.setRowSelectionInterval(row, row);

        // Get the context from the selected row
        Context context = (Context)historyModel.getValueAt(row, 2);

        // Create popup menu
        JPopupMenu popup = new JPopupMenu();

        JMenuItem undoToHereItem = new JMenuItem("Undo to here");
        undoToHereItem.addActionListener(event -> undoHistoryUntil(context));
        popup.add(undoToHereItem);

        JMenuItem resetToHereItem = new JMenuItem("Reset Context to Here");
        resetToHereItem.addActionListener(event -> resetContextTo(context));
        popup.add(resetToHereItem);

        // Register popup with theme manager
        if (chrome.themeManager != null) {
            chrome.themeManager.registerPopupMenu(popup);
        }

        // Show popup menu
        popup.show(historyTable, e.getX(), e.getY());
    }

    /**
     * Restore context to a specific point in history
     */
    private void undoHistoryUntil(Context targetContext) {
        chrome.disableUserActionButtons();
        chrome.disableContextActionButtons();
        chrome.currentUserTask = contextManager.undoContextUntilAsync(targetContext);
    }

    /**
     * Creates a new context based on the files and fragments from a historical context,
     * while preserving current conversation history
     */
    private void resetContextTo(Context targetContext) {
        chrome.disableUserActionButtons();
        chrome.disableContextActionButtons();
        chrome.currentUserTask = contextManager.resetContextToAsync(targetContext);
    }

    /**
     * Updates the context history table with the current context history, and selects the given context
     * 
     * @param contextToSelect Context to select in the history table
     */
    public void updateHistoryTable(Context contextToSelect) {
        logger.debug("Updating context history table with context {}",
                     contextToSelect != null ? contextToSelect.getAction() : "null");
        
        SwingUtilities.invokeLater(() -> {
            historyModel.setRowCount(0);

            // Track which row to select
            int rowToSelect = -1;
            int currentRow = 0;

            // Add rows for each context in history
            for (var ctx : contextManager.getContextHistory()) {
                // Add emoji for AI responses, empty for user actions
                String emoji = (ctx.getParsedOutput() != null) ? "🤖" : "";
                historyModel.addRow(new Object[]{
                        emoji,
                        ctx.getAction(),
                        ctx // We store the actual context object in hidden column
                });

                // If this is the context we want to select, record its row
                if (ctx.equals(contextToSelect)) {
                    rowToSelect = currentRow;
                }
                currentRow++;
            }

            // Set selection if we found the context
            if (rowToSelect >= 0) {
                historyTable.setRowSelectionInterval(rowToSelect, rowToSelect);
                historyTable.scrollRectToVisible(historyTable.getCellRect(rowToSelect, 0, true));
            }
        });
    }
    
    /**
     * Sets the initial width of the history panel
     */
    public void setInitialWidth() {
        // Keep the resize weight consistent with the initial setting (0.2)
        setDividerLocation(0.2);
        
        // Re-validate to ensure the UI picks up changes
        revalidate();
        repaint();
    }
    
    /**
     * Returns the history table for selection checks
     * 
     * @return The JTable containing context history
     */
    public JTable getHistoryTable() {
        return historyTable;
    }
    
    /**
     * Builds the LLM streaming area where markdown output is displayed
     */
    private JScrollPane buildLLMStreamScrollPane() {
        llmStreamArea = new MarkdownOutputPanel();

        // Wrap it in a scroll pane so it can scroll if content is large
        var jsp = new JScrollPane(llmStreamArea);
        jsp.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        jsp.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        new SmartScroll(jsp);

        // Add a text change listener to update capture buttons
        llmStreamArea.addTextChangeListener(() -> chrome.updateCaptureButtons(null));

        return jsp;
    }

    /**
     * Builds the system messages area that appears below the LLM output area.
     */
    private JScrollPane buildSystemMessagesArea() {
        // Create text area for system messages
        systemArea = new JTextArea();
        systemArea.setEditable(false);
        systemArea.setLineWrap(true);
        systemArea.setWrapStyleWord(true);
        systemArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        systemArea.setRows(3);

        // Create scroll pane with border and title
        var scrollPane = new JScrollPane(systemArea);
        scrollPane.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "System Messages",
                javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
                javax.swing.border.TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)
        ));
        new SmartScroll(scrollPane);

        return scrollPane;
    }
    
    /**
     * Builds the "Capture Output" panel with a horizontal layout:
     * [Capture Text]
     */
    private JPanel buildCaptureOutputPanel() {
        var panel = new JPanel(new BorderLayout(5, 3));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 0, 5, 0));

        // Placeholder area in center - will get all extra space
        captureDescriptionArea = new JTextArea("");
        captureDescriptionArea.setEditable(false);
        captureDescriptionArea.setBackground(panel.getBackground());
        captureDescriptionArea.setBorder(null);
        captureDescriptionArea.setFont(new Font(Font.DIALOG, Font.PLAIN, 12));
        captureDescriptionArea.setLineWrap(true);
        captureDescriptionArea.setWrapStyleWord(true);
        panel.add(captureDescriptionArea, BorderLayout.CENTER);

        // Buttons panel on the right
        var buttonsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));

        // "Copy Text" button
        copyTextButton = new JButton("Copy Text");
        copyTextButton.setMnemonic(KeyEvent.VK_T);
        copyTextButton.setToolTipText("Copy the output to clipboard");
        copyTextButton.addActionListener(e -> {
            String text = llmStreamArea.getText();
            if (!text.isBlank()) {
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                    new java.awt.datatransfer.StringSelection(text), null);
                chrome.toolErrorRaw("Copied to clipboard");
            }
        });
        // Set minimum size
        copyTextButton.setMinimumSize(copyTextButton.getPreferredSize());
        buttonsPanel.add(copyTextButton);
        
        // "Open in New Window" button
        var openWindowButton = new JButton("Open in New Window");
        openWindowButton.setMnemonic(KeyEvent.VK_W);
        openWindowButton.setToolTipText("Open the output in a new window");
        openWindowButton.addActionListener(e -> {
            String text = llmStreamArea.getText();
            if (!text.isBlank()) {
                new OutputWindow(text, chrome.themeManager != null && chrome.themeManager.isDarkTheme());
            }
        });
        // Set minimum size
        openWindowButton.setMinimumSize(openWindowButton.getPreferredSize());
        buttonsPanel.add(openWindowButton);


        // Add buttons panel to the right
        panel.add(buttonsPanel, BorderLayout.EAST);

        return panel;
    }
    /**
     * Gets the current text from the LLM output area
     */
    public String getLlmOutputText() {
        return llmStreamArea.getText();
    }
    
    /**
     * Sets the text in the LLM output area
     */
    public void setLlmOutput(String text) {
        llmStreamArea.setText(text);
        // Scroll to the top
        SwingUtilities.invokeLater(() -> {
            llmScrollPane.getVerticalScrollBar().setValue(0);
        });
    }
    
    /**
     * Appends text to the LLM output area
     */
    public void appendLlmOutput(String text) {
        llmStreamArea.append(text);
    }
    
    /**
     * Clears the LLM output area
     */
    public void clear() {
        llmStreamArea.clear();
    }
    
    /**
     * Appends text to the system output area with timestamp
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
    }
    
    /**
     * Sets the enabled state of the copy text button
     */
    public void setCopyButtonEnabled(boolean enabled) {
        copyTextButton.setEnabled(enabled);
    }
    
    /**
     * Sets the enabled state of the edit references button
     * 
     * @deprecated This method is kept for compatibility with Chrome class but does nothing now
     */
    public void setEditReferencesButtonEnabled(boolean enabled) {
        // Edit References button has been removed
    }
    
    /**
     * Updates the files description area with formatted file list
     */
    public void updateFilesDescription(Set<RepoFile> files) {
        // Method kept for compatibility with Chrome class but does nothing now
        // References are no longer shown in the output pane
    }
    
    /**
     * Updates the theme of the output components
     */
    public void updateTheme(boolean isDark) {
        llmStreamArea.updateTheme(isDark);
    }

    /**
     * Gets the LLM scroll pane
     */
    public JScrollPane getLlmScrollPane() {
        return llmScrollPane;
    }
    
    /**
     * Inner class representing a detached window for viewing output text
     */
    private class OutputWindow extends JFrame {
        /**
         * Creates a new output window with the given text content
         * 
         * @param text The markdown text to display
         * @param isDark Whether to use dark theme
         */
        public OutputWindow(String text, boolean isDark) {
            super("Output");
            setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            
            // Create markdown panel with the text
            var outputPanel = new MarkdownOutputPanel();
            outputPanel.updateTheme(isDark);
            outputPanel.setText(text);
            
            // Add to a scroll pane
            var scrollPane = new JScrollPane(outputPanel);
            scrollPane.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            
            // Add the scroll pane to the frame
            add(scrollPane);
            
            // Set size and position
            setSize(800, 600);
            setLocationRelativeTo(null);
            
            // Add ESC key binding to close the window
            var rootPane = getRootPane();
            var actionMap = rootPane.getActionMap();
            var inputMap = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
            
            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeWindow");
            actionMap.put("closeWindow", new AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    dispose();
                }
            });
            
            // Make window visible
            setVisible(true);
        }
    }
}
