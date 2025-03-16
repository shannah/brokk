package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.CodeUnit;
import io.github.jbellis.brokk.Completions;
import io.github.jbellis.brokk.IAnalyzer;
import org.fife.ui.autocomplete.BasicCompletion;
import org.fife.ui.autocomplete.Completion;
import org.fife.ui.autocomplete.DefaultCompletionProvider;
import javax.swing.text.JTextComponent;

import java.util.List;

/**
 * A completion provider for Java classes and members using Completions.completeClassesAndMembers
 */
public class SymbolCompletionProvider extends DefaultCompletionProvider {

    private final IAnalyzer analyzer;

    public SymbolCompletionProvider(IAnalyzer analyzer) {
        this.analyzer = analyzer;
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

        // Convert to RSTA completions
        return completions.stream()
                .map(c -> (Completion) new BasicCompletion(this, c.fqName()))
                .toList();
    }
}
