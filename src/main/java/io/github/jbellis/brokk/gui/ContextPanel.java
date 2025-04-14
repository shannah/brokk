package io.github.jbellis.brokk.gui;

import dev.langchain4j.data.message.AiMessage;
import io.github.jbellis.brokk.Context;
import io.github.jbellis.brokk.ContextFragment;
import io.github.jbellis.brokk.ContextFragment.PathFragment;
import io.github.jbellis.brokk.ContextFragment.VirtualFragment;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.Models;
import io.github.jbellis.brokk.analyzer.BrokkFile;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.CodeUnitType;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.gui.dialogs.CallGraphDialog;
import io.github.jbellis.brokk.gui.dialogs.PlanDialog;
import io.github.jbellis.brokk.gui.dialogs.MultiFileSelectionDialog;
import io.github.jbellis.brokk.gui.dialogs.MultiFileSelectionDialog.SelectionMode;
import io.github.jbellis.brokk.gui.dialogs.SymbolSelectionDialog;
import io.github.jbellis.brokk.prompts.CopyExternalPrompts;
import io.github.jbellis.brokk.util.HtmlToMarkdown;
import io.github.jbellis.brokk.util.StackTrace;
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
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class ContextPanel extends JPanel {
    private static final Logger logger = LogManager.getLogger(ContextPanel.class);

    /**
     * Enum representing the different types of context actions that can be performed.
     */
    public enum ContextAction {
        EDIT, READ, SUMMARIZE, DROP, COPY, PASTE
    }

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

                    ContextFragment selected = null;
                    if (row >= 0) {
                        // Select the row only if:
                        // 1. No rows are currently selected, or
                        // 2. Only one row is selected and it's not the row we clicked on
                        if (contextTable.getSelectedRowCount() == 0 || (contextTable.getSelectedRowCount() == 1 && contextTable.getSelectedRow() != row)) {
                            contextTable.setRowSelectionInterval(row, row);
                        }
                        selected = (ContextFragment) contextTable.getModel().getValueAt(row, FRAGMENT_COLUMN);
                    }

                    // Assign to effectively final variable for use in lambdas
                    final ContextFragment fragmentToShow = selected;

                    if (fragmentToShow instanceof ContextFragment.PlanFragment) {
    JMenuItem editPlanItem = new JMenuItem("Edit Plan");
    editPlanItem.addActionListener(evt -> {
        var currentPlan = contextManager.selectedContext().getPlan();
        var dialog = new PlanDialog(chrome, contextManager, currentPlan);
        dialog.setVisible(true);
    });
    contextMenu.add(editPlanItem);
    contextMenu.addSeparator();
    JMenuItem copyItem = new JMenuItem("Copy");
    copyItem.addActionListener(evt -> {
        var selectedList = java.util.List.of(fragmentToShow);
        chrome.currentUserTask = performContextActionAsync(ContextAction.COPY, selectedList);
    });
    contextMenu.add(copyItem);
    JMenuItem dropItem = new JMenuItem("Drop");
    dropItem.addActionListener(evt -> {
        contextManager.setPlan(io.github.jbellis.brokk.ContextFragment.PlanFragment.EMPTY);
    });
    contextMenu.add(dropItem);
} else if (row >= 0) {
                        // Show Contents as the first action
                        JMenuItem showContentsItem = new JMenuItem("Show Contents");
                        // Use the effectively final variable in the lambda
                        showContentsItem.addActionListener(e1 -> showFragmentPreview(fragmentToShow));
                        contextMenu.add(showContentsItem);
                        contextMenu.addSeparator();

                        // If this is the AutoContext row, show AutoContext items
                        if (fragmentToShow instanceof ContextFragment.AutoContext) {
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
                            if (hasGit && fragmentToShow instanceof ContextFragment.ProjectPathFragment ppf) {
                                JMenuItem viewHistoryItem = new JMenuItem("View History");
                                viewHistoryItem.addActionListener(ev -> {
                                    // Already know it's a ProjectPathFragment here, use ppf captured by the outer if
                                    chrome.getGitPanel().addFileHistoryTab(ppf.file());
                                });
                                contextMenu.add(viewHistoryItem);
                            } else if (fragmentToShow instanceof ContextFragment.ConversationFragment cf) {
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
                            chrome.currentUserTask = performContextActionAsync(ContextAction.EDIT, selectedFragments);
                        });

                        JMenuItem readAllRefsItem = new JMenuItem("Read All References");
                        readAllRefsItem.addActionListener(e1 -> {
                            var selectedFragments = getSelectedFragments();
                            chrome.currentUserTask = performContextActionAsync(ContextAction.READ, selectedFragments);
                        });

                        JMenuItem summarizeAllRefsItem = new JMenuItem("Summarize All References");
                        summarizeAllRefsItem.addActionListener(e1 -> {
                            var selectedFragments = getSelectedFragments();
                            chrome.currentUserTask = performContextActionAsync(ContextAction.SUMMARIZE, selectedFragments);
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
                            chrome.currentUserTask = performContextActionAsync(ContextAction.COPY, selectedFragments);
                        });
                        contextMenu.add(copySelectionItem);

                        JMenuItem dropSelectionItem = new JMenuItem("Drop");
                        dropSelectionItem.addActionListener(ev -> {
                            var selectedFragments = getSelectedFragments();
                            chrome.currentUserTask = performContextActionAsync(ContextAction.DROP, selectedFragments);
                        });
                        contextMenu.add(dropSelectionItem);

                        if (!contextManager.selectedContext().equals(contextManager.topContext())) {
                            dropSelectionItem.setEnabled(false);
                    } else if (contextTable.getSelectedRowCount() == 1 && fragmentToShow instanceof ContextFragment.AutoContext) {
                        // Check if AutoContext is enabled using the fragmentToShow variable
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
                    e -> chrome.currentUserTask = performContextActionAsync(ContextAction.PASTE, List.<ContextFragment>of()),
                    KeyStroke.getKeyStroke(KeyEvent.VK_V, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
                    JComponent.WHEN_FOCUSED
            );

        // Setup right-click popup menu for when no rows are selected
        tablePopupMenu = new JPopupMenu();

        // Add options submenu
        JMenu addMenu = new JMenu("Add");

        JMenuItem editMenuItem = new JMenuItem("Edit Files");
            editMenuItem.addActionListener(e -> {
                chrome.currentUserTask = performContextActionAsync(ContextAction.EDIT, List.<ContextFragment>of());
            });
            // Only add Edit Files when git is present
            if (contextManager != null && contextManager.getProject() != null && contextManager.getProject().hasGit()) {
            addMenu.add(editMenuItem);
        }

        JMenuItem readMenuItem = new JMenuItem("Read Files");
            readMenuItem.addActionListener(e -> {
                chrome.currentUserTask = performContextActionAsync(ContextAction.READ, List.<ContextFragment>of());
            });
            addMenu.add(readMenuItem);

            JMenuItem summarizeMenuItem = new JMenuItem("Summarize Files");
            summarizeMenuItem.addActionListener(e -> {
                chrome.currentUserTask = performContextActionAsync(ContextAction.SUMMARIZE, List.<ContextFragment>of());
            });
            addMenu.add(summarizeMenuItem);

        JMenuItem symbolMenuItem = new JMenuItem("Symbol Usage");
            symbolMenuItem.addActionListener(e -> {
                chrome.currentUserTask = findSymbolUsageAsync();
            });
            addMenu.add(symbolMenuItem);

        JMenuItem callersMenuItem = new JMenuItem("Callers");
            callersMenuItem.addActionListener(e -> {
                chrome.currentUserTask = findMethodCallersAsync();
            });
            addMenu.add(callersMenuItem);

        JMenuItem calleesMenuItem = new JMenuItem("Callees");
            calleesMenuItem.addActionListener(e -> {
                chrome.currentUserTask = findMethodCalleesAsync();
            });
            addMenu.add(calleesMenuItem);

        tablePopupMenu.add(addMenu);
        tablePopupMenu.addSeparator();

        JMenuItem dropAllMenuItem = new JMenuItem("Drop All");
            dropAllMenuItem.addActionListener(e -> {

                chrome.currentUserTask = performContextActionAsync(ContextAction.DROP, List.<ContextFragment>of());
            });
            tablePopupMenu.add(dropAllMenuItem);

            JMenuItem copyAllMenuItem = new JMenuItem("Copy All");
            copyAllMenuItem.addActionListener(e -> {
                chrome.currentUserTask = performContextActionAsync(ContextAction.COPY, List.<ContextFragment>of());
            });
            tablePopupMenu.add(copyAllMenuItem);

            JMenuItem pasteMenuItem = new JMenuItem("Paste");
            pasteMenuItem.addActionListener(e -> {
                chrome.currentUserTask = performContextActionAsync(ContextAction.PASTE, List.<ContextFragment>of());
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
                chrome.currentUserTask = performContextActionAsync(
                        ContextAction.EDIT,
                        List.of(new ContextFragment.ProjectPathFragment(fileRef.getRepoFile()))
                );
            } else {
                chrome.toolErrorRaw("Cannot edit file: " + fileRef.getFullPath() + " - no ProjectFile available"); // Corrected message
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
                chrome.currentUserTask = performContextActionAsync(
                        ContextAction.READ,
                        List.of(new ContextFragment.ProjectPathFragment(fileRef.getRepoFile()))
                );
            } else {
                chrome.toolErrorRaw("Cannot read file: " + fileRef.getFullPath() + " - no ProjectFile available"); // Corrected message
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
                chrome.currentUserTask = performContextActionAsync(
                        ContextAction.SUMMARIZE,
                        List.of(new ContextFragment.ProjectPathFragment(fileRef.getRepoFile()))
                );
            } else {
                chrome.toolErrorRaw("Cannot summarize: " + fileRef.getFullPath() + " - ProjectFile information not available"); // Corrected message
            }
        });
        return summarizeItem;
    }

    // ------------------------------------------------------------------
    // Context Action Logic
    // ------------------------------------------------------------------

    /**
     * Shows the symbol selection dialog and adds usage information for the selected symbol.
     */
    public Future<?> findSymbolUsageAsync()
    {
        // Use contextManager's task submission
        return contextManager.submitContextTask("Find Symbol Usage", () -> {
            try {
                var analyzer = contextManager.getProject().getAnalyzer(); // Use contextManager
                if (analyzer.isEmpty()) {
                    chrome.toolErrorRaw("Code Intelligence is empty; nothing to add"); // Use chrome
                    return;
                }

                String symbol = showSymbolSelectionDialog("Select Symbol", CodeUnitType.ALL);
                if (symbol != null && !symbol.isBlank()) {
                    contextManager.usageForIdentifier(symbol); // Use contextManager
                } else {
                    chrome.systemOutput("No symbol selected."); // Use chrome
                }
            } catch (CancellationException cex) {
                chrome.systemOutput("Symbol selection canceled."); // Use chrome
            }
            // No finally needed, submitContextTask handles enabling buttons
        });
    }

    /**
     * Shows the method selection dialog and adds callers information for the selected method.
     */
    public Future<?> findMethodCallersAsync()
    {
        // Use contextManager's task submission
        return contextManager.submitContextTask("Find Method Callers", () -> {
            try {
                var analyzer = contextManager.getProject().getAnalyzer(); // Use contextManager
                if (analyzer.isEmpty()) {
                    chrome.toolErrorRaw("Code Intelligence is empty; nothing to add"); // Use chrome
                    return;
                }

                var dialog = showCallGraphDialog("Select Method", true);
                if (dialog == null || !dialog.isConfirmed()) { // Check confirmed state
                    chrome.systemOutput("No method selected."); // Use chrome
                } else {
                    // Use contextManager
                    contextManager.callersForMethod(dialog.getSelectedMethod(), dialog.getDepth(), dialog.getCallGraph());
                }
            } catch (CancellationException cex) {
                chrome.systemOutput("Method selection canceled."); // Use chrome
            }
            // No finally needed, submitContextTask handles enabling buttons
        });
    }

    /**
     * Shows the call graph dialog and adds callees information for the selected method.
     */
    public Future<?> findMethodCalleesAsync()
    {
        // Use contextManager's task submission
        return contextManager.submitContextTask("Find Method Callees", () -> {
            try {
                var analyzer = contextManager.getProject().getAnalyzer(); // Use contextManager
                if (analyzer.isEmpty()) {
                    chrome.toolErrorRaw("Code Intelligence is empty; nothing to add"); // Use chrome
                    return;
                }

                var dialog = showCallGraphDialog("Select Method for Callees", false);
                 if (dialog == null || !dialog.isConfirmed() || dialog.getSelectedMethod() == null || dialog.getSelectedMethod().isBlank()) {
                    chrome.systemOutput("No method selected."); // Use chrome
                } else {
                    // Use contextManager
                    contextManager.calleesForMethod(dialog.getSelectedMethod(), dialog.getDepth(), dialog.getCallGraph());
                }
            } catch (CancellationException cex) {
                chrome.systemOutput("Method selection canceled."); // Use chrome
            }
            // No finally needed, submitContextTask handles enabling buttons
        });
    }

    /**
     * Show the symbol selection dialog with a type filter
     */
    private String showSymbolSelectionDialog(String title, Set<CodeUnitType> typeFilter)
    {
        var analyzer = contextManager.getProject().getAnalyzer(); // Use contextManager
        var dialogRef = new AtomicReference<SymbolSelectionDialog>();
        SwingUtil.runOnEDT(() -> {
            var dialog = new SymbolSelectionDialog(chrome.getFrame(), analyzer, title, typeFilter); // Use chrome
            dialog.setSize((int) (chrome.getFrame().getWidth() * 0.9), dialog.getHeight()); // Use chrome
            dialog.setLocationRelativeTo(chrome.getFrame()); // Use chrome
            dialog.setVisible(true);
            dialogRef.set(dialog);
        });
        try {
            var dialog = dialogRef.get();
            if (dialog != null && dialog.isConfirmed()) {
                return dialog.getSelectedSymbol();
            }
            return null;
        } finally {
            chrome.focusInput(); // Use chrome
        }
    }

    /**
     * Show the call graph dialog for configuring method and depth
     */
    private CallGraphDialog showCallGraphDialog(String title, boolean isCallerGraph)
    {
        var analyzer = contextManager.getProject().getAnalyzer(); // Use contextManager
        var dialogRef = new AtomicReference<CallGraphDialog>();
        SwingUtil.runOnEDT(() -> {
            var dialog = new CallGraphDialog(chrome.getFrame(), analyzer, title, isCallerGraph); // Use chrome
            dialog.setSize((int) (chrome.getFrame().getWidth() * 0.9), dialog.getHeight()); // Use chrome
            dialog.setLocationRelativeTo(chrome.getFrame()); // Use chrome
            dialog.setVisible(true);
            dialogRef.set(dialog);
        });
        try {
            var dialog = dialogRef.get();
            if (dialog != null && dialog.isConfirmed()) {
                return dialog;
            }
            return null;
        } finally {
            chrome.focusInput(); // Use chrome
        }
    }

    /**
     * Performed by the action buttons/menus in the context panel: "edit / read / copy / drop / summarize / paste"
     * If selectedFragments is empty, it means "All". We handle logic accordingly.
     */
    public Future<?> performContextActionAsync(ContextAction action, List<? extends ContextFragment> selectedFragments) // Use wildcard
    {
        // Use submitContextTask from ContextManager to run the action on the appropriate executor
        return contextManager.submitContextTask(action + " action", () -> { // Qualify contextManager
            try {
                switch (action) {
                    case EDIT -> doEditAction(selectedFragments);
                    case READ -> doReadAction(selectedFragments);
                    case COPY -> doCopyAction(selectedFragments);
                    case DROP -> doDropAction(selectedFragments);
                    case SUMMARIZE -> doSummarizeAction(selectedFragments);
                    case PASTE -> doPasteAction();
                }
            } catch (CancellationException cex) {
                chrome.systemOutput(action + " canceled."); // Qualify chrome
            }
            // No finally block needed here as submitContextTask handles enabling buttons
        });
    }


    /** Edit Action: Only allows selecting Project Files */
    private void doEditAction(List<? extends ContextFragment> selectedFragments) { // Use wildcard
        var project = contextManager.getProject(); // Qualify contextManager
        if (selectedFragments.isEmpty()) {
            // Show dialog allowing ONLY file selection (no external)
            var selection = showMultiSourceSelectionDialog("Edit Files",
                                                           false, // No external files for edit
                                                           project.getRepo().getTrackedFiles(), // Only tracked files
                                                           Set.of(SelectionMode.FILES)); // Only FILES mode

            if (selection != null && selection.files() != null && !selection.files().isEmpty()) {
                // We disallowed external files, so this cast should be safe
                var projectFiles = toProjectFilesUnsafe(selection.files());
                contextManager.editFiles(projectFiles); // Qualify contextManager
            } else {
                chrome.systemOutput("No files selected for editing."); // Qualify chrome
            }
        } else {
            // Edit files from selected fragments
            var files = new HashSet<ProjectFile>();
            for (var fragment : selectedFragments) {
                files.addAll(fragment.files(project));
            }
            contextManager.editFiles(files); // Qualify contextManager
        }
    }

    /** Read Action: Allows selecting Files (internal/external) */
    private void doReadAction(List<? extends ContextFragment> selectedFragments) { // Use wildcard
        var project = contextManager.getProject(); // Qualify contextManager
        if (selectedFragments.isEmpty()) {
            // Show dialog allowing ONLY file selection (internal + external)
            // TODO when we can extract a single class from a source file, enable classes as well
            var selection = showMultiSourceSelectionDialog("Add Read-Only Context",
                                                           true, // Allow external files
                                                           project.getFiles(), // All project files for completion
                                                           Set.of(SelectionMode.FILES)); // FILES mode only

            if (selection == null || selection.files() == null || selection.files().isEmpty()) {
                chrome.systemOutput("No files selected."); // Qualify chrome
                return;
            }

            contextManager.addReadOnlyFiles(selection.files()); // Qualify contextManager
            chrome.systemOutput("Added " + selection.files().size() + " file(s) as read-only context."); // Qualify chrome
        } else {
            // Add files from selected fragments
            var files = new HashSet<BrokkFile>();
            for (var fragment : selectedFragments) {
                files.addAll(fragment.files(project));
            }
            contextManager.addReadOnlyFiles(files); // Qualify contextManager
        }
    }

    private void doCopyAction(List<? extends ContextFragment> selectedFragments) { // Use wildcard
        String content;
        if (selectedFragments.isEmpty()) {
            // gather entire context
            var msgs = CopyExternalPrompts.instance.collectMessages(contextManager); // Qualify contextManager
            var combined = new StringBuilder();
            for (var m : msgs) {
                if (!(m instanceof AiMessage)) {
                    combined.append(Models.getText(m)).append("\n\n");
                }
            }

            // Get instructions from context
            combined.append("\n<goal>\n").append(chrome.getInputText()).append("\n</goal>"); // Qualify chrome
            content = combined.toString();
        } else {
            // copy only selected fragments
            var sb = new StringBuilder();
            for (var frag : selectedFragments) {
                try {
                    sb.append(frag.text()).append("\n\n");
                } catch (IOException e) {
                    contextManager.removeBadFragment(frag, e); // Qualify contextManager
                    chrome.toolErrorRaw("Error reading fragment: " + e.getMessage()); // Qualify chrome
                }
            }
            content = sb.toString();
        }

        var sel = new java.awt.datatransfer.StringSelection(content);
        var cb = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
        cb.setContents(sel, sel);
        chrome.systemOutput("Content copied to clipboard"); // Qualify chrome
    }

    private void doPasteAction() {
        // Get text from clipboard
        String clipboardText;
        try {
            var clipboard = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
            var contents = clipboard.getContents(null);
            if (contents == null || !contents.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor)) {
                chrome.toolErrorRaw("No text on clipboard"); // Qualify chrome
                return;
            }
            clipboardText = (String) contents.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor);
            if (clipboardText.isBlank()) {
                chrome.toolErrorRaw("Clipboard is empty"); // Qualify chrome
                return;
            }
        } catch (Exception e) {
            chrome.toolErrorRaw("Failed to read clipboard: " + e.getMessage()); // Qualify chrome
            return;
        }

        // Process the clipboard text
        clipboardText = clipboardText.trim();
        String content = clipboardText;
        boolean wasUrl = false;

        if (isUrl(clipboardText)) {
            try {
                chrome.systemOutput("Fetching " + clipboardText); // Qualify chrome
                // Use the static method from ContextTools
                content = io.github.jbellis.brokk.tools.ContextTools.fetchUrlContent(new URI(clipboardText));
                // Use the standard HTML converter
                content = HtmlToMarkdown.maybeConvertToMarkdown(content);
                wasUrl = true;
                chrome.actionComplete(); // Qualify chrome
            } catch (IOException | URISyntaxException e) { // Catch URISyntaxException too
                chrome.toolErrorRaw("Failed to fetch or process URL content: " + e.getMessage()); // Qualify chrome
                // Continue with the URL as text if fetch fails
                content = clipboardText; // Reset content to original URL string on error
            }
        }

        // Try to parse as stacktrace
        var stacktrace = StackTrace.parse(content);
        if (stacktrace != null && contextManager.addStacktraceFragment(stacktrace)) { // Qualify contextManager
            return;
        }

        // Add as string fragment (possibly converted from HTML)
        Future<String> summaryFuture = contextManager.submitSummarizeTaskForPaste(content); // Qualify contextManager
        String finalContent = content;
        contextManager.pushContext(ctx -> { // Qualify contextManager
            var fragment = new ContextFragment.PasteTextFragment(finalContent, summaryFuture);
            return ctx.addPasteFragment(fragment, summaryFuture);
        });

        // Inform the user about what happened
        String message = wasUrl ? "URL content fetched and added" : "Clipboard content added as text";
        chrome.systemOutput(message); // Qualify chrome
    }

    private void doDropAction(List<? extends ContextFragment> selectedFragments) // Use wildcard
    {
        if (selectedFragments.isEmpty()) {
            if (contextManager.topContext().isEmpty()) { // Qualify contextManager
                chrome.toolErrorRaw("No context to drop"); // Qualify chrome
                return;
            }
            contextManager.dropAll(); // Qualify contextManager
        } else {
            var pathFragsToRemove = new ArrayList<PathFragment>();
            var virtualToRemove = new ArrayList<VirtualFragment>();
            boolean clearHistory = false;

            for (var frag : selectedFragments) {
                if (frag instanceof ContextFragment.ConversationFragment) {
                    clearHistory = true;
                } else if (frag instanceof ContextFragment.AutoContext) {
                    contextManager.setAutoContextFiles(0); // Qualify contextManager
                } else if (frag instanceof ContextFragment.PathFragment pf) {
                    pathFragsToRemove.add(pf);
                } else {
                    assert frag instanceof ContextFragment.VirtualFragment : frag;
                    virtualToRemove.add((VirtualFragment) frag);
                }
            }

            if (clearHistory) {
                contextManager.clearHistory(); // Qualify contextManager
                chrome.systemOutput("Cleared conversation history"); // Qualify chrome
            }

            contextManager.drop(pathFragsToRemove, virtualToRemove); // Qualify contextManager

            if (!pathFragsToRemove.isEmpty() || !virtualToRemove.isEmpty()) {
                chrome.systemOutput("Dropped " + (pathFragsToRemove.size() + virtualToRemove.size()) + " items"); // Qualify chrome
            }
        }
    }

    private void doSummarizeAction(List<? extends ContextFragment> selectedFragments) { // Use wildcard
        var project = contextManager.getProject(); // Qualify contextManager
        var analyzer = project.getAnalyzer();
        if (analyzer.isEmpty()) {
            chrome.toolErrorRaw("Code Intelligence is empty; nothing to add"); // Qualify chrome
            return;
        }

        HashSet<CodeUnit> sources = new HashSet<>();
        if (selectedFragments.isEmpty()) {
            // Show dialog allowing selection of files OR classes for summarization
            // Only allow selecting project files that contain classes for the Files tab
            var completableProjectFiles = project.getFiles().stream()
                    .filter(f -> !analyzer.getClassesInFile(f).isEmpty())
                    .collect(Collectors.toSet());

            var selection = showMultiSourceSelectionDialog("Summarize Sources",
                                                           false, // No external files for summarize
                                                           completableProjectFiles, // Project files with classes
                                                           Set.of(SelectionMode.FILES, SelectionMode.CLASSES)); // Both modes

            if (selection == null || selection.isEmpty()) {
                chrome.systemOutput("No files or classes selected for summarization."); // Qualify chrome
                return;
            }

            // Process selected files
            if (selection.files() != null) {
                var projectFiles = toProjectFilesUnsafe(selection.files()); // Should be safe
                for (var file : projectFiles) {
                    sources.addAll(analyzer.getClassesInFile(file));
                }
            }

            // Process selected classes
            if (selection.classes() != null) {
                sources.addAll(selection.classes());
            }
        } else {
            // Extract sources from selected fragments
            for (var frag : selectedFragments) {
                sources.addAll(frag.sources(project));
            }
        }

        if (sources.isEmpty()) {
            chrome.toolErrorRaw("No classes found in the selected " + (selectedFragments.isEmpty() ? "files" : "fragments")); // Qualify chrome
            return;
        }

        boolean success = contextManager.summarizeClasses(sources); // Qualify contextManager
        if (success) {
            chrome.systemOutput("Summarized " + sources.size() + " classes"); // Qualify chrome
        } else {
            chrome.toolErrorRaw("No summarizable classes found"); // Qualify chrome
        }
    }

    /**
     * Cast BrokkFile to ProjectFile. Will throw if ExternalFiles are present.
     * Use with caution, only when external files are disallowed or handled separately.
     */
    private List<ProjectFile> toProjectFilesUnsafe(List<BrokkFile> files) {
        if (files == null) return List.of();
        return files.stream().map(f -> {
            if (f instanceof ProjectFile pf) {
                return pf;
            }
            throw new ClassCastException("Expected only ProjectFile but got " + f.getClass().getName());
        }).toList();
    }

    /**
     * Show the multi-source selection dialog with configurable modes.
     * This is called by the do*Action methods within this panel.
     *
     * @param title              Dialog title.
     * @param allowExternalFiles Allow selection of external files in the Files tab.
     * @param completions        Set of completable project files.
     * @param modes              Set of selection modes (FILES, CLASSES) to enable.
     * @return The Selection record containing lists of files and/or classes, or null if cancelled.
     */
    private MultiFileSelectionDialog.Selection showMultiSourceSelectionDialog(String title, boolean allowExternalFiles, Set<ProjectFile> completions, Set<SelectionMode> modes) {
        var dialogRef = new AtomicReference<MultiFileSelectionDialog>();
        var analyzer = contextManager.getProject().getAnalyzer(); // Qualify contextManager

        SwingUtil.runOnEDT(() -> {
            var dialog = new MultiFileSelectionDialog(chrome.getFrame(), contextManager.getProject(), analyzer, title, allowExternalFiles, completions, modes); // Qualify chrome and contextManager
            // Use dialog's preferred size after packing, potentially adjust width
            dialog.setSize(Math.max(600, dialog.getWidth()), Math.max(550, dialog.getHeight()));
            dialog.setLocationRelativeTo(chrome.getFrame()); // Qualify chrome
            dialog.setVisible(true);
            dialogRef.set(dialog);
        });
        try {
            var dialog = dialogRef.get();
            if (dialog != null && dialog.isConfirmed()) {
                return dialog.getSelection(); // Return the Selection record
            }
            return null; // Indicate cancellation or no selection
        } finally {
            chrome.focusInput(); // Qualify chrome
        }
    }

    private boolean isUrl(String text) {
        return text.matches("^https?://\\S+$");
    }

    /**
     * Table cell renderer for displaying file references.
     */
    static class FileReferencesTableCellRenderer implements TableCellRenderer {
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
    static class FileReferenceList extends JPanel {
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
        static class FileReferenceData {
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
