package io.github.jbellis.brokk.gui;

import static java.util.Objects.requireNonNull;
import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import io.github.jbellis.brokk.AnalyzerWrapper;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.Service;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.CodeUnitType;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.analyzer.SkeletonProvider;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.gui.components.MaterialButton;
import io.github.jbellis.brokk.gui.components.OverlayPanel;
import io.github.jbellis.brokk.gui.components.SpinnerIconUtil;
import io.github.jbellis.brokk.gui.dialogs.AttachContextDialog;
import io.github.jbellis.brokk.gui.dialogs.CallGraphDialog;
import io.github.jbellis.brokk.gui.dialogs.DropActionDialog;
import io.github.jbellis.brokk.gui.dialogs.SymbolSelectionDialog;
import io.github.jbellis.brokk.gui.util.ContextMenuUtils;
import io.github.jbellis.brokk.gui.util.Icons;
import io.github.jbellis.brokk.gui.util.KeyboardShortcutUtil;
import io.github.jbellis.brokk.prompts.CopyExternalPrompts;
import io.github.jbellis.brokk.tools.WorkspaceTools;
import io.github.jbellis.brokk.util.HtmlToMarkdown;
import io.github.jbellis.brokk.util.ImageUtil;
import io.github.jbellis.brokk.util.Messages;
import io.github.jbellis.brokk.util.StackTrace;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.*;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import okhttp3.OkHttpClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class WorkspacePanel extends JPanel {
    private static final Logger logger = LogManager.getLogger(WorkspacePanel.class);
    private final String EMPTY_CONTEXT = "Empty Workspace--use Edit or Read or Summarize to add content";

    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .writeTimeout(1, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    /** Enum representing the different types of context actions that can be performed. */
    public enum ContextAction {
        EDIT,
        SUMMARIZE,
        DROP,
        COPY,
        PASTE,
        RUN_TESTS
    }

    public enum PopupMenuMode {
        FULL,
        COPY_ONLY
    }

    /** Sealed interface representing different popup menu scenarios */
    public sealed interface PopupScenario permits NoSelection, FileBadge, SingleFragment, MultiFragment {
        List<Action> getActions(WorkspacePanel panel);
    }

    public static final class NoSelection implements PopupScenario {
        @Override
        public List<Action> getActions(WorkspacePanel panel) {
            var actions = new ArrayList<Action>();

            // Always add drop all action but enable/disable based on workspace state
            var dropAllAction = WorkspaceAction.DROP_ALL.createAction(panel);
            if (!panel.workspaceCurrentlyEditable) {
                dropAllAction.setEnabled(false);
                dropAllAction.putValue(Action.SHORT_DESCRIPTION, "Drop All is disabled in read-only mode");
            }
            actions.add(dropAllAction);

            actions.add(WorkspaceAction.COPY_ALL.createAction(panel));
            actions.add(WorkspaceAction.PASTE.createAction(panel));

            return actions;
        }
    }

    public static final class FileBadge implements PopupScenario {
        private final TableUtils.FileReferenceList.FileReferenceData fileRef;

        public FileBadge(TableUtils.FileReferenceList.FileReferenceData fileRef) {
            this.fileRef = fileRef;
        }

        @Override
        public List<Action> getActions(WorkspacePanel panel) {
            var actions = new ArrayList<Action>();

            if (fileRef.getRepoFile() != null) {
                actions.add(WorkspaceAction.SHOW_IN_PROJECT.createFileAction(panel, fileRef.getRepoFile()));
                actions.add(WorkspaceAction.VIEW_FILE.createFileAction(panel, fileRef.getRepoFile()));

                if (panel.hasGit()) {
                    actions.add(WorkspaceAction.VIEW_HISTORY.createFileAction(panel, fileRef.getRepoFile()));
                } else {
                    actions.add(
                            WorkspaceAction.VIEW_HISTORY.createDisabledAction("Git not available for this project."));
                }
                if (ContextManager.isTestFile(fileRef.getRepoFile())) {
                    actions.add(WorkspaceAction.RUN_TESTS.createFileRefAction(panel, fileRef));
                } else {
                    var disabledAction = WorkspaceAction.RUN_TESTS.createDisabledAction("Not a test file");
                    actions.add(disabledAction);
                }

                actions.add(null); // Separator
                actions.add(WorkspaceAction.EDIT_FILE.createFileRefAction(panel, fileRef));
                actions.add(WorkspaceAction.SUMMARIZE_FILE.createFileRefAction(panel, fileRef));
            }

            return actions;
        }
    }

    public static final class SingleFragment implements PopupScenario {
        private final ContextFragment fragment;

        public SingleFragment(ContextFragment fragment) {
            this.fragment = fragment;
        }

        @Override
        public List<Action> getActions(WorkspacePanel panel) {
            var actions = new ArrayList<Action>();

            // Show in Project (for PROJECT_PATH fragments)
            if (fragment.getType() == ContextFragment.FragmentType.PROJECT_PATH) {
                fragment.files().stream()
                        .findFirst()
                        .ifPresent(projectFile ->
                                actions.add(WorkspaceAction.SHOW_IN_PROJECT.createFileAction(panel, projectFile)));
            }

            // View File/Contents
            if (fragment.getType() == ContextFragment.FragmentType.PROJECT_PATH) {
                actions.add(WorkspaceAction.VIEW_FILE.createFragmentAction(panel, fragment));
            } else {
                actions.add(WorkspaceAction.SHOW_CONTENTS.createFragmentAction(panel, fragment));
            }

            // View History/Compress History
            if (panel.hasGit() && fragment.getType() == ContextFragment.FragmentType.PROJECT_PATH) {
                fragment.files().stream()
                        .findFirst()
                        .ifPresent(projectFile ->
                                actions.add(WorkspaceAction.VIEW_HISTORY.createFileAction(panel, projectFile)));
            } else if (fragment.getType() == ContextFragment.FragmentType.HISTORY) {
                var cf = (ContextFragment.HistoryFragment) fragment;
                var uncompressedExists = cf.entries().stream().anyMatch(entry -> !entry.isCompressed());
                if (uncompressedExists) {
                    actions.add(WorkspaceAction.COMPRESS_HISTORY.createAction(panel));
                } else {
                    actions.add(WorkspaceAction.COMPRESS_HISTORY.createDisabledAction(
                            "No uncompressed history to compress"));
                }
            } else {
                actions.add(WorkspaceAction.VIEW_HISTORY.createDisabledAction(
                        "View History is available only for single project files."));
            }

            // Add Run Tests action if the fragment is associated with a test file
            if (fragment.getType() == ContextFragment.FragmentType.PROJECT_PATH
                    && fragment.files().stream().anyMatch(ContextManager::isTestFile)) {
                actions.add(WorkspaceAction.RUN_TESTS.createFragmentsAction(panel, List.of(fragment)));
            } else {
                var disabledAction = WorkspaceAction.RUN_TESTS.createDisabledAction("No test files in selection");
                actions.add(disabledAction);
            }

            actions.add(null); // Separator

            // Edit/Read/Summarize
            if (fragment.getType() == ContextFragment.FragmentType.PROJECT_PATH) {
                fragment.files().stream().findFirst().ifPresent(projectFile -> {
                    var fileData = new TableUtils.FileReferenceList.FileReferenceData(
                            projectFile.getFileName(), projectFile.toString(), projectFile);
                    actions.add(WorkspaceAction.EDIT_FILE.createFileRefAction(panel, fileData));
                    actions.add(WorkspaceAction.SUMMARIZE_FILE.createFileRefAction(panel, fileData));
                });
            } else {
                var selectedFragments = List.of(fragment);
                actions.add(WorkspaceAction.EDIT_ALL_REFS.createFragmentsAction(panel, selectedFragments));
                actions.add(WorkspaceAction.SUMMARIZE_ALL_REFS.createFragmentsAction(panel, selectedFragments));
            }

            actions.add(null); // Separator
            actions.add(WorkspaceAction.COPY.createFragmentsAction(panel, List.of(fragment)));

            // Always add drop action but enable/disable based on workspace state
            var dropAction = WorkspaceAction.DROP.createFragmentsAction(panel, List.of(fragment));
            if (!panel.workspaceCurrentlyEditable || !panel.isOnLatestContext()) {
                dropAction.setEnabled(false);
                String tooltip = !panel.workspaceCurrentlyEditable
                        ? "Drop is disabled in read-only mode"
                        : "Drop is only available when viewing the latest context";
                dropAction.putValue(Action.SHORT_DESCRIPTION, tooltip);
            }
            actions.add(dropAction);

            return actions;
        }
    }

    public static final class MultiFragment implements PopupScenario {
        private final List<ContextFragment> fragments;

        public MultiFragment(List<ContextFragment> fragments) {
            this.fragments = fragments;
        }

        @Override
        public List<Action> getActions(WorkspacePanel panel) {
            var actions = new ArrayList<Action>();

            actions.add(WorkspaceAction.SHOW_CONTENTS.createDisabledAction(
                    "Cannot view contents of multiple items at once."));
            actions.add(WorkspaceAction.VIEW_HISTORY.createDisabledAction("Cannot view history for multiple items."));
            // Add Run Tests action if all selected fragment is associated with a test file
            if (fragments.stream().flatMap(f -> f.files().stream()).allMatch(ContextManager::isTestFile)) {
                actions.add(WorkspaceAction.RUN_TESTS.createFragmentsAction(panel, fragments));
            } else {
                var disabledAction = WorkspaceAction.RUN_TESTS.createDisabledAction("No test files in selection");
                actions.add(disabledAction);
            }

            actions.add(null); // Separator

            actions.add(WorkspaceAction.EDIT_ALL_REFS.createFragmentsAction(panel, fragments));
            actions.add(WorkspaceAction.SUMMARIZE_ALL_REFS.createFragmentsAction(panel, fragments));

            actions.add(null); // Separator
            actions.add(WorkspaceAction.COPY.createFragmentsAction(panel, fragments));

            // Always add drop action but enable/disable based on workspace state
            var dropAction = WorkspaceAction.DROP.createFragmentsAction(panel, fragments);
            if (!panel.workspaceCurrentlyEditable || !panel.isOnLatestContext()) {
                dropAction.setEnabled(false);
                String tooltip = !panel.workspaceCurrentlyEditable
                        ? "Drop is disabled in read-only mode"
                        : "Drop is only available when viewing the latest context";
                dropAction.putValue(Action.SHORT_DESCRIPTION, tooltip);
            }
            actions.add(dropAction);

            return actions;
        }
    }

    /** Enum of reusable workspace actions */
    public enum WorkspaceAction {
        SHOW_IN_PROJECT("Show in Project"),
        VIEW_FILE("View File"),
        SHOW_CONTENTS("Show Contents"),
        VIEW_HISTORY("View History"),
        COMPRESS_HISTORY("Compress History"),
        EDIT_FILE("Edit File"),
        SUMMARIZE_FILE("Summarize File"),
        EDIT_ALL_REFS("Edit all References"),
        SUMMARIZE_ALL_REFS("Summarize all References"),
        COPY("Copy"),
        DROP("Drop"),
        DROP_ALL("Drop All"),
        COPY_ALL("Copy All"),
        PASTE("Paste text, images, urls"),
        RUN_TESTS("Run Tests");

        private final String label;

        WorkspaceAction(String label) {
            this.label = label;
        }

        public AbstractAction createAction(WorkspacePanel panel) {
            return new AbstractAction(label) {
                @Override
                public void actionPerformed(ActionEvent e) {
                    switch (WorkspaceAction.this) {
                        case DROP_ALL -> panel.performContextActionAsync(ContextAction.DROP, List.of());
                        case COPY_ALL -> panel.performContextActionAsync(ContextAction.COPY, List.of());
                        case PASTE -> panel.performContextActionAsync(ContextAction.PASTE, List.of());
                        case COMPRESS_HISTORY -> panel.contextManager.compressHistoryAsync();
                        case RUN_TESTS -> panel.performContextActionAsync(ContextAction.RUN_TESTS, List.of());
                        default ->
                            throw new UnsupportedOperationException("Action not implemented: " + WorkspaceAction.this);
                    }
                }
            };
        }

        public AbstractAction createDisabledAction(String tooltip) {
            var action = new AbstractAction(label) {
                @Override
                public void actionPerformed(ActionEvent e) {
                    // Disabled actions do nothing
                }
            };
            action.setEnabled(false);
            action.putValue(Action.SHORT_DESCRIPTION, tooltip);
            return action;
        }

        public AbstractAction createFileAction(WorkspacePanel panel, ProjectFile file) {
            return new AbstractAction(label) {
                @Override
                public void actionPerformed(ActionEvent e) {
                    switch (WorkspaceAction.this) {
                        case SHOW_IN_PROJECT -> panel.chrome.showFileInProjectTree(file);
                        case VIEW_FILE -> {
                            var fragment = new ContextFragment.ProjectPathFragment(file, panel.contextManager);
                            panel.showFragmentPreview(fragment);
                        }
                        case VIEW_HISTORY -> panel.chrome.addFileHistoryTab(file);
                        default ->
                            throw new UnsupportedOperationException(
                                    "File action not implemented: " + WorkspaceAction.this);
                    }
                }
            };
        }

        public AbstractAction createFileRefAction(
                WorkspacePanel panel, TableUtils.FileReferenceList.FileReferenceData fileRef) {
            var baseName = this == EDIT_FILE ? "Edit " : "Summarize ";
            return new AbstractAction(baseName + fileRef.getFullPath()) {
                @Override
                public void actionPerformed(ActionEvent e) {
                    if (fileRef.getRepoFile() != null) {
                        var contextAction =
                                switch (WorkspaceAction.this) {
                                    case EDIT_FILE -> ContextAction.EDIT;
                                    case SUMMARIZE_FILE -> ContextAction.SUMMARIZE;
                                    default ->
                                        throw new UnsupportedOperationException(
                                                "File ref action not implemented: " + WorkspaceAction.this);
                                };
                        var fragment =
                                new ContextFragment.ProjectPathFragment(fileRef.getRepoFile(), panel.contextManager);
                        panel.performContextActionAsync(contextAction, List.of(fragment));
                    } else {
                        panel.chrome.toolError("Cannot " + label.toLowerCase(Locale.ROOT) + ": " + fileRef.getFullPath()
                                + " - no ProjectFile available");
                    }

                    // Apply edit restrictions
                    if (WorkspaceAction.this == EDIT_FILE) {
                        var project = panel.contextManager.getProject();
                        var repoFile = fileRef.getRepoFile();
                        if (!panel.hasGit()) {
                            setEnabled(false);
                            putValue(Action.SHORT_DESCRIPTION, "Editing not available without Git");
                        } else if (repoFile == null) {
                            setEnabled(false);
                            putValue(Action.SHORT_DESCRIPTION, "Editing not available for external files");
                        } else if (!project.getRepo().getTrackedFiles().contains(repoFile)) {
                            setEnabled(false);
                            putValue(Action.SHORT_DESCRIPTION, "Cannot edit untracked file: " + fileRef.getFullPath());
                        }
                    }
                }
            };
        }

        public AbstractAction createFragmentAction(WorkspacePanel panel, ContextFragment fragment) {
            return new AbstractAction(label) {
                @Override
                public void actionPerformed(ActionEvent e) {
                    switch (WorkspaceAction.this) {
                        case VIEW_FILE, SHOW_CONTENTS -> panel.showFragmentPreview(fragment);
                        default ->
                            throw new UnsupportedOperationException(
                                    "Fragment action not implemented: " + WorkspaceAction.this);
                    }
                }
            };
        }

        public AbstractAction createFragmentsAction(WorkspacePanel panel, List<ContextFragment> fragments) {
            return new AbstractAction(label) {
                @Override
                public void actionPerformed(ActionEvent e) {
                    var contextAction =
                            switch (WorkspaceAction.this) {
                                case EDIT_ALL_REFS -> ContextAction.EDIT;
                                case SUMMARIZE_ALL_REFS -> ContextAction.SUMMARIZE;
                                case COPY -> ContextAction.COPY;
                                case DROP -> ContextAction.DROP;
                                case RUN_TESTS -> ContextAction.RUN_TESTS;
                                default ->
                                    throw new UnsupportedOperationException(
                                            "Fragments action not implemented: " + WorkspaceAction.this);
                            };
                    panel.performContextActionAsync(contextAction, fragments);

                    // Apply enable/disable logic for specific actions
                    if (WorkspaceAction.this == EDIT_ALL_REFS) {
                        if (!panel.allTrackedProjectFiles(fragments)) {
                            var hasFiles = panel.hasFiles(fragments);
                            setEnabled(false);
                            if (!hasFiles) {
                                putValue(Action.SHORT_DESCRIPTION, "No files associated with the selection to edit.");
                            } else {
                                putValue(
                                        Action.SHORT_DESCRIPTION,
                                        "Cannot edit because selection includes untracked or external files.");
                            }
                        }
                    } else if (WorkspaceAction.this == SUMMARIZE_ALL_REFS) {
                        if (!panel.hasFiles(fragments)) {
                            setEnabled(false);
                            putValue(Action.SHORT_DESCRIPTION, "No files associated with the selection to summarize.");
                        }
                    }
                }
            };
        }
    }

    /** Immutable record containing description text and associated file references */
    public static record DescriptionWithReferences(
            String description,
            List<TableUtils.FileReferenceList.FileReferenceData> fileReferences,
            ContextFragment fragment) {
        public DescriptionWithReferences {
            // Defensive copy for immutability
            fileReferences = List.copyOf(fileReferences);
        }

        @Override
        public String toString() {
            return description; // For backward compatibility with existing code that expects String
        }
    }

    /**
     * Custom table cell renderer for the Description column that displays description text on top and file reference
     * badges below.
     */
    private static class DescriptionWithBadgesRenderer implements javax.swing.table.TableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(
                JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            // Create main panel with vertical layout
            JPanel panel = new JPanel();
            // FlowLayout centers components vertically within the row, keeping badges aligned
            // with the description text.
            panel.setLayout(new FlowLayout(FlowLayout.LEFT, 3, 0));
            panel.setOpaque(true);

            // Set colors based on selection
            if (isSelected) {
                panel.setBackground(table.getSelectionBackground());
                panel.setForeground(table.getSelectionForeground());
            } else {
                panel.setBackground(table.getBackground());
                panel.setForeground(table.getForeground());
            }

            // Extract data from the DescriptionWithReferences record
            DescriptionWithReferences data = (DescriptionWithReferences) value;
            if (data == null) {
                return panel; // Return empty panel if no data
            }

            String description = data.description();
            List<TableUtils.FileReferenceList.FileReferenceData> fileReferences = data.fileReferences();

            // Calculate available width for description after reserving space for badges
            int colWidth = table.getColumnModel().getColumn(column).getWidth();
            int reservedWidth = fileReferences.isEmpty() ? 0 : 130; // room for visible/overflow badges + gap
            var fm = table.getFontMetrics(table.getFont());
            String clipped = ellipsize(description, fm, Math.max(colWidth - reservedWidth, 30));

            // Create description label (possibly clipped with …)
            JLabel descLabel = new JLabel(clipped);
            descLabel.setOpaque(false);
            descLabel.setForeground(panel.getForeground());
            descLabel.setVerticalAlignment(SwingConstants.CENTER); // Center alignment with LOC column
            descLabel.setAlignmentY(Component.CENTER_ALIGNMENT);

            // If we clipped the text, show the full description in a tooltip
            if (!clipped.equals(description)) {
                descLabel.setToolTipText(description);
            }

            // Add description to panel
            descLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(descLabel);

            // Add file badges if there are any
            if (!fileReferences.isEmpty()) {
                // Add small horizontal gap between description and badges
                panel.add(Box.createHorizontalStrut(3));

                // Calculate available width for badges (table column width minus padding and description width)
                int availableWidth = table.getColumnModel().getColumn(column).getWidth()
                        - 20
                        - descLabel.getPreferredSize().width; // Leave some padding

                // Create adaptive file reference list
                var badgeList =
                        new TableUtils.FileReferenceList.AdaptiveFileReferenceList(fileReferences, availableWidth, 4);
                badgeList.setOpaque(false);
                badgeList.setAlignmentX(Component.LEFT_ALIGNMENT);
                badgeList.setAlignmentY(Component.CENTER_ALIGNMENT);

                // Set badge colors based on selection
                badgeList.setSelected(isSelected);

                panel.add(badgeList);
            }

            // Calculate preferred height
            int preferredHeight = calculatePreferredHeight(panel);

            // Set row height if different from current
            if (table.getRowHeight(row) != preferredHeight) {
                SwingUtilities.invokeLater(() -> table.setRowHeight(row, preferredHeight));
            }

            return panel;
        }

        private int calculatePreferredHeight(JPanel panel) {
            // Force layout to get accurate measurements
            panel.doLayout();
            return panel.getPreferredSize().height;
        }

        /** Return a possibly-truncated version of {@code text} that fits within {@code maxWidth}. */
        private static String ellipsize(String text, FontMetrics fm, int maxWidth) {
            if (fm.stringWidth(text) <= maxWidth) {
                return text;
            }

            String ellipsis = "...";
            int ellipsisWidth = fm.stringWidth(ellipsis);
            if (ellipsisWidth >= maxWidth) { // not even room for the ellipsis
                return ellipsis;
            }

            int low = 0;
            int high = text.length();
            while (low < high) {
                int mid = (low + high) >>> 1;
                String candidate = text.substring(0, mid) + ellipsis;
                int w = fm.stringWidth(candidate);
                if (w > maxWidth) {
                    high = mid;
                } else {
                    low = mid + 1;
                }
            }
            // low is first index that does NOT fit; use low-1
            return text.substring(0, Math.max(0, low - 1)) + ellipsis;
        }
    }

    /** Helper for building popup menus consistently */
    public static class PopupBuilder {
        private final JPopupMenu popup;
        private final Chrome chrome;

        private PopupBuilder(Chrome chrome) {
            this.popup = new JPopupMenu();
            this.chrome = chrome;
        }

        public static PopupBuilder create(Chrome chrome) {
            return new PopupBuilder(chrome);
        }

        public PopupBuilder add(List<Action> actions) {
            for (var action : actions) {
                if (action == null) {
                    popup.addSeparator();
                } else {
                    popup.add(new JMenuItem(action));
                }
            }
            return this;
        }

        public PopupBuilder addSeparator() {
            popup.addSeparator();
            return this;
        }

        public void show(Component invoker, int x, int y) {
            chrome.themeManager.registerPopupMenu(popup);
            popup.show(invoker, x, y);
        }
    }

    // Columns
    public static final int LOC_COLUMN = 0;
    public static final int DESCRIPTION_COLUMN = 1;
    private final int FRAGMENT_COLUMN = 2;

    // Column dimensions
    private static final int LOC_COLUMN_WIDTH = 55;
    private static final int LOC_COLUMN_RIGHT_PADDING = 6;
    private static final int DESCRIPTION_COLUMN_WIDTH = 480;

    // Parent references
    private final Chrome chrome;
    private final ContextManager contextManager;

    private JTable contextTable;

    private final PopupMenuMode popupMenuMode;
    private JPanel locSummaryPanel;
    private JPanel summaryWithAdd;
    private JPanel warningPanel; // Panel for warning messages
    private JPanel analyzerRebuildPanel;
    private @Nullable JLabel analyzerRebuildSpinner;
    private boolean workspaceCurrentlyEditable = true;

    @Nullable
    private Context currentContext;

    private OverlayPanel workspaceOverlay;
    private JLayeredPane workspaceLayeredPane;

    @Nullable
    private JMenuItem dropAllMenuItem = null;

    // Global dispatcher for Cmd/Ctrl+Shift+I to open Attach Context
    @Nullable
    private KeyEventDispatcher globalAttachDispatcher = null;

    // Observers for bottom-controls height changes
    private final List<BottomControlsListener> bottomControlsListeners = new ArrayList<>();

    // Buttons
    // Table popup menu (when no row is selected)
    private JPopupMenu tablePopupMenu;

    private static final String READ_ONLY_TIP = "Select latest activity to enable";
    private static final String COPY_ALL_ACTION_CMD = "workspace.copyAll";
    private static final String DROP_ALL_ACTION_CMD = "workspace.dropAll";

    /** Primary constructor allowing menu-mode selection */
    public WorkspacePanel(Chrome chrome, ContextManager contextManager, PopupMenuMode popupMenuMode) {
        super(new BorderLayout());

        setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Workspace",
                TitledBorder.DEFAULT_JUSTIFICATION,
                TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)));

        this.chrome = chrome;
        this.contextManager = contextManager;
        this.popupMenuMode = popupMenuMode;

        buildContextPanel();

        safeGetLabel(0).setText(EMPTY_CONTEXT);
        setWorkspaceEditable(true); // Set initial state

        // Register a listener so WorkspacePanel updates cost estimates / table when the selected model changes.
        try {
            var ip = chrome.getInstructionsPanel();
            ip.addModelSelectionListener(cfg -> SwingUtilities.invokeLater(() -> {
                // Recompute the table contents and cost estimates for the currently selected context.
                populateContextTable(contextManager.selectedContext());
                refreshMenuState();
            }));
        } catch (Exception ex) {
            logger.debug("Could not register model selection listener for WorkspacePanel", ex);
        }
    }

    /** Convenience constructor – defaults to FULL popup menu */
    public WorkspacePanel(Chrome chrome, ContextManager contextManager) {
        this(chrome, contextManager, PopupMenuMode.FULL);
    }

    /** Build the context panel (unified table + action buttons). */
    private void buildContextPanel() {
        DefaultTableModel tableModel = new DefaultTableModel(new Object[] {"LOC", "Description", "Fragment"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return switch (columnIndex) {
                    case LOC_COLUMN -> Integer.class;
                    case DESCRIPTION_COLUMN -> DescriptionWithReferences.class;
                    case FRAGMENT_COLUMN -> ContextFragment.class;
                    default -> Object.class;
                };
            }
        };

        contextTable = new JTable(tableModel) {
            @Override
            public Component prepareRenderer(javax.swing.table.TableCellRenderer renderer, int row, int column) {
                Component c = super.prepareRenderer(renderer, row, column);
                if (c != null) {
                    c.setEnabled(workspaceCurrentlyEditable);
                }
                return c;
            }
        };

        // Add custom cell renderer for the "Description" column that includes badges
        contextTable
                .getColumnModel()
                .getColumn(DESCRIPTION_COLUMN)
                .setCellRenderer(new DescriptionWithBadgesRenderer());

        // Set right alignment for LOC column numbers with font metrics-based baseline alignment
        var locRenderer = new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(
                    JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

                // Calculate baseline-aligned padding using font metrics - match TableUtils calculation
                var tableFont = table.getFont();
                var fontMetrics = table.getFontMetrics(tableFont);

                // Use a small offset to align with description text baseline (similar to TableUtils approach)
                int baselineOffset = fontMetrics.getLeading();

                setBorder(BorderFactory.createEmptyBorder(0, 0, baselineOffset, LOC_COLUMN_RIGHT_PADDING));
                return c;
            }
        };
        locRenderer.setHorizontalAlignment(SwingConstants.RIGHT);
        locRenderer.setVerticalAlignment(SwingConstants.CENTER);
        contextTable.getColumnModel().getColumn(LOC_COLUMN).setCellRenderer(locRenderer);

        // Remove file references column setup - badges will be in description column

        // Hide the FRAGMENT_COLUMN from view
        contextTable.getColumnModel().getColumn(FRAGMENT_COLUMN).setMinWidth(0);
        contextTable.getColumnModel().getColumn(FRAGMENT_COLUMN).setMaxWidth(0);
        contextTable.getColumnModel().getColumn(FRAGMENT_COLUMN).setWidth(0);

        // LOC column: precise width so right-aligned numbers align with description text start
        contextTable.getColumnModel().getColumn(LOC_COLUMN).setPreferredWidth(LOC_COLUMN_WIDTH);
        contextTable.getColumnModel().getColumn(LOC_COLUMN).setMaxWidth(LOC_COLUMN_WIDTH);
        contextTable.getColumnModel().getColumn(LOC_COLUMN).setMinWidth(LOC_COLUMN_WIDTH);
        contextTable.getColumnModel().getColumn(DESCRIPTION_COLUMN).setPreferredWidth(DESCRIPTION_COLUMN_WIDTH);

        // Add mouse listener to handle file reference badge clicks
        contextTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeHandleFileRefClick(e);
            }

            private void maybeHandleFileRefClick(MouseEvent e) {
                // If it's a popup trigger, let the table's main popup handler deal with it.
                if (!e.isPopupTrigger()) {
                    // Handle badge clicks in the new description column layout
                    int col = contextTable.columnAtPoint(e.getPoint());
                    if (col == DESCRIPTION_COLUMN) { // Description column
                        int row = contextTable.rowAtPoint(e.getPoint());
                        if (row >= 0) {
                            // Check if the cell contains file references
                            var descriptionData = (DescriptionWithReferences)
                                    contextTable.getModel().getValueAt(row, DESCRIPTION_COLUMN);
                            if (descriptionData != null
                                    && !descriptionData.fileReferences().isEmpty()) {
                                // Check if the click actually hit a badge (not just anywhere in the cell)
                                var clickedRef = TableUtils.findClickedReference(
                                        e.getPoint(), row, DESCRIPTION_COLUMN, contextTable);

                                if (clickedRef != null && clickedRef.getRepoFile() != null) {
                                    // Direct badge click: delegate to central handler
                                    ContextMenuUtils.handleFileReferenceClick(
                                            e,
                                            contextTable,
                                            chrome,
                                            () -> {}, // Workspace doesn’t need to refresh suggestions
                                            DESCRIPTION_COLUMN);
                                } else {
                                    // Check for overflow “+ N more” badge click
                                    boolean isOverflowClick = TableUtils.isClickOnOverflowBadge(
                                            e.getPoint(), row, DESCRIPTION_COLUMN, contextTable);
                                    if (isOverflowClick) {
                                        // Obtain the renderer component to fetch hidden files list
                                        Component rendererComp = contextTable.prepareRenderer(
                                                contextTable.getCellRenderer(row, DESCRIPTION_COLUMN),
                                                row,
                                                DESCRIPTION_COLUMN);

                                        var afl = (TableUtils.FileReferenceList.AdaptiveFileReferenceList)
                                                TableUtils.findFileReferenceList((Container) rendererComp);

                                        java.util.List<TableUtils.FileReferenceList.FileReferenceData> hiddenFiles =
                                                afl != null ? afl.getHiddenFiles() : java.util.List.of();

                                        TableUtils.showOverflowPopup(
                                                chrome, contextTable, row, DESCRIPTION_COLUMN, hiddenFiles);
                                        e.consume(); // Stop further processing
                                    }
                                }
                            }
                        }
                    }
                }
            }
        });

        // Add mouse motion for tooltips
        contextTable.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int row = contextTable.rowAtPoint(e.getPoint());
                int col = contextTable.columnAtPoint(e.getPoint());
                if (row >= 0 && col == DESCRIPTION_COLUMN) {
                    // Show full description - badge tooltips will be handled by new renderer
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
        chrome.themeManager.registerPopupMenu(contextMenu);

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
                if (e.isConsumed()) return;
                if (!e.isPopupTrigger()) return;

                int row = contextTable.rowAtPoint(e.getPoint());

                // Handle COPY_ONLY mode
                if (popupMenuMode == PopupMenuMode.COPY_ONLY) {
                    if (row >= 0) {
                        if (contextTable.getSelectedRowCount() == 0
                                || !Arrays.stream(contextTable.getSelectedRows())
                                        .anyMatch(r -> r == row)) {
                            contextTable.setRowSelectionInterval(row, row);
                        }
                        JMenuItem copyItem = new JMenuItem("Copy to Active Workspace");
                        copyItem.addActionListener(ev -> {
                            var fragsToCopy = getSelectedFragments();
                            contextManager.addFilteredToContextAsync(requireNonNull(currentContext), fragsToCopy);
                        });
                        contextMenu.removeAll();
                        contextMenu.add(copyItem);
                        chrome.themeManager.registerPopupMenu(contextMenu);
                        contextMenu.show(contextTable, e.getX(), e.getY());
                    }
                    return;
                }

                // Ensure row selection
                if (row >= 0) {
                    boolean rowIsSelected =
                            Arrays.stream(contextTable.getSelectedRows()).anyMatch(r -> r == row);
                    if (!rowIsSelected) {
                        contextTable.setRowSelectionInterval(row, row);
                    }
                }

                List<ContextFragment> selectedFragments = getSelectedFragments();

                // Show empty table menu if no selection
                if (row < 0 || selectedFragments.isEmpty()) {
                    tablePopupMenu.show(contextTable, e.getX(), e.getY());
                    chrome.themeManager.registerPopupMenu(tablePopupMenu);
                    return;
                }

                // Classify scenario and build menu
                PopupScenario scenario = classifyScenario(e, selectedFragments);
                List<Action> actions = scenario.getActions(WorkspacePanel.this);

                PopupBuilder.create(chrome).add(actions).show(contextTable, e.getX(), e.getY());

                e.consume();
            }
        });

        // Set selection mode to allow multiple selection
        contextTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        // Install custom TransferHandler for copy operations and file-list drop import from ProjectTree
        final TransferHandler fileListDropHandler = new TransferHandler() {
            @Override
            public int getSourceActions(JComponent c) {
                return COPY;
            }

            @Override
            @Nullable
            protected Transferable createTransferable(JComponent c) {
                String contentToCopy = getSelectedContent(getSelectedFragments());
                if (!contentToCopy.isEmpty()) {
                    return new StringSelection(contentToCopy);
                }
                return null;
            }

            @Override
            public boolean canImport(TransferSupport support) {
                // Accept file list drops only
                boolean isFileList = support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
                return isFileList;
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) {
                    return false;
                }

                if (!workspaceCurrentlyEditable || !isOnLatestContext()) {
                    chrome.systemNotify(READ_ONLY_TIP, "Workspace", JOptionPane.INFORMATION_MESSAGE);
                    return false;
                }

                try {
                    @SuppressWarnings("unchecked")
                    List<File> files =
                            (List<File>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (files == null || files.isEmpty()) {
                        return false;
                    }

                    var projectRoot = contextManager
                            .getProject()
                            .getRoot()
                            .toAbsolutePath()
                            .normalize();
                    // Map to ProjectFile inside this project; ignore anything outside
                    java.util.LinkedHashSet<ProjectFile> projectFiles = files.stream()
                            .map(File::toPath)
                            .map(Path::toAbsolutePath)
                            .map(Path::normalize)
                            .filter(p -> {
                                boolean inside = p.startsWith(projectRoot);
                                if (!inside) {
                                    logger.debug("Ignoring dropped file outside project: {}", p);
                                }
                                return inside;
                            })
                            .map(p -> projectRoot.relativize(p))
                            .map(rel -> new ProjectFile(projectRoot, rel))
                            .collect(Collectors.toCollection(java.util.LinkedHashSet::new));

                    if (projectFiles.isEmpty()) {
                        chrome.systemOutput("No project files found in drop");
                        return false;
                    }

                    // Ask the user what to do
                    var analyzedExts = contextManager.getProject().getAnalyzerLanguages().stream()
                            .flatMap(lang -> lang.getExtensions().stream())
                            .collect(Collectors.toSet());
                    boolean canSummarize = projectFiles.stream().anyMatch(pf -> analyzedExts.contains(pf.extension()));
                    java.awt.Point pointer = null;
                    try {
                        var pi = java.awt.MouseInfo.getPointerInfo();
                        if (pi != null) {
                            pointer = pi.getLocation();
                        }
                    } catch (Exception ignore) {
                        // ignore
                    }
                    var selection = DropActionDialog.show(chrome.getFrame(), canSummarize, pointer);
                    if (selection == null) {
                        chrome.systemOutput("Drop canceled");
                        return false;
                    }
                    switch (selection) {
                        case EDIT -> {
                            // Only allow editing tracked files; others are silently ignored by editFiles
                            contextManager.submitContextTask(() -> {
                                contextManager.addFiles(projectFiles);
                            });
                        }
                        case SUMMARIZE -> {
                            if (!isAnalyzerReady()) {
                                return false;
                            }
                            contextManager.submitContextTask(() -> {
                                contextManager.addSummaries(
                                        new java.util.HashSet<ProjectFile>(projectFiles),
                                        Collections.<CodeUnit>emptySet());
                            });
                        }
                        default -> {
                            logger.warn("Unexpected drop selection: {}", selection);
                            return false;
                        }
                    }

                    return true;
                } catch (Exception ex) {
                    logger.error("Error importing dropped files into workspace", ex);
                    chrome.toolError("Failed to import dropped files: " + ex.getMessage());
                    return false;
                }
            }
        };
        contextTable.setTransferHandler(fileListDropHandler);

        // Add Ctrl+V paste handler for the table
        var pasteKeyStroke = KeyStroke.getKeyStroke(
                java.awt.event.KeyEvent.VK_V,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        var pasteActionKey = "pasteAction";
        contextTable.getInputMap(JComponent.WHEN_FOCUSED).put(pasteKeyStroke, pasteActionKey);
        contextTable.getActionMap().put(pasteActionKey, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                performContextActionAsync(ContextAction.PASTE, List.of());
            }
        });

        // Setup right-click popup menu for when no rows are selected
        tablePopupMenu = new JPopupMenu();

        // Add options submenu
        JMenu addMenu = new JMenu("Add");
        // Removed populateAddMenu, as "Read Files" is being removed, and other "Add" actions are not in this scope.
        // A placeholder or a direct button will be used for "Add" functionality as part of the overall UI redesign.
        // For now, this menu will remain empty or handle only universal actions (Paste, Drop All).
        // If there's no other menu items, we won't add it.
        // This is temporarily removing AddMenuFactory.populateAddMenu to simplify the current refactor.
        // A more robust "Add" mechanism will be introduced later.
        // AddMenuFactory.populateAddMenu(addMenu, this); // Removed

        // If addMenu is empty, don't add it.
        if (addMenu.getMenuComponentCount() > 0) {
            tablePopupMenu.add(addMenu);
            tablePopupMenu.addSeparator();
        }

        JMenuItem dropAllMenuItem = new JMenuItem("Drop All");
        dropAllMenuItem.setActionCommand(DROP_ALL_ACTION_CMD);
        dropAllMenuItem.addActionListener(e -> performContextActionAsync(ContextAction.DROP, List.of()));
        tablePopupMenu.add(dropAllMenuItem);

        JMenuItem copyAllMenuItem = new JMenuItem("Copy All");
        copyAllMenuItem.setActionCommand(COPY_ALL_ACTION_CMD);
        copyAllMenuItem.addActionListener(e -> {
            performContextActionAsync(ContextAction.COPY, List.of());
        });
        tablePopupMenu.add(copyAllMenuItem);

        JMenuItem pasteMenuItem = new JMenuItem("Paste text, images, urls");
        pasteMenuItem.addActionListener(e -> {
            performContextActionAsync(ContextAction.PASTE, List.of());
        });
        tablePopupMenu.add(pasteMenuItem);

        // Register the popup menu with the theme manager
        chrome.themeManager.registerPopupMenu(tablePopupMenu);

        // Build summary panel
        var contextSummaryPanel = new JPanel(new BorderLayout());
        locSummaryPanel = new JPanel();
        locSummaryPanel.setLayout(new BoxLayout(locSummaryPanel, BoxLayout.Y_AXIS));
        JLabel innerLabel = new JLabel(" ");
        innerLabel.setBorder(new EmptyBorder(5, 5, 2, 5));
        JLabel costLabel = new JLabel(" ");
        costLabel.setBorder(new EmptyBorder(0, 5, 5, 5));
        locSummaryPanel.add(innerLabel);
        locSummaryPanel.add(costLabel);
        locSummaryPanel.setBorder(BorderFactory.createEmptyBorder());

        // Container to hold the summary labels and the add button
        summaryWithAdd = new JPanel(new BorderLayout());
        summaryWithAdd.setOpaque(false);
        summaryWithAdd.add(locSummaryPanel, BorderLayout.CENTER);

        if (popupMenuMode == PopupMenuMode.FULL) {
            // Add button to show Add popup (same menu as table's Add)
            var addButton = new MaterialButton();
            addButton.setIcon(Icons.ATTACH_FILE);
            addButton.setToolTipText("Add content to workspace (Ctrl/Cmd+Shift+I)");
            addButton.setFocusable(false);
            addButton.setOpaque(false);
            addButton.addActionListener(e -> {
                attachContextViaDialog();
            });
            // Keyboard shortcut: Cmd/Ctrl+Shift+A opens the Attach Context dialog
            KeyboardShortcutUtil.registerGlobalShortcut(
                    WorkspacePanel.this,
                    KeyboardShortcutUtil.createPlatformShiftShortcut(KeyEvent.VK_I),
                    "attachContext",
                    () -> SwingUtilities.invokeLater(() -> {
                        attachContextViaDialog();
                    }));

            // Wrap the button so it vertically centers nicely with the labels
            var buttonWrapper = new JPanel(new GridBagLayout());
            buttonWrapper.setOpaque(false);
            buttonWrapper.add(addButton);

            summaryWithAdd.add(buttonWrapper, BorderLayout.EAST);
        }

        contextSummaryPanel.add(summaryWithAdd, BorderLayout.NORTH);

        // Warning panel (for red/yellow context size warnings)
        warningPanel = new JPanel(new BorderLayout()); // Changed to BorderLayout
        warningPanel.setBorder(new EmptyBorder(0, 5, 5, 5)); // Top, Left, Bottom, Right padding
        contextSummaryPanel.add(warningPanel, BorderLayout.CENTER);

        // Analyzer rebuild notification panel (below warning panel)
        analyzerRebuildPanel = new JPanel(new BorderLayout());
        analyzerRebuildPanel.setBorder(new EmptyBorder(0, 5, 5, 5)); // Top, Left, Bottom, Right padding
        analyzerRebuildPanel.setVisible(false);
        contextSummaryPanel.add(analyzerRebuildPanel, BorderLayout.SOUTH);

        // Table panel with overlay support
        var tablePanel = new JPanel(new BorderLayout());
        var tableScrollPane = new JScrollPane(
                contextTable, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        tableScrollPane.setPreferredSize(new Dimension(600, 150));

        // Create gray semi-transparent overlay for history viewing that allows mouse clicks through
        workspaceOverlay = OverlayPanel.createNonBlockingGrayOverlay(
                overlay -> {}, // No action on click - this overlay is not meant to be dismissed by user
                READ_ONLY_TIP,
                30 // Very light transparency (30/255 ≈ 12%) - subtle indication
                );
        workspaceOverlay.setVisible(false); // Start hidden

        // Create layered pane to support overlay
        workspaceLayeredPane = workspaceOverlay.createLayeredPane(tableScrollPane);
        // Ensure drops are accepted when over the layered pane, scroll pane, or empty areas
        tableScrollPane.setTransferHandler(fileListDropHandler);
        tableScrollPane.getViewport().setTransferHandler(fileListDropHandler);
        workspaceLayeredPane.setTransferHandler(fileListDropHandler);

        // Add mouse listeners to key components to ensure focus
        var focusMouseListener = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                SwingUtilities.invokeLater(contextTable::requestFocusInWindow);
            }
        };

        // Add to the table (this should definitely work)
        contextTable.addMouseListener(focusMouseListener);

        // Add to the table header
        if (contextTable.getTableHeader() != null) {
            var tableHeader = contextTable.getTableHeader();
            tableHeader.addMouseListener(focusMouseListener);

            // Right-click popup menu on the header (same as title / empty areas)
            MouseAdapter headerPopupListener = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    showPopup(e);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    showPopup(e);
                }

                private void showPopup(MouseEvent e) {
                    if (e.isPopupTrigger()) {
                        tablePopupMenu.show(e.getComponent(), e.getX(), e.getY());
                    }
                }
            };
            tableHeader.addMouseListener(headerPopupListener);
        }

        // Add to the scroll pane viewport for empty areas
        tableScrollPane.getViewport().addMouseListener(focusMouseListener);

        BorderUtils.addFocusBorder(tableScrollPane, contextTable);

        // Add a mouse listener to the scroll pane *and its viewport* for right-clicks on empty areas
        var emptyAreaPopupListener = new MouseAdapter() {
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
                    // Use the scroll pane directly instead of getting it from the layered pane
                    var scrollPane = tableScrollPane;
                    // Get the event point in view coordinates
                    var viewPoint = SwingUtilities.convertPoint(
                            scrollPane, e.getPoint(), scrollPane.getViewport().getView());

                    // If the click is in the table and on a row, let the table's listener handle it
                    if (contextTable.getRowCount() > 0) {
                        int row = contextTable.rowAtPoint(viewPoint);
                        if (row >= 0) {
                            return;
                        }
                    }

                    // Otherwise show the table popup menu
                    tablePopupMenu.show(scrollPane, e.getX(), e.getY());
                }
            }
        };
        tableScrollPane.addMouseListener(emptyAreaPopupListener);
        tableScrollPane.getViewport().addMouseListener(emptyAreaPopupListener);

        tablePanel.add(workspaceLayeredPane, BorderLayout.CENTER);

        setLayout(new BorderLayout());

        add(tablePanel, BorderLayout.CENTER);

        add(contextSummaryPanel, BorderLayout.SOUTH);

        // Shared listener for panel-wide context menu and focus
        MouseAdapter panelPopupAndFocusListener = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!e.isPopupTrigger() && SwingUtilities.isLeftMouseButton(e)) {
                    contextTable.requestFocusInWindow();
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    tablePopupMenu.show(e.getComponent(), e.getX(), e.getY());
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    tablePopupMenu.show(e.getComponent(), e.getX(), e.getY());
                }
            }
        };

        // Add the listener to the panel itself
        WorkspacePanel.this.addMouseListener(panelPopupAndFocusListener);
        // Add the listener to the summary panel and its sub-panels to ensure consistent popup behavior
        contextSummaryPanel.addMouseListener(panelPopupAndFocusListener);
        locSummaryPanel.addMouseListener(panelPopupAndFocusListener);
        warningPanel.addMouseListener(panelPopupAndFocusListener);
    }

    /** Gets the list of selected fragments */
    public List<ContextFragment> getSelectedFragments() {
        List<ContextFragment> v = SwingUtil.runOnEdt(
                () -> {
                    var tableModel = (DefaultTableModel) contextTable.getModel();
                    return Arrays.stream(contextTable.getSelectedRows())
                            .mapToObj(row -> (ContextFragment) tableModel.getValueAt(row, FRAGMENT_COLUMN))
                            .collect(Collectors.toList());
                },
                List.of());
        return castNonNull(v);
    }

    /** Returns the set of selected project files that are part of the current context (editable or read-only). */
    public Set<ProjectFile> getSelectedProjectFiles() {
        return getSelectedFragments().stream()
                .filter(f -> f.getType() == ContextFragment.FragmentType.PROJECT_PATH)
                .flatMap(f -> f.files().stream())
                .collect(Collectors.toSet());
    }

    /** Populates the context table from a Context object. */
    public void populateContextTable(@Nullable Context ctx) {
        assert SwingUtilities.isEventDispatchThread() : "Not on EDT";
        this.currentContext = ctx;
        var tableModel = (DefaultTableModel) contextTable.getModel();
        tableModel.setRowCount(0);

        if (ctx == null || ctx.isEmpty()) {
            // Reset summary label
            safeGetLabel(0).setText(EMPTY_CONTEXT);

            // Clear and hide the cost label
            var cost = safeGetLabel(1);
            cost.setText(" ");
            cost.setVisible(false);

            // Remove any warning messages
            warningPanel.removeAll();

            revalidate();
            repaint();
            return;
        }

        var allFragments = ctx.getAllFragmentsInDisplayOrder();
        int totalLines = 0;
        StringBuilder fullText = new StringBuilder();
        for (var frag : allFragments) {
            String locText;
            if (frag.isText() || frag.getType().isOutput()) {
                var text = frag.text();
                fullText.append(text).append("\n");
                int loc = text.split("\\r?\\n", -1).length;
                totalLines += loc;
                locText = "%,d".formatted(loc);
            } else {
                locText = "Image";
            }
            var desc = frag.description();

            // Mark editable if it's in the editable streams
            boolean isEditable = ctx.fileFragments().anyMatch(e -> e == frag);
            if (isEditable) {
                desc = "✏️ " + desc;
            }

            // Build file references for the record
            List<TableUtils.FileReferenceList.FileReferenceData> fileReferences = new ArrayList<>();
            if (frag.getType() != ContextFragment.FragmentType.PROJECT_PATH) {
                fileReferences = frag.files().stream()
                        .map(file -> new TableUtils.FileReferenceList.FileReferenceData(
                                file.getFileName(), file.toString(), file))
                        .distinct()
                        .sorted(Comparator.comparing(TableUtils.FileReferenceList.FileReferenceData::getFileName))
                        .toList();
            }

            // Create rich description object
            var descriptionWithRefs = new DescriptionWithReferences(desc, fileReferences, frag);
            tableModel.addRow(new Object[] {locText, descriptionWithRefs, frag});
        }

        var innerLabel = safeGetLabel(0);
        var costLabel = safeGetLabel(1);

        // Prepare UI placeholders while computing tokens off the EDT
        innerLabel.setForeground(UIManager.getColor("Label.foreground"));
        innerLabel.setText("Calculating token estimate...");
        innerLabel.setToolTipText("Total: %,d LOC".formatted(totalLines));
        costLabel.setText(" ");
        costLabel.setVisible(false);
        warningPanel.removeAll();
        warningPanel.revalidate();
        warningPanel.repaint();

        revalidate();
        repaint();

        // Notify listeners that bottom controls height may have changed (may shrink when warnings cleared)
        fireBottomControlsHeightChanged();

        // Compute tokens off the EDT and update UI on completion
        computeApproxTokensAsync(fullText, totalLines);
    }

    /** Called by Chrome to refresh the table if context changes */
    public void updateContextTable() {
        SwingUtilities.invokeLater(() -> {
            populateContextTable(requireNonNull(contextManager.selectedContext()));
            refreshMenuState(); // Update menu states when context changes
        });
    }

    private JTextArea createWarningTextArea(String text, Color foregroundColor, String tooltip) {
        JTextArea textArea = new JTextArea(text);
        textArea.setWrapStyleWord(true);
        textArea.setLineWrap(true);
        textArea.setEditable(false);
        textArea.setFocusable(false);
        textArea.setOpaque(false);
        textArea.setFont(UIManager.getFont("Label.font"));
        textArea.setForeground(foregroundColor);
        textArea.setToolTipText(tooltip);
        textArea.setBorder(null);
        return textArea;
    }

    /** Shows a preview of the fragment contents */
    private void showFragmentPreview(ContextFragment fragment) {
        chrome.openFragmentPreview(fragment);
    }

    // ------------------------------------------------------------------
    // Context Action Logic
    // ------------------------------------------------------------------

    // Public getter for ContextManager needed by AddMenuFactory
    public ContextManager getContextManager() {
        return contextManager;
    }

    /** Checks if analyzer is ready for operations, shows error message if not. */
    private boolean isAnalyzerReady() {
        if (!contextManager.getAnalyzerWrapper().isReady()) {
            chrome.systemNotify(
                    AnalyzerWrapper.ANALYZER_BUSY_MESSAGE,
                    AnalyzerWrapper.ANALYZER_BUSY_TITLE,
                    JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        return true;
    }

    /** Returns true if the project has Git support */
    private boolean hasGit() {
        return contextManager.getProject().hasGit();
    }

    /** Returns true if the fragments have associated files */
    private boolean hasFiles(List<ContextFragment> fragments) {
        return fragments.stream()
                .flatMap(frag -> frag.files().stream())
                .findAny()
                .isPresent();
    }

    /** Returns true if all files from the fragments are tracked project files */
    private boolean allTrackedProjectFiles(List<ContextFragment> fragments) {
        var project = contextManager.getProject();
        var allFiles = fragments.stream()
                .flatMap(frag -> frag.files().stream())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        return !allFiles.isEmpty()
                && allFiles.stream()
                        .filter(ProjectFile.class::isInstance)
                        .map(ProjectFile.class::cast)
                        .allMatch(pf -> pf.exists()
                                && project.getRepo().getTrackedFiles().contains(pf));
    }

    /**
     * Returns true if the workspace is currently on the latest (top) context. When false, the workspace is viewing
     * historical context and should be read-only.
     */
    private boolean isOnLatestContext() {
        return Objects.equals(contextManager.selectedContext(), contextManager.topContext());
    }

    /** Classifies the popup scenario based on the mouse event and table state */
    private PopupScenario classifyScenario(MouseEvent e, List<ContextFragment> selectedFragments) {
        int row = contextTable.rowAtPoint(e.getPoint());
        int col = contextTable.columnAtPoint(e.getPoint());

        // Handle file badge clicks in new description column layout
        if (col == DESCRIPTION_COLUMN && row >= 0) { // Description column
            ContextFragment fragment = (ContextFragment) contextTable.getModel().getValueAt(row, FRAGMENT_COLUMN);
            if (fragment != null && fragment.getType() != ContextFragment.FragmentType.PROJECT_PATH) {
                var fileReferences = fragment.files().stream()
                        .map(file -> new TableUtils.FileReferenceList.FileReferenceData(
                                file.getFileName(), file.toString(), file))
                        .distinct()
                        .sorted(Comparator.comparing(TableUtils.FileReferenceList.FileReferenceData::getFileName))
                        .collect(Collectors.toList());

                if (!fileReferences.isEmpty()) {
                    // We need to determine if the click was specifically on a badge
                    Rectangle cellRect = contextTable.getCellRect(row, col, false);
                    int yInCell = e.getPoint().y - cellRect.y;

                    // Estimate if click is in lower half of cell (where badges are)
                    if (yInCell > cellRect.height / 2) {
                        // Try to find which specific badge was clicked
                        TableUtils.FileReferenceList.FileReferenceData clickedFileRef =
                                TableUtils.findClickedReference(e.getPoint(), row, col, contextTable);

                        if (clickedFileRef != null && clickedFileRef.getRepoFile() != null) {
                            return new FileBadge(clickedFileRef);
                        }
                    }
                }
            }
        }

        // Handle selection-based scenarios
        if (selectedFragments.isEmpty()) {
            return new NoSelection();
        } else if (selectedFragments.size() == 1) {
            return new SingleFragment(selectedFragments.getFirst());
        } else {
            return new MultiFragment(selectedFragments);
        }
    }

    /** Shows the symbol selection dialog and adds usage information for the selected symbol. */
    public void findSymbolUsageAsync() {
        if (!isAnalyzerReady()) {
            return;
        }

        contextManager.submitContextTask(() -> {
            try {
                var analyzer = contextManager.getAnalyzerUninterrupted();
                if (analyzer.isEmpty()) {
                    chrome.toolError("Code Intelligence is empty; nothing to add");
                    return;
                }

                var selection = showSymbolSelectionDialog("Select Symbol", CodeUnitType.ALL);
                if (selection != null
                        && selection.symbol() != null
                        && !selection.symbol().isBlank()) {
                    contextManager.usageForIdentifier(selection.symbol(), selection.includeTestFiles());
                } else {
                    chrome.systemOutput("No symbol selected.");
                }
            } catch (CancellationException cex) {
                chrome.systemOutput("Symbol selection canceled.");
            }
            // No finally needed, submitContextTask handles enabling buttons
        });
    }

    /** Shows the method selection dialog and adds callers information for the selected method. */
    public void findMethodCallersAsync() {
        if (!isAnalyzerReady()) {
            return;
        }

        contextManager.submitContextTask(() -> {
            try {
                var analyzer = contextManager.getAnalyzerUninterrupted();
                if (analyzer.isEmpty()) {
                    chrome.toolError("Code Intelligence is empty; nothing to add");
                    return;
                }

                var dialog = showCallGraphDialog("Select Method", true);
                if (dialog == null || !dialog.isConfirmed()) { // Check confirmed state
                    chrome.systemOutput("No method selected.");
                } else {
                    var selectedMethod = dialog.getSelectedMethod();
                    var callGraph = dialog.getCallGraph();
                    if (selectedMethod != null && callGraph != null) {
                        contextManager.addCallersForMethod(selectedMethod, dialog.getDepth(), callGraph);
                    } else {
                        chrome.systemOutput("Method selection incomplete or cancelled.");
                    }
                }
            } catch (CancellationException cex) {
                chrome.systemOutput("Method selection canceled.");
            }
            // No finally needed, submitContextTask handles enabling buttons
        });
    }

    /** Shows the call graph dialog and adds callees information for the selected method. */
    public void findMethodCalleesAsync() {
        if (!isAnalyzerReady()) {
            return;
        }

        contextManager.submitContextTask(() -> {
            try {
                var analyzer = contextManager.getAnalyzerUninterrupted();
                if (analyzer.isEmpty()) {
                    chrome.toolError("Code Intelligence is empty; nothing to add");
                    return;
                }

                var dialog = showCallGraphDialog("Select Method for Callees", false);
                if (dialog == null || !dialog.isConfirmed()) {
                    chrome.systemOutput("No method selected.");
                } else {
                    var selectedMethod = dialog.getSelectedMethod();
                    var callGraph = dialog.getCallGraph();
                    if (selectedMethod != null && callGraph != null) {
                        contextManager.calleesForMethod(selectedMethod, dialog.getDepth(), callGraph);
                    } else {
                        chrome.systemOutput("Method selection incomplete or cancelled.");
                    }
                }
            } catch (CancellationException cex) {
                chrome.systemOutput("Method selection canceled.");
            }
            // No finally needed, submitContextTask handles enabling buttons
        });
    }

    /** Show the symbol selection dialog with a type filter */
    private @Nullable SymbolSelectionDialog.SymbolSelection showSymbolSelectionDialog(
            String title, Set<CodeUnitType> typeFilter) {
        var analyzer = contextManager.getAnalyzerUninterrupted();
        var dialogRef = new AtomicReference<SymbolSelectionDialog>();
        SwingUtil.runOnEdt(() -> {
            var dialog = new SymbolSelectionDialog(chrome.getFrame(), analyzer, title, typeFilter);
            dialog.setSize((int) (chrome.getFrame().getWidth() * 0.9), dialog.getHeight());
            dialog.setLocationRelativeTo(chrome.getFrame());
            dialog.setVisible(true);
            dialogRef.set(dialog);
        });
        var dialog = castNonNull(dialogRef.get());
        return dialog.isConfirmed() ? dialog.getSelection() : null;
    }

    /** Show the call graph dialog for configuring method and depth */
    private @Nullable CallGraphDialog showCallGraphDialog(String title, boolean isCallerGraph) {
        var analyzer = contextManager.getAnalyzerUninterrupted();
        var dialogRef = new AtomicReference<CallGraphDialog>();
        SwingUtil.runOnEdt(() -> {
            var dialog = new CallGraphDialog(chrome.getFrame(), analyzer, title, isCallerGraph);
            dialog.setSize((int) (chrome.getFrame().getWidth() * 0.9), dialog.getHeight());
            dialog.setLocationRelativeTo(chrome.getFrame());
            dialog.setVisible(true);
            dialogRef.set(dialog);
        });

        var dialog = castNonNull(dialogRef.get());
        return dialog.isConfirmed() ? dialog : null;
    }

    /**
     * Performed by the action buttons/menus in the context panel: "edit / read / copy / drop / summarize / paste" If
     * selectedFragments is empty, it means "All". We handle logic accordingly.
     */
    public Future<?> performContextActionAsync(
            ContextAction action, List<? extends ContextFragment> selectedFragments) // Use wildcard
            {
        // Use submitContextTask from ContextManager to run the action on the appropriate executor
        return contextManager.submitContextTask(() -> {
            try {
                switch (action) {
                    case EDIT -> doEditAction(selectedFragments);
                    case COPY -> doCopyAction(selectedFragments);
                    case DROP -> doDropAction(selectedFragments);
                    case SUMMARIZE -> doSummarizeAction(selectedFragments);
                    case PASTE -> doPasteAction();
                    case RUN_TESTS -> doRunTestsAction(selectedFragments);
                }
            } catch (CancellationException cex) {
                chrome.systemOutput(action + " canceled.");
            } finally {
                SwingUtilities.invokeLater(chrome::focusInput);
            }
        });
    }

    /** Edit Action: Only allows selecting Project Files */
    private void doEditAction(List<? extends ContextFragment> selectedFragments) { // Use wildcard
        assert !selectedFragments.isEmpty();
        var files = selectedFragments.stream()
                .flatMap(fragment -> fragment.files().stream())
                .collect(Collectors.toSet());
        contextManager.addFiles(files);
    }

    private void doCopyAction(List<? extends ContextFragment> selectedFragments) {
        var content = getSelectedContent(selectedFragments);
        var sel = new java.awt.datatransfer.StringSelection(content);
        var cb = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
        cb.setContents(sel, sel);
        chrome.systemOutput("Content copied to clipboard");
    }

    private String getSelectedContent(List<? extends ContextFragment> selectedFragments) {
        String content;
        if (selectedFragments.isEmpty()) {
            // gather entire context
            List<ChatMessage> msgs;
            try {
                msgs = CopyExternalPrompts.instance.collectMessages(contextManager);
            } catch (InterruptedException e) {
                // the signature is misleading, IE will never be thrown on this path
                throw new AssertionError(e);
            }
            var combined = new StringBuilder();
            for (var m : msgs) {
                if (!(m instanceof AiMessage)) {
                    combined.append(Messages.getText(m)).append("\n\n");
                }
            }

            // Get instructions from context
            combined.append("\n<goal>\n").append(chrome.getInputText()).append("\n</goal>");
            content = combined.toString();
        } else {
            // copy only selected fragments
            var sb = new StringBuilder();
            for (var frag : selectedFragments) {
                sb.append(frag.text()).append("\n\n"); // No analyzer
            }
            content = sb.toString();
        }
        return content;
    }

    private void doPasteAction() {
        assert !SwingUtilities.isEventDispatchThread();

        var clipboard = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
        var contents = clipboard.getContents(null);
        if (contents == null) {
            chrome.toolError("Clipboard is empty or unavailable");
            return;
        }

        // Log all available flavors for debugging
        var flavors = contents.getTransferDataFlavors();
        logger.debug(
                "Clipboard flavors available: {}",
                java.util.Arrays.stream(flavors).map(DataFlavor::getMimeType).collect(Collectors.joining(", ")));

        // Prioritize Image Flavors - check all available flavors for image compatibility
        for (var flavor : flavors) {
            try {
                // Check if it's the standard image flavor or has a MIME type indicating an image
                if (flavor.isFlavorJavaFileListType() || flavor.getMimeType().startsWith("image/")) {
                    logger.debug("Attempting to process flavor: {}", flavor.getMimeType());
                    Object data = contents.getTransferData(flavor);
                    java.awt.Image image = null;

                    switch (data) {
                        case Image image1 -> image = image1;
                        case java.io.InputStream inputStream ->
                            // Try to read the stream as an image using ImageIO
                            image = javax.imageio.ImageIO.read(inputStream);
                        case List<?> fileList
                        when !fileList.isEmpty() -> {
                            // Handle file list (e.g., dragged image file from file manager)
                            var file = fileList.getFirst();
                            if (file instanceof java.io.File f
                                    && f.getName().matches("(?i).*(png|jpg|jpeg|gif|bmp)$")) {
                                image = javax.imageio.ImageIO.read(f);
                            }
                        }
                        default -> {}
                    }

                    if (image != null) {
                        contextManager.addPastedImageFragment(image);
                        chrome.systemOutput("Pasted image added to context");
                        return;
                    }
                }
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("INCR")) {
                    chrome.toolError(
                            "Unable to paste image data from Windows to Brokk running under WSL. This is a limitation of WSL. You can write the image to a file and read it that way instead.");
                    return;
                }
                logger.error("Failed to process image flavor: {}", flavor.getMimeType(), e);
            }
        }

        // Text Flavor
        if (contents.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor)) {
            String clipboardText;
            try {
                clipboardText = (String) contents.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor);
                if (clipboardText.isBlank()) {
                    chrome.toolError("Clipboard text is empty");
                    return;
                }
            } catch (Exception e) {
                chrome.toolError("Failed to read clipboard text: " + e.getMessage());
                return;
            }

            // Process the clipboard text
            clipboardText = clipboardText.trim();
            String content = clipboardText;
            boolean wasUrl = false;

            if (isUrl(clipboardText)) {
                URI uri;
                try {
                    uri = new URI(clipboardText);
                } catch (URISyntaxException e) {
                    logger.warn("Thought we had a url but we did not: " + clipboardText);
                    // Not a valid URI, proceed to treat as plain text
                    uri = null;
                }

                if (uri != null) { // Only proceed if URI parsing was successful
                    // Try to handle as image URL first
                    if (ImageUtil.isImageUri(uri, httpClient)) {
                        try {
                            chrome.systemOutput("Fetching image from " + clipboardText);
                            java.awt.Image image = ImageUtil.downloadImage(uri, httpClient);
                            if (image != null) {
                                contextManager.addPastedImageFragment(image);
                                chrome.systemOutput("Pasted image from URL added to context");
                                chrome.actionComplete();
                                return; // Image handled, done with paste action
                            } else {
                                logger.warn(
                                        "URL {} identified as image by ImageUtil, but downloadImage returned null. Falling back to text.",
                                        clipboardText);
                                chrome.systemOutput("Could not load image from URL. Trying to fetch as text.");
                            }
                        } catch (
                                Exception e) { // Catching general exception as downloadImage might throw various things
                            // indirectly
                            logger.warn(
                                    "Failed to fetch or decode image from URL {}: {}. Falling back to text.",
                                    clipboardText,
                                    e.getMessage());
                            chrome.systemOutput(
                                    "Failed to load image from URL: " + e.getMessage() + ". Trying to fetch as text.");
                            // Fall through to fetching as text
                        }
                    }

                    // Fallback: If not an image URL or image fetching failed, try to fetch as text
                    try {
                        chrome.systemOutput("Fetching content from " + clipboardText);
                        content = WorkspaceTools.fetchUrlContent(uri);
                        content = HtmlToMarkdown.maybeConvertToMarkdown(content);
                        wasUrl = true;
                        chrome.actionComplete();
                    } catch (IOException e) {
                        chrome.toolError("Failed to fetch or process URL content as text: " + e.getMessage());
                        content = clipboardText; // Revert to original clipboard text if fetch fails
                    }
                }
            }

            // Try to parse as stacktrace
            var stacktrace = StackTrace.parse(content);
            if (stacktrace != null && contextManager.addStacktraceFragment(stacktrace)) {
                return;
            }

            // Add as string fragment (possibly converted from HTML)
            contextManager.addPastedTextFragment(content);

            // Inform the user about what happened
            if (stacktrace == null) {
                // addStackTraceFragment sends its own messages
                String message = wasUrl ? "URL content fetched and added" : "Clipboard content added as text";
                chrome.systemOutput(message);
            }
        } else {
            chrome.toolError("Unsupported clipboard content type");
        }
    }

    private void doDropAction(List<? extends ContextFragment> selectedFragments) {
        if (selectedFragments.isEmpty()) {
            if (contextManager.topContext().isEmpty()) {
                return;
            }
            contextManager.dropAll();
            contextManager.setSelectedContext(contextManager.topContext());
        } else {
            for (var frag : selectedFragments) {
                if (frag.getType() == ContextFragment.FragmentType.HISTORY) {
                    contextManager.clearHistory();
                    break;
                }
            }

            contextManager.drop(selectedFragments); // Use the new ID-based method
        }
    }

    private void doRunTestsAction(List<? extends ContextFragment> selectedFragments) {
        var testFiles = selectedFragments.stream()
                .flatMap(frag -> frag.files().stream())
                .filter(ContextManager::isTestFile)
                .collect(Collectors.toSet());

        if (testFiles.isEmpty() && !selectedFragments.isEmpty()) {
            chrome.toolError("No test files found in the selection to run.");
            return;
        }

        if (testFiles.isEmpty()) {
            chrome.toolError("No test files specified to run.");
            return;
        }

        chrome.getContextManager().runTests(testFiles);
    }

    public void attachContextViaDialog() {
        attachContextViaDialog(false);
    }

    public void attachContextViaDialog(boolean defaultSummarizeChecked) {
        assert SwingUtilities.isEventDispatchThread();
        var dlg = new AttachContextDialog(chrome.getFrame(), contextManager, defaultSummarizeChecked);
        dlg.setLocationRelativeTo(chrome.getFrame());
        dlg.setVisible(true); // modal; blocks until closed and selection is set
        var result = dlg.getSelection();

        if (result == null) return;

        var fragment = result.fragment();
        boolean summarize = result.summarize();

        contextManager.submitContextTask(() -> {
            if (summarize) {
                switch (fragment.getType()) {
                    case PROJECT_PATH -> {
                        var files = fragment.files();
                        contextManager.addSummaries(files, Collections.emptySet());
                    }
                    case CODE -> {
                        var cf = (ContextFragment.CodeFragment) fragment;
                        var cu = cf.getCodeUnit();
                        if (cu.isClass()) {
                            contextManager.addSummaries(Collections.emptySet(), java.util.Set.of(cu));
                        } else {
                            var analyzer = contextManager
                                    .getAnalyzerUninterrupted()
                                    .as(SkeletonProvider.class)
                                    .orElseThrow();
                            analyzer.getSkeleton(cu.fqName()).ifPresent(st -> {
                                var summary = new ContextFragment.StringFragment(
                                        contextManager,
                                        st,
                                        "Summary of " + cu.fqName(),
                                        cu.source().getSyntaxStyle());
                                contextManager.addVirtualFragment(summary);
                            });
                        }
                    }
                    case CALL_GRAPH, USAGE -> {
                        // For Usages+Summarize we already returned a CallGraphFragment. Any CALL_GRAPH/USAGE just add.
                        if (fragment instanceof ContextFragment.VirtualFragment vf) {
                            contextManager.addVirtualFragment(vf);
                        }
                    }
                    default -> {
                        throw new AssertionError();
                    }
                }
                return;
            }

            // Non-summarize path: attach fragments directly
            if (fragment instanceof ContextFragment.ProjectPathFragment ppf) {
                contextManager.addPathFragments(java.util.List.of(ppf));
            } else if (fragment instanceof ContextFragment.VirtualFragment vf) {
                contextManager.addVirtualFragment(vf);
            } else {
                throw new AssertionError(fragment);
            }
        });
    }

    private void doSummarizeAction(List<? extends ContextFragment> selectedFragments) {
        if (!isAnalyzerReady()) {
            return;
        }

        HashSet<ProjectFile> selectedFiles = new HashSet<>();
        HashSet<CodeUnit> selectedClasses = new HashSet<>();

        // Fragment case: Extract files and classes from selected fragments
        // FIXME: prefer classes where available (more selective)
        selectedFragments.stream().flatMap(frag -> frag.files().stream()).forEach(selectedFiles::add);

        if (selectedFiles.isEmpty() && selectedClasses.isEmpty()) {
            chrome.toolError("No files or classes identified for summarization in the selection.");
            return;
        }

        // Call the updated addSummaries method, which outputs a message on success
        boolean success = contextManager.addSummaries(selectedFiles, selectedClasses);
        if (!success) {
            chrome.toolError("No summarizable content found in the selected files or symbols.");
        }
    }

    private boolean isUrl(String text) {
        return text.matches("^https?://\\S+$");
    }

    /** Return the JLabel stored in {@code locSummaryPanel} at the given index or throw */
    private JLabel safeGetLabel(int index) {
        return switch (locSummaryPanel.getComponent(index)) {
            case JLabel lbl -> lbl;
            default -> throw new IllegalStateException("Expected JLabel at locSummaryPanel index " + index);
        };
    }

    /**
     * Return the combined preferred height of the bottom controls (summary, warnings, analyzer rebuild panel) so other
     * panels can align to it.
     */
    public int getBottomControlsPreferredHeight() {
        int h = 0;
        // Use the overall summary container (includes the add-button wrapper).
        h += summaryWithAdd.getPreferredSize().height;
        // Only include warning and analyzer panels when they are visible.
        if (warningPanel.isVisible()) {
            h += warningPanel.getPreferredSize().height;
        }
        if (analyzerRebuildPanel.isVisible()) {
            h += analyzerRebuildPanel.getPreferredSize().height;
        }
        return h;
    }

    // --- Bottom controls height observer API ---

    public interface BottomControlsListener {
        void bottomControlsHeightChanged(int newHeight);
    }

    public void addBottomControlsListener(BottomControlsListener l) {
        bottomControlsListeners.add(l);
    }

    public void removeBottomControlsListener(BottomControlsListener l) {
        bottomControlsListeners.remove(l);
    }

    private void fireBottomControlsHeightChanged() {
        int h = getBottomControlsPreferredHeight();
        for (var l : bottomControlsListeners) {
            try {
                l.bottomControlsHeightChanged(h);
            } catch (Exception ignore) {
                // Listener exceptions should not affect UI flow
            }
        }
    }

    /** Calculate cost estimate for only the model currently selected in InstructionsPanel. */
    private String calculateCostEstimate(int inputTokens, Service service) {
        var instructionsPanel = chrome.getInstructionsPanel();
        Service.ModelConfig config = instructionsPanel.getSelectedModel();

        if (config.name().isBlank()) {
            return "";
        }

        var model = service.getModel(config);
        if (model instanceof Service.UnavailableStreamingModel) {
            return "";
        }

        var pricing = service.getModelPricing(config.name());
        if (pricing.bands().isEmpty()) {
            return "";
        }

        // Calculate estimated cost: input tokens * input price + min(4k, input/2) * output price
        long estimatedOutputTokens = Math.min(4000, inputTokens / 2);
        if (service.isReasoning(config)) {
            estimatedOutputTokens += 1000;
        }
        double estimatedCost = pricing.estimateCost(inputTokens, 0, estimatedOutputTokens);

        if (service.isFreeTier(config.name())) {
            return "$0.00 (Free Tier)";
        } else {
            return String.format("$%.2f", estimatedCost);
        }
    }

    private void computeApproxTokensAsync(StringBuilder fullText, int totalLines) {
        contextManager
                .submitBackgroundTask(
                        "Compute token estimate", () -> Messages.getApproximateTokens(fullText.toString()))
                .thenAccept(approxTokens -> SwingUtilities.invokeLater(() -> {
                    var innerLabel = safeGetLabel(0);
                    var costLabel = safeGetLabel(1);

                    // Check for context size warning against the selected model only
                    var service = contextManager.getService();
                    var instructionsPanel = chrome.getInstructionsPanel();
                    Service.ModelConfig selectedConfig = instructionsPanel.getSelectedModel();

                    boolean showRedWarning = false;
                    boolean showYellowWarning = false;
                    int selectedModelMaxInputTokens = -1;
                    String selectedModelName = selectedConfig.name();

                    if (!selectedModelName.isBlank()) {
                        try {
                            var modelInstance = service.getModel(selectedConfig);
                            if (modelInstance == null) {
                                logger.debug("Selected model unavailable for context warning: {}", selectedModelName);
                            } else if (modelInstance instanceof Service.UnavailableStreamingModel) {
                                logger.debug("Selected model unavailable for context warning: {}", selectedModelName);
                            } else {
                                selectedModelMaxInputTokens = service.getMaxInputTokens(modelInstance);
                                if (selectedModelMaxInputTokens <= 0) {
                                    logger.warn(
                                            "Selected model {} has invalid maxInputTokens: {}",
                                            selectedModelName,
                                            selectedModelMaxInputTokens);
                                } else {
                                    // Red warning: context > 90.9% of max (approxTokens > maxInputTokens / 1.1)
                                    if (approxTokens > selectedModelMaxInputTokens / 1.1) {
                                        showRedWarning = true;
                                    }
                                    // Yellow warning: context > 50% of max (approxTokens > maxInputTokens / 2.0)
                                    else if (approxTokens > selectedModelMaxInputTokens / 2.0) {
                                        showYellowWarning = true;
                                    }
                                }
                            }
                        } catch (Exception e) {
                            logger.warn(
                                    "Error processing selected model {} for context warning: {}",
                                    selectedModelName,
                                    e.getMessage(),
                                    e);
                        }
                    }

                    String warningTooltip =
                            """
        Consider replacing full files with summaries or tackling a smaller piece of your problem to start with.
        Deep Scan can help surface the parts of your codebase that are necessary to solving the problem.
        """;

                    // Always set the standard summary text on innerLabel (single line to avoid layout jumps)
                    innerLabel.setForeground(UIManager.getColor("Label.foreground")); // Reset to default color
                    String costEstimate = calculateCostEstimate(approxTokens, service);
                    String costText = costEstimate.isBlank() ? "n/a" : costEstimate;

                    // Single-line summary to keep the paperclip aligned
                    innerLabel.setText("%,dK tokens ≈ %s/req".formatted(approxTokens / 1000, costText));

                    // Preserve details in tooltip
                    innerLabel.setToolTipText("Total: %,d LOC is ~%,d tokens with an estimated cost of %s per request"
                            .formatted(totalLines, approxTokens, costText));

                    // Keep the secondary label hidden to avoid changing the row height
                    costLabel.setText(" ");
                    costLabel.setVisible(false);

                    // Remove any existing warning labels from the warningPanel
                    warningPanel.removeAll();

                    if (showRedWarning) {
                        String warningText = String.format(
                                "Warning! Your Workspace (~%,d tokens) fills more than 90%% of the context window for the selected model: %s (%,d). Performance will be degraded.",
                                approxTokens, selectedModelName, selectedModelMaxInputTokens);

                        JTextArea warningArea = createWarningTextArea(warningText, Color.RED, warningTooltip);
                        warningPanel.add(warningArea, BorderLayout.CENTER);

                    } else if (showYellowWarning) {
                        String warningText = String.format(
                                "Warning! Your Workspace (~%,d tokens) fills more than half of the context window for the selected model: %s (%,d). Performance may be degraded.",
                                approxTokens, selectedModelName, selectedModelMaxInputTokens);

                        JTextArea warningArea = createWarningTextArea(
                                warningText,
                                Color.YELLOW,
                                warningTooltip); // Standard yellow might be hard to see on some themes
                        warningPanel.add(warningArea, BorderLayout.CENTER);
                    }

                    warningPanel.revalidate();
                    warningPanel.repaint();

                    revalidate();
                    repaint();

                    // Notify listeners that bottom controls height may have changed
                    fireBottomControlsHeightChanged();
                }));
    }

    /**
     * Sets the editable state of the workspace panel.
     *
     * @param editable true to make the workspace editable, false otherwise.
     */
    public void setWorkspaceEditable(boolean editable) {
        SwingUtilities.invokeLater(() -> {
            this.workspaceCurrentlyEditable = editable;

            // Show/hide overlay based on editable state and update title
            if (editable) {
                workspaceOverlay.hideOverlay();
            } else {
                workspaceOverlay.showOverlay();
            }

            // Repaint to show title change
            repaint();

            refreshMenuState();
        });
    }

    /** Shows the analyzer rebuild notification with spinner. */
    public void showAnalyzerRebuildSpinner() {
        SwingUtilities.invokeLater(() -> {
            if (analyzerRebuildSpinner == null) {
                analyzerRebuildSpinner = new JLabel();
                var spinnerIcon = SpinnerIconUtil.getSpinner(chrome, /*small=*/ true);
                if (spinnerIcon != null) {
                    analyzerRebuildSpinner.setIcon(spinnerIcon);
                }

                // Create notification text that supports wrapping
                JTextArea notificationText =
                        new JTextArea("Rebuilding Code Intelligence. Workspace will automatically update");
                notificationText.setWrapStyleWord(true);
                notificationText.setLineWrap(true);
                notificationText.setEditable(false);
                notificationText.setFocusable(false);
                notificationText.setOpaque(false);
                notificationText.setFont(UIManager.getFont("Label.font"));
                notificationText.setForeground(UIManager.getColor("Label.foreground"));
                notificationText.setBorder(null);

                // Layout: spinner on left (aligned to top), text on right
                JPanel spinnerWrapper = new JPanel(new BorderLayout());
                spinnerWrapper.setOpaque(false);
                spinnerWrapper.add(analyzerRebuildSpinner, BorderLayout.NORTH);

                JPanel contentPanel = new JPanel(new BorderLayout(5, 0));
                contentPanel.setOpaque(false);
                contentPanel.add(spinnerWrapper, BorderLayout.WEST);
                contentPanel.add(notificationText, BorderLayout.CENTER);

                analyzerRebuildPanel.removeAll();
                analyzerRebuildPanel.add(contentPanel, BorderLayout.CENTER);
            }

            analyzerRebuildPanel.setVisible(true);
            analyzerRebuildPanel.revalidate();
            analyzerRebuildPanel.repaint();

            // Notify listeners about layout change
            fireBottomControlsHeightChanged();
        });
    }

    /** Hides the analyzer rebuild notification. */
    public void hideAnalyzerRebuildSpinner() {
        SwingUtilities.invokeLater(() -> {
            analyzerRebuildPanel.setVisible(false);
            analyzerRebuildPanel.revalidate();
            analyzerRebuildPanel.repaint();

            // Notify listeners about layout change
            fireBottomControlsHeightChanged();
        });
    }

    /** Sets the drop all menu item reference for dynamic state updates. */
    public void setDropAllMenuItem(JMenuItem dropAllMenuItem) {
        this.dropAllMenuItem = dropAllMenuItem;
    }

    private void refreshMenuState() {
        var editable = workspaceCurrentlyEditable;

        for (var component : tablePopupMenu.getComponents()) {
            if (component instanceof JMenuItem mi) {
                boolean copyAll = COPY_ALL_ACTION_CMD.equals(mi.getActionCommand());
                boolean dropAll = DROP_ALL_ACTION_CMD.equals(mi.getActionCommand());

                if (dropAll) {
                    // "Drop All" is always available
                    mi.setVisible(true);
                    mi.setEnabled(true);
                    mi.setToolTipText(null);
                } else if (copyAll) {
                    // "Copy All" is always enabled and visible
                    mi.setEnabled(true);
                    mi.setVisible(true);
                    mi.setToolTipText(null);
                } else {
                    // Other menu items (including JMenu "Add") are enabled based on workspace editability
                    mi.setEnabled(editable);
                    mi.setVisible(true);
                    mi.setToolTipText(editable ? null : READ_ONLY_TIP);
                }
            }
        }

        // Also update the global drop all menu item
        if (dropAllMenuItem != null) {
            dropAllMenuItem.setEnabled(true);
        }
    }

    @Override
    public void addNotify() {
        super.addNotify();
        registerGlobalAttachDispatcher();
    }

    @Override
    public void removeNotify() {
        unregisterGlobalAttachDispatcher();
        super.removeNotify();
    }

    private void registerGlobalAttachDispatcher() {
        if (globalAttachDispatcher != null) return;

        globalAttachDispatcher = new KeyEventDispatcher() {
            @Override
            public boolean dispatchKeyEvent(KeyEvent e) {
                if (e.getID() != KeyEvent.KEY_PRESSED) return false;

                int mods = e.getModifiersEx();
                int shortcutMask =
                        Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx(); // Cmd on macOS, Ctrl elsewhere
                boolean hasShortcut = (mods & shortcutMask) != 0;
                boolean hasShift = (mods & InputEvent.SHIFT_DOWN_MASK) != 0;

                if (hasShortcut && hasShift && e.getKeyCode() == KeyEvent.VK_I) {
                    SwingUtilities.invokeLater(() -> attachContextViaDialog());
                    // Consume the event so focused components (e.g., terminal) don't handle it
                    return true;
                }
                return false;
            }
        };

        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(globalAttachDispatcher);
    }

    private void unregisterGlobalAttachDispatcher() {
        if (globalAttachDispatcher != null) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(globalAttachDispatcher);
            globalAttachDispatcher = null;
        }
    }
}
