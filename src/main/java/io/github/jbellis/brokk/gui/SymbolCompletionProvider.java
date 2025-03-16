package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.CodeUnitType;
import java.util.Set;

import io.github.jbellis.brokk.Completions;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
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
    private final Set<CodeUnitType> typeFilter;

    public SymbolCompletionProvider(IAnalyzer analyzer) {
        this(analyzer, Set.of(CodeUnitType.CLASS, FUNCTION, FIELD));
    }
    
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
        return completions.stream()
                .filter(c -> typeFilter.contains(c.kind()))
                .map(c -> (Completion) new BasicCompletion(this, c.fqName()))
                .toList();
    }
}
