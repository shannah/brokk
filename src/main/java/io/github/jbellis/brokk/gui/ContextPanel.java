package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.Context;
import io.github.jbellis.brokk.ContextFragment;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.Models;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class ContextPanel extends JPanel {
    private static final Logger logger = LogManager.getLogger(ContextPanel.class);

    // Columns
    private final int FILES_REFERENCED_COLUMN = 2;
    private final int FRAGMENT_COLUMN = 3;

    // Parent references
    private final Chrome chrome;
    private final ContextManager contextManager;

    private JTable contextTable;
    private JPanel locSummaryLabel;

    // Buttons
    // Table popup menu (when no row is selected)
    private JPopupMenu tablePopupMenu;

    /**
     * Constructor for the context panel
     */
    public ContextPanel(Chrome chrome, ContextManager contextManager) {
        super(new BorderLayout());
        this.chrome = chrome;
        this.contextManager = contextManager;

        setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Context",
                javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
                javax.swing.border.TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)
        ));

        buildContextPanel();

        ((JLabel) locSummaryLabel.getComponent(0)).setText("No context - use Edit or Read or Summarize to add content");
    }

    /**
     * Build the context panel (unified table + action buttons).
     */
    private void buildContextPanel() {
        contextTable = new JTable(new DefaultTableModel(new Object[]{"LOC", "Description", "Files Referenced", "Fragment"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return switch (columnIndex) {
                    case 0 -> Integer.class;
                    case 1 -> String.class;
                    case 2 -> List.class; // Our references column
                    case 3 -> ContextFragment.class;
                    default -> Object.class;
                };
            }
        });
        contextTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        // Add custom cell renderer for the "Description" column
        contextTable.getColumnModel().getColumn(1).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                var c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (value != null && value.toString().startsWith("✏️")) {
                    setFont(getFont().deriveFont(Font.ITALIC));
                } else {
                    setFont(getFont().deriveFont(Font.PLAIN));
                }
                return c;
            }
        });

        // Files Referenced column: use our FileReferencesTableCellRenderer
        var fileRenderer = new FileReferencesTableCellRenderer();
        contextTable.getColumnModel().getColumn(FILES_REFERENCED_COLUMN).setCellRenderer(fileRenderer);

        // Increase row height to accommodate file "badges"
        contextTable.setRowHeight(23);

        // Hide the FRAGMENT_COLUMN from view
        contextTable.getColumnModel().getColumn(FRAGMENT_COLUMN).setMinWidth(0);
        contextTable.getColumnModel().getColumn(FRAGMENT_COLUMN).setMaxWidth(0);
        contextTable.getColumnModel().getColumn(FRAGMENT_COLUMN).setWidth(0);

        // Column widths
        contextTable.getColumnModel().getColumn(0).setPreferredWidth(50);
        contextTable.getColumnModel().getColumn(0).setMaxWidth(100);
        contextTable.getColumnModel().getColumn(1).setPreferredWidth(230);
        contextTable.getColumnModel().getColumn(2).setPreferredWidth(250);

        // Add mouse motion for tooltips
        contextTable.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int row = contextTable.rowAtPoint(e.getPoint());
                int col = contextTable.columnAtPoint(e.getPoint());
                if (row >= 0 && col == FILES_REFERENCED_COLUMN) {
                    var value = contextTable.getValueAt(row, col);
                    if (value != null) {
                        // Show file references in a multiline tooltip
                        @SuppressWarnings("unchecked") List<FileReferenceList.FileReferenceData> refs = (List<FileReferenceList.FileReferenceData>) value;
                        if (!refs.isEmpty()) {
                            var sb = new StringBuilder("<html>");
                            for (FileReferenceList.FileReferenceData r : refs) {
                                sb.append(r.getFullPath()).append("<br>");
                            }
                            sb.append("</html>");
                            contextTable.setToolTipText(sb.toString());
                            return;
                        }
                    }
                } else if (row >= 0 && col == 1) {
                    // Show full description
                    var value = contextTable.getValueAt(row, col);
                    if (value != null) {
                        contextTable.setToolTipText(value.toString());
                        return;
                    }
                }
                contextTable.setToolTipText(null);
            }
        });

        // Add double-click to open fragment preview
        contextTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = contextTable.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        var fragment = (ContextFragment) contextTable.getModel().getValueAt(row, FRAGMENT_COLUMN);
                        if (fragment != null) {
                            showFragmentPreview(fragment);
                        }
                    }
                }
            }
        });

        // Create a single JPopupMenu for the table
        JPopupMenu contextMenu = new JPopupMenu();
        if (chrome.themeManager != null) {
            chrome.themeManager.registerPopupMenu(contextMenu);
        } else {
            // Register this popup menu later when the theme manager is available
            SwingUtilities.invokeLater(() -> {
                if (chrome.themeManager != null) {
                    chrome.themeManager.registerPopupMenu(contextMenu);
                }
            });
        }

        // Add a mouse listener so we control exactly when the popup shows
        contextTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                handlePopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                handlePopup(e);
            }

            private void handlePopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int row = contextTable.rowAtPoint(e.getPoint());
                    int col = contextTable.columnAtPoint(e.getPoint());

                    // Clear the menu and rebuild according to row/column
                    contextMenu.removeAll();

                    if (row >= 0) {
                        // Select the row only if:
                        // 1. No rows are currently selected, or
                        // 2. Only one row is selected and it's not the row we clicked on
                        if (contextTable.getSelectedRowCount() == 0 || (contextTable.getSelectedRowCount() == 1 && contextTable.getSelectedRow() != row)) {
                            contextTable.setRowSelectionInterval(row, row);
                        }
                        var fragment = (ContextFragment) contextTable.getModel().getValueAt(row, FRAGMENT_COLUMN);

                        // Show Contents as the first action
                        JMenuItem showContentsItem = new JMenuItem("Show Contents");
                        showContentsItem.addActionListener(e1 -> showFragmentPreview(fragment));
                        contextMenu.add(showContentsItem);
                        contextMenu.addSeparator();

                        // If this is the AutoContext row, show AutoContext items
                        if (fragment instanceof ContextFragment.AutoContext) {
                            JMenuItem setAutoContext5Item = new JMenuItem("Set AutoContext to 5");
                            setAutoContext5Item.addActionListener(e1 -> chrome.contextManager.setAutoContextFilesAsync(5));

                            JMenuItem setAutoContext10Item = new JMenuItem("Set AutoContext to 10");
                            setAutoContext10Item.addActionListener(e1 -> chrome.contextManager.setAutoContextFilesAsync(10));

                            JMenuItem setAutoContext20Item = new JMenuItem("Set AutoContext to 20");
                            setAutoContext20Item.addActionListener(e1 -> chrome.contextManager.setAutoContextFilesAsync(20));

                            JMenuItem setAutoContextCustomItem = new JMenuItem("Set AutoContext...");
                            setAutoContextCustomItem.addActionListener(e1 -> chrome.showSetAutoContextSizeDialog());

                            contextMenu.add(setAutoContext5Item);
                            contextMenu.add(setAutoContext10Item);
                            contextMenu.add(setAutoContext20Item);
                            contextMenu.add(setAutoContextCustomItem);

                        } else {
                            // Otherwise, show "View History" only if it's a ProjectPathFragment and Git is available
                            boolean hasGit = contextManager != null && contextManager.getProject() != null
                                    && contextManager.getProject().hasGit();
                            if (hasGit && fragment instanceof ContextFragment.ProjectPathFragment(ProjectFile f)) {
                                JMenuItem viewHistoryItem = new JMenuItem("View History");
                                viewHistoryItem.addActionListener(ev -> {
                                    // Already know it's a ProjectPathFragment here
                                    chrome.getGitPanel().addFileHistoryTab(f);
                                });
                                contextMenu.add(viewHistoryItem);
                            } else if (fragment instanceof ContextFragment.ConversationFragment cf) {
                                // Add Compress History option for conversation fragment
                                JMenuItem compressHistoryItem = new JMenuItem("Compress History");
                                compressHistoryItem.addActionListener(e1 -> {
                                    // Call ContextManager to compress history
                                    chrome.setCurrentUserTask(contextManager.compressHistoryAsync());
                                });
                                contextMenu.add(compressHistoryItem);
                                // Only enable if uncompressed entries exist
                                var uncompressedExists = cf.getHistory().stream().anyMatch(entry -> !entry.isCompressed());
                                compressHistoryItem.setEnabled(uncompressedExists);
                            }
                        }

                        // If the user right-clicked on the references column, show reference options
                        var fileActionsAdded = false;
                        if (col == FILES_REFERENCED_COLUMN) {
                            @SuppressWarnings("unchecked") List<FileReferenceList.FileReferenceData> references = (List<FileReferenceList.FileReferenceData>) contextTable.getValueAt(row, col);
                            if (references != null && !references.isEmpty()) {
                                FileReferenceList.FileReferenceData targetRef = findClickedReference(e.getPoint(), row, col, references);
                                if (targetRef != null) {
                                    // If clicking on a specific file reference, show specific file options
                                    contextMenu.addSeparator();
                                    contextMenu.add(buildAddMenuItem(targetRef));
                                    contextMenu.add(buildReadMenuItem(targetRef));
                                    contextMenu.add(buildSummarizeMenuItem(targetRef));
                                }
                            }
                            fileActionsAdded = true;
                        }

                        // If clicking in the row but not on a specific reference, show "all references" options
                        if (!fileActionsAdded) {
                            contextMenu.addSeparator();

                            JMenuItem editAllRefsItem = new JMenuItem("Edit All References");
                            editAllRefsItem.addActionListener(e1 -> {
                                var selectedFragments = getSelectedFragments();
                                chrome.currentUserTask = contextManager.performContextActionAsync(Chrome.ContextAction.EDIT, selectedFragments);
                            });

                            JMenuItem readAllRefsItem = new JMenuItem("Read All References");
                            readAllRefsItem.addActionListener(e1 -> {
                                var selectedFragments = getSelectedFragments();
                                chrome.currentUserTask = contextManager.performContextActionAsync(Chrome.ContextAction.READ, selectedFragments);
                            });

                            JMenuItem summarizeAllRefsItem = new JMenuItem("Summarize All References");
                            summarizeAllRefsItem.addActionListener(e1 -> {
                                var selectedFragments = getSelectedFragments();
                                chrome.currentUserTask = contextManager.performContextActionAsync(Chrome.ContextAction.SUMMARIZE, selectedFragments);
                            });

                            contextMenu.add(editAllRefsItem);
                            contextMenu.add(readAllRefsItem);
                            contextMenu.add(summarizeAllRefsItem);
                        }

                        // Add Copy and Drop actions with a separator
                        contextMenu.addSeparator();
                        JMenuItem copySelectionItem = new JMenuItem("Copy");
                        copySelectionItem.addActionListener(ev -> {
                            var selectedFragments = getSelectedFragments();
                            chrome.currentUserTask = contextManager.performContextActionAsync(Chrome.ContextAction.COPY, selectedFragments);
                        });
                        contextMenu.add(copySelectionItem);

                        JMenuItem dropSelectionItem = new JMenuItem("Drop");
                        dropSelectionItem.addActionListener(ev -> {
                            var selectedFragments = getSelectedFragments();
                            chrome.currentUserTask = contextManager.performContextActionAsync(Chrome.ContextAction.DROP, selectedFragments);
                        });
                        contextMenu.add(dropSelectionItem);

                        if (!contextManager.selectedContext().equals(contextManager.topContext())) {
                            dropSelectionItem.setEnabled(false);
                        } else if (contextTable.getSelectedRowCount() == 1 && fragment instanceof ContextFragment.AutoContext) {
                            dropSelectionItem.setEnabled(contextManager.selectedContext().isAutoContextEnabled());
                        }
                    } else {
                        // No row selected - show the popup with all options
                        tablePopupMenu.show(contextTable, e.getX(), e.getY());
                    }

                    // Final theme registration before showing
                    if (chrome.themeManager != null) {
                        chrome.themeManager.registerPopupMenu(contextMenu);
                    }
                    contextMenu.show(contextTable, e.getX(), e.getY());
                }
            }
        });

        // Set selection mode to allow multiple selection
        contextTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        // Add Ctrl+V shortcut for paste in the table
        contextTable.registerKeyboardAction(
                e -> chrome.getContextManager().performContextActionAsync(Chrome.ContextAction.PASTE, List.of()),
                KeyStroke.getKeyStroke(KeyEvent.VK_V, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
                JComponent.WHEN_FOCUSED
        );

        // Setup right-click popup menu for when no rows are selected
        tablePopupMenu = new JPopupMenu();

        // Add options submenu
        JMenu addMenu = new JMenu("Add");

        JMenuItem editMenuItem = new JMenuItem("Edit Files");
        editMenuItem.addActionListener(e -> {
            chrome.currentUserTask = contextManager.performContextActionAsync(Chrome.ContextAction.EDIT, List.of());
        });
        // Only add Edit Files when git is present
        if (contextManager != null && contextManager.getProject() != null && contextManager.getProject().hasGit()) {
            addMenu.add(editMenuItem);
        }

        JMenuItem readMenuItem = new JMenuItem("Read Files");
        readMenuItem.addActionListener(e -> {
            chrome.currentUserTask = contextManager.performContextActionAsync(Chrome.ContextAction.READ, List.of());
        });
        addMenu.add(readMenuItem);

        JMenuItem summarizeMenuItem = new JMenuItem("Summarize Files");
        summarizeMenuItem.addActionListener(e -> {
            chrome.currentUserTask = contextManager.performContextActionAsync(Chrome.ContextAction.SUMMARIZE, List.of());
        });
        addMenu.add(summarizeMenuItem);

        JMenuItem symbolMenuItem = new JMenuItem("Symbol Usage");
        symbolMenuItem.addActionListener(e -> {
            chrome.currentUserTask = contextManager.findSymbolUsageAsync();
        });
        addMenu.add(symbolMenuItem);

        JMenuItem callersMenuItem = new JMenuItem("Callers");
        callersMenuItem.addActionListener(e -> {
            chrome.currentUserTask = contextManager.findMethodCallersAsync();
        });
        addMenu.add(callersMenuItem);

        JMenuItem calleesMenuItem = new JMenuItem("Callees");
        calleesMenuItem.addActionListener(e -> {
            chrome.currentUserTask = contextManager.findMethodCalleesAsync();
        });
        addMenu.add(calleesMenuItem);

        tablePopupMenu.add(addMenu);
        tablePopupMenu.addSeparator();

        JMenuItem dropAllMenuItem = new JMenuItem("Drop All");
        dropAllMenuItem.addActionListener(e -> {

            chrome.currentUserTask = contextManager.performContextActionAsync(Chrome.ContextAction.DROP, List.of());
        });
        tablePopupMenu.add(dropAllMenuItem);

        JMenuItem copyAllMenuItem = new JMenuItem("Copy All");
        copyAllMenuItem.addActionListener(e -> {
            chrome.currentUserTask = contextManager.performContextActionAsync(Chrome.ContextAction.COPY, List.of());
        });
        tablePopupMenu.add(copyAllMenuItem);

        JMenuItem pasteMenuItem = new JMenuItem("Paste");
        pasteMenuItem.addActionListener(e -> {
            chrome.currentUserTask = contextManager.performContextActionAsync(Chrome.ContextAction.PASTE, List.of());
        });
        tablePopupMenu.add(pasteMenuItem);

        // Register the popup menu with the theme manager
        if (chrome.themeManager != null) {
            chrome.themeManager.registerPopupMenu(tablePopupMenu);
        } else {
            SwingUtilities.invokeLater(() -> {
                if (chrome.themeManager != null) {
                    chrome.themeManager.registerPopupMenu(tablePopupMenu);
                }
            });
        }

        // Add a selection listener so we can update the action availability
        contextTable.getSelectionModel().

                addListSelectionListener(e ->

                                         {
                                             if (!e.getValueIsAdjusting()) {
                                             }
                                         });

        // Build summary panel
        var contextSummaryPanel = new JPanel(new BorderLayout());
        locSummaryLabel = new

                JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        JLabel innerLabel = new JLabel(" ");
        innerLabel.setFont(new

                                   Font(Font.MONOSPACED, Font.PLAIN, 12));
        innerLabel.setBorder(new

                                     EmptyBorder(5, 5, 5, 5));
        locSummaryLabel.add(innerLabel);
        locSummaryLabel.setBorder(BorderFactory.createEmptyBorder());
        contextSummaryPanel.add(locSummaryLabel, BorderLayout.NORTH);

        // Table panel
        var tablePanel = new JPanel(new BorderLayout());
        var tableScrollPane = new JScrollPane(contextTable, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        tableScrollPane.setPreferredSize(new Dimension(600, 150));

        // Add a mouse listener to the scroll pane for right-clicks on empty areas
        tableScrollPane.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                handleScrollPanePopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                handleScrollPanePopup(e);
            }

            private void handleScrollPanePopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    // Get the event point in view coordinates
                    Point viewPoint = SwingUtilities.convertPoint(tableScrollPane, e.getPoint(),
                                                                  tableScrollPane.getViewport().getView());

                    // If the click is in the table and on a row, let the table's listener handle it
                    if (contextTable.getRowCount() > 0) {
                        int row = contextTable.rowAtPoint(viewPoint);
                        if (row >= 0) {
                            return;
                        }
                    }

                    // Otherwise show the table popup menu
                    tablePopupMenu.show(tableScrollPane, e.getX(), e.getY());
                }
            }
        });

        tablePanel.add(tableScrollPane, BorderLayout.CENTER);

        setLayout(new BorderLayout());

        add(tablePanel, BorderLayout.CENTER);

        add(contextSummaryPanel, BorderLayout.SOUTH);

        tableScrollPane.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                contextTable.requestFocusInWindow();
            }
        });
    }


    /**
     * Gets the list of selected fragments
     */
    public List<ContextFragment> getSelectedFragments() {
        var fragments = new ArrayList<ContextFragment>();
        int[] selectedRows = contextTable.getSelectedRows();
        var tableModel = (DefaultTableModel) contextTable.getModel();
        for (int row : selectedRows) {
            fragments.add((ContextFragment) tableModel.getValueAt(row, FRAGMENT_COLUMN));
        }
        return fragments;
    }

    /**
     * Populates the context table from a Context object.
     */
    public void populateContextTable(Context ctx) {
        assert SwingUtilities.isEventDispatchThread() : "Not on EDT";
        var tableModel = (DefaultTableModel) contextTable.getModel();
        tableModel.setRowCount(0);

        if (ctx == null || ctx.isEmpty()) {
            ((JLabel) locSummaryLabel.getComponent(0)).setText(
                    "No context - use Edit or Read or Summarize to add content");
            revalidate();
            repaint();
            return;
        }

        var allFragments = ctx.getAllFragmentsInDisplayOrder();
        int totalLines = 0;
        StringBuilder fullText = new StringBuilder();
        for (var frag : allFragments) {
            var text = getTextSafe(frag);
            fullText.append(text).append("\n");
            int loc = text.split("\\r?\\n", -1).length;
            totalLines += loc;
            var desc = frag.description();

            // Mark editable if it's in the editable streams
            boolean isEditable = ctx.editableFiles().anyMatch(e -> e == frag);
            if (isEditable) {
                desc = "✏️ " + desc;
            }

            // Build file references
            List<FileReferenceList.FileReferenceData> fileReferences = new ArrayList<>();
            if (!(frag instanceof ContextFragment.ProjectPathFragment)) {
                fileReferences = frag.files(contextManager.getProject())
                        .stream()
                        .map(file -> new FileReferenceList.FileReferenceData(file.getFileName(), file.toString(), file))
                        .distinct()
                        .sorted(Comparator.comparing(FileReferenceList.FileReferenceData::getFileName))
                        .collect(Collectors.toList());
            }

            tableModel.addRow(new Object[]{loc, desc, fileReferences, frag});
        }

        var approxTokens = Models.getApproximateTokens(fullText.toString());
        ((JLabel) locSummaryLabel.getComponent(0)).setText(
                "Total: %,d LOC, or about %,dk tokens".formatted(totalLines, approxTokens / 1000)
        );

        revalidate();
        repaint();
    }

    /**
     * Get the fragment text or remove it if it errors out
     */
    private String getTextSafe(ContextFragment fragment) {
        try {
            return fragment.text();
        } catch (IOException e) {
            chrome.systemOutput("Error reading fragment: " + e.getMessage());
            contextManager.removeBadFragment(fragment, e);
            return "";
        }
    }

// No longer needed as edit button is now in the menu

    /**
     * Called by Chrome to refresh the table if context changes
     */
    public void updateContextTable() {
        SwingUtilities.invokeLater(() -> {
            populateContextTable(contextManager.selectedContext());
        });
    }

    /**
     * Determine which file reference was clicked based on mouse coordinates.
     * We replicate the FlowLayout calculations to find the badge at xInCell.
     */
    private FileReferenceList.FileReferenceData findClickedReference(Point pointInTableCoords,
                                                                     int row, int col,
                                                                     List<FileReferenceList.FileReferenceData> references) {
        // Get cell rectangle so we can convert to cell-local coordinates
        Rectangle cellRect = contextTable.getCellRect(row, col, false);
        int xInCell = pointInTableCoords.x - cellRect.x;
        int yInCell = pointInTableCoords.y - cellRect.y;
        if (xInCell < 0 || yInCell < 0) {
            return null;
        }

        // We used a smaller font in FileReferenceList: about 0.85 of table's font size
        var baseFont = contextTable.getFont();
        float scaledSize = baseFont.getSize() * 0.85f;
        Font badgeFont = baseFont.deriveFont(Font.PLAIN, scaledSize);
        FontMetrics fm = contextTable.getFontMetrics(badgeFont);

        // FlowLayout left gap was 4, label has ~12px horizontal padding
        final int hgap = 4;
        final int horizontalPadding = 12; // total left+right
        // We drew a 1.5f stroke around the badge, but a small offset is enough
        final int borderThickness = 3; // extra width for border + rounding
        int currentX = 0;

        for (FileReferenceList.FileReferenceData ref : references) {
            String text = ref.getFileName();
            int textWidth = fm.stringWidth(text);
            // label’s total width = text + internal padding + border
            int labelWidth = textWidth + horizontalPadding + borderThickness;
            // Check if user clicked in [currentX .. currentX + labelWidth]
            if (xInCell >= currentX && xInCell <= currentX + labelWidth) {
                return ref;
            }
            currentX += (labelWidth + hgap);
        }
        return null;
    }

    /**
     * Build "Add file" menu item for a single file reference
     */
    private JMenuItem buildAddMenuItem(FileReferenceList.FileReferenceData fileRef) {
        JMenuItem editItem = new JMenuItem("Edit " + fileRef.getFullPath());
        editItem.addActionListener(e -> {
            if (fileRef.getRepoFile() != null) {
                chrome.currentUserTask = chrome.getContextManager().performContextActionAsync(
                        Chrome.ContextAction.EDIT,
                        List.of(new ContextFragment.ProjectPathFragment(fileRef.getRepoFile()))
                );
            } else {
                chrome.toolErrorRaw("Cannot add file: " + fileRef.getFullPath() + " - no RepoFile available");
            }
        });
        // Disable for dependency projects
        if (contextManager != null && contextManager.getProject() != null && !contextManager.getProject().hasGit()) {
            editItem.setEnabled(false);
            editItem.setToolTipText("Editing not available without Git");
        }
        return editItem;
    }

    /**
     * Build "Read file" menu item for a single file reference
     */
    private JMenuItem buildReadMenuItem(FileReferenceList.FileReferenceData fileRef) {
        JMenuItem readItem = new JMenuItem("Read " + fileRef.getFullPath());
        readItem.addActionListener(e -> {
            if (fileRef.getRepoFile() != null) {
                chrome.currentUserTask = chrome.getContextManager().performContextActionAsync(
                        Chrome.ContextAction.READ,
                        List.of(new ContextFragment.ProjectPathFragment(fileRef.getRepoFile()))
                );
            } else {
                chrome.toolErrorRaw("Cannot read file: " + fileRef.getFullPath() + " - no RepoFile available");
            }
        });
        return readItem;
    }

    /**
     * Shows a preview of the fragment contents
     */
    private void showFragmentPreview(ContextFragment fragment) {
        chrome.openFragmentPreview(fragment, "text/java");
    }

    /**
     * Build "Summarize file" menu item for a single file reference
     */
    private JMenuItem buildSummarizeMenuItem(FileReferenceList.FileReferenceData fileRef) {
        JMenuItem summarizeItem = new JMenuItem("Summarize " + fileRef.getFullPath());
        summarizeItem.addActionListener(e -> {
            if (fileRef.getRepoFile() != null) {
                chrome.currentUserTask = chrome.getContextManager().performContextActionAsync(
                        Chrome.ContextAction.SUMMARIZE,
                        List.of(new ContextFragment.ProjectPathFragment(fileRef.getRepoFile()))
                );
            } else {
                chrome.toolErrorRaw("Cannot summarize: " + fileRef.getFullPath() + " - file information not available");
            }
        });
        return summarizeItem;
    }

    /**
     * Table cell renderer for displaying file references.
     */
    public static class FileReferencesTableCellRenderer implements TableCellRenderer {
        public FileReferencesTableCellRenderer() {
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            // Convert the value to a list of FileReferenceData
            List<FileReferenceList.FileReferenceData> fileRefs = convertToFileReferences(value);

            FileReferenceList component = new FileReferenceList(fileRefs);

            // Set colors based on selection
            if (isSelected) {
                component.setBackground(table.getSelectionBackground());
                component.setForeground(table.getSelectionForeground());
                component.setSelected(true);
            } else {
                component.setBackground(table.getBackground());
                component.setForeground(table.getForeground());
                component.setSelected(false);
            }

            // Ensure the component is properly painted in the table
            component.setOpaque(true);

            // Set border to match the editor's border for consistency when transitioning
            component.setBorder(BorderFactory.createEmptyBorder(1, 2, 1, 2));

            return component;
        }

        /**
         * Converts various input types to a list of FileReferenceData objects.
         */
        @SuppressWarnings("unchecked")
        public static List<FileReferenceList.FileReferenceData> convertToFileReferences(Object value) {
            if (value == null) {
                return new ArrayList<>();
            }

            if (value instanceof List) {
                return (List<FileReferenceList.FileReferenceData>) value;
            } else {
                throw new IllegalArgumentException("Input is not supported for FileReferencesTableCellRenderer. Expected List<FileReferenceData>");
            }
        }
    }

    /**
     * Component to display and interact with a list of file references.
     */
    public static class FileReferenceList extends JPanel {
        private final List<FileReferenceData> fileReferences = new ArrayList<>();
        private boolean selected = false;

        private static final Color BADGE_BORDER = new Color(66, 139, 202);
        private static final Color BADGE_FOREGROUND = new Color(66, 139, 202);
        private static final Color BADGE_HOVER_BORDER = new Color(51, 122, 183);
        private static final Color SELECTED_BADGE_BORDER = Color.BLACK;  // for better contrast
        private static final Color SELECTED_BADGE_FOREGROUND = Color.BLACK;  // for better contrast
        private static final int BADGE_ARC_WIDTH = 10;
        private static final float BORDER_THICKNESS = 1.5f;

        public FileReferenceList() {
            setLayout(new FlowLayout(FlowLayout.LEFT, 4, 2));
            setOpaque(true);
        }

        public FileReferenceList(List<FileReferenceData> fileReferences) {
            this();
            setFileReferences(fileReferences);
        }


        /**
         * Updates the displayed file references
         */
        public void setFileReferences(List<FileReferenceData> fileReferences) {
            this.fileReferences.clear();
            if (fileReferences != null) {
                this.fileReferences.addAll(fileReferences);
            }

            // Rebuild the UI
            removeAll();

            // Add each file reference as a label
            for (FileReferenceData file : this.fileReferences) {
                JLabel fileLabel = createBadgeLabel(file.getFileName());
                fileLabel.setOpaque(false);

                // Set tooltip to show the full path
                fileLabel.setToolTipText(file.getFullPath());

                add(fileLabel);
            }

            revalidate();
            repaint();
        }

        /**
         * Sets the selection state of this component
         * @param selected true if this component is in a selected table row
         */
        public void setSelected(boolean selected) {
            if (this.selected == selected) {
                return; // No change needed
            }

            this.selected = selected;

            // Just update the badges directly - we're already on the EDT when this is called
            removeAll();
            for (FileReferenceData file : this.fileReferences) {
                JLabel fileLabel = createBadgeLabel(file.getFileName());
                fileLabel.setOpaque(false);
                fileLabel.setToolTipText(file.getFullPath());
                add(fileLabel);
            }
            revalidate();
            repaint();
        }

        /**
         * Returns whether this component is currently selected
         */
        public boolean isSelected() {
            return selected;
        }

        private JLabel createBadgeLabel(String text) {
            JLabel label = new JLabel(text) {
                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2d = (Graphics2D) g.create();
                    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                    // Determine if hovering
                    boolean isHovered = getMousePosition() != null;

                    // Set border color based on selection state and hover state
                    Color borderColor;
                    if (selected) {
                        borderColor = isHovered ? SELECTED_BADGE_BORDER.brighter() : SELECTED_BADGE_BORDER;
                    } else {
                        borderColor = isHovered ? BADGE_HOVER_BORDER : BADGE_BORDER;
                    }
                    g2d.setColor(borderColor);

                    // Use a thicker stroke for the border
                    g2d.setStroke(new BasicStroke(BORDER_THICKNESS));

                    // Draw rounded rectangle border only
                    g2d.draw(new RoundRectangle2D.Float(BORDER_THICKNESS / 2, BORDER_THICKNESS / 2,
                                                        getWidth() - BORDER_THICKNESS, getHeight() - BORDER_THICKNESS,
                                                        BADGE_ARC_WIDTH, BADGE_ARC_WIDTH));

                    g2d.dispose();

                    // Then draw the text
                    super.paintComponent(g);
                }
            };

            // Style the badge - use a smaller font for table cell
            float fontSize = label.getFont().getSize() * 0.85f;
            label.setFont(label.getFont().deriveFont(Font.PLAIN, fontSize));
            // Set foreground color based on selection state
            label.setForeground(selected ? SELECTED_BADGE_FOREGROUND : BADGE_FOREGROUND);
            label.setBorder(new EmptyBorder(1, 6, 1, 6));

            return label;
        }

        /**
         * Represents a file reference with metadata for context menu usage.
         */
        public static class FileReferenceData {
            private final String fileName;
            private final String fullPath;
            private final ProjectFile projectFile; // Optional, if available

            public FileReferenceData(String fileName, String fullPath, ProjectFile projectFile) {
                this.fileName = fileName;
                this.fullPath = fullPath;
                this.projectFile = projectFile;
            }

            // Getters
            public String getFileName() {
                return fileName;
            }

            public String getFullPath() {
                return fullPath;
            }

            public ProjectFile getRepoFile() {
                return projectFile;
            }

            @Override
            public String toString() {
                return fileName;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                FileReferenceData that = (FileReferenceData) o;
                return fullPath.equals(that.fullPath);
            }

            @Override
            public int hashCode() {
                return fullPath.hashCode();
            }
        }
    }
}
