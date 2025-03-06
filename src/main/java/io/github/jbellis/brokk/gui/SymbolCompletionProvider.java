package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.Analyzer;
import io.github.jbellis.brokk.Completions;
import org.fife.ui.autocomplete.BasicCompletion;
import org.fife.ui.autocomplete.Completion;
import org.fife.ui.autocomplete.DefaultCompletionProvider;
import javax.swing.text.JTextComponent;

import java.util.List;

/**
 * A completion provider for Java classes and members using Completions.completeClassesAndMembers
 */
public class SymbolCompletionProvider extends DefaultCompletionProvider {

    private final Analyzer analyzer;

    public SymbolCompletionProvider(Analyzer analyzer) {
        this.analyzer = analyzer;
        setAutoActivationRules(false, ".");
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
        List<String> completions = Completions.completeClassesAndMembers(text, analyzer, true);
        
        // Convert to RSTA completions
        return completions.stream()
                .map(c -> (Completion) new BasicCompletion(this, c))
                .toList();
    }
}
