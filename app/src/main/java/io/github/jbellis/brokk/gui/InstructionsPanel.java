package io.github.jbellis.brokk.gui;

import static io.github.jbellis.brokk.gui.Constants.*;
import static java.util.Objects.requireNonNull;
import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import com.github.tjake.jlama.model.AbstractModel;
import com.github.tjake.jlama.model.functions.Generator;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import io.github.jbellis.brokk.*;
import io.github.jbellis.brokk.agents.ArchitectAgent;
import io.github.jbellis.brokk.agents.CodeAgent;
import io.github.jbellis.brokk.agents.ContextAgent;
import io.github.jbellis.brokk.agents.SearchAgent;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.git.IGitRepo;
import io.github.jbellis.brokk.gui.TableUtils.FileReferenceList.FileReferenceData;
import io.github.jbellis.brokk.gui.components.MaterialButton;
import io.github.jbellis.brokk.gui.components.ModelSelector;
import io.github.jbellis.brokk.gui.components.OverlayPanel;
import io.github.jbellis.brokk.gui.components.SplitButton;
import io.github.jbellis.brokk.gui.components.SwitchIcon;
import io.github.jbellis.brokk.gui.dialogs.SettingsDialog;
import io.github.jbellis.brokk.gui.dialogs.SettingsGlobalPanel;
import io.github.jbellis.brokk.gui.git.GitWorktreeTab;
import io.github.jbellis.brokk.gui.mop.ThemeColors;
import io.github.jbellis.brokk.gui.util.AddMenuFactory;
import io.github.jbellis.brokk.gui.util.ContextMenuUtils;
import io.github.jbellis.brokk.gui.util.GitUiUtil;
import io.github.jbellis.brokk.gui.util.Icons;
import io.github.jbellis.brokk.gui.wand.WandButton;
import io.github.jbellis.brokk.prompts.CodePrompts;
import io.github.jbellis.brokk.tools.WorkspaceTools;
import io.github.jbellis.brokk.util.LoggingExecutorService;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.*;
import javax.swing.undo.UndoManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.fife.ui.autocomplete.AutoCompletion;
import org.fife.ui.autocomplete.Completion;
import org.fife.ui.autocomplete.DefaultCompletionProvider;
import org.fife.ui.autocomplete.ShorthandCompletion;
import org.jetbrains.annotations.Nullable;

/**
 * The InstructionsPanel encapsulates the command input area, history dropdown, mic button, model dropdown, and the
 * action buttons. It also includes the system messages and command result areas. All initialization and action code
 * related to these components has been moved here.
 */
public class InstructionsPanel extends JPanel implements IContextManager.ContextListener {
    private static final Logger logger = LogManager.getLogger(InstructionsPanel.class);

    public static final String ACTION_ARCHITECT = "Architect";
    public static final String ACTION_CODE = "Code";
    public static final String ACTION_ASK = "Ask";
    public static final String ACTION_SEARCH = "Search";
    public static final String ACTION_RUN = "Run";
    public static final String ACTION_RUN_TESTS = "Run Selected Tests";
    public static final String ACTION_SCAN_PROJECT = "Scan Project";

    private static final String PLACEHOLDER_TEXT =
            """
                                                   Put your instructions or questions here.  Brokk will suggest relevant files below; right-click on them to add them to your Workspace.  The Workspace will be visible to the AI when coding or answering your questions. Type "@" for add more context.

                                                   More tips are available in the Getting Started section in the Output panel above.
                                                   """;

    private final Color defaultActionButtonBg;
    private final Color secondaryActionButtonBg;

    private final Chrome chrome;
    private final JTextArea instructionsArea;
    private final VoiceInputButton micButton;
    private final JCheckBox modeSwitch;
    private final JCheckBox codeCheckBox;
    private final JCheckBox searchProjectCheckBox;
    // Labels flanking the mode switch; bold the selected side
    private final JLabel codeModeLabel = new JLabel("Code");
    private final JLabel answerModeLabel = new JLabel("Ask");
    private final MaterialButton actionButton;
    private final WandButton wandButton;
    private final ModelSelector modelSelector;
    private String storedAction;
    private final ContextManager contextManager;
    private JTable referenceFileTable;
    private JLabel failureReasonLabel;
    private JPanel suggestionContentPanel;
    private CardLayout suggestionCardLayout;
    private final JPanel centerPanel;
    private @Nullable JPanel modeIndicatorPanel;
    private @Nullable JLabel modeBadge;
    private @Nullable JComponent inputLayeredPane;
    private ActionGroupPanel actionGroupPanel;
    private @Nullable TitledBorder instructionsTitledBorder;
    private @Nullable SplitButton branchSplitButton;

    // Card panel that holds the two mutually-exclusive checkboxes so they occupy the same slot.
    private @Nullable JPanel optionsPanel;
    private static final String OPTIONS_CARD_CODE = "OPTIONS_CODE";
    private static final String OPTIONS_CARD_ASK = "OPTIONS_ASK";
    private static final int CONTEXT_SUGGESTION_DELAY = 100; // ms for paste/bulk changes
    private static final int CONTEXT_SUGGESTION_TYPING_DELAY = 1000; // ms for single character typing
    private final javax.swing.Timer contextSuggestionTimer; // Timer for debouncing quick context suggestions
    private final AtomicBoolean forceSuggestions = new AtomicBoolean(false);
    // Worker for autocontext suggestion tasks. we don't use CM.backgroundTasks b/c we want this to be single threaded
    private final ExecutorService suggestionWorker = new LoggingExecutorService(
            Executors.newSingleThreadExecutor(r -> {
                Thread t = Executors.defaultThreadFactory().newThread(r);
                t.setName("Brokk-Suggestion-Worker");
                t.setDaemon(true);
                return t;
            }),
            e -> logger.error("Unexpected error", e));
    // Generation counter to identify the latest suggestion request
    private final AtomicLong suggestionGeneration = new AtomicLong(0);
    private final OverlayPanel commandInputOverlay; // Overlay to initially disable command input
    private final UndoManager commandInputUndoManager;
    private AutoCompletion instructionAutoCompletion;
    private InstructionsCompletionProvider instructionCompletionProvider;
    private @Nullable String lastCheckedInputText = null;
    private @Nullable float[][] lastCheckedEmbeddings = null;
    private @Nullable List<FileReferenceData> pendingQuickContext = null;

    public InstructionsPanel(Chrome chrome) {
        super(new BorderLayout(2, 2));
        this.instructionsTitledBorder = BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Instructions - Code",
                TitledBorder.DEFAULT_JUSTIFICATION,
                TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12));
        setBorder(this.instructionsTitledBorder);

        this.chrome = chrome;
        this.contextManager = chrome.getContextManager();
        this.commandInputUndoManager = new UndoManager();
        commandInputOverlay = new OverlayPanel(overlay -> activateCommandInput(), "Click to enter your instructions");
        commandInputOverlay.setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));

        // Set up custom focus traversal policy for tab navigation
        setFocusTraversalPolicy(new InstructionsPanelFocusTraversalPolicy());
        setFocusCycleRoot(true);
        setFocusTraversalPolicyProvider(true);

        // Initialize components
        instructionsArea = buildCommandInputField(); // Build first to add listener
        wandButton = new WandButton(
                contextManager, chrome, instructionsArea, this::getInstructions, this::populateInstructionsArea);
        micButton = new VoiceInputButton(
                instructionsArea,
                contextManager,
                () -> {
                    activateCommandInput();
                    chrome.systemOutput("Recording");
                },
                msg -> chrome.toolError(msg, "Error"));
        micButton.setFocusable(true);

        // Initialize Action Selection UI
        modeSwitch = new JCheckBox();
        KeyStroke toggleKs =
                io.github.jbellis.brokk.gui.util.KeyboardShortcutUtil.createPlatformShortcut(KeyEvent.VK_M);
        String tooltipText =
                """
                <html>
                <b>Code Mode:</b> For generating or modifying code based on your instructions.<br>
                <b>Ask Mode:</b> For answering questions about your project or general programming topics.<br>
                <br>
                Click to toggle between Code and Ask modes (%s).
                </html>
                """
                        .formatted(formatKeyStroke(toggleKs));
        modeSwitch.setToolTipText(tooltipText);
        // Also show the same tooltip when hovering the labels to improve discoverability.
        codeModeLabel.setToolTipText(tooltipText);
        answerModeLabel.setToolTipText(tooltipText);
        // Keep tooltips visible longer (30 seconds) so users have time to read the HTML content.
        javax.swing.ToolTipManager.sharedInstance().setDismissDelay(30_000);
        var switchIcon = new SwitchIcon();
        modeSwitch.setIcon(switchIcon);
        modeSwitch.setSelectedIcon(switchIcon);
        modeSwitch.setFocusPainted(true);
        modeSwitch.setFocusable(true);
        modeSwitch.setBorderPainted(false);
        modeSwitch.setBorder(BorderFactory.createEmptyBorder());
        modeSwitch.setContentAreaFilled(false);
        modeSwitch.setOpaque(false);
        modeSwitch.setIconTextGap(0);
        modeSwitch.setRolloverEnabled(false);
        modeSwitch.setMargin(new Insets(0, 0, 0, 0));
        modeSwitch.setText("");

        codeCheckBox = new JCheckBox("Plan First");
        codeCheckBox.setFocusable(true);
        // Register a global platform-aware shortcut (Cmd/Ctrl+S) to toggle "Search".
        KeyStroke toggleSearchKs =
                io.github.jbellis.brokk.gui.util.KeyboardShortcutUtil.createPlatformShortcut(KeyEvent.VK_SEMICOLON);

        codeCheckBox.setToolTipText("<html><b>Plan First:</b><br><ul>"
                + "<li><b>checked:</b> Plan usage of multiple agents. Useful for large refactorings; will add files to the Workspace.</li>"
                + "<li><b>unchecked:</b> Assumes necessary files are already in Workspace. Useful for small, well-defined code changes.</li>"
                + "</ul>  (" + formatKeyStroke(toggleSearchKs) + ")</html>");

        searchProjectCheckBox = new JCheckBox("Search");
        searchProjectCheckBox.setFocusable(true);

        // Append the shortcut to the tooltip for discoverability
        searchProjectCheckBox.setToolTipText("<html><b>Search:</b><br><ul>"
                + "<li><b>checked:</b> Performs an &quot;agentic&quot; search across your entire project (even files not in the Workspace) to find relevant code</li>"
                + "<li><b>unchecked:</b> Asks using only the Workspace (faster for follow-ups)</li>"
                + "</ul> (" + formatKeyStroke(toggleSearchKs) + ")</html>");

        io.github.jbellis.brokk.gui.util.KeyboardShortcutUtil.registerGlobalShortcut(
                chrome.getFrame().getRootPane(),
                toggleSearchKs,
                "ToggleSearchFirst",
                () -> SwingUtilities.invokeLater(() -> {
                    // Toggle "Search First" when in Answer mode; toggle "Plan First" when in Code mode.
                    if (modeSwitch.isSelected()) {
                        searchProjectCheckBox.doClick();
                    } else {
                        codeCheckBox.doClick();
                    }
                }));

        // Load persisted checkbox states (default to checked)
        var proj = chrome.getProject();
        modeSwitch.setSelected(proj.getInstructionsAskMode());
        codeCheckBox.setSelected(proj.getPlanFirst());
        searchProjectCheckBox.setSelected(proj.getSearch());

        // default stored action: Search (Ask + Search)
        storedAction = ACTION_SEARCH;

        // Toggle listeners update visibility and storedAction
        modeSwitch.addItemListener(e2 -> {
            boolean askMode = modeSwitch.isSelected();
            if (askMode) {
                // Show the ASK card (search checkbox)
                if (optionsPanel != null) {
                    ((CardLayout) optionsPanel.getLayout()).show(optionsPanel, OPTIONS_CARD_ASK);
                }
                searchProjectCheckBox.setEnabled(true);
                // Checked => Search, Unchecked => Answer
                storedAction = searchProjectCheckBox.isSelected() ? ACTION_SEARCH : ACTION_ASK;
            } else {
                // Show the CODE card (plan/code checkbox)
                if (optionsPanel != null) {
                    ((CardLayout) optionsPanel.getLayout()).show(optionsPanel, OPTIONS_CARD_CODE);
                }
                // Enable the Code checkbox only when the project has a Git repository available
                codeCheckBox.setEnabled(chrome.getProject().hasGit());
                // Inverted semantics: checked = Architect (Plan First)
                storedAction = codeCheckBox.isSelected() ? ACTION_ARCHITECT : ACTION_CODE;
            }
            // Update label emphasis
            updateModeLabels();
            refreshModeIndicator();
            try {
                chrome.getProject().setInstructionsAskMode(askMode);
            } catch (Exception ex) {
                logger.warn("Unable to persist instructions mode", ex);
            }
        });

        codeCheckBox.addActionListener(e -> {
            if (!modeSwitch.isSelected()) {
                // Inverted semantics: checked = Architect (Plan First)
                storedAction = codeCheckBox.isSelected() ? ACTION_ARCHITECT : ACTION_CODE;
            }
            proj.setPlanFirst(codeCheckBox.isSelected());
        });

        searchProjectCheckBox.addActionListener(e -> {
            if (modeSwitch.isSelected()) {
                storedAction = searchProjectCheckBox.isSelected() ? ACTION_SEARCH : ACTION_ASK;
            }
            proj.setSearch(searchProjectCheckBox.isSelected());
        });

        // Initial checkbox visibility is handled by the optionsPanel (CardLayout) in buildBottomPanel().

        this.defaultActionButtonBg = UIManager.getColor("Button.default.background");
        // this is when the button is in the blocking state
        this.secondaryActionButtonBg = UIManager.getColor("Button.background");
        // Single Action button (Go/Stop toggle) — rounded visual style via custom painting
        actionButton = new ThemeAwareRoundedButton(
                () -> isActionRunning(), this.secondaryActionButtonBg, this.defaultActionButtonBg);

        KeyStroke submitKs = io.github.jbellis.brokk.util.GlobalUiSettings.getKeybinding(
                "instructions.submit",
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_ENTER, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        actionButton.setToolTipText("Run the selected action" + " (" + formatKeyStroke(submitKs) + ")");
        actionButton.setOpaque(false);
        actionButton.setContentAreaFilled(false);
        actionButton.setFocusPainted(true);
        actionButton.setFocusable(true);
        actionButton.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        actionButton.setRolloverEnabled(true);
        actionButton.addActionListener(e -> onActionButtonPressed());
        actionButton.setBackground(this.defaultActionButtonBg);

        modelSelector = new ModelSelector(chrome);
        modelSelector.selectConfig(chrome.getProject().getCodeModelConfig());
        modelSelector.addSelectionListener(cfg -> chrome.getProject().setCodeModelConfig(cfg));
        // Ensure model selector component is focusable
        modelSelector.getComponent().setFocusable(true);

        // Top Bar (History, Configure Models, Stop) (North)
        JPanel topBarPanel = buildTopBarPanel();
        add(topBarPanel, BorderLayout.NORTH);

        // Center Panel (Command Input + Result) (Center)
        this.centerPanel = buildCenterPanel();
        add(this.centerPanel, BorderLayout.CENTER);

        // Bottom Bar (Mic, Model, Actions) (South)
        JPanel bottomPanel = buildBottomPanel();
        add(bottomPanel, BorderLayout.SOUTH);
        // Ensure initial label bolding matches current mode
        updateModeLabels();
        refreshModeIndicator();

        // Initialize the reference file table and suggestion area
        initializeReferenceFileTable();

        // Initialize and configure the context suggestion timer
        contextSuggestionTimer = new javax.swing.Timer(CONTEXT_SUGGESTION_DELAY, this::triggerContextSuggestion);
        contextSuggestionTimer.setRepeats(false);
        instructionsArea.getDocument().addDocumentListener(new DocumentListener() {
            private void checkAndHandleSuggestions(DocumentEvent e) {
                if (getInstructions().split("\\s+").length >= 2) {
                    // Only restart timer if significant change (not just single character)
                    if (e.getType() == DocumentEvent.EventType.INSERT && e.getLength() > 1) {
                        contextSuggestionTimer.setInitialDelay(CONTEXT_SUGGESTION_DELAY); // Ensure normal delay
                        contextSuggestionTimer.restart();
                    } else if (e.getType() == DocumentEvent.EventType.INSERT) {
                        // For single character inserts, use longer delay
                        contextSuggestionTimer.setInitialDelay(CONTEXT_SUGGESTION_TYPING_DELAY);
                        contextSuggestionTimer.restart();
                    } else if (e.getType() == DocumentEvent.EventType.REMOVE) {
                        contextSuggestionTimer.setInitialDelay(CONTEXT_SUGGESTION_DELAY); // Ensure normal delay
                        contextSuggestionTimer.restart();
                    }
                } else {
                    // Input is blank or too short: stop timer, invalidate generation, reset state, schedule UI clear.
                    contextSuggestionTimer.stop();
                    long myGen = suggestionGeneration.incrementAndGet(); // Invalidate any running/pending task
                    logger.trace(
                            "Input cleared/shortened, stopping timer and invalidating suggestions (gen {})", myGen);

                    // Reset internal state immediately
                    InstructionsPanel.this.lastCheckedInputText = null;
                    InstructionsPanel.this.lastCheckedEmbeddings = null;

                    // Schedule UI update, guarded by generation check
                    SwingUtilities.invokeLater(() -> {
                        if (myGen == suggestionGeneration.get()) {
                            logger.trace("Applying UI clear for gen {}", myGen);
                            referenceFileTable.setValueAt(List.of(), 0, 0);
                            failureReasonLabel.setVisible(false);
                            suggestionCardLayout.show(suggestionContentPanel, "TABLE"); // Show empty table
                        } else {
                            logger.trace(
                                    "Skipping UI clear for gen {} (current gen {})", myGen, suggestionGeneration.get());
                        }
                    });
                }
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                checkAndHandleSuggestions(e);
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                checkAndHandleSuggestions(e);
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                // Not typically fired for plain text components, but handle just in case
                checkAndHandleSuggestions(e);
            }
        });

        // Do not set a global default button on the root pane. This prevents plain Enter
        // from submitting when focus is in other UI components (e.g., history/branch lists).

        // Add this panel as a listener to context changes
        this.contextManager.addContextListener(this);

        // --- Autocomplete Setup ---
        instructionCompletionProvider = new InstructionsCompletionProvider();
        instructionAutoCompletion = new AutoCompletion(instructionCompletionProvider);
        instructionAutoCompletion.setAutoActivationEnabled(false);
        instructionAutoCompletion.setTriggerKey(
                KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, java.awt.event.InputEvent.CTRL_DOWN_MASK));
        instructionAutoCompletion.install(instructionsArea);

        // Buttons start disabled and will be enabled by ContextManager when session loading completes
        disableButtons();
    }

    public UndoManager getCommandInputUndoManager() {
        return commandInputUndoManager;
    }

    public JTextArea getInstructionsArea() {
        return instructionsArea;
    }

    private JTextArea buildCommandInputField() {
        var area = new JTextArea(3, 40);
        // The BorderUtils will now handle the border, including focus behavior and padding.
        BorderUtils.addFocusBorder(area, area);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setRows(3); // Initial rows
        area.setMinimumSize(new Dimension(100, 80));
        area.setEnabled(false); // Start disabled
        area.setText(PLACEHOLDER_TEXT); // Keep placeholder, will be cleared on activation
        area.getDocument().addUndoableEditListener(commandInputUndoManager);
        ((AbstractDocument) area.getDocument()).setDocumentFilter(new AtTriggerFilter());

        // Submit shortcut is handled globally by Chrome.registerGlobalKeyboardShortcuts()

        // Undo/Redo shortcuts are handled globally by Chrome.registerGlobalKeyboardShortcuts()

        // Ctrl/Cmd + V  →  if clipboard has an image, route to WorkspacePanel paste;
        // otherwise, use the default JTextArea paste behaviour.
        var pasteKeyStroke = KeyStroke.getKeyStroke(
                KeyEvent.VK_V, java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        area.getInputMap().put(pasteKeyStroke, "smartPaste");
        area.getActionMap().put("smartPaste", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                var clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                var contents = clipboard.getContents(null);
                boolean imageHandled = false;

                if (contents == null) {
                    return;
                }

                for (var flavor : contents.getTransferDataFlavors()) {
                    try {
                        if (flavor.equals(DataFlavor.imageFlavor)
                                || flavor.getMimeType().startsWith("image/")) {
                            // Re-use existing WorkspacePanel logic
                            chrome.getContextPanel()
                                    .performContextActionAsync(WorkspacePanel.ContextAction.PASTE, List.of());
                            imageHandled = true;
                            break;
                        }
                    } catch (Exception ex) {
                        // Ignore and fall back to default paste handling
                    }
                }

                if (!imageHandled) {
                    area.paste(); // Default text paste
                }
            }
        });

        // Add Shift+Enter shortcut to insert a newline
        var shiftEnter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, java.awt.event.InputEvent.SHIFT_DOWN_MASK);
        area.getInputMap().put(shiftEnter, "insertNewline");
        area.getActionMap().put("insertNewline", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                int caretPosition = area.getCaretPosition();
                area.insert("\n", caretPosition);
            }
        });

        // Override Tab key to shift focus instead of inserting tab character
        var tabKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0);
        area.getInputMap().put(tabKeyStroke, "transferFocus");
        area.getActionMap().put("transferFocus", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                area.transferFocus();
            }
        });

        // Override Shift+Tab key to shift focus backward
        var shiftTabKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_TAB, java.awt.event.InputEvent.SHIFT_DOWN_MASK);
        area.getInputMap().put(shiftTabKeyStroke, "transferFocusBackward");
        area.getActionMap().put("transferFocusBackward", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                area.transferFocusBackward();
            }
        });

        return area;
    }

    private JPanel buildTopBarPanel() {
        // Restored History dropdown alongside branch split button.
        // Replaced history dropdown with compact branch split button in top bar
        JPanel topBarPanel = new JPanel(new BorderLayout(H_GAP, 0));
        topBarPanel.setBorder(BorderFactory.createEmptyBorder(0, H_PAD, 2, H_PAD));

        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        var modelComp = modelSelector.getComponent();

        // Lock control heights to the larger of mic and model selector so one growing won't shrink the other
        var micPref = micButton.getPreferredSize();
        var modelPref = modelComp.getPreferredSize();
        int controlHeight = Math.max(micPref.height, modelPref.height);

        var micDim = new Dimension(controlHeight, controlHeight);
        micButton.setPreferredSize(micDim);
        micButton.setMinimumSize(micDim);
        micButton.setMaximumSize(micDim);

        var modelDim = new Dimension(modelPref.width, controlHeight);
        modelComp.setPreferredSize(modelDim);
        modelComp.setMinimumSize(new Dimension(50, controlHeight));
        modelComp.setMaximumSize(new Dimension(Integer.MAX_VALUE, controlHeight));

        leftPanel.add(micButton);
        leftPanel.add(Box.createHorizontalStrut(H_GAP));
        leftPanel.add(modelComp);

        // Place mic, model, branch dropdown, and history dropdown left-to-right in the top bar
        // Insert small gap, branch split button, then history dropdown so ordering is: mic, model, branch, history.
        leftPanel.add(Box.createHorizontalStrut(H_GAP));

        var cm = chrome.getContextManager();
        var project = chrome.getProject();
        this.branchSplitButton = new SplitButton("No Git");
        branchSplitButton.setToolTipText("Current Git branch — click to create/select branches");
        branchSplitButton.setFocusable(true);

        int branchWidth = 210;
        var branchDim = new Dimension(branchWidth, controlHeight);
        branchSplitButton.setPreferredSize(branchDim);
        branchSplitButton.setMinimumSize(branchDim);
        branchSplitButton.setMaximumSize(branchDim);
        branchSplitButton.setAlignmentY(Component.CENTER_ALIGNMENT);

        // Build a fresh popup menu on demand so the branch list is always up-to-date
        Supplier<JPopupMenu> branchMenuSupplier = () -> {
            var menu = new JPopupMenu();
            try {
                if (project.hasGit()) {
                    IGitRepo repo = project.getRepo();
                    List<String> localBranches;
                    if (repo instanceof GitRepo gitRepo) {
                        localBranches = gitRepo.listLocalBranches();
                    } else {
                        localBranches = List.of();
                    }
                    String current = repo.getCurrentBranch();

                    if (!current.isBlank()) {
                        JMenuItem header = new JMenuItem("Current: " + current);
                        header.setEnabled(false);
                        menu.add(header);
                        menu.add(new JSeparator());
                    }

                    // Local branches
                    for (var b : localBranches) {
                        JMenuItem item = new JMenuItem(b);
                        item.addActionListener(ev -> {
                            // Checkout in background via ContextManager to get spinner/cancel behavior
                            cm.submitExclusiveAction(() -> {
                                try {
                                    IGitRepo r = project.getRepo();
                                    r.checkout(b);
                                    SwingUtilities.invokeLater(() -> {
                                        try {
                                            var currentBranch = r.getCurrentBranch();
                                            var displayBranch = currentBranch.isBlank() ? b : currentBranch;
                                            refreshBranchUi(displayBranch);
                                        } catch (Exception ex) {
                                            logger.debug("Error updating branch UI after checkout", ex);
                                            refreshBranchUi(b);
                                        }
                                        chrome.systemOutput("Checked out: " + b);
                                    });
                                } catch (Exception ex) {
                                    logger.error("Error checking out branch {}", b, ex);
                                    SwingUtilities.invokeLater(
                                            () -> chrome.toolError("Error checking out branch: " + ex.getMessage()));
                                }
                            });
                        });
                        menu.add(item);
                    }

                } else {
                    JMenuItem noRepo = new JMenuItem("No Git repository");
                    noRepo.setEnabled(false);
                    menu.add(noRepo);
                }
            } catch (Exception ex) {
                logger.error("Error building branch menu", ex);
                JMenuItem err = new JMenuItem("Error loading branches");
                err.setEnabled(false);
                menu.add(err);
            }

            // If project has git, add actions
            if (project.hasGit()) {
                menu.addSeparator();

                JMenuItem create = new JMenuItem("Create New Branch...");
                create.addActionListener(ev -> {
                    // Prompt on EDT
                    SwingUtilities.invokeLater(() -> {
                        String name = JOptionPane.showInputDialog(chrome.getFrame(), "New branch name:");
                        if (name == null || name.isBlank()) return;
                        final String proposed = name.strip();
                        cm.submitExclusiveAction(() -> {
                            try {
                                IGitRepo r = project.getRepo();
                                String sanitized = r.sanitizeBranchName(proposed);
                                String source = r.getCurrentBranch();
                                try {
                                    if (r instanceof GitRepo gitRepo) {
                                        // Prefer atomic create+checkout if available on concrete GitRepo
                                        try {
                                            gitRepo.createAndCheckoutBranch(sanitized, source);
                                        } catch (NoSuchMethodError | UnsupportedOperationException nsme) {
                                            // Fallback to create + checkout on GitRepo
                                            gitRepo.createBranch(sanitized, source);
                                            gitRepo.checkout(sanitized);
                                        }
                                    } else {
                                        // Repo implementation doesn't support branch creation via IGitRepo
                                        throw new UnsupportedOperationException(
                                                "Repository implementation does not support branch creation");
                                    }
                                } catch (NoSuchMethodError | UnsupportedOperationException nsme) {
                                    // Re-throw so outer catch displays the error to the user as before
                                    throw nsme;
                                }
                                SwingUtilities.invokeLater(() -> {
                                    try {
                                        refreshBranchUi(sanitized);
                                    } catch (Exception ex) {
                                        logger.debug("Error updating branch UI after branch creation", ex);
                                    }
                                    chrome.systemOutput("Created and checked out: " + sanitized);
                                });
                            } catch (Exception ex) {
                                logger.error("Error creating branch", ex);
                                SwingUtilities.invokeLater(
                                        () -> chrome.toolError("Error creating branch: " + ex.getMessage()));
                            }
                        });
                    });
                });
                menu.add(create);

                JMenuItem refresh = new JMenuItem("Refresh Branches");
                refresh.addActionListener(ev -> {
                    // Menu is rebuilt when shown; simply notify user
                    SwingUtilities.invokeLater(() -> chrome.systemOutput("Branches refreshed"));
                });
                menu.add(refresh);
            }

            return menu;
        };
        branchSplitButton.setMenuSupplier(branchMenuSupplier);

        // Show the popup when the main button area is clicked (treat as a dropdown)
        branchSplitButton.addActionListener(ev -> SwingUtilities.invokeLater(() -> {
            try {
                var menu = branchMenuSupplier.get();
                // Allow theme manager to style/popups as other popups do
                try {
                    chrome.themeManager.registerPopupMenu(menu);
                } catch (Exception e) {
                    logger.debug("Error registering popup menu", e);
                }
                var bsb = requireNonNull(branchSplitButton);
                menu.show(bsb, 0, bsb.getHeight());
            } catch (Exception ex) {
                logger.error("Error showing branch dropdown", ex);
            }
        }));

        // Initialize current branch label and enabled state
        try {
            if (project.hasGit()) {
                IGitRepo repo = project.getRepo();
                String cur = repo.getCurrentBranch();
                if (!cur.isBlank()) {
                    branchSplitButton.setText("branch: " + cur);
                    branchSplitButton.setEnabled(true);
                } else {
                    branchSplitButton.setText("branch: Unknown");
                    branchSplitButton.setEnabled(true);
                }
            } else {
                branchSplitButton.setText("No Git");
                branchSplitButton.setEnabled(false);
            }
        } catch (Exception ex) {
            logger.error("Error initializing branch button", ex);
            branchSplitButton.setText("No Git");
            branchSplitButton.setEnabled(false);
        }

        var historyDropdown = createHistoryDropdown();
        // Make the control itself compact; popup will expand on open
        historyDropdown.setPreferredSize(new Dimension(120, controlHeight));
        historyDropdown.setMinimumSize(new Dimension(120, controlHeight));
        historyDropdown.setMaximumSize(new Dimension(400, controlHeight));
        historyDropdown.setAlignmentY(Component.CENTER_ALIGNMENT);
        leftPanel.add(historyDropdown);
        leftPanel.add(Box.createHorizontalStrut(H_GAP));

        // Add branchSplitButton after the History dropdown
        leftPanel.add(branchSplitButton);

        topBarPanel.add(leftPanel, BorderLayout.WEST);

        return topBarPanel;
    }

    private JPanel buildCenterPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.PAGE_AXIS));

        // Command Input Field
        JScrollPane commandScrollPane = new JScrollPane(instructionsArea);
        commandScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        commandScrollPane.setPreferredSize(new Dimension(600, 80)); // Use preferred size for layout
        commandScrollPane.setMinimumSize(new Dimension(100, 80));

        // Create layered pane with overlay
        this.inputLayeredPane = commandInputOverlay.createLayeredPane(commandScrollPane);
        this.inputLayeredPane.setBorder(new EmptyBorder(0, H_PAD, 0, H_PAD));

        panel.add(buildModeIndicatorPanel()); // Mode badge

        // Add the layered input directly (drawer will host tool panels)
        panel.add(this.inputLayeredPane);

        // Reference-file table will be inserted just below the command input (now layeredPane)
        // by initializeReferenceFileTable()

        return panel;
    }

    /**
     * Initializes the file-reference table that sits directly beneath the command-input field and wires a context-menu
     * that targets the specific badge the mouse is over (mirrors ContextPanel behaviour).
     */
    private void initializeReferenceFileTable() {
        // ----- create the table itself --------------------------------------------------------
        referenceFileTable = new JTable(new javax.swing.table.DefaultTableModel(new Object[] {"File References"}, 1) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return List.class;
            }
        });
        referenceFileTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        // Dynamically set row height based on renderer's preferred size
        referenceFileTable.setRowHeight(TableUtils.measuredBadgeRowHeight(referenceFileTable));

        referenceFileTable.setTableHeader(null); // single-column ⇒ header not needed
        referenceFileTable.setShowGrid(false);
        referenceFileTable
                .getColumnModel()
                .getColumn(0)
                .setCellRenderer(new TableUtils.FileReferencesTableCellRenderer());

        // Clear initial content (it will be populated by context suggestions)
        referenceFileTable.setValueAt(List.of(), 0, 0);

        // ----- context-menu support -----------------------------------------------------------
        referenceFileTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                if (e.isPopupTrigger()) {
                    ContextMenuUtils.handleFileReferenceClick(
                            e, referenceFileTable, chrome, () -> triggerContextSuggestion(null));
                }
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                ContextMenuUtils.handleFileReferenceClick(
                        e, referenceFileTable, chrome, () -> triggerContextSuggestion(null));
            }
        });

        // Clear selection when the table loses focus
        referenceFileTable.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                referenceFileTable.clearSelection();
            }
        });

        // ----- create failure reason label ----------------------------------------------------
        this.failureReasonLabel = new JLabel();
        this.suggestionCardLayout = new CardLayout();
        this.suggestionContentPanel = new JPanel(this.suggestionCardLayout);

        // Configure failureReasonLabel
        failureReasonLabel.setFont(referenceFileTable.getFont()); // Use same font as table/badges
        failureReasonLabel.setBorder(BorderFactory.createEmptyBorder(0, H_PAD, 0, H_PAD));
        failureReasonLabel.setVisible(false); // Initially hidden

        // Configure suggestionContentPanel
        JScrollPane localTableScrollPane = new JScrollPane(referenceFileTable);
        localTableScrollPane.setBorder(BorderFactory.createEmptyBorder());
        localTableScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);
        suggestionContentPanel.add(localTableScrollPane, "TABLE");
        suggestionContentPanel.add(failureReasonLabel, "LABEL");

        // ----- create container panel for content (table/label) -------------------------------
        var suggestionAreaPanel = new JPanel(new BorderLayout(H_GLUE, 0));
        suggestionAreaPanel.setBorder(BorderFactory.createEmptyBorder(V_GLUE, H_PAD, V_GLUE, H_PAD));

        // Only the card layout panel now (Deep Scan button removed)
        suggestionAreaPanel.add(suggestionContentPanel, BorderLayout.CENTER);

        // Apply height constraints to the container panel
        int currentPanelRowHeight = referenceFileTable.getRowHeight();
        int fixedHeight = currentPanelRowHeight + 2;
        suggestionAreaPanel.setPreferredSize(new Dimension(600, fixedHeight));
        suggestionAreaPanel.setMinimumSize(new Dimension(100, fixedHeight));
        suggestionAreaPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, fixedHeight));

        // Insert the container panel beneath the command-input area (index 2)
        centerPanel.add(suggestionAreaPanel, 2);
    }

    // Emphasize selected label by color; dim the non-selected one (no bold to avoid width changes)
    private void updateModeLabels() {
        boolean askMode = modeSwitch.isSelected();

        // Base and dimmed colors (theme-aware via UIManager)
        java.awt.Color base = UIManager.getColor("Label.foreground");
        if (base == null) base = codeModeLabel.getForeground();

        boolean isDark = UIManager.getBoolean("laf.dark");
        java.awt.Color dim = isDark
                ? darkenColor(base, 0.6f) // darken for dark theme
                : lightenColor(base, 0.4f); // lighten for light theme

        // Keep fonts consistent (plain) to prevent layout shifts
        Font baseFont = codeModeLabel.getFont().deriveFont(Font.PLAIN);
        codeModeLabel.setFont(baseFont);
        answerModeLabel.setFont(baseFont);

        if (askMode) {
            codeModeLabel.setForeground(dim);
            answerModeLabel.setForeground(base);
        } else {
            codeModeLabel.setForeground(base);
            answerModeLabel.setForeground(dim);
        }
    }

    private static java.awt.Color lightenColor(java.awt.Color base, float amount) {
        amount = Math.max(0f, Math.min(1f, amount));
        int r = Math.round(base.getRed() + (255 - base.getRed()) * amount);
        int g = Math.round(base.getGreen() + (255 - base.getGreen()) * amount);
        int b = Math.round(base.getBlue() + (255 - base.getBlue()) * amount);
        r = Math.max(0, Math.min(255, r));
        g = Math.max(0, Math.min(255, g));
        b = Math.max(0, Math.min(255, b));
        return new java.awt.Color(r, g, b);
    }

    private static java.awt.Color darkenColor(java.awt.Color base, float factor) {
        factor = Math.max(0f, Math.min(1f, factor));
        int r = Math.round(base.getRed() * factor);
        int g = Math.round(base.getGreen() * factor);
        int b = Math.round(base.getBlue() * factor);
        r = Math.max(0, Math.min(255, r));
        g = Math.max(0, Math.min(255, g));
        b = Math.max(0, Math.min(255, b));
        return new java.awt.Color(r, g, b);
    }

    private JPanel buildModeIndicatorPanel() {
        if (modeIndicatorPanel != null) return modeIndicatorPanel;

        var panel = new JPanel(new FlowLayout(FlowLayout.LEFT, H_GAP, 0));
        panel.setBorder(BorderFactory.createEmptyBorder(0, H_PAD, 2, H_PAD));
        panel.setOpaque(false);

        modeBadge = new JLabel("CODE MODE") {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    Color bg = getBackground();
                    int arc = Math.max(getHeight(), 16);
                    g2.setColor(bg);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
                } finally {
                    g2.dispose();
                }
                super.paintComponent(g);
            }
        };
        modeBadge.setOpaque(false); // we paint background ourselves
        modeBadge.setBorder(BorderFactory.createEmptyBorder(2, 10, 2, 10));
        modeBadge.setFont(
                modeBadge.getFont().deriveFont(Font.BOLD, modeBadge.getFont().getSize2D()));
        // initial colors will be set by refreshModeIndicator()
        panel.add(modeBadge);
        // Hide the mode badge to avoid confusion; keep component in hierarchy so layout indices remain stable
        panel.setVisible(false);

        modeIndicatorPanel = panel;
        return panel;
    }

    private void refreshModeIndicator() {
        boolean askMode = modeSwitch.isSelected();
        boolean isDark = UIManager.getBoolean("laf.dark");

        Color badgeBg = null;
        Color badgeFg = null;
        Color accent = null;

        try {
            if (askMode) {
                badgeBg = ThemeColors.getColor(isDark, "mode_answer_bg");
                badgeFg = ThemeColors.getColor(isDark, "mode_answer_fg");
                accent = ThemeColors.getColor(isDark, "mode_answer_accent");
            } else {
                badgeBg = ThemeColors.getColor(isDark, "mode_code_bg");
                badgeFg = ThemeColors.getColor(isDark, "mode_code_fg");
                accent = ThemeColors.getColor(isDark, "mode_code_accent");
            }
        } catch (Exception ignored) {
            // fallbacks below
        }
        if (badgeBg == null) {
            badgeBg = askMode ? new Color(0x1F6FEB) : new Color(0x2EA043);
        }
        if (badgeFg == null) {
            badgeFg = isDark ? Color.WHITE : new Color(0x0A0A0A);
        }
        if (accent == null) {
            accent = askMode ? new Color(0x1F6FEB) : new Color(0x2EA043);
        }

        if (modeBadge != null) {
            modeBadge.setText(askMode ? "ASK MODE" : "CODE MODE");
            modeBadge.setBackground(badgeBg);
            modeBadge.setForeground(badgeFg);
            modeBadge.repaint();
        }

        if (inputLayeredPane != null) {
            var inner = new EmptyBorder(0, H_PAD, 0, H_PAD);
            var stripe = new MatteBorder(0, 4, 0, 0, accent);
            inputLayeredPane.setBorder(BorderFactory.createCompoundBorder(stripe, inner));
            inputLayeredPane.revalidate();
            inputLayeredPane.repaint();
        }

        actionGroupPanel.setAccentColor(accent);

        if (instructionsTitledBorder != null) {
            instructionsTitledBorder.setTitle(askMode ? "Instructions - Ask" : "Instructions - Code");
            revalidate();
            repaint();
        }
    }

    /** Updates the Project Files drawer title to reflect the current Git branch. Ensures EDT execution. */
    private void updateProjectFilesDrawerTitle(String branchName) {
        var panel = chrome.getProjectFilesPanel();
        if (SwingUtilities.isEventDispatchThread()) {
            GitUiUtil.updatePanelBorderWithBranch(panel, "Project Files", branchName);
        } else {
            SwingUtilities.invokeLater(() -> GitUiUtil.updatePanelBorderWithBranch(panel, "Project Files", branchName));
        }
    }

    /**
     * Public hook to refresh branch UI (branch selector label and Project Files drawer title). Ensures EDT compliance
     * and no-ops if not a git project or selector not initialized.
     */
    public void refreshBranchUi(String branchName) {
        Runnable task = () -> {
            if (!chrome.getProject().hasGit()) {
                return;
            }
            if (branchSplitButton == null) {
                // Selector not initialized (e.g., no Git or UI not yet built) -> no-op
                return;
            }
            branchSplitButton.setText("branch: " + branchName);
            updateProjectFilesDrawerTitle(branchName);

            // Also notify the Git Log tab to refresh and select the current branch
            chrome.updateLogTab();
            chrome.selectCurrentBranchInLogTab();
        };
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    /**
     * Format a KeyStroke into a human-readable short string such as "Ctrl+M" or "Meta+Enter". Falls back to
     * KeyStroke.toString() on error.
     */
    private static String formatKeyStroke(KeyStroke ks) {
        try {
            int modifiers = ks.getModifiers();
            int keyCode = ks.getKeyCode();
            String modText = java.awt.event.InputEvent.getModifiersExText(modifiers);
            String keyText = KeyEvent.getKeyText(keyCode);
            if (modText == null || modText.isBlank()) return keyText;
            return modText + "+" + keyText;
        } catch (Exception e) {
            return ks.toString();
        }
    }

    private JPanel buildBottomPanel() {
        JPanel bottomPanel = new JPanel();
        bottomPanel.setLayout(new BoxLayout(bottomPanel, BoxLayout.LINE_AXIS));
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

        // Action selector group: Code/Answer switch inside a bordered panel
        this.actionGroupPanel = new ActionGroupPanel(codeModeLabel, modeSwitch, answerModeLabel);
        this.actionGroupPanel.setAlignmentY(Component.CENTER_ALIGNMENT);

        // Visually highlight Code/Ask group when the switch gains focus
        modeSwitch.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent e) {
                actionGroupPanel.setAccentColor(new Color(0x1F6FEB));
            }

            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                // Restore mode accent
                refreshModeIndicator();
            }
        });

        bottomPanel.add(this.actionGroupPanel);
        bottomPanel.add(Box.createHorizontalStrut(H_GAP));

        // Dynamic options depending on toggle selection — use a CardLayout so the checkbox occupies a stable slot.
        optionsPanel = new JPanel(new CardLayout());

        // Create a CODE card that contains the Plan First checkbox.
        JPanel codeOptionsPanel = new JPanel();
        codeOptionsPanel.setOpaque(false);
        codeOptionsPanel.setLayout(new BoxLayout(codeOptionsPanel, BoxLayout.LINE_AXIS));
        codeOptionsPanel.setAlignmentY(Component.CENTER_ALIGNMENT);
        codeOptionsPanel.add(codeCheckBox);

        optionsPanel.add(codeOptionsPanel, OPTIONS_CARD_CODE);
        optionsPanel.add(searchProjectCheckBox, OPTIONS_CARD_ASK);

        // Group the card panel so it stays aligned with other toolbar controls.
        Box optionGroup = Box.createHorizontalBox();
        optionGroup.setOpaque(false);
        optionGroup.setAlignmentY(Component.CENTER_ALIGNMENT);
        optionsPanel.setAlignmentY(Component.CENTER_ALIGNMENT);

        int planFixedHeight = Math.max(
                Math.max(actionButton.getPreferredSize().height, actionGroupPanel.getPreferredSize().height), 32);

        // Constrain the card panel height to align with other toolbar controls.
        var optPanelPref = optionsPanel.getPreferredSize();
        optionsPanel.setPreferredSize(new Dimension(optPanelPref.width, planFixedHeight));
        optionsPanel.setMaximumSize(new Dimension(optPanelPref.width, planFixedHeight));
        optionsPanel.setMinimumSize(new Dimension(0, planFixedHeight));
        optionsPanel.setAlignmentY(Component.CENTER_ALIGNMENT);

        // Add the composite card panel; the PLAN button lives inside the CODE card now.
        optionGroup.add(optionsPanel);

        bottomPanel.add(optionGroup);
        bottomPanel.add(Box.createHorizontalStrut(H_GAP));

        // Ensure the initial visible card matches the current mode
        if (optionsPanel != null) {
            ((CardLayout) optionsPanel.getLayout())
                    .show(optionsPanel, modeSwitch.isSelected() ? OPTIONS_CARD_ASK : OPTIONS_CARD_CODE);
        }

        // Flexible space between action controls and Go/Stop
        bottomPanel.add(Box.createHorizontalGlue());

        // Wand button (Magic Ask) on the right
        wandButton.setAlignmentY(Component.CENTER_ALIGNMENT);
        // Size set after fixedHeight is computed below
        bottomPanel.add(wandButton);
        bottomPanel.add(Box.createHorizontalStrut(4));

        // Action button (Go/Stop toggle) on the right
        actionButton.setAlignmentY(Component.CENTER_ALIGNMENT);
        // Make the action button slightly smaller while keeping a fixed minimum height
        int fixedHeight = Math.max(actionButton.getPreferredSize().height, 32);
        var prefSize = new Dimension(64, fixedHeight);
        actionButton.setPreferredSize(prefSize);
        actionButton.setMinimumSize(prefSize);
        actionButton.setMaximumSize(prefSize);
        actionButton.setMargin(new Insets(4, 10, 4, 10));

        // Repaint when focus changes so focus border is visible
        actionButton.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent e) {
                actionButton.repaint();
            }

            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                actionButton.repaint();
            }
        });

        // Size the wand button to match height of action button
        var wandSize = new Dimension(fixedHeight, fixedHeight);
        wandButton.setPreferredSize(wandSize);
        wandButton.setMinimumSize(wandSize);
        wandButton.setMaximumSize(wandSize);

        bottomPanel.add(actionButton);

        return bottomPanel;
    }

    /** Opens the Plan Options: ensures the correct card is visible and focuses the primary control. */
    public void openPlanOptions() {
        SwingUtilities.invokeLater(() -> {
            try {
                boolean askMode = modeSwitch.isSelected();
                if (askMode) {
                    if (optionsPanel != null) {
                        ((CardLayout) optionsPanel.getLayout()).show(optionsPanel, OPTIONS_CARD_ASK);
                    }
                    searchProjectCheckBox.requestFocusInWindow();
                } else {
                    if (optionsPanel != null) {
                        ((CardLayout) optionsPanel.getLayout()).show(optionsPanel, OPTIONS_CARD_CODE);
                    }
                    codeCheckBox.requestFocusInWindow();
                }
                refreshModeIndicator();
            } catch (Exception ex) {
                logger.debug("openPlanOptions failed (non-fatal)", ex);
            }
        });
    }

    @SuppressWarnings("unused")
    private SplitButton createHistoryDropdown() {
        final var placeholder = "History";
        final var noHistory = "(No history items)";

        var project = chrome.getProject();

        var dropdown = new SplitButton(placeholder);
        dropdown.setToolTipText("Select a previous instruction from history");
        dropdown.setFocusable(true);

        // Build popup menu on demand, same pattern as branch button
        Supplier<JPopupMenu> historyMenuSupplier = () -> {
            var menu = new JPopupMenu();
            List<String> historyItems = project.loadTextHistory();

            logger.trace("History items loaded: {}", historyItems.size());
            if (historyItems.isEmpty()) {
                JMenuItem noHistoryItem = new JMenuItem(noHistory);
                noHistoryItem.setEnabled(false);
                menu.add(noHistoryItem);
            } else {
                for (var item : historyItems) {
                    JMenuItem historyMenuItem = new JMenuItem();
                    // Truncate display text but keep full text in tooltip
                    String displayText = item.replace('\n', ' ');
                    if (displayText.length() > 60) {
                        displayText = displayText.substring(0, 57) + "...";
                    }
                    historyMenuItem.setText(displayText);
                    historyMenuItem.setToolTipText(item);

                    historyMenuItem.addActionListener(ev -> {
                        commandInputOverlay.hideOverlay();
                        instructionsArea.setEnabled(true);

                        instructionsArea.setText(item);
                        commandInputUndoManager.discardAllEdits();
                        instructionsArea.requestFocusInWindow();
                    });
                    menu.add(historyMenuItem);
                }
            }
            return menu;
        };

        dropdown.setMenuSupplier(historyMenuSupplier);

        // Show popup when main button area is clicked (same as branch button)
        dropdown.addActionListener(ev -> SwingUtilities.invokeLater(() -> {
            try {
                var menu = historyMenuSupplier.get();
                chrome.themeManager.registerPopupMenu(menu);
                menu.show(dropdown, 0, dropdown.getHeight());
            } catch (Exception ex) {
                logger.error("Error showing history dropdown", ex);
            }
        }));

        return dropdown;
    }

    /**
     * Checks if the current context managed by the ContextManager contains any image fragments.
     *
     * @return true if the top context exists and contains at least one PasteImageFragment, false otherwise.
     */
    private boolean contextHasImages() {
        var contextManager = chrome.getContextManager();
        return contextManager
                .topContext()
                .allFragments()
                .anyMatch(f -> !f.isText() && !f.getType().isOutput());
    }

    /**
     * Shows a modal error dialog informing the user that the required models lack vision support. Offers to open the
     * Model settings tab.
     *
     * @param requiredModelsInfo A string describing the model(s) that lack vision support (e.g., model names).
     */
    private void showVisionSupportErrorDialog(String requiredModelsInfo) {
        String message =
                """
                         <html>The current operation involves images, but the following selected model(s) do not support vision:<br>
                         <b>%s</b><br><br>
                         Please select vision-capable models in the settings to proceed with image-based tasks.</html>
                         """
                        .formatted(requiredModelsInfo);
        Object[] options = {"Open Model Settings", "Cancel"};
        int choice = JOptionPane.showOptionDialog(
                chrome.getFrame(),
                message,
                "Model Vision Support Error",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.ERROR_MESSAGE,
                null, // icon
                options,
                options[0] // Default button (open settings)
                );

        if (choice == JOptionPane.YES_OPTION) { // Open Settings
            SwingUtilities.invokeLater(
                    () -> SettingsDialog.showSettingsDialog(chrome, SettingsGlobalPanel.MODELS_TAB_TITLE));
        }
        // In either case (Settings opened or Cancel pressed), the original action is aborted by returning from the
        // caller.
    }

    /**
     * Centralized model selection from the dropdown with fallback and optional vision check. Returns null if selection
     * fails or vision is required but unsupported.
     */
    private @Nullable StreamingChatModel selectDropdownModelOrShowError(String actionLabel, boolean requireVision) {
        var cm = chrome.getContextManager();
        var models = cm.getService();

        Service.ModelConfig config;
        try {
            config = modelSelector.getModel();
        } catch (IllegalStateException e) {
            chrome.toolError("Please finish configuring your custom model or select a favorite first.");
            return null;
        }

        var selectedModel = models.getModel(config);
        if (selectedModel == null) {
            chrome.toolError("Selected model '" + config.name() + "' is not available with reasoning level "
                    + config.reasoning());
            selectedModel = castNonNull(models.getModel(Service.GPT_5_MINI));
        }

        if (requireVision && contextHasImages() && !models.supportsVision(selectedModel)) {
            showVisionSupportErrorDialog(models.nameOf(selectedModel) + " (" + actionLabel + ")");
            return null;
        }

        return selectedModel;
    }

    // --- Public API ---

    /**
     * Gets the current user input text. If the placeholder is currently displayed, it returns an empty string,
     * otherwise it returns the actual text content.
     */
    public String getInstructions() {
        var v = SwingUtil.runOnEdt(
                () -> {
                    return instructionsArea.getText().equals(PLACEHOLDER_TEXT) ? "" : instructionsArea.getText();
                },
                "");
        return castNonNull(v);
    }

    /**
     * Clears the command input field and ensures the text color is set to the standard foreground. This prevents the
     * placeholder from reappearing inadvertently.
     */
    public void clearCommandInput() {
        SwingUtilities.invokeLater(() -> {
            instructionsArea.setText("");
            commandInputUndoManager.discardAllEdits(); // Clear undo history as well
        });
    }

    public void requestCommandInputFocus() {
        SwingUtilities.invokeLater(instructionsArea::requestFocus);
    }

    /**
     * Toggle between Code and Answer modes by flipping the modeSwitch. This reuses the existing ItemListener on
     * modeSwitch so storedAction, checkbox visibility and labels are updated consistently.
     */
    public void toggleCodeAnswerMode() {
        SwingUtilities.invokeLater(() -> {
            // Flip the checkbox; its ItemListener will update storedAction and UI
            boolean newAsk = !modeSwitch.isSelected();
            modeSwitch.setSelected(newAsk);
            // Ensure labels are updated immediately
            updateModeLabels();
            // Place focus back in the command input for convenience
            requestCommandInputFocus();
        });
    }

    // --- Private Execution Logic ---

    /**
     * Called by the contextSuggestionTimer or external events (like context changes) to initiate a context suggestion
     * task. It increments the generation counter and submits the task to the sequential worker executor.
     */
    private void triggerContextSuggestion(@Nullable ActionEvent e) { // ActionEvent will be null for external triggers
        var goal = getInstructions(); // Capture snapshot on EDT

        // Basic checks before submitting to worker
        if (goal.isBlank()) {
            // The DocumentListener handles clearing
            logger.trace("triggerContextSuggestion called with empty goal, not submitting task.");
            return;
        }

        // Increment generation and submit the task
        long myGen = suggestionGeneration.incrementAndGet();
        if (e == null) { // If triggered externally (e.g., context change)
            forceSuggestions.set(true);
            logger.trace("Forcing suggestion at generation {} due to external trigger", myGen);
        }
        logger.trace("Submitting suggestion task generation {}", myGen);
        suggestionWorker.submit(() -> processInputSuggestions(myGen, goal));
    }

    /**
     * Performs the actual context suggestion logic off the EDT. This method includes checks against the current
     * `suggestionGeneration` to ensure only the latest task proceeds and updates the UI.
     *
     * @param myGen The generation number of this specific task.
     * @param snapshot The input text captured when this task was initiated.
     */
    private void processInputSuggestions(long myGen, String snapshot) {
        logger.trace("Starting suggestion task generation {}", myGen);

        // 0. Initial staleness check
        if (myGen != suggestionGeneration.get()) {
            logger.trace("Task {} is stale (current gen {}), aborting early.", myGen, suggestionGeneration.get());
            showPendingContext(null);
            return;
        }

        boolean currentForceState = forceSuggestions.get(); // Read the state for this task

        // Conditionally skip checks if currentForceState is true
        if (!currentForceState) {
            // 1. Quick literal check
            if (snapshot.equals(lastCheckedInputText)) {
                logger.trace("Task {} input is literally unchanged (not forced), aborting.", myGen);
                showPendingContext(null);
                return;
            }
        } else {
            logger.trace("Task {} is forced, skipping literal check.", myGen);
        }

        // 2. Embedding Model Check (This check MUST run even if forced, as we need the model)
        if (!Brokk.embeddingModelFuture.isDone()) {
            SwingUtilities.invokeLater(() -> showFailureLabel("Waiting for model download"));
            logger.trace("Task {} waiting for model.", myGen);
            return; // Don't proceed further until model is ready
        }
        AbstractModel embeddingModel;
        try {
            embeddingModel = Brokk.embeddingModelFuture.get();
            assert embeddingModel != null;
        } catch (ExecutionException | InterruptedException ex) {
            logger.error("Task {} failed to get embedding model", myGen, ex);
            SwingUtilities.invokeLater(() -> showFailureLabel("Error loading embedding model"));
            return;
        }

        // 3. Staleness check before embedding
        if (myGen != suggestionGeneration.get()) {
            logger.trace("Task {} is stale before embedding, aborting.", myGen);
            showPendingContext(null);
            return;
        }

        // 4. Compute Embeddings
        var chunks = Arrays.stream(snapshot.split("[.\\n]"))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
        float[][] newEmbeddings = chunks.isEmpty()
                ? new float[0][]
                : chunks.stream()
                        .map(chunk -> embeddingModel.embed(chunk, Generator.PoolingType.AVG))
                        .toArray(float[][]::new);

        // 5. Staleness check after embedding
        if (myGen != suggestionGeneration.get()) {
            logger.trace("Task {} is stale after embedding, aborting.", myGen);
            showPendingContext(null);
            return;
        }

        if (!currentForceState) {
            // 6. Semantic Comparison
            boolean isDifferent = isSemanticallyDifferent(snapshot, newEmbeddings);

            if (!isDifferent) {
                logger.trace("Task {} input is semantically similar (not forced), aborting ContextAgent.", myGen);
                showPendingContext(null);
                return;
            }
        } else {
            logger.trace("Task {} is forced, skipping semantic similarity check.", myGen);
        }

        // 8. Run ContextAgent
        logger.debug("Task {} fetching QUICK context recommendations for: '{}'", myGen, snapshot);
        var model = contextManager.getService().quickestModel();
        ContextAgent.RecommendationResult recommendations;
        try {
            ContextAgent agent = new ContextAgent(contextManager, model, snapshot, false);
            recommendations = agent.getRecommendations(false);

            // 10. Process results
            if (!recommendations.success()) {
                logger.debug("Task {} quick context suggestion failed: {}", myGen, recommendations.reasoning());
                showPendingContext(recommendations.reasoning());
                return;
            }

            // Set our snapshot as the new semantic baseline
            this.lastCheckedInputText = snapshot;
            this.lastCheckedEmbeddings = newEmbeddings;

            // process the recommendations
            var fileRefs = recommendations.fragments().stream()
                    .flatMap(f -> f.files().stream()) // No analyzer
                    .distinct()
                    .map(pf -> new FileReferenceData(pf.getFileName(), pf.toString(), pf))
                    .toList();
            if (fileRefs.isEmpty()) {
                logger.debug("Task {} found no relevant files.", myGen);
                showPendingContext("No quick suggestions");
                return;
            }

            // Update the UI with our new recommendations, or save them for the next task to use
            logger.debug("Task {} updating quick reference table with {} suggestions", myGen, fileRefs.size());
            if (myGen == suggestionGeneration.get()) {
                SwingUtilities.invokeLater(() -> showSuggestionsTable(fileRefs));
                pendingQuickContext = null;
            } else {
                pendingQuickContext = fileRefs;
            }
        } catch (InterruptedException ex) {
            // shouldn't happen
            throw new RuntimeException(ex);
        } finally {
            if (currentForceState) {
                forceSuggestions.set(false);
                logger.trace("Task {} cleared forceSuggestions.", myGen);
            }
        }
    }

    private void showPendingContext(@Nullable String failureExplanation) {
        // do this on the serial task thread, before we move to the EDT
        var contextToDisplay = pendingQuickContext;
        pendingQuickContext = null;

        SwingUtilities.invokeLater(() -> {
            if (contextToDisplay != null) {
                showSuggestionsTable(contextToDisplay);
            } else if (failureExplanation != null) {
                showFailureLabel(failureExplanation);
            }
        });
    }

    /**
     * Checks if the new text/embeddings are semantically different from the last processed state
     * (`lastCheckedInputText`, `lastCheckedEmbeddings`).
     */
    private boolean isSemanticallyDifferent(String currentText, float[][] newEmbeddings) {
        if (lastCheckedInputText == null || lastCheckedEmbeddings == null) {
            // First run or state was reset. Treat as different and store the new embeddings.
            logger.debug("New embeddings input is trivially different from empty old");
            return true;
        }

        // Compare lengths
        if (newEmbeddings.length != lastCheckedEmbeddings.length) {
            logger.debug("New embeddings length differs from last checked embeddings length.");
            return true;
        }

        // Compare pairwise cosine similarity
        final float SIMILARITY_THRESHOLD = 0.85f;
        float minSimilarity = Float.MAX_VALUE;
        for (int i = 0; i < newEmbeddings.length; i++) {
            float similarity = cosine(newEmbeddings[i], lastCheckedEmbeddings[i]);
            if (similarity < minSimilarity) {
                minSimilarity = similarity;
            }
            if (similarity < SIMILARITY_THRESHOLD) {
                var msg =
                        """
                          New embeddings similarity = %.3f, triggering recompute

                          # Old text
                          %s

                          # New text
                          %s
                          """
                                .formatted(similarity, lastCheckedInputText, currentText);
                logger.debug(msg);
                return true;
            }
        }

        logger.debug("Minimum similarity was {}", minSimilarity);

        // If lengths match and all similarities are above threshold, it's not different enough.
        // Do NOT update lastCheckedEmbeddings here, keep the previous ones for the next comparison.
        return false;
    }

    /** Helper to show the failure label with a message. */
    private void showFailureLabel(String message) {
        boolean isDark = UIManager.getBoolean("laf.dark");
        failureReasonLabel.setForeground(ThemeColors.getColor(isDark, "badge_foreground"));
        failureReasonLabel.setText(message);
        failureReasonLabel.setVisible(true);
        // tableScrollPane was made local to initializeReferenceFileTable, find it via parent of referenceFileTable
        var scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane.class, referenceFileTable);
        if (scrollPane != null) {
            scrollPane.setVisible(false); // Ensure table scrollpane is hidden
        }
        referenceFileTable.setValueAt(List.of(), 0, 0); // Clear table data
        suggestionCardLayout.show(suggestionContentPanel, "LABEL"); // Show label
    }

    /** Helper to show the suggestions table with file references. */
    private void showSuggestionsTable(List<FileReferenceData> fileRefs) {
        referenceFileTable.setValueAt(fileRefs, 0, 0);
        failureReasonLabel.setVisible(false);
        var scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane.class, referenceFileTable);
        if (scrollPane != null) {
            scrollPane.setVisible(true); // Ensure table scrollpane is visible
        }
        suggestionCardLayout.show(suggestionContentPanel, "TABLE"); // Show table
    }

    /**
     * Executes the core logic for the "Ask" command. This runs inside the Runnable passed to
     * contextManager.submitAction.
     */
    public static TaskResult executeAskCommand(IContextManager cm, StreamingChatModel model, String question) {
        List<ChatMessage> messages;
        try {
            messages = CodePrompts.instance.collectAskMessages(cm, question, model);
        } catch (InterruptedException e) {
            return new TaskResult(
                    cm,
                    "Ask: " + question,
                    cm.getIo().getLlmRawMessages(),
                    Set.of(),
                    new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED));
        }
        var llm = cm.getLlm(model, "Answer: " + question);

        return executeAskCommand(llm, messages, cm, question);
    }

    public static TaskResult executeAskCommand(
            Llm llm, List<ChatMessage> messages, IContextManager cm, String question) {
        // Build and send the request to the LLM
        TaskResult.StopDetails stop = null;
        Llm.StreamingResult response = null;
        try {
            response = llm.sendRequest(messages, true);
        } catch (InterruptedException e) {
            stop = new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED);
        }

        // Determine stop details based on the response
        if (response != null) {
            if (response.error() != null) {
                String explanation = Objects.requireNonNullElse(response.error().getMessage(), "Unknown LLM error");
                stop = new TaskResult.StopDetails(TaskResult.StopReason.LLM_ERROR, explanation);
            } else {
                stop = new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS);
            }
        }

        // construct TaskResult
        requireNonNull(stop);
        return new TaskResult(
                cm,
                "Ask: " + question,
                List.copyOf(cm.getIo().getLlmRawMessages()),
                Set.of(), // Ask never changes files
                stop);
    }

    // --- Action Handlers ---

    public void runArchitectCommand() {
        var goal = getInstructions();
        if (goal.isBlank()) {
            chrome.toolError("Please provide an initial goal or instruction for the Architect");
            return;
        }

        chrome.getProject().addToInstructionsHistory(goal, 20);
        clearCommandInput();

        var project = chrome.getProject();
        var runInWorktree = project.getArchitectRunInWorktree();

        if (runInWorktree) {
            runArchitectInNewWorktree(goal);
        } else {
            // User confirmed options, now submit the actual agent execution to the background.
            runArchitectCommand(goal);
        }
    }

    private void runArchitectInNewWorktree(String originalInstructions) {
        var currentProject = chrome.getProject();
        ContextManager cm = chrome.getContextManager();

        // Start branch name generation task (LLM summarization)
        var branchNameWorker = new ContextManager.SummarizeWorker(cm, originalInstructions, 3);
        branchNameWorker.execute();

        // Add to history of current project (already done by caller if not worktree)
        // No need to clearCommandInput, also done by caller

        // don't use submitAction, we're going to kick off a new Worktree + Chrome and run in that, leaving the original
        // free
        cm.submitExclusiveAction(() -> {
            try {
                chrome.showOutputSpinner("Setting up Git worktree...");

                // Retrieve the generated branch name suggestion from the SummarizeWorker
                String rawBranchNameSuggestion = branchNameWorker.get(); // Blocks until SummarizeWorker is done
                String generatedBranchName = cm.getRepo().sanitizeBranchName(rawBranchNameSuggestion);

                // Check Git availability (original position relative to setup)
                if (!currentProject.hasGit() || !currentProject.getRepo().supportsWorktrees()) {
                    chrome.hideOutputSpinner();
                    chrome.toolError(
                            "Cannot create worktree: Project is not a Git repository or worktrees are not supported.");
                    populateInstructionsArea(originalInstructions); // Restore instructions if setup fails
                    return;
                }

                Path newWorktreePath;
                String actualBranchName;

                MainProject projectForWorktreeSetup = currentProject.getMainProject();
                IGitRepo repo = projectForWorktreeSetup.getRepo();
                if (!(repo instanceof GitRepo mainGitRepo)) {
                    chrome.hideOutputSpinner();
                    chrome.toolError(
                            "Cannot create worktree: Main project repository does not support Git operations.");
                    populateInstructionsArea(originalInstructions);
                    return;
                }
                String sourceBranchForNew =
                        mainGitRepo.getCurrentBranch(); // New branch is created from current branch of main repo

                var setupResult = GitWorktreeTab.setupNewGitWorktree(
                        projectForWorktreeSetup,
                        mainGitRepo,
                        generatedBranchName,
                        true, // Always creating a new branch in this flow
                        sourceBranchForNew);
                newWorktreePath = setupResult.worktreePath();
                actualBranchName = setupResult.branchName();

                chrome.systemOutput("New worktree created at: " + newWorktreePath + " on branch: " + actualBranchName);

                // Define the initial task to run in the new project, using pre-collected options
                Consumer<Chrome> initialArchitectTask = newWorktreeChrome -> {
                    InstructionsPanel newWorktreeIP = newWorktreeChrome.getInstructionsPanel();
                    // Run the architect command directly with the original instructions and determined options
                    newWorktreeIP.runArchitectCommand(originalInstructions);
                };

                MainProject mainProject = (currentProject instanceof MainProject mainProj)
                        ? mainProj
                        : (MainProject) currentProject.getParent();

                new Brokk.OpenProjectBuilder(newWorktreePath)
                        .parent(mainProject)
                        .initialTask(initialArchitectTask)
                        .sourceContextForSession(cm.topContext())
                        .open()
                        .thenAccept(success -> {
                            if (Boolean.TRUE.equals(success)) {
                                chrome.systemOutput("New worktree opened for Architect");
                            } else {
                                chrome.toolError("Failed to open the new worktree project for Architect.");
                                populateInstructionsArea(originalInstructions);
                            }
                        })
                        .exceptionally(ex -> {
                            chrome.toolError("Error opening new worktree project: " + ex.getMessage());
                            populateInstructionsArea(originalInstructions);
                            return null;
                        });
            } catch (InterruptedException e) {
                logger.debug("Architect worktree setup interrupted.", e);
                populateInstructionsArea(originalInstructions);
            } catch (GitAPIException | IOException | ExecutionException ex) {
                chrome.toolError("Error setting up worktree: " + ex.getMessage());
                populateInstructionsArea(originalInstructions);
            } finally {
                chrome.hideOutputSpinner();
            }
        });
    }

    /**
     * Overload for programmatic invocation of Architect agent after options are determined, typically called directly
     * or from the worktree setup.
     *
     * @param goal The user's goal/instructions.
     */
    public Future<TaskResult> runArchitectCommand(String goal) {
        return submitAction(ACTION_ARCHITECT, goal, scope -> {
            var service = chrome.getContextManager().getService();
            var planningModel = service.getModel(Service.GEMINI_2_5_PRO);
            if (planningModel == null) {
                throw new ModelUnavailableException();
            }

            // Determine Code model from the Instructions dropdown
            Service.ModelConfig codeCfg = modelSelector.getModel();
            var codeModel = service.getModel(codeCfg);
            if (codeModel == null) {
                throw new ModelUnavailableException();
            }

            // Proceed with execution using the selected options
            var agent = new ArchitectAgent(chrome.getContextManager(), planningModel, codeModel, goal, scope);
            return agent.execute();
        });
    }

    public void runCodeCommand() {
        var contextManager = chrome.getContextManager();

        // fetch and save model config
        Service.ModelConfig config;
        try {
            config = modelSelector.getModel();
            chrome.getProject().setCodeModelConfig(config);
        } catch (IllegalStateException e) {
            chrome.toolError("Please finish configuring your custom model or select a favorite first.");
            return;
        }
        var model = contextManager.getService().getModel(config);
        if (model == null) {
            chrome.toolError("Selected model '" + config.name() + "' is not available with reasoning level "
                    + config.reasoning());
            model = castNonNull(contextManager.getService().getModel(Service.GPT_5_MINI));
        }

        prepareAndRunCodeCommand(model);
    }

    // Core method to prepare and submit the Code action
    private void prepareAndRunCodeCommand(StreamingChatModel modelToUse) {
        var input = getInstructions();
        if (input.isBlank()) {
            chrome.toolError("Please enter a command or text");
            return;
        }

        var contextManager = chrome.getContextManager();
        var models = contextManager.getService();

        if (contextHasImages() && !models.supportsVision(modelToUse)) {
            showVisionSupportErrorDialog(models.nameOf(modelToUse) + " (Code)");
            return;
        }

        // If Workspace is empty, ask the user how to proceed
        if (chrome.getContextManager().topContext().isEmpty()) {
            String message =
                    "Are you sure you want to code against an empty Workspace? This is the right thing to do if you want to create new source files with no other context. Otherwise, run Search first or manually add context to the Workspace.";
            Object[] options = {"Code", "Search", "Cancel"};
            int choice = JOptionPane.showOptionDialog(
                    chrome.getFrame(),
                    message,
                    "Empty Workspace",
                    JOptionPane.YES_NO_CANCEL_OPTION,
                    JOptionPane.QUESTION_MESSAGE,
                    null,
                    options,
                    options[0]);

            if (choice == 1) { // Search
                runSearchCommand();
                return;
            } else if (choice != 0) { // Cancel or closed dialog
                return;
            }
        }

        chrome.getProject().addToInstructionsHistory(input, 20);
        clearCommandInput();
        // disableButtons() is called by submitAction via chrome.disableActionButtons()
        submitAction(ACTION_CODE, input, () -> {
            var contextManager1 = chrome.getContextManager();

            CodeAgent agent = new CodeAgent(contextManager1, modelToUse);
            var result = agent.runTask(input, Set.of());
            chrome.setSkipNextUpdateOutputPanelOnContextChange(true);
            return result;
        });
    }

    // Public entry point for default Ask model
    public void runAskCommand(String input) {
        final var modelToUse = selectDropdownModelOrShowError("Ask", true);
        if (modelToUse == null) {
            return;
        }
        prepareAndRunAskCommand(modelToUse, input);
    }

    // Core method to prepare and submit the Ask action
    private void prepareAndRunAskCommand(StreamingChatModel modelToUse, String input) {
        if (input.isBlank()) {
            chrome.toolError("Please enter a question");
            return;
        }

        chrome.getProject().addToInstructionsHistory(input, 20);
        clearCommandInput();
        // disableButtons() is called by submitAction via chrome.disableActionButtons()
        submitAction(ACTION_ASK, input, () -> {
            var result = executeAskCommand(contextManager, modelToUse, input);

            // Persist to history regardless of success/failure
            chrome.setSkipNextUpdateOutputPanelOnContextChange(true);
            return result;
        });
    }

    public @Nullable Future<TaskResult> runSearchCommand() {
        var input = getInstructions();
        if (input.isBlank()) {
            chrome.toolError("Please provide a search query");
            return null;
        }
        chrome.getProject().addToInstructionsHistory(input, 20);
        clearCommandInput();

        return executeSearchInternal(input);
    }

    private Future<TaskResult> executeSearchInternal(String query) {
        final var modelToUse = selectDropdownModelOrShowError("Search", true);
        if (modelToUse == null) {
            throw new IllegalStateException("LLM not found, usually this indicates a network error");
        }

        return submitAction(ACTION_SEARCH, query, () -> {
            assert !query.isBlank();

            var cm = chrome.getContextManager();
            SearchAgent agent = new SearchAgent(
                    query, cm, modelToUse, EnumSet.of(SearchAgent.Terminal.ANSWER, SearchAgent.Terminal.TASK_LIST));
            var result = agent.execute();

            chrome.setSkipNextUpdateOutputPanelOnContextChange(true);
            return result;
        });
    }

    public Future<TaskResult> runSearchCommand(String query) {
        assert !query.isBlank();
        return executeSearchInternal(query);
    }

    /**
     * Runs the given task, handling spinner and add-to-history of the TaskResult, including partial result on
     * interruption
     */
    public Future<TaskResult> submitAction(String action, String input, Callable<TaskResult> task) {
        var cm = chrome.getContextManager();
        // Map some actions to a more user-friendly display string for the spinner.
        // We keep the original `action` (used for LLM output / history) unchanged to avoid
        // affecting other subsystems that detect action by name, but present a clearer label
        // to the user while the operation runs.
        String displayAction;
        if (InstructionsPanel.ACTION_ARCHITECT.equals(action)) {
            displayAction = "Code With Plan";
        } else if (InstructionsPanel.ACTION_SEARCH.equals(action)) {
            displayAction = "Ask with Search";
        } else if (InstructionsPanel.ACTION_ASK.equals(action)) {
            displayAction = "Ask";
        } else {
            displayAction = action;
        }

        return cm.submitLlmAction(action, () -> {
            try {
                chrome.showOutputSpinner("Executing " + displayAction + " command...");
                try (var scope = cm.beginTask(input, false)) {
                    var result = task.call();
                    scope.append(result);
                    if (result.stopDetails().reason() == TaskResult.StopReason.INTERRUPTED) {
                        populateInstructionsArea(input);
                    }
                    return result;
                }
            } finally {
                chrome.hideOutputSpinner();
                contextManager.checkBalanceAndNotify();
                notifyActionComplete(action);
            }
        });
    }

    /** Overload that provides a TaskScope to the task body so callers can pass it to agents. */
    public Future<TaskResult> submitAction(
            String action, String input, java.util.function.Function<ContextManager.TaskScope, TaskResult> task) {
        var cm = chrome.getContextManager();
        // need to set the correct parser here since we're going to append to the same fragment during the action
        String finalAction = (action + " MODE").toUpperCase(java.util.Locale.ROOT);
        // Map some actions to a more user-friendly display string for the spinner.
        // We keep the original `finalAction` (used for LLM output / history) unchanged to avoid
        // affecting other subsystems that detect action by name, but present a clearer label
        // to the user while the operation runs.
        String displayAction;
        if (InstructionsPanel.ACTION_ARCHITECT.equals(action)) {
            displayAction = "Code With Plan";
        } else if (InstructionsPanel.ACTION_SEARCH.equals(action)) {
            displayAction = "Ask with Search";
        } else if (InstructionsPanel.ACTION_ASK.equals(action)) {
            displayAction = "Ask";
        } else {
            displayAction = action;
        }

        return cm.submitLlmAction(finalAction, () -> {
            try {
                chrome.showOutputSpinner("Executing " + displayAction + " command...");
                try (var scope = cm.beginTask(input, false)) {
                    var result = task.apply(scope);
                    scope.append(result);
                    if (result.stopDetails().reason() == TaskResult.StopReason.INTERRUPTED) {
                        populateInstructionsArea(input);
                    }
                    return result;
                }
            } finally {
                chrome.hideOutputSpinner();
                contextManager.checkBalanceAndNotify();
                notifyActionComplete(action);
            }
        });
    }

    // Methods to disable and enable buttons.
    void disableButtons() {
        // Disable ancillary controls only; leave the action button alone so it can become "Stop"
        modeSwitch.setEnabled(false);
        codeCheckBox.setEnabled(false);
        searchProjectCheckBox.setEnabled(false);

        // Keep the action button usable for "Stop" while a task is running.
        if (isActionRunning()) {
            actionButton.setIcon(Icons.STOP);
            actionButton.setText(null);
            actionButton.setEnabled(true);
            actionButton.setToolTipText("Cancel the current operation");
            // always use the off red of the light theme
            Color badgeBackgroundColor = ThemeColors.getColor(false, "git_badge_background");
            actionButton.setBackground(badgeBackgroundColor);
        } else {
            // If there is no running action, keep the action button enabled so the user can start an action.
            actionButton.setEnabled(true);
            actionButton.setBackground(defaultActionButtonBg);
        }

        // Wand is disabled while any action is running
        wandButton.setEnabled(!isActionRunning());
    }

    /**
     * Updates the enabled state of all action buttons based on project load status and ContextManager availability.
     * Called when actions complete.
     */
    private void updateButtonStates() {
        boolean gitAvailable = chrome.getProject().hasGit();

        // Toggle
        modeSwitch.setEnabled(true);

        // Checkbox visibility and enablement
        if (!modeSwitch.isSelected()) {
            // Show the CODE card
            if (optionsPanel != null) {
                ((CardLayout) optionsPanel.getLayout()).show(optionsPanel, OPTIONS_CARD_CODE);
            }
            codeCheckBox.setEnabled(gitAvailable);
        } else {
            // Show the ASK card
            if (optionsPanel != null) {
                ((CardLayout) optionsPanel.getLayout()).show(optionsPanel, OPTIONS_CARD_ASK);
            }
            searchProjectCheckBox.setEnabled(true);
        }

        // Action button reflects current running state
        KeyStroke submitKs = io.github.jbellis.brokk.util.GlobalUiSettings.getKeybinding(
                "instructions.submit",
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_ENTER, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        if (isActionRunning()) {
            actionButton.setIcon(Icons.STOP);
            actionButton.setText(null);
            actionButton.setToolTipText("Cancel the current operation");
            actionButton.setBackground(secondaryActionButtonBg);
        } else {
            actionButton.setIcon(Icons.ARROW_WARM_UP);
            actionButton.setText(null);
            actionButton.setToolTipText("Run the selected action" + " (" + formatKeyStroke(submitKs) + ")");
            actionButton.setBackground(defaultActionButtonBg);
        }
        actionButton.setEnabled(true);

        // Enable/disable wand depending on running state
        wandButton.setEnabled(!isActionRunning());

        // Ensure the action button is the root pane's default button so Enter triggers it by default.
        // This mirrors the intended "default" behavior for the Go action.
        // Intentionally avoid setting a root default button to keep Enter key
        // behavior local to the focused component.

        // Ensure storedAction is consistent with current UI
        if (!modeSwitch.isSelected()) {
            // Inverted semantics: checked = Architect (Plan First)
            storedAction = codeCheckBox.isSelected() ? ACTION_ARCHITECT : ACTION_CODE;
        } else {
            // Ask-mode: checked => Search, unchecked => Ask/Answer
            storedAction = searchProjectCheckBox.isSelected() ? ACTION_SEARCH : ACTION_ASK;
        }

        // Keep label emphasis in sync with selected mode
        updateModeLabels();
        refreshModeIndicator();

        chrome.enableHistoryPanel();
    }

    @Override
    public void contextChanged(Context newCtx) {
        // Otherwise, proceed with the normal suggestion logic by submitting a task
        logger.debug("Context changed externally, triggering suggestion check.");
        triggerContextSuggestion(null); // Use null ActionEvent to indicate non-timer trigger
    }

    void enableButtons() {
        // Called when an action completes. Reset buttons based on current CM/project state.
        updateButtonStates();
    }

    private void notifyActionComplete(String actionName) {
        chrome.notifyActionComplete("Action '" + actionName + "' completed.");
    }

    private boolean isActionRunning() {
        return chrome.getContextManager().isLlmTaskInProgress();
    }

    public void onActionButtonPressed() {
        if (isActionRunning()) {
            // Stop action
            chrome.getContextManager().interruptLlmAction();
        } else {
            // Go action
            switch (storedAction) {
                case ACTION_ARCHITECT -> runArchitectCommand();
                case ACTION_CODE -> runCodeCommand();
                case ACTION_SEARCH -> runSearchCommand();
                case ACTION_ASK -> runAskCommand(getInstructions());
                case ACTION_SCAN_PROJECT -> runScanProjectCommand();
                default -> runArchitectCommand();
            }
        }
        // Always return focus to the instructions area to avoid re-triggering with Enter on the button
        requestCommandInputFocus();
    }

    public void populateInstructionsArea(String text) {
        SwingUtilities.invokeLater(() -> {
            // If placeholder is active or area is disabled, activate input first
            if (instructionsArea.getText().equals(PLACEHOLDER_TEXT) || !instructionsArea.isEnabled()) {
                activateCommandInput(); // This enables, clears placeholder, requests focus
            }
            SwingUtilities.invokeLater(() -> {
                instructionsArea.setText(text);
                commandInputUndoManager.discardAllEdits(); // Reset undo history for the repopulated content
                instructionsArea.requestFocusInWindow(); // Ensure focus after text set
                instructionsArea.setCaretPosition(text.length()); // Move caret to end
            });
        });
    }

    /**
     * Hides the command input overlay, enables the input field and deep scan button, clears the placeholder text if
     * present, and requests focus for the input field.
     */
    private void activateCommandInput() {
        commandInputOverlay.hideOverlay(); // Hide the overlay
        // Enable input and deep scan button
        instructionsArea.setEnabled(true);
        // Clear placeholder only if it's still present
        if (instructionsArea.getText().equals(PLACEHOLDER_TEXT)) {
            clearCommandInput();
        }
        instructionsArea.requestFocusInWindow(); // Give it focus
    }

    public void runScanProjectCommand() {
        var goal = getInstructions();
        if (goal.isBlank()) {
            chrome.toolError("Please provide instructions before scanning the project");
            return;
        }

        final var modelToUse = selectDropdownModelOrShowError("Scan Project", true);
        if (modelToUse == null) {
            return;
        }

        chrome.getProject().addToInstructionsHistory(goal, 20);
        clearCommandInput();

        submitAction(ACTION_SCAN_PROJECT, goal, () -> {
            try {
                var cm = chrome.getContextManager();
                var contextAgent = new ContextAgent(cm, modelToUse, goal, true);
                var recommendation = contextAgent.getRecommendations(true);
                var totalTokens = contextAgent.calculateFragmentTokens(recommendation.fragments());
                int finalBudget = cm.getService().getMaxInputTokens(modelToUse) / 2;

                if (totalTokens > finalBudget) {
                    var summary = ContextFragment.getSummary(recommendation.fragments());
                    cm.addVirtualFragment(new ContextFragment.StringFragment(
                            cm,
                            summary,
                            "Summary of Project Scan",
                            recommendation.fragments().stream()
                                    .findFirst()
                                    .map(ContextFragment::syntaxStyle)
                                    .orElseThrow()));
                } else {
                    WorkspaceTools.addToWorkspace(cm, recommendation);
                }
                return new TaskResult(
                        chrome.getContextManager(),
                        ACTION_SCAN_PROJECT + ": " + goal,
                        List.copyOf(chrome.getContextManager().getIo().getLlmRawMessages()),
                        Set.of(),
                        new TaskResult.StopDetails(TaskResult.StopReason.SUCCESS));
            } catch (InterruptedException e) {
                return new TaskResult(
                        chrome.getContextManager(),
                        ACTION_SCAN_PROJECT + ": " + goal,
                        List.copyOf(chrome.getContextManager().getIo().getLlmRawMessages()),
                        Set.of(),
                        new TaskResult.StopDetails(TaskResult.StopReason.INTERRUPTED));
            }
        });
    }

    public VoiceInputButton getVoiceInputButton() {
        return this.micButton;
    }

    /**
     * Returns the currently selected Code model configuration from the model selector. Falls back to a reasonable
     * default if none is available.
     */
    public Service.ModelConfig getSelectedModel() {
        try {
            return modelSelector.getModel();
        } catch (IllegalStateException e) {
            // Fallback to a basic default; Reasoning & Tier defaulted inside ModelConfig
            return new Service.ModelConfig(Service.GPT_5_MINI);
        }
    }

    /**
     * Register a listener to be notified when the model selection in the InstructionsPanel changes. The listener
     * receives the new Service.ModelConfig.
     */
    public void addModelSelectionListener(Consumer<Service.ModelConfig> listener) {
        modelSelector.addSelectionListener(listener);
    }

    /** Returns cosine similarity of two equal-length vectors. */
    private static float cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vectors differ in length");
        }
        if (a.length == 0) {
            throw new IllegalArgumentException("Vectors must have at least one element");
        }

        double dot = 0.0;
        double magA = 0.0;
        double magB = 0.0;

        for (int i = 0; i < a.length; i++) {
            double x = a[i];
            double y = b[i];
            dot += x * y;
            magA += x * x;
            magB += y * y;
        }

        double denominator = Math.sqrt(magA) * Math.sqrt(magB);
        if (denominator == 0.0) {
            throw new IllegalArgumentException("One of the vectors is zero-length");
        }

        return (float) (dot / denominator);
    }

    private static class ThemeAwareRoundedButton extends MaterialButton implements ThemeAware {
        private static final long serialVersionUID = 1L;
        private final Supplier<Boolean> isActionRunning;
        private final Color secondaryActionButtonBg;
        private final Color defaultActionButtonBg;

        public ThemeAwareRoundedButton(
                Supplier<Boolean> isActionRunning, Color secondaryActionButtonBg, Color defaultActionButtonBg) {
            super();
            this.isActionRunning = isActionRunning;
            this.secondaryActionButtonBg = secondaryActionButtonBg;
            this.defaultActionButtonBg = defaultActionButtonBg;
        }

        @Override
        protected void paintComponent(Graphics g) {
            // Paint rounded background (same behavior as previous anonymous class)
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int arc = 12;
                Color bg = getBackground();
                if (!isEnabled()) {
                    Color disabled = UIManager.getColor("Button.disabledBackground");
                    if (disabled != null) bg = disabled;
                } else if (getModel().isPressed()) {
                    bg = bg.darker();
                } else if (getModel().isRollover()) {
                    bg = bg.brighter();
                }
                g2.setColor(bg);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }

        @Override
        protected void paintBorder(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int arc = 12;
                Color borderColor;
                if (isFocusOwner()) {
                    // Use a blue focus color for visibility when focused
                    borderColor = new Color(0x1F6FEB);
                } else {
                    borderColor = UIManager.getColor("Component.borderColor");
                    if (borderColor == null) borderColor = Color.GRAY;
                }
                g2.setColor(borderColor);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public void applyTheme(GuiTheme guiTheme, boolean wordWrap) {
            if (this.isActionRunning.get()) {
                setBackground(this.secondaryActionButtonBg);
            } else {
                setBackground(this.defaultActionButtonBg);
            }
            // Mark for any global reapply mechanism
            // putClientProperty("brokk.primaryButton", true);

            // Update visuals
            // revalidate();
            // repaint();
        }

        /**
         * Backwards-compatible single-argument applyTheme method.
         *
         * <p>Some ThemeAware interface variants (depending on codebase versions) declare a single-argument
         * applyTheme(GuiTheme). Provide this overload so the class fulfills that contract as well.
         */
        @Override
        public void applyTheme(GuiTheme guiTheme) {
            // Delegate to the two-argument variant with a sensible default for wordWrap.
            applyTheme(guiTheme, false);
        }
    }

    private final class AtTriggerFilter extends DocumentFilter {
        private boolean isPopupOpen = false; // Guard against re-entrant calls

        @Override
        public void insertString(FilterBypass fb, int offs, String str, AttributeSet a) throws BadLocationException {
            super.insertString(fb, offs, str, a);
            if (!isPopupOpen) {
                maybeHandleAt(fb, offs + str.length());
            }
        }

        @Override
        public void replace(FilterBypass fb, int offs, int len, String str, AttributeSet a)
                throws BadLocationException {
            super.replace(fb, offs, len, str, a);
            if (!isPopupOpen) {
                maybeHandleAt(fb, offs + str.length());
            }
        }

        private void maybeHandleAt(DocumentFilter.FilterBypass fb, int caretPos) {
            try {
                if (fb.getDocument().getLength() >= caretPos
                        && caretPos > 0
                        && fb.getDocument().getText(caretPos - 1, 1).equals("@")) {
                    // Schedule popup display on EDT
                    SwingUtilities.invokeLater(() -> showAddPopup(caretPos - 1));
                }
            } catch (BadLocationException ignored) {
                // Ignore, means text was changing rapidly
            }
        }

        private void showAddPopup(int atOffset) {
            if (isPopupOpen) {
                return; // Already showing one
            }

            isPopupOpen = true;
            try {
                Rectangle r = instructionsArea.modelToView2D(atOffset).getBounds();
                // Point p = SwingUtilities.convertPoint(instructionsArea, r.x, r.y + r.height, chrome.getFrame()); //
                // Unused variable p

                JPopupMenu popup = AddMenuFactory.buildAddPopup(chrome.getContextPanel());

                // Add action listeners to set the flag when an item is clicked
                for (Component comp : popup.getComponents()) {
                    if (comp instanceof JMenuItem item) {
                        // Get original listeners
                        java.awt.event.ActionListener[] originalListeners = item.getActionListeners();
                        // Remove them to re-wrap
                        for (java.awt.event.ActionListener al : originalListeners) {
                            item.removeActionListener(al);
                        }
                        // Add new listener that removes "@" then calls originals
                        item.addActionListener(actionEvent -> {
                            SwingUtilities.invokeLater(
                                    () -> { // Ensure document modification is on EDT
                                        try {
                                            instructionsArea.getDocument().remove(atOffset, 1);
                                        } catch (BadLocationException ble) {
                                            logger.warn(
                                                    "Could not remove @ symbol after selection in ActionListener", ble);
                                        }
                                    });
                            for (java.awt.event.ActionListener al : originalListeners) {
                                al.actionPerformed(actionEvent);
                            }
                        });
                    }
                }

                popup.addPopupMenuListener(new PopupMenuListener() {
                    @Override
                    public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                        // Unregister listener to avoid memory leaks
                        popup.removePopupMenuListener(this);
                        isPopupOpen = false; // Allow new popups
                        // Removal of "@" is now handled by the JMenuItem's ActionListener
                    }

                    @Override
                    public void popupMenuWillBecomeVisible(PopupMenuEvent e) {}

                    @Override
                    public void popupMenuCanceled(PopupMenuEvent e) {
                        // Unregister listener
                        popup.removePopupMenuListener(this);
                        isPopupOpen = false; // Allow new popups
                        // Do not remove "@" on cancel
                    }
                });

                chrome.themeManager.registerPopupMenu(popup);
                popup.show(instructionsArea, r.x, r.y + r.height);

                // Preselect the first item in the popup
                if (popup.getComponentCount() > 0) {
                    Component firstComponent = popup.getComponent(0);
                    if (firstComponent instanceof JMenuItem) { // Or more generally, MenuElement
                        MenuElement[] path = {popup, (MenuElement) firstComponent};
                        MenuSelectionManager.defaultManager().setSelectedPath(path);
                    }
                }
            } catch (BadLocationException ble) {
                isPopupOpen = false; // Reset guard on error
                logger.warn("Could not show @ popup", ble);
            } catch (Exception ex) {
                isPopupOpen = false; // Reset guard on any other error
                logger.error("Error showing @ popup", ex);
            }
        }
    }

    private class InstructionsCompletionProvider extends DefaultCompletionProvider {
        private final Map<String, List<Completion>> completionCache = new ConcurrentHashMap<>();
        private static final int CACHE_SIZE = 100;

        @Override
        public String getAlreadyEnteredText(JTextComponent comp) {
            Document doc = comp.getDocument();
            int dot = comp.getCaretPosition();
            Element root = doc.getDefaultRootElement();
            int line = root.getElementIndex(dot);
            int lineStart = root.getElement(line).getStartOffset();
            try {
                String lineText = doc.getText(lineStart, dot - lineStart);
                int space = lineText.lastIndexOf(' ');
                int tab = lineText.lastIndexOf('\t');
                int separator = Math.max(space, tab);
                return lineText.substring(separator + 1);
            } catch (BadLocationException e) {
                logger.warn("BadLocationException in getAlreadyEnteredText", e);
                return "";
            }
        }

        @Override
        public List<Completion> getCompletions(JTextComponent comp) {
            String text = getAlreadyEnteredText(comp);
            if (text.isEmpty()) {
                return List.of();
            }

            // Check cache first
            List<Completion> cached = completionCache.get(text);
            if (cached != null) {
                return cached;
            }

            List<Completion> completions;
            if (text.contains("/") || text.contains("\\")) {
                var allFiles = contextManager.getProject().getAllFiles();
                List<ShorthandCompletion> fileCompletions = Completions.scoreShortAndLong(
                        text,
                        allFiles,
                        ProjectFile::getFileName,
                        ProjectFile::toString,
                        f -> 0,
                        f -> new ShorthandCompletion(this, f.getFileName(), f.toString()));
                completions = new ArrayList<>(fileCompletions.stream().limit(50).toList());
            } else {
                var analyzer = contextManager.getAnalyzerWrapper().getNonBlocking();
                if (analyzer == null) {
                    return List.of();
                }
                var symbols = Completions.completeSymbols(text, analyzer);
                completions = symbols.stream()
                        .limit(50)
                        .map(symbol -> (Completion) new ShorthandCompletion(this, symbol.shortName(), symbol.fqName()))
                        .toList();
            }

            // Cache the result
            if (completionCache.size() > CACHE_SIZE) {
                completionCache.clear();
            }
            completionCache.put(text, completions);

            return completions;
        }
    }

    /**
     * Custom focus traversal policy for InstructionsPanel that defines the tab order: instructionsArea → actionButton →
     * modeSwitch → codeCheckBox/searchProjectCheckBox → micButton → modelSelector → historyDropdown → branchSplitButton
     */
    private class InstructionsPanelFocusTraversalPolicy extends FocusTraversalPolicy {
        @Override
        public Component getComponentAfter(Container aContainer, Component aComponent) {
            if (aComponent == instructionsArea) {
                return actionButton;
            } else if (aComponent == actionButton) {
                return modeSwitch;
            } else if (aComponent == modeSwitch) {
                // Return the appropriate checkbox based on current mode
                return modeSwitch.isSelected() ? searchProjectCheckBox : codeCheckBox;
            } else if (aComponent == codeCheckBox || aComponent == searchProjectCheckBox) {
                return micButton;
            } else if (aComponent == micButton) {
                return modelSelector.getComponent();
            } else if (aComponent == modelSelector.getComponent()) {
                // Find history dropdown in the top bar
                return findHistoryDropdown();
            } else if (aComponent == findHistoryDropdown()) {
                // Find branch split button in the top bar
                return findBranchSplitButton();
            } else if (aComponent == findBranchSplitButton()) {
                // Return to main window or next focusable component
                return getNextFocusableComponent();
            }
            return actionButton; // Fallback to action button
        }

        @Override
        public Component getComponentBefore(Container aContainer, Component aComponent) {
            if (aComponent == actionButton) {
                return instructionsArea;
            } else if (aComponent == modeSwitch) {
                return actionButton;
            } else if (aComponent == codeCheckBox || aComponent == searchProjectCheckBox) {
                return modeSwitch;
            } else if (aComponent == micButton) {
                return modeSwitch.isSelected() ? searchProjectCheckBox : codeCheckBox;
            } else if (aComponent == modelSelector.getComponent()) {
                return micButton;
            } else if (aComponent == findHistoryDropdown()) {
                return modelSelector.getComponent();
            } else if (aComponent == findBranchSplitButton()) {
                return findHistoryDropdown();
            } else if (aComponent == getNextFocusableComponent()) {
                return findBranchSplitButton();
            }
            return actionButton; // Fallback to action button
        }

        @Override
        public Component getFirstComponent(Container aContainer) {
            return instructionsArea;
        }

        @Override
        public Component getLastComponent(Container aContainer) {
            return findBranchSplitButton();
        }

        @Override
        public Component getDefaultComponent(Container aContainer) {
            return instructionsArea;
        }

        private Component findHistoryDropdown() {
            // Search for history dropdown in the top bar
            return findComponentInHierarchy(
                    InstructionsPanel.this,
                    comp -> comp instanceof SplitButton splitButton && "History".equals(splitButton.getText()),
                    actionButton);
        }

        private Component findBranchSplitButton() {
            // Search for branch split button in the top bar
            return findComponentInHierarchy(
                    InstructionsPanel.this,
                    comp -> comp instanceof SplitButton splitButton && !"History".equals(splitButton.getText()),
                    actionButton);
        }

        private Component getNextFocusableComponent() {
            // Return the next focusable component in the main window
            Container parent = InstructionsPanel.this.getParent();
            while (parent != null && !(parent instanceof Window)) {
                parent = parent.getParent();
            }
            if (parent instanceof Window) {
                return parent.getFocusTraversalPolicy().getComponentAfter(parent, InstructionsPanel.this);
            }
            return actionButton; // Fallback to action button
        }

        private Component findComponentInHierarchy(
                Container container, java.util.function.Predicate<Component> predicate, Component fallback) {
            for (Component comp : container.getComponents()) {
                if (predicate.test(comp)) {
                    return comp;
                }
                if (comp instanceof Container containerComp) {
                    Component found = findComponentInHierarchy(containerComp, predicate, fallback);
                    if (found != fallback) {
                        return found;
                    }
                }
            }
            return fallback;
        }
    }

    private static class ModelUnavailableException extends RuntimeException {
        public ModelUnavailableException() {
            super("Model is unavailable. Usually this indicates a networking problem.");
        }
    }
}
