package io.github.jbellis.brokk.gui.terminal;

import static java.util.Objects.requireNonNull;

import com.google.common.base.Splitter;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.Service;
import io.github.jbellis.brokk.TaskResult;
import io.github.jbellis.brokk.agents.ArchitectAgent;
import io.github.jbellis.brokk.agents.SearchAgent;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.git.GitWorkflow;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.gui.ThemeAware;
import io.github.jbellis.brokk.gui.components.MaterialButton;
import io.github.jbellis.brokk.gui.util.Icons;
import io.github.jbellis.brokk.util.Json;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.DropMode;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.TransferHandler;
import javax.swing.UIManager;
import javax.swing.border.TitledBorder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** A simple, theme-aware task list panel supporting add, remove and complete toggle. */
public class TaskListPanel extends JPanel implements ThemeAware, IContextManager.ContextListener {

    private static final Logger logger = LogManager.getLogger(TaskListPanel.class);
    private boolean isLoadingTasks = false;
    private @Nullable UUID sessionIdAtLoad = null;
    private @Nullable IContextManager registeredContextManager = null;

    private final DefaultListModel<@Nullable TaskItem> model = new DefaultListModel<>();
    private final JList<TaskItem> list = new JList<>(model);
    private final JTextField input = new JTextField();
    private final MaterialButton removeBtn = new MaterialButton();
    private final MaterialButton toggleDoneBtn = new MaterialButton();
    private final MaterialButton playBtn = new MaterialButton();
    private final MaterialButton playAllBtn = new MaterialButton();
    private final MaterialButton combineBtn = new MaterialButton();
    private final MaterialButton splitBtn = new MaterialButton();
    private final MaterialButton clearCompletedBtn = new MaterialButton();
    private final Chrome chrome;
    private final Timer llmStateTimer;
    private final Timer runningFadeTimer;
    private long runningAnimStartMs = 0L;

    private @Nullable JTextArea inlineEditor = null;
    private int editingIndex = -1;
    private @Nullable Integer runningIndex = null;
    private final LinkedHashSet<Integer> pendingQueue = new LinkedHashSet<>();
    private boolean queueActive = false;
    private @Nullable List<Integer> currentRunOrder = null;

    public TaskListPanel(Chrome chrome) {
        super(new BorderLayout(4, 4));
        setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Task List",
                TitledBorder.DEFAULT_JUSTIFICATION,
                TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)));

        this.chrome = chrome;

        // Center: list with custom renderer
        list.setCellRenderer(new TaskRenderer());
        list.setVisibleRowCount(12);
        list.setFixedCellHeight(-1);
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        // Update button states based on selection
        list.addListSelectionListener(e -> updateButtonStates());

        // Enable drag-and-drop reordering
        list.setDragEnabled(true);
        list.setDropMode(DropMode.INSERT);
        list.setTransferHandler(new TaskReorderTransferHandler());

        // List keyboard shortcuts
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "toggleDone");
        list.getActionMap().put("toggleDone", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                toggleSelectedDone();
            }
        });
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "deleteTasks");
        list.getActionMap().put("deleteTasks", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                removeSelected();
            }
        });
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "editTask");
        list.getActionMap().put("editTask", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                editSelected();
            }
        });
        list.getInputMap()
                .put(
                        KeyStroke.getKeyStroke(
                                KeyEvent.VK_A, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
                        "selectAll");
        list.getActionMap().put("selectAll", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                list.setSelectionInterval(0, Math.max(0, model.size() - 1));
            }
        });

        list.getInputMap()
                .put(
                        KeyStroke.getKeyStroke(
                                KeyEvent.VK_C, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
                        "copyTasks");
        list.getActionMap().put("copyTasks", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                copySelectedTasks();
            }
        });

        // Run Architect with Ctrl/Cmd+Enter
        list.getInputMap()
                .put(
                        KeyStroke.getKeyStroke(
                                KeyEvent.VK_ENTER, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
                        "runArchitect");
        list.getActionMap().put("runArchitect", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                runArchitectOnSelected();
            }
        });

        // Context menu (right-click)
        var popup = new JPopupMenu();
        var toggleItem = new JMenuItem("Toggle Done");
        toggleItem.addActionListener(e -> toggleSelectedDone());
        popup.add(toggleItem);
        var editItem = new JMenuItem("Edit");
        editItem.addActionListener(e -> editSelected());
        popup.add(editItem);
        var splitItem = new JMenuItem("Split...");
        splitItem.addActionListener(e -> splitSelectedTask());
        popup.add(splitItem);
        var copyItem = new JMenuItem("Copy");
        copyItem.addActionListener(e -> copySelectedTasks());
        popup.add(copyItem);
        var deleteItem = new JMenuItem("Delete");
        deleteItem.addActionListener(e -> removeSelected());
        popup.add(deleteItem);

        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                if (e.isPopupTrigger()) {
                    boolean includesRunning = false;
                    boolean includesPending = false;
                    int[] sel = list.getSelectedIndices();
                    if (runningIndex != null) {
                        for (int si : sel) {
                            if (si == runningIndex.intValue()) {
                                includesRunning = true;
                                break;
                            }
                        }
                    }
                    for (int si : sel) {
                        if (pendingQueue.contains(si)) {
                            includesPending = true;
                            break;
                        }
                    }
                    boolean block = includesRunning || includesPending;
                    toggleItem.setEnabled(!block);
                    editItem.setEnabled(!block);
                    boolean exactlyOne = sel.length == 1;
                    splitItem.setEnabled(!block && exactlyOne && !queueActive);
                    deleteItem.setEnabled(!block);
                    popup.show(list, e.getX(), e.getY());
                }
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                if (e.isPopupTrigger()) {
                    boolean includesRunning = false;
                    boolean includesPending = false;
                    int[] sel = list.getSelectedIndices();
                    if (runningIndex != null) {
                        for (int si : sel) {
                            if (si == runningIndex.intValue()) {
                                includesRunning = true;
                                break;
                            }
                        }
                    }
                    for (int si : sel) {
                        if (pendingQueue.contains(si)) {
                            includesPending = true;
                            break;
                        }
                    }
                    boolean block = includesRunning || includesPending;
                    toggleItem.setEnabled(!block);
                    editItem.setEnabled(!block);
                    boolean exactlyOne = sel.length == 1;
                    splitItem.setEnabled(!block && exactlyOne && !queueActive);
                    deleteItem.setEnabled(!block);
                    popup.show(list, e.getX(), e.getY());
                }
            }
        });

        // South: controls
        var controls = new JPanel(new GridBagLayout());
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 2, 2, 2);
        gbc.gridy = 0;

        gbc.gridx = 0;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Single-line input (no wrap). Shortcuts: Enter adds, Ctrl/Cmd+Enter adds, Ctrl/Cmd+Shift+Enter adds and keeps,
        // Escape clears
        input.setColumns(50);
        input.putClientProperty("JTextField.placeholderText", "Add task here and press Enter");
        input.setToolTipText("Add task here and press Enter");
        // Enter adds
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "addTask");
        // Ctrl/Cmd+Enter also adds
        input.getInputMap()
                .put(
                        KeyStroke.getKeyStroke(
                                KeyEvent.VK_ENTER, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
                        "addTask");
        input.getActionMap().put("addTask", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                addTask();
            }
        });
        input.getInputMap()
                .put(
                        KeyStroke.getKeyStroke(
                                KeyEvent.VK_ENTER,
                                Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | KeyEvent.SHIFT_DOWN_MASK),
                        "addTaskKeep");
        input.getActionMap().put("addTaskKeep", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                var prev = input.getText();
                addTask();
                input.setText(prev);
                input.requestFocusInWindow();
            }
        });
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "clearInput");
        input.getActionMap().put("clearInput", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                input.setText("");
            }
        });

        controls.add(input, gbc);

        removeBtn.setIcon(Icons.REMOVE);
        // Show a concise HTML tooltip and append the Delete shortcut (display only; no action registered).
        removeBtn.setAppendShortcutToTooltip(true);
        removeBtn.setShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), null, null, null);
        removeBtn.setToolTipText(
                "<html><body style='width:300px'>Remove the selected tasks from the list.<br>Tasks that are running or queued cannot be removed.</body></html>");
        removeBtn.addActionListener(e -> removeSelected());

        toggleDoneBtn.setIcon(Icons.CHECK);
        // Show a concise HTML tooltip and append the Space shortcut (display only; no action registered).
        toggleDoneBtn.setAppendShortcutToTooltip(true);
        toggleDoneBtn.setShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), null, null, null);
        toggleDoneBtn.setToolTipText(
                "<html><body style='width:300px'>Mark the selected tasks as done or not done.<br>Running or queued tasks cannot be toggled.</body></html>");
        toggleDoneBtn.addActionListener(e -> toggleSelectedDone());

        playBtn.setIcon(Icons.PLAY);
        // Show a helpful HTML tooltip and append the platform Enter shortcut (Ctrl/Cmd+Enter display only).
        playBtn.setAppendShortcutToTooltip(true);
        playBtn.setShortcut(
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_ENTER, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
                null,
                null,
                null);
        playBtn.setToolTipText(
                "<html><body style='width:300px'>Run Architect on the selected tasks in order.<br>Tasks already marked done are skipped.<br>One task runs at a time: the current task is highlighted and the rest are queued.<br>Disabled while another AI task is running.</body></html>");
        playBtn.addActionListener(e -> runArchitectOnSelected());

        playAllBtn.setIcon(Icons.FAST_FORWARD);
        playAllBtn.setToolTipText(
                "<html><body style='width:300px'>Run Architect on all tasks in order.<br>Tasks already marked done are skipped.<br>One task runs at a time: the current task is highlighted and the rest are queued.<br>Disabled while another AI task is running.</body></html>");
        playAllBtn.addActionListener(e -> runArchitectOnAll());

        combineBtn.setIcon(Icons.CELL_MERGE);
        combineBtn.setToolTipText(
                "<html><body style='width:300px'>Combine two selected tasks into one new task.<br>The text from both tasks will be merged and the originals deleted.<br>Enabled only when exactly 2 tasks are selected.</body></html>");
        combineBtn.addActionListener(e -> combineSelectedTasks());

        splitBtn.setIcon(Icons.FORK_RIGHT);
        splitBtn.setToolTipText(
                "<html><body style='width:300px'>Split the selected task into multiple tasks.<br>Enter one task per line in the dialog.</body></html>");
        splitBtn.addActionListener(e -> splitSelectedTask());

        clearCompletedBtn.setIcon(Icons.CLEAR_ALL);
        clearCompletedBtn.setToolTipText(
                "<html><body style='width:300px'>Remove all completed tasks from this session.<br>You will be asked to confirm. This cannot be undone.</body></html>");
        clearCompletedBtn.addActionListener(e -> clearCompletedTasks());

        {
            // Make the buttons visually tighter and grouped
            removeBtn.setMargin(new Insets(0, 0, 0, 0));
            toggleDoneBtn.setMargin(new Insets(0, 0, 0, 0));
            playBtn.setMargin(new Insets(0, 0, 0, 0));
            playAllBtn.setMargin(new Insets(0, 0, 0, 0));
            combineBtn.setMargin(new Insets(0, 0, 0, 0));
            splitBtn.setMargin(new Insets(0, 0, 0, 0));
            clearCompletedBtn.setMargin(new Insets(0, 0, 0, 0));

            // Top toolbar (below title, above list): left group + separator + play all/clear completed
            JPanel topToolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            topToolbar.setOpaque(false);

            // Left group: remaining buttons
            topToolbar.add(removeBtn);
            topToolbar.add(toggleDoneBtn);
            topToolbar.add(playBtn);
            topToolbar.add(combineBtn);
            topToolbar.add(splitBtn);

            // Vertical separator between groups
            JSeparator sep = new JSeparator(SwingConstants.VERTICAL);
            sep.setPreferredSize(new java.awt.Dimension(8, 24));
            topToolbar.add(sep);

            // Right group: Play All and Clear Completed
            topToolbar.add(playAllBtn);
            topToolbar.add(clearCompletedBtn);

            add(topToolbar, BorderLayout.NORTH);
        }

        var scroll =
                new JScrollPane(list, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        add(scroll, BorderLayout.CENTER);
        add(controls, BorderLayout.SOUTH);

        // Ensure correct initial layout with wrapped rows after the panel becomes visible
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                SwingUtilities.invokeLater(() -> {
                    list.revalidate();
                    list.repaint();
                });
            }
        });

        // Edit on double-click only to avoid interfering with multi-select
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                    int index = list.locationToIndex(e.getPoint());
                    if (index < 0) return;
                    startInlineEdit(index);
                }
            }
        });
        updateButtonStates();
        llmStateTimer = new Timer(300, e -> updateButtonStates());
        llmStateTimer.setRepeats(true);
        llmStateTimer.start();

        runningFadeTimer = new Timer(40, e -> {
            Integer ri = runningIndex;
            if (ri != null) {
                var rect = list.getCellBounds(ri, ri);
                if (rect != null) list.repaint(rect);
                else list.repaint();
            } else {
                ((Timer) e.getSource()).stop();
            }
        });
        runningFadeTimer.setRepeats(true);

        loadTasksForCurrentSession();

        try {
            IContextManager cm = chrome.getContextManager();
            registeredContextManager = cm;
            cm.addContextListener(this);
        } catch (Exception e) {
            logger.debug("Unable to register TaskListPanel as context listener", e);
        }
    }

    /**
     * Automatically commits any modified files with a message that incorporates the provided task description. -
     * Suggests a commit message via GitWorkflow and combines it with the taskDescription. - Commits all modified files
     * as a single commit. - Reports success/failure on the EDT and refreshes relevant Git UI.
     */
    public static void autoCommitChanges(Chrome chrome, String taskDescription) {
        var cm = chrome.getContextManager();
        var repo = cm.getProject().getRepo();
        java.util.Set<GitRepo.ModifiedFile> modified;
        try {
            modified = repo.getModifiedFiles();
        } catch (GitAPIException e) {
            SwingUtilities.invokeLater(
                    () -> chrome.toolError("Unable to determine modified files: " + e.getMessage(), "Commit Error"));
            return;
        }
        if (modified.isEmpty()) {
            chrome.systemOutput("No changes to commit for task: " + taskDescription);
            return;
        }

        cm.submitExclusiveAction(() -> {
            try {
                var workflowService = new GitWorkflow(cm);
                var filesToCommit =
                        modified.stream().map(GitRepo.ModifiedFile::file).collect(Collectors.toList());

                String suggested = workflowService.suggestCommitMessage(filesToCommit);
                String message;
                if (suggested.isBlank()) {
                    message = taskDescription;
                } else if (!taskDescription.isBlank()
                        && !suggested.toLowerCase(Locale.ROOT).contains(taskDescription.toLowerCase(Locale.ROOT))) {
                    message = suggested + " - " + taskDescription;
                } else {
                    message = suggested;
                }

                var commitResult = workflowService.commit(filesToCommit, message);

                SwingUtilities.invokeLater(() -> {
                    var gitRepo = (GitRepo) repo;
                    chrome.systemOutput("Committed " + gitRepo.shortHash(commitResult.commitId()) + ": "
                            + commitResult.firstLine());
                    chrome.updateCommitPanel();
                    chrome.updateLogTab();
                    chrome.selectCurrentBranchInLogTab();
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(
                        () -> chrome.toolError("Auto-commit failed: " + e.getMessage(), "Commit Error"));
            }
            return null;
        });
    }

    private void addTask() {
        var raw = input.getText();
        if (raw == null) return;
        var lines = Splitter.on(Pattern.compile("\\R+")).split(raw.strip());
        int added = 0;
        for (var line : lines) {
            var text = line.strip();
            if (!text.isEmpty()) {
                model.addElement(new TaskItem(text, false));
                added++;
            }
        }
        if (added > 0) {
            input.setText("");
            input.requestFocusInWindow();
            saveTasksForCurrentSession();
        }
    }

    private void removeSelected() {
        int[] indices = list.getSelectedIndices();
        if (indices.length > 0) {
            // Determine how many tasks can actually be removed (exclude running/queued)
            int deletableCount = 0;
            for (int idx : indices) {
                if (runningIndex != null && idx == runningIndex.intValue()) {
                    continue; // running task cannot be removed
                }
                if (pendingQueue.contains(idx)) {
                    continue; // queued task cannot be removed
                }
                if (idx >= 0 && idx < model.size()) {
                    deletableCount++;
                }
            }

            if (deletableCount == 0) {
                // No-op if only running/queued tasks were selected
                updateButtonStates();
                return;
            }

            String plural = deletableCount == 1 ? "task" : "tasks";
            String message = "This will remove " + deletableCount + " selected " + plural + " from this session.\n"
                    + "Tasks that are running or queued will not be removed.\n"
                    + "This action cannot be undone.";
            int result = chrome.showConfirmDialog(
                    message, "Remove Selected Tasks?", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

            if (result != JOptionPane.YES_OPTION) {
                updateButtonStates();
                return;
            }

            boolean removedAny = false;
            // Remove from bottom to top to keep indices valid
            for (int i = indices.length - 1; i >= 0; i--) {
                int idx = indices[i];
                if (runningIndex != null && idx == runningIndex.intValue()) {
                    continue; // skip running task
                }
                if (pendingQueue.contains(idx)) {
                    continue; // skip pending task
                }
                if (idx >= 0 && idx < model.size()) {
                    model.remove(idx);
                    removedAny = true;
                }
            }
            if (removedAny) {
                updateButtonStates();
                saveTasksForCurrentSession();
            } else {
                // No-op if only the running/pending tasks were selected
                updateButtonStates();
            }
        }
    }

    private void toggleSelectedDone() {
        int[] indices = list.getSelectedIndices();
        if (indices.length > 0) {
            boolean changed = false;
            for (int idx : indices) {
                if (runningIndex != null && idx == runningIndex.intValue()) {
                    continue; // skip running task
                }
                if (pendingQueue.contains(idx)) {
                    continue; // skip pending task
                }
                if (idx >= 0 && idx < model.getSize()) {
                    var it = model.get(idx);
                    model.set(idx, new TaskItem(it.text(), !it.done()));
                    changed = true;
                }
            }
            if (changed) {
                updateButtonStates();
                saveTasksForCurrentSession();
            }
        }
    }

    private void editSelected() {
        int idx = list.getSelectedIndex();
        if (idx < 0) return;
        if (runningIndex != null && idx == runningIndex.intValue()) {
            JOptionPane.showMessageDialog(
                    this,
                    "Cannot edit a task that is currently running.",
                    "Edit Disabled",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (pendingQueue.contains(idx)) {
            JOptionPane.showMessageDialog(
                    this,
                    "Cannot edit a task that is queued for running.",
                    "Edit Disabled",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        startInlineEdit(idx);
    }

    private void startInlineEdit(int index) {
        if (runningIndex != null && index == runningIndex.intValue()) {
            JOptionPane.showMessageDialog(
                    this,
                    "Cannot edit a task that is currently running.",
                    "Edit Disabled",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (pendingQueue.contains(index)) {
            JOptionPane.showMessageDialog(
                    this,
                    "Cannot edit a task that is queued for running.",
                    "Edit Disabled",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (index < 0 || index >= model.size()) return;

        // Commit any existing editor first
        if (inlineEditor != null) {
            stopInlineEdit(true);
        }

        editingIndex = index;
        var item = model.get(index);

        // Use a wrapped JTextArea so the inline editor preserves word-wrap like the renderer.
        inlineEditor = new JTextArea(item.text());
        inlineEditor.setLineWrap(true);
        inlineEditor.setWrapStyleWord(true);
        inlineEditor.setOpaque(false);
        inlineEditor.setEditable(true);
        inlineEditor.setBorder(BorderFactory.createEmptyBorder());
        inlineEditor.setFont(list.getFont());

        // Position editor over the cell (to the right of the checkbox area)
        java.awt.Rectangle cell = list.getCellBounds(index, index);
        int checkboxRegionWidth = 28;
        int editorX = cell.x + checkboxRegionWidth;
        int editorY = cell.y;

        int availableWidth = Math.max(10, cell.width - checkboxRegionWidth - 4);
        // Size the text area to compute wrapped preferred height so the editor shows multiple lines if needed.
        inlineEditor.setSize(availableWidth, Short.MAX_VALUE);
        int prefH = inlineEditor.getPreferredSize().height;
        int editorH = Math.max(cell.height - 2, prefH);

        // Ensure list can host an overlay component
        if (list.getLayout() != null) {
            list.setLayout(null);
        }

        inlineEditor.setBounds(editorX, editorY, availableWidth, editorH);
        list.add(inlineEditor);
        inlineEditor.requestFocusInWindow();
        inlineEditor.selectAll();

        // Key bindings for commit/cancel
        // Map Enter (and platform menu shortcut + Enter) to commitEdit so Enter does NOT insert a newline.
        inlineEditor.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "commitEdit");
        inlineEditor
                .getInputMap()
                .put(
                        KeyStroke.getKeyStroke(
                                KeyEvent.VK_ENTER, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
                        "commitEdit");
        inlineEditor.getActionMap().put("commitEdit", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                stopInlineEdit(true);
            }
        });
        inlineEditor.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancelEdit");
        inlineEditor.getActionMap().put("cancelEdit", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                stopInlineEdit(false);
            }
        });

        // Commit on focus loss
        inlineEditor.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                stopInlineEdit(true);
            }
        });

        list.repaint(cell);
    }

    private void stopInlineEdit(boolean commit) {
        if (inlineEditor == null) return;
        int index = editingIndex;
        var editor = inlineEditor;

        if (commit && index >= 0 && index < model.size()) {
            var cur = model.get(index);
            String newText = editor.getText();
            if (newText != null) {
                newText = newText.strip();
                if (!newText.isEmpty() && !newText.equals(cur.text())) {
                    model.set(index, new TaskItem(newText, cur.done()));
                    saveTasksForCurrentSession();
                }
            }
        }

        list.remove(editor);
        inlineEditor = null;
        editingIndex = -1;
        list.revalidate();
        list.repaint();
        input.requestFocusInWindow();
    }

    private void updateButtonStates() {
        boolean hasSelection = list.getSelectedIndex() >= 0;
        boolean llmBusy = false;
        try {
            llmBusy = chrome.getContextManager().isLlmTaskInProgress();
        } catch (Exception ex) {
            logger.debug("Unable to query LLM busy state", ex);
        }

        boolean selectedIsDone = false;
        boolean selectionIncludesRunning = false;
        boolean selectionIncludesPending = false;
        int[] selIndices = list.getSelectedIndices();
        for (int si : selIndices) {
            if (runningIndex != null && si == runningIndex.intValue()) {
                selectionIncludesRunning = true;
            }
            if (pendingQueue.contains(si)) {
                selectionIncludesPending = true;
            }
        }
        int sel = list.getSelectedIndex();
        if (sel >= 0 && sel < model.getSize()) {
            TaskItem it = model.get(sel);
            selectedIsDone = it != null && it.done();
        }

        // Remove/Toggle disabled if no selection OR selection includes running/pending
        boolean blockEdits = selectionIncludesRunning || selectionIncludesPending;
        removeBtn.setEnabled(hasSelection && !blockEdits);
        toggleDoneBtn.setEnabled(hasSelection && !blockEdits);

        // Play enabled only if: selection exists, not busy, not done, no running/pending in selection, and no active
        // queue
        playBtn.setEnabled(hasSelection && !llmBusy && !selectedIsDone && !blockEdits && !queueActive);

        // Play All enabled if: there are tasks, not busy, no active queue
        boolean hasTasks = model.getSize() > 0;
        playAllBtn.setEnabled(hasTasks && !llmBusy && !queueActive);

        // Combine enabled only if exactly 2 tasks selected and no running/pending in selection
        combineBtn.setEnabled(selIndices.length == 2 && !blockEdits);
        // Split enabled only if exactly 1 task selected and no running/pending in selection and no active queue
        splitBtn.setEnabled(selIndices.length == 1 && !blockEdits && !queueActive);

        // Clear Completed enabled if any task is done
        boolean anyCompleted = false;
        for (int i = 0; i < model.getSize(); i++) {
            TaskItem it2 = model.get(i);
            if (it2 != null && it2.done()) {
                anyCompleted = true;
                break;
            }
        }
        clearCompletedBtn.setEnabled(anyCompleted);
    }

    private UUID getCurrentSessionId() {
        return chrome.getContextManager().getCurrentSessionId();
    }

    private Path getTasksFilePath(UUID sessionId) {
        Path root = chrome.getContextManager().getRoot();
        return root.resolve(".brokk")
                .resolve("sessions")
                .resolve(sessionId.toString())
                .resolve("tasklist.json");
    }

    private void loadTasksForCurrentSession() {
        var sid = getCurrentSessionId();
        this.sessionIdAtLoad = sid;
        Path file = getTasksFilePath(sid);
        isLoadingTasks = true;
        try {
            if (!Files.exists(file)) {
                model.clear();
            } else {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                if (!json.isBlank()) {
                    TaskListData data = Json.fromJson(json, TaskListData.class);
                    model.clear();
                    for (TaskEntryDto dto : data.tasks) {
                        if (!dto.text.isBlank()) {
                            model.addElement(new TaskItem(dto.text, dto.done));
                        }
                    }
                } else {
                    model.clear();
                }
            }
        } catch (Exception e) {
            logger.debug("Failed loading tasks for session {} from {}", sid, file, e);
        } finally {
            isLoadingTasks = false;
            updateButtonStates();
        }
    }

    private void saveTasksForCurrentSession() {
        if (isLoadingTasks) return;
        UUID sid = this.sessionIdAtLoad;
        if (sid == null) {
            sid = getCurrentSessionId();
            this.sessionIdAtLoad = sid;
        }
        Path file = getTasksFilePath(sid);
        try {
            Files.createDirectories(file.getParent());
            TaskListData data = new TaskListData();
            for (int i = 0; i < model.size(); i++) {
                TaskItem it = model.get(i);
                data.tasks.add(new TaskEntryDto(it.text(), it.done()));
            }
            String json = Json.toJson(data);
            Files.writeString(file, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.debug("Failed saving tasks for session {} to {}", sid, file, e);
        }
    }

    /** Append a collection of tasks to the end of the current list and persist them for the active session. */
    public void appendTasks(List<String> tasks) {
        if (tasks.isEmpty()) {
            return;
        }
        boolean added = false;
        for (var t : tasks) {
            var text = t.strip();
            if (!text.isEmpty()) {
                model.addElement(new TaskItem(text, false));
                added = true;
            }
        }
        if (added) {
            saveTasksForCurrentSession();
            updateButtonStates();
        }
    }

    private void runArchitectOnSelected() {
        int[] selected = list.getSelectedIndices();
        if (selected.length == 0) {
            JOptionPane.showMessageDialog(
                    this, "Select at least one task.", "No selection", JOptionPane.WARNING_MESSAGE);
            return;
        }

        runArchitectOnIndices(selected);
    }

    private void runArchitectOnAll() {
        if (model.getSize() == 0) {
            return;
        }

        // Select all tasks
        int[] allIndices = new int[model.getSize()];
        for (int i = 0; i < model.getSize(); i++) {
            allIndices[i] = i;
        }

        list.setSelectionInterval(0, model.getSize() - 1);
        runArchitectOnIndices(allIndices);
    }

    private void runArchitectOnIndices(int[] selected) {
        // Build the ordered list of indices to run: valid, not done
        Arrays.sort(selected);
        var toRun = new java.util.ArrayList<Integer>(selected.length);
        for (int idx : selected) {
            if (idx >= 0 && idx < model.getSize()) {
                TaskItem it = model.get(idx);
                if (it != null && !it.done()) {
                    toRun.add(idx);
                }
            }
        }
        if (toRun.isEmpty()) {
            JOptionPane.showMessageDialog(
                    this, "All selected tasks are already done.", "Nothing to run", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // Record the full ordered run for context awareness
        currentRunOrder = java.util.List.copyOf(toRun);

        // Set up queue: first runs now, the rest are pending
        int first = toRun.get(0);
        pendingQueue.clear();
        if (toRun.size() > 1) {
            for (int i = 1; i < toRun.size(); i++) pendingQueue.add(toRun.get(i));
            queueActive = true;
        } else {
            queueActive = false;
        }

        // Reflect pending state in UI and disable Play buttons to avoid double trigger
        list.repaint();

        // Start the first task
        startRunForIndex(first);
    }

    private void startRunForIndex(int idx) {
        if (idx < 0 || idx >= model.getSize()) {
            startNextIfAny();
            return;
        }
        TaskItem item = model.get(idx);
        if (item == null || item.done()) {
            startNextIfAny();
            return;
        }

        String originalPrompt = item.text();
        if (originalPrompt.isBlank()) {
            startNextIfAny();
            return;
        }

        // Set running visuals
        runningIndex = idx;
        runningAnimStartMs = System.currentTimeMillis();
        runningFadeTimer.start();
        list.repaint();

        // IMMEDIATE FEEDBACK: inform user tasks were submitted without waiting for LLM work
        int totalToRun = currentRunOrder != null ? currentRunOrder.size() : 1;
        SwingUtilities.invokeLater(() -> chrome.systemOutput(
                "Submitted " + totalToRun + " task(s) for execution. Running task 1 of " + totalToRun + "..."));

        var cm = chrome.getContextManager();

        var future = runArchitectOnTaskAsync(idx, cm, originalPrompt);

        // When finished (on background thread), update UI state on EDT
        future.whenComplete((res, ex) -> SwingUtilities.invokeLater(() -> {
            if (ex != null) {
                logger.error("Internal error running architect", ex);
                finishQueueOnError();
                return;
            }
            try {
                if (res == null || res.stopDetails().reason() != TaskResult.StopReason.SUCCESS) {
                    finishQueueOnError();
                    return;
                }

                if (Objects.equals(runningIndex, idx) && idx < model.size()) {
                    var it = model.get(idx);
                    if (it != null) {
                        model.set(idx, new TaskItem(it.text(), true));
                        saveTasksForCurrentSession();
                    }
                }
            } finally {
                // Clear running, advance queue
                runningIndex = null;
                runningFadeTimer.stop();
                list.repaint();
                updateButtonStates();
                startNextIfAny();
            }
        }));
    }

    @NotNull
    CompletableFuture<TaskResult> runArchitectOnTaskAsync(int idx, ContextManager cm, String originalPrompt) {
        // Submit an LLM action that will perform optional search + architect work off the EDT.
        return cm.submitLlmAction("Execute Task " + (idx + 1), () -> {
            chrome.showOutputSpinner("Executing Task command...");
            TaskResult result;
            try (var scope = cm.beginTask(originalPrompt, false)) {
                result = runArchitectOnTaskInternal(idx, cm, originalPrompt, scope);
            } finally {
                chrome.hideOutputSpinner();
                cm.checkBalanceAndNotify();
            }

            // do this AFTER the TaskScope closes with both search + architect results
            if (result.stopDetails().reason() == TaskResult.StopReason.SUCCESS) {
                // Only auto-commit if we're processing multiple tasks as part of a queue
                if (queueActive) {
                    autoCommitChanges(chrome, originalPrompt);
                    cm.compressHistory(); // synchronous compress (avoid deadlock with async variant)
                }
            }

            return result;
        });
    }

    private @NotNull TaskResult runArchitectOnTaskInternal(
            int idx, ContextManager cm, String originalPrompt, ContextManager.TaskScope scope) {
        // Optionally run SearchAgent; we can skip search if this is the first task
        // AND there are items in the context to edit (NOT context.isEmpty which includes task history)
        boolean skipSearch =
                idx == 0 && cm.liveContext().getEditableFragments().findAny().isPresent();
        if (skipSearch) {
            logger.debug("Skipping SearchAgent for first task since workspace has editable fragments");
        } else {
            var scanModel = cm.getService().getScanModel();
            SearchAgent agent =
                    new SearchAgent(originalPrompt, cm, scanModel, EnumSet.of(SearchAgent.Terminal.WORKSPACE));
            chrome.setSkipNextUpdateOutputPanelOnContextChange(true);
            var searchResult = agent.execute();
            scope.append(searchResult);
            if (searchResult.stopDetails().reason() != TaskResult.StopReason.SUCCESS) {
                return searchResult;
            }
        }

        var planningModel = requireNonNull(cm.getService().getModel(Service.GEMINI_2_5_PRO));
        var codeModel = requireNonNull(
                cm.getService().getModel(chrome.getInstructionsPanel().getSelectedModel()));

        var architectAgent = new ArchitectAgent(cm, planningModel, codeModel, originalPrompt, scope);
        var archResult = architectAgent.execute();
        scope.append(archResult);
        return archResult;
    }

    private void startNextIfAny() {
        if (pendingQueue.isEmpty()) {
            // Queue finished
            queueActive = false;
            currentRunOrder = null;
            list.repaint();
            updateButtonStates();
            return;
        }
        // Get next pending index in insertion order and start it
        int next = pendingQueue.getFirst();
        pendingQueue.remove(next);
        list.repaint();
        startRunForIndex(next);
    }

    private void finishQueueOnError() {
        runningIndex = null;
        runningFadeTimer.stop();
        pendingQueue.clear();
        queueActive = false;
        currentRunOrder = null;
        list.repaint();
        updateButtonStates();
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        // Keep default Swing theming; adjust list selection for readability if needed
        boolean dark = guiTheme.isDarkTheme();
        Color selBg = UIManager.getColor("List.selectionBackground");
        Color selFg = UIManager.getColor("List.selectionForeground");
        if (selBg == null) selBg = dark ? new Color(60, 90, 140) : new Color(200, 220, 255);
        if (selFg == null) selFg = dark ? Color.WHITE : Color.BLACK;
        list.setSelectionBackground(selBg);
        list.setSelectionForeground(selFg);
        revalidate();
        repaint();
    }

    public void disablePlay() {
        playBtn.setEnabled(false);
        playAllBtn.setEnabled(false);
    }

    public void enablePlay() {
        updateButtonStates();
    }

    /**
     * TransferHandler for in-place reordering via drag-and-drop. Keeps data locally and performs MOVE operations within
     * the same list.
     */
    private final class TaskReorderTransferHandler extends TransferHandler {
        private @Nullable int[] indices = null;
        private int addIndex = -1;
        private int addCount = 0;

        @Override
        public int getSourceActions(JComponent c) {
            return MOVE;
        }

        @Override
        protected @Nullable Transferable createTransferable(JComponent c) {
            // Commit any inline edit before starting a drag
            stopInlineEdit(true);

            indices = list.getSelectedIndices();

            // Disallow dragging if selection includes the running task
            if (runningIndex != null && indices != null) {
                for (int i : indices) {
                    if (i == runningIndex) {
                        Toolkit.getDefaultToolkit().beep();
                        indices = null;
                        return null; // cancel drag
                    }
                }
            }

            // Disallow dragging when a queue is active or if selection includes pending
            if (queueActive) {
                Toolkit.getDefaultToolkit().beep();
                indices = null;
                return null;
            }
            if (indices != null) {
                for (int i : indices) {
                    if (pendingQueue.contains(i)) {
                        Toolkit.getDefaultToolkit().beep();
                        indices = null;
                        return null;
                    }
                }
            }

            addIndex = -1;
            addCount = 0;

            // We keep the data locally; return a simple dummy transferable
            return new StringSelection("tasks");
        }

        @Override
        public boolean canImport(TransferSupport support) {
            if (queueActive) return false;
            if (indices != null && runningIndex != null) {
                for (int i : indices) {
                    if (i == runningIndex.intValue()) {
                        return false; // cannot drop a running task
                    }
                }
            }
            if (indices != null) {
                for (int i : indices) {
                    if (pendingQueue.contains(i)) return false;
                }
            }
            return support.isDrop();
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (!support.isDrop()) {
                return false;
            }
            if (queueActive) return false;
            if (indices != null && runningIndex != null) {
                for (int i : indices) {
                    if (i == runningIndex.intValue()) {
                        return false; // cannot drop a running task
                    }
                }
            }
            if (indices != null) {
                for (int i : indices) {
                    if (pendingQueue.contains(i)) return false;
                }
            }
            var dl = (JList.DropLocation) support.getDropLocation();
            int index = dl.getIndex();
            int max = model.getSize();
            if (index < 0 || index > max) {
                index = max;
            }
            addIndex = index;

            if (indices == null || indices.length == 0) {
                return false;
            }

            // Snapshot the items being moved
            var items = new java.util.ArrayList<TaskItem>(indices.length);
            for (int i : indices) {
                if (i >= 0 && i < model.size()) {
                    items.add(model.get(i));
                }
            }

            // Insert items at drop index
            for (var it : items) {
                model.add(index++, it);
            }
            addCount = items.size();

            // Select the inserted range
            if (addCount > 0) {
                list.setSelectionInterval(addIndex, addIndex + addCount - 1);
            }
            return true;
        }

        @Override
        protected void exportDone(JComponent source, Transferable data, int action) {
            if (action == MOVE && indices != null) {
                // Adjust indices if we inserted before some of the original positions
                if (addCount > 0) {
                    for (int i = 0; i < indices.length; i++) {
                        if (indices[i] >= addIndex) {
                            indices[i] += addCount;
                        }
                    }
                }
                // Remove original items (from bottom to top to keep indices valid)
                for (int i = indices.length - 1; i >= 0; i--) {
                    int idx = indices[i];
                    if (idx >= 0 && idx < model.size()) {
                        model.remove(idx);
                    }
                }
            }
            indices = null;
            addIndex = -1;
            addCount = 0;
            saveTasksForCurrentSession();
        }
    }

    private void combineSelectedTasks() {
        int[] indices = list.getSelectedIndices();
        if (indices.length != 2) {
            JOptionPane.showMessageDialog(
                    this, "Select exactly two tasks to combine.", "Invalid Selection", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Check if either task is running or pending
        for (int idx : indices) {
            if (runningIndex != null && idx == runningIndex) {
                JOptionPane.showMessageDialog(
                        this,
                        "Cannot combine tasks while one is currently running.",
                        "Combine Disabled",
                        JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            if (pendingQueue.contains(idx)) {
                JOptionPane.showMessageDialog(
                        this,
                        "Cannot combine tasks while one is queued for running.",
                        "Combine Disabled",
                        JOptionPane.INFORMATION_MESSAGE);
                return;
            }
        }

        Arrays.sort(indices);
        int firstIdx = indices[0];
        int secondIdx = indices[1];

        if (firstIdx < 0 || secondIdx >= model.size()) {
            return;
        }

        TaskItem firstTask = model.get(firstIdx);
        TaskItem secondTask = model.get(secondIdx);

        if (firstTask == null || secondTask == null) {
            return;
        }

        // Combine the text with a separator
        String combinedText = firstTask.text() + " | " + secondTask.text();

        // Both tasks are considered done if either one is done
        boolean combinedDone = firstTask.done() || secondTask.done();

        // Create the new combined task
        TaskItem combinedTask = new TaskItem(combinedText, combinedDone);

        // Add the combined task at the position of the first selected task
        model.set(firstIdx, combinedTask);

        // Remove the second task (higher index first to keep indices valid)
        model.remove(secondIdx);

        // Select the combined task
        list.setSelectedIndex(firstIdx);

        saveTasksForCurrentSession();
        updateButtonStates();
    }

    private void splitSelectedTask() {
        int[] indices = list.getSelectedIndices();
        if (indices.length != 1) {
            JOptionPane.showMessageDialog(
                    this, "Select exactly one task to split.", "Invalid Selection", JOptionPane.WARNING_MESSAGE);
            return;
        }

        int idx = indices[0];

        if (runningIndex != null && idx == runningIndex) {
            JOptionPane.showMessageDialog(
                    this,
                    "Cannot split a task that is currently running.",
                    "Split Disabled",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (pendingQueue.contains(idx)) {
            JOptionPane.showMessageDialog(
                    this,
                    "Cannot split a task that is queued for running.",
                    "Split Disabled",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        if (queueActive) {
            JOptionPane.showMessageDialog(
                    this,
                    "Cannot split tasks while a run is in progress.",
                    "Split Disabled",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        stopInlineEdit(true);

        if (idx < 0 || idx >= model.size()) {
            return;
        }

        TaskItem original = model.get(idx);
        if (original == null) return;

        var textArea = new JTextArea();
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setText(original.text());

        var scroll = new JScrollPane(
                textArea, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setPreferredSize(new java.awt.Dimension(420, 180));

        var panel = new JPanel(new BorderLayout(6, 6));
        panel.add(new JLabel("Enter one task per line:"), BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);

        int result = JOptionPane.showConfirmDialog(
                this, panel, "Split Task", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result != JOptionPane.OK_OPTION) {
            return;
        }

        var lines = normalizeSplitLines(textArea.getText());

        if (lines.isEmpty()) {
            return;
        }

        // Replace the original with the first line; insert remaining lines after; mark all as not done
        model.set(idx, new TaskItem(lines.getFirst(), false));
        for (int i = 1; i < lines.size(); i++) {
            model.add(idx + i, new TaskItem(lines.get(i), false));
        }

        // Select the new block
        list.setSelectionInterval(idx, idx + lines.size() - 1);

        saveTasksForCurrentSession();
        updateButtonStates();
        list.revalidate();
        list.repaint();
    }

    static List<String> normalizeSplitLines(@Nullable String input) {
        if (input == null) return java.util.Collections.emptyList();
        return Arrays.stream(input.split("\\R+"))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * Compute vertical padding to center content within a cell of a given minimum height. If contentHeight >=
     * minHeight, returns zero padding. Otherwise splits the extra space between top and bottom, with top =
     * floor(extra/2).
     *
     * <p>Centering approach: - We dynamically compute the padding to position the text vertically without changing
     * layout managers. - In the renderer, we apply the resulting top padding as a paint offset (or as an EmptyBorder
     * when using a text component). Why not change layouts or switch to HTML/StyledDocument? - Changing the layout per
     * cell (e.g., GridBag/Box) is heavier and can degrade performance on large lists. - HTML/StyledDocument introduce
     * different wrapping/metrics and are more expensive than simple text painting, and do not inherently solve vertical
     * placement within a taller cell. - Keeping rendering lightweight and predictable avoids jank and keeps word-wrap
     * behavior stable, which is especially important when the inline editor uses JTextArea wrapping.
     */
    static Insets verticalPaddingForCell(int contentHeight, int minHeight) {
        int extra = minHeight - contentHeight;
        if (extra <= 0) {
            return new Insets(0, 0, 0, 0);
        }
        int top = extra / 2;
        int bottom = extra - top;
        return new Insets(top, 0, bottom, 0);
    }

    private void clearCompletedTasks() {
        if (model.isEmpty()) {
            return;
        }

        // Count how many completed tasks would be removed (exclude the running task for safety)
        int completedCount = 0;
        for (int i = 0; i < model.size(); i++) {
            TaskItem it = model.get(i);
            if (it != null && it.done()) {
                if (runningIndex != null && i == runningIndex) {
                    continue;
                }
                completedCount++;
            }
        }

        if (completedCount == 0) {
            // Nothing to clear
            updateButtonStates();
            return;
        }

        String plural = completedCount == 1 ? "task" : "tasks";
        String message = "This will remove " + completedCount + " completed " + plural + " from this session.\n"
                + "This action cannot be undone.";
        int result = chrome.showConfirmDialog(
                message, "Clear Completed Tasks?", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

        if (result != JOptionPane.YES_OPTION) {
            updateButtonStates();
            return;
        }

        boolean removedAny = false;
        // Remove from bottom to top to keep indices valid
        for (int i = model.size() - 1; i >= 0; i--) {
            TaskItem it = model.get(i);
            if (it != null && it.done()) {
                // Do not remove the running task even if marked done (safety)
                if (runningIndex != null && i == runningIndex) {
                    continue;
                }
                model.remove(i);
                removedAny = true;
            }
        }

        if (removedAny) {
            saveTasksForCurrentSession();
        }
        updateButtonStates();
    }

    private void copySelectedTasks() {
        int[] indices = list.getSelectedIndices();
        if (indices.length == 0) {
            return;
        }

        var taskTexts = new java.util.ArrayList<String>(indices.length);
        for (int idx : indices) {
            if (idx >= 0 && idx < model.getSize()) {
                var item = model.get(idx);
                if (item != null) {
                    taskTexts.add(item.text());
                }
            }
        }

        if (!taskTexts.isEmpty()) {
            String clipboardText = String.join("\n", taskTexts);
            StringSelection selection = new StringSelection(clipboardText);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
        }
    }

    @Override
    public void removeNotify() {
        try {
            saveTasksForCurrentSession();
        } catch (Exception e) {
            logger.debug("Error saving tasks on removeNotify", e);
        }
        try {
            var cm = registeredContextManager;
            if (cm != null) {
                cm.removeContextListener(this);
            }
        } catch (Exception e) {
            logger.debug("Error unregistering TaskListPanel as context listener", e);
        } finally {
            registeredContextManager = null;
        }
        llmStateTimer.stop();
        runningFadeTimer.stop();
        super.removeNotify();
    }

    @Override
    public void contextChanged(Context newCtx) {
        UUID current = getCurrentSessionId();
        UUID loaded = this.sessionIdAtLoad;
        if (!Objects.equals(current, loaded)) {
            SwingUtilities.invokeLater(this::loadTasksForCurrentSession);
        }
    }

    private record TaskItem(String text, boolean done) {}

    private final class TaskRenderer extends JPanel implements ListCellRenderer<TaskItem> {
        private final JCheckBox check = new JCheckBox();
        private final WrappedTextView view = new WrappedTextView();

        TaskRenderer() {
            super(new BorderLayout(6, 0));
            setOpaque(true);

            check.setOpaque(false);
            check.setIcon(Icons.CIRCLE);
            check.setSelectedIcon(Icons.CHECK);
            check.setVerticalAlignment(SwingConstants.CENTER);
            add(check, BorderLayout.WEST);

            view.setOpaque(false);
            add(view, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(
                JList<? extends TaskItem> list, TaskItem value, int index, boolean isSelected, boolean cellHasFocus) {

            boolean isRunningRow = (!value.done()
                    && TaskListPanel.this.runningIndex != null
                    && TaskListPanel.this.runningIndex == index);
            boolean isPendingRow = (!value.done() && TaskListPanel.this.pendingQueue.contains(index));

            // Icon logic: running takes precedence, then pending, then done/undone
            if (isRunningRow) {
                check.setSelected(false);
                check.setIcon(Icons.ARROW_UPLOAD_READY);
                check.setSelectedIcon(null);
            } else if (isPendingRow) {
                check.setSelected(false);
                check.setIcon(Icons.PENDING);
                check.setSelectedIcon(null);
            } else {
                check.setIcon(Icons.CIRCLE);
                check.setSelectedIcon(Icons.CHECK);
                check.setSelected(value.done());
            }

            // Set text and editing visibility
            view.setText(value.text());
            boolean isEditingRow =
                    (TaskListPanel.this.inlineEditor != null && TaskListPanel.this.editingIndex == index);
            view.setVisible(!isEditingRow);

            // Font and strike-through
            Font base = list.getFont();
            view.setFont(base.deriveFont(Font.PLAIN));
            view.setStrikeThrough(value.done());

            // Compute wrapping height based on available width (with safe fallbacks for first render)
            int checkboxRegionWidth = 28;
            int width = list.getWidth();
            if (width <= 0) {
                java.awt.Container parent = list.getParent();
                if (parent instanceof javax.swing.JViewport vp) {
                    width = vp.getWidth();
                }
            }
            if (width <= 0) {
                // Final fallback to a reasonable width to avoid giant first row
                width = 600;
            }
            int available = Math.max(1, width - checkboxRegionWidth - 8);

            // Measure content height for the width and compute minHeight invariant
            view.setAvailableWidth(available);
            int contentH = view.getContentHeight();

            // Ensure minimum height to show full checkbox icon and preserve wrapping behavior.
            // Guard against regressions: do not change this formula; minHeight must remain Math.max(contentH, 48).
            int minHeight = Math.max(contentH, 48);
            assert minHeight == Math.max(contentH, 48)
                    : "minHeight must remain Math.max(contentH, 48) to keep wrapping stable";
            this.setPreferredSize(new java.awt.Dimension(available + checkboxRegionWidth, minHeight));

            // Vertically center the text within the row by applying top padding as a paint offset.
            // We intentionally avoid changing layouts or switching to HTML so that wrapping remains predictable
            // and rendering stays lightweight. The paint offset gives the same visual effect as a dynamic
            // EmptyBorder without incurring layout churn.
            Insets pad = verticalPaddingForCell(contentH, minHeight);
            if (isEditingRow) {
                pad = new Insets(0, 0, 0, 0);
            }
            view.setTopPadding(pad.top);

            // State coloring and subtle running animation
            if (isRunningRow) {
                long now = System.currentTimeMillis();
                long start = TaskListPanel.this.runningAnimStartMs;
                double periodMs = 5000.0;
                double t = ((now - start) % (long) periodMs) / periodMs; // 0..1
                double ratio = 0.5 * (1 - Math.cos(2 * Math.PI * t)); // 0..1 smooth in/out

                java.awt.Color bgBase = list.getBackground();
                java.awt.Color selBg = list.getSelectionBackground();
                if (selBg == null) selBg = bgBase;

                int r = (int) Math.round(bgBase.getRed() * (1 - ratio) + selBg.getRed() * ratio);
                int g = (int) Math.round(bgBase.getGreen() * (1 - ratio) + selBg.getGreen() * ratio);
                int b = (int) Math.round(bgBase.getBlue() * (1 - ratio) + selBg.getBlue() * ratio);
                setBackground(new java.awt.Color(r, g, b));

                if (isSelected) {
                    view.setForeground(list.getSelectionForeground());
                    java.awt.Color borderColor = selBg.darker();
                    setBorder(javax.swing.BorderFactory.createLineBorder(borderColor, 1));
                } else {
                    view.setForeground(list.getForeground());
                    setBorder(null);
                }
            } else if (isSelected) {
                setBackground(list.getSelectionBackground());
                view.setForeground(list.getSelectionForeground());
                setBorder(null);
            } else {
                setBackground(list.getBackground());
                view.setForeground(list.getForeground());
                setBorder(null);
            }

            return this;
        }
    }

    /**
     * Lightweight wrapped text painter used in the renderer (no JTextArea). - Measures wrapped content height for a
     * given available width. - Applies vertical centering by honoring a top padding paint offset. - Keeps rendering
     * fast and stable; avoids layout churn and preserves wrap behavior consistent with the inline editor (which uses
     * JTextArea only while editing).
     */
    private static final class WrappedTextView extends JComponent {
        private String text = "";
        private int availableWidth = 0;
        private int contentHeight = 0;
        private boolean strikeThrough = false;
        private int topPadding = 0;

        void setText(String text) {
            this.text = text;
            // Re-measure on text change
            measure();
            repaint();
        }

        void setAvailableWidth(int w) {
            if (w < 1) w = 1;
            if (this.availableWidth != w) {
                this.availableWidth = w;
                measure();
                revalidate();
                repaint();
            }
        }

        void setStrikeThrough(boolean on) {
            if (this.strikeThrough != on) {
                this.strikeThrough = on;
                repaint();
            }
        }

        void setTopPadding(int padTop) {
            this.topPadding = Math.max(0, padTop);
            repaint();
        }

        int getContentHeight() {
            return contentHeight;
        }

        @Override
        public void setFont(Font font) {
            super.setFont(font);
            measure();
            revalidate();
            repaint();
        }

        private void measure() {
            Font f = getFont();
            if (f == null) {
                contentHeight = 0;
                return;
            }
            FontMetrics fm = getFontMetrics(f);
            int lineHeight = fm.getHeight();
            if (availableWidth <= 0 || text.isEmpty()) {
                contentHeight = lineHeight;
                return;
            }

            var lines = wrapLines(text, fm, availableWidth);
            contentHeight = Math.max(lineHeight, lines.size() * lineHeight);
        }

        @Override
        public java.awt.Dimension getPreferredSize() {
            // The parent panel dictates minHeight; this view reports its content height and width.
            return new java.awt.Dimension(Math.max(1, availableWidth), Math.max(0, topPadding + contentHeight));
        }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            super.paintComponent(g);
            if (text.isEmpty()) return;

            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            try {
                g2.setColor(getForeground());
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                int lineHeight = fm.getHeight();
                int y = topPadding + fm.getAscent();

                var lines = wrapLines(text, fm, availableWidth);
                for (var line : lines) {
                    g2.drawString(line, 0, y);
                    if (strikeThrough) {
                        int yStrike = y - Math.round(fm.getAscent() * 0.4f);
                        int w = fm.stringWidth(line);
                        g2.drawLine(0, yStrike, w, yStrike);
                    }
                    y += lineHeight;
                }
            } finally {
                g2.dispose();
            }
        }

        private List<String> wrapLines(String text, FontMetrics fm, int maxWidth) {
            // Handle explicit newlines as hard breaks first.
            var paragraphs = text.split("\\R", -1);
            var result = new ArrayList<String>(paragraphs.length);
            for (var para : paragraphs) {
                wrapParagraph(para, fm, maxWidth, result);
            }
            if (result.isEmpty()) {
                result.add("");
            }
            return result;
        }

        private void wrapParagraph(String para, FontMetrics fm, int maxWidth, List<String> out) {
            if (para.isEmpty()) {
                out.add("");
                return;
            }
            var breaker = BreakIterator.getLineInstance(java.util.Locale.ROOT);
            breaker.setText(para);
            int start = breaker.first();
            int end = breaker.next();
            StringBuilder current = new StringBuilder();
            while (end != BreakIterator.DONE) {
                String word = para.substring(start, end);
                String candidate = current + word;
                int w = fm.stringWidth(candidate);
                if (w <= maxWidth) {
                    current.append(word);
                    start = end;
                    end = breaker.next();
                } else {
                    if (current.length() == 0) {
                        // Single word longer than maxWidth: hard-break by characters
                        end = hardBreakByChars(para, start, fm, maxWidth, out);
                        start = end;
                        current.setLength(0);
                        end = breaker.following(start);
                        if (end == BreakIterator.DONE && start < para.length()) {
                            end = para.length();
                        }
                    } else {
                        out.add(current.toString().stripTrailing());
                        current.setLength(0);
                        // keep evaluating the same word on next loop
                    }
                }
            }
            if (start < para.length()) {
                current.append(para.substring(start));
            }
            if (current.length() > 0) {
                out.add(current.toString().stripTrailing());
            }
        }

        private int hardBreakByChars(String text, int start, FontMetrics fm, int maxWidth, List<String> out) {
            int i = start;
            StringBuilder sb = new StringBuilder();
            while (i < text.length()) {
                char ch = text.charAt(i);
                int w = fm.stringWidth(sb.toString() + ch);
                if (w > maxWidth) {
                    if (sb.length() == 0) {
                        // Always place at least one char to make progress
                        sb.append(ch);
                        i++;
                    }
                    out.add(sb.toString());
                    sb.setLength(0);
                    // Continue building next line without incrementing i
                } else {
                    sb.append(ch);
                    i++;
                }
                // If next char would overflow and we already have content, flush
                if (i < text.length() && fm.stringWidth(sb.toString() + text.charAt(i)) > maxWidth && sb.length() > 0) {
                    out.add(sb.toString());
                    sb.setLength(0);
                }
            }
            if (sb.length() > 0) {
                out.add(sb.toString());
            }
            return i;
        }
    }

    private static final class TaskListData {
        public List<TaskEntryDto> tasks = new ArrayList<>();

        public TaskListData() {}
    }

    private static final class TaskEntryDto {
        public String text = "";
        public boolean done;

        public TaskEntryDto(String text, boolean done) {
            this.text = text;
            this.done = done;
        }
    }
}
