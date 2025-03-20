package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.Completions;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.CodeUnitType;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
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
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * A reusable panel for selecting Java symbols (classes and members) with autocomplete.
 */
public class SymbolSelectionPanel extends JPanel {

    private final JTextField symbolInput;
    private final AutoCompletion autoCompletion;
    private final IAnalyzer analyzer;
    private final Set<CodeUnitType> typeFilter;
    private final int maxResults;

    public SymbolSelectionPanel(IAnalyzer analyzer, Set<CodeUnitType> typeFilter) {
        this(analyzer, typeFilter, Integer.MAX_VALUE);
    }

    public SymbolSelectionPanel(IAnalyzer analyzer, Set<CodeUnitType> typeFilter, int maxResults) {
        super(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        
        this.analyzer = analyzer;
        this.typeFilter = typeFilter;
        this.maxResults = maxResults;
        assert analyzer != null;
        assert typeFilter != null;

        // Build text input with autocomplete at the top
        symbolInput = new JTextField(30);
        var provider = createSymbolCompletionProvider(analyzer);
        autoCompletion = new AutoCompletion(provider);
        // Trigger with Ctrl+Space (Always. On Mac cmd-space is Spotlight)
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
        add(inputPanel, BorderLayout.CENTER);

        // Set tooltip
        symbolInput.setToolTipText("Enter a class or member name");
    }

    /**
     * Create the symbol completion provider using Completions.completeClassesAndMembers
     */
    private CompletionProvider createSymbolCompletionProvider(IAnalyzer analyzer) {
        return new SymbolCompletionProvider(analyzer, typeFilter);
    }

    /**
     * Get the text from the input field
     */
    public String getSymbolText() {
        return symbolInput.getText().trim();
    }

    /**
     * Set the text for the input field
     */
    public void setSymbolText(String text) {
        symbolInput.setText(text);
    }

    /**
     * Get the text field component for setting default button actions
     */
    public JTextField getInputField() {
        return symbolInput;
    }

    public void addSymbolSelectionListener(Runnable onChange) {
        symbolInput.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                onChange.run();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                onChange.run();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                onChange.run();
            }
        });
    }

    /**
     * A completion provider for Java classes and members using Completions.completeClassesAndMembers
     */
    public class SymbolCompletionProvider extends DefaultCompletionProvider {

        private final IAnalyzer analyzer;
        private final Set<CodeUnitType> typeFilter;
        private final int maxResults;

        public SymbolCompletionProvider(IAnalyzer analyzer, Set<CodeUnitType> typeFilter) {
            this.analyzer = analyzer;
            this.typeFilter = typeFilter;
            this.maxResults = SymbolSelectionPanel.this.maxResults;
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
                    .limit(maxResults)
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
