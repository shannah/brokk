package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.Completions;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.CodeUnitType;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.Project;
import org.fife.ui.autocomplete.AutoCompletion;
import org.fife.ui.autocomplete.Completion;
import org.fife.ui.autocomplete.CompletionProvider;
import org.fife.ui.autocomplete.DefaultCompletionProvider;
import org.fife.ui.autocomplete.ShorthandCompletion;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Set;

/**
 * A dialog for selecting Java symbols (classes and members) with autocomplete.
 */
public class SymbolSelectionDialog extends JDialog {

    private final Project project;
    private final JTextField symbolInput;
    private final AutoCompletion autoCompletion;
    private final JButton okButton;
    private final JButton cancelButton;
    private final IAnalyzer analyzer;
    private final Set<CodeUnitType> typeFilter;

    // The selected symbol
    private String selectedSymbol = null;

    // Indicates if the user confirmed the selection
    private boolean confirmed = false;

    public SymbolSelectionDialog(Frame parent, Project project, String title, Set<CodeUnitType> typeFilter) {
        super(parent, title, true); // modal dialog
        assert parent != null;
        assert project != null;
        assert title != null;
        assert typeFilter != null;

        this.project = project;
        this.analyzer = project.getAnalyzer();
        this.typeFilter = typeFilter;
        assert analyzer != null;

        JPanel mainPanel = new JPanel(new BorderLayout(8, 8));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // Build text input with autocomplete at the top
        symbolInput = new JTextField(30);
        var provider = createSymbolCompletionProvider(analyzer);
        autoCompletion = new AutoCompletion(provider);
        // Trigger with Ctrl+Space
        autoCompletion.setAutoActivationEnabled(false);
        autoCompletion.setTriggerKey(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK));
        autoCompletion.install(symbolInput);

        JPanel inputPanel = new JPanel(new BorderLayout());
        inputPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        inputPanel.add(symbolInput, BorderLayout.CENTER);
        String autocompleteText;
        if (typeFilter.equals(CodeUnitType.ALL)) {
            autocompleteText = "Ctrl-space to autocomplete class and member names";
        } else {
            assert typeFilter.size() == 1 : "Expected exactly one type filter";
            var type = typeFilter.iterator().next();
            autocompleteText = "Ctrl-space to autocomplete " + type.toString().toLowerCase() + " names";
        }
        inputPanel.add(new JLabel(autocompleteText), BorderLayout.SOUTH);
        mainPanel.add(inputPanel, BorderLayout.NORTH);

        // Buttons at the bottom
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        okButton = new JButton("OK");
        okButton.addActionListener(e -> doOk());
        cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> {
            confirmed = false;
            dispose();
        });
        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        mainPanel.add(buttonPanel, BorderLayout.SOUTH);

        // Handle escape key to close dialog
        KeyStroke escapeKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
        getRootPane().registerKeyboardAction(e -> {
            confirmed = false;
            selectedSymbol = null;
            dispose();
        }, escapeKeyStroke, JComponent.WHEN_IN_FOCUSED_WINDOW);

        // Set OK as the default button (responds to Enter key)
        getRootPane().setDefaultButton(okButton);

        // Add a tooltip to indicate Enter key functionality
        symbolInput.setToolTipText("Enter a class or member name and press Enter to confirm");
        
        setContentPane(mainPanel);
        pack();
        setLocationRelativeTo(parent);
    }

    /**
     * Create the symbol completion provider using Completions.completeClassesAndMembers
     */
    private CompletionProvider createSymbolCompletionProvider(IAnalyzer analyzer) {
        return new SymbolCompletionProvider(analyzer, typeFilter);
    }

    /**
     * When OK is pressed, get the symbol from the text input.
     */
    private void doOk() {
        confirmed = true;
        selectedSymbol = null;

        String typed = symbolInput.getText().trim();
        if (!typed.isEmpty()) {
            selectedSymbol = typed;
        }
        dispose();
    }

    /**
     * Return true if user confirmed the selection.
     */
    public boolean isConfirmed() {
        return confirmed;
    }

    /**
     * Return the selected symbol or null if none.
     */
    public String getSelectedSymbol() {
        return selectedSymbol;
    }

    /**
     * A completion provider for Java classes and members using Completions.completeClassesAndMembers
     */
    public class SymbolCompletionProvider extends DefaultCompletionProvider {

        private final IAnalyzer analyzer;
        private final Set<CodeUnitType> typeFilter;
    
        public SymbolCompletionProvider(IAnalyzer analyzer, Set<CodeUnitType> typeFilter) {
            this.analyzer = analyzer;
            this.typeFilter = typeFilter;
        }
    
        @Override
        public List<Completion> getCompletions(JTextComponent comp) {
            String text = comp.getText();
            int caretPosition = comp.getCaretPosition();
    
            // If the caret is not at the end, adjust the text
            if (caretPosition < text.length()) {
                text = text.substring(0, caretPosition);
            }
    
            // Get completions using the brokk Completions utility
            var completions = analyzer == null
                            ? List.<CodeUnit>of()
                            : Completions.completeClassesAndMembers(text, analyzer);

            // Convert to RSTA completions, filtering by the requested types
            var L = completions.stream()
                    .filter(c -> typeFilter.contains(c.kind()))
                    .map(c -> (Completion) new ShorthandCompletion(this, c.shortName(), c.fqName()))
                    .toList();

            if (L.isEmpty()) {
                autoCompletion.setShowDescWindow(false);
                return L;
            }

            // Dynamically size the description window based on the longest shortNae
            var tooltipFont = UIManager.getFont("ToolTip.font");
            var fontMetrics = symbolInput.getFontMetrics(tooltipFont);
            int maxWidth = L.stream()
                    .mapToInt(c -> fontMetrics.stringWidth(c.getInputText()))
                    .max()
                    .orElseThrow();
            // this doesn't seem to work at all, maybe it's hardcoded at startup
            autoCompletion.setChoicesWindowSize(maxWidth + 10, 3 * fontMetrics.getHeight() + 10); // 5px margin on each side

            autoCompletion.setShowDescWindow(true);
            int maxDescWidth = L.stream()
                    .mapToInt(c -> fontMetrics.stringWidth(c.getReplacementText()))
                    .max()
                    .orElseThrow();
            // Desc uses a different (monospaced) font but I'm not sure how to infer which
            // So, hack in a 1.2 factor
            autoCompletion.setDescriptionWindowSize((int) (1.2 * maxDescWidth + 10), 3 * fontMetrics.getHeight() + 10);
            return L;
        }
    }
}
