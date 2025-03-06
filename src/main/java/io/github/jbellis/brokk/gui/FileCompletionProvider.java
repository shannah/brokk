package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.Completions;
import io.github.jbellis.brokk.RepoFile;
import org.fife.ui.autocomplete.BasicCompletion;
import org.fife.ui.autocomplete.Completion;
import org.fife.ui.autocomplete.DefaultCompletionProvider;

import javax.swing.text.JTextComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Custom CompletionProvider for files that replicates the old logic.
 */
public class FileCompletionProvider extends DefaultCompletionProvider {

    private final Collection<RepoFile> repoFiles;

    public FileCompletionProvider(Collection<RepoFile> repoFiles) {
        super();
        this.repoFiles = repoFiles;
    }

    @Override
    public List<Completion> getCompletions(JTextComponent tc) {
        var input = getAlreadyEnteredText(tc);
        String partialLower = input.toLowerCase();
        Map<String, RepoFile> baseToFullPath = new HashMap<>();
        List<Completion> completions = new ArrayList<>();

        for (RepoFile p : repoFiles) {
            baseToFullPath.put(p.getFileName(), p);
        }

        // Matching base filenames
        baseToFullPath.forEach((base, path) -> {
            if (base.toLowerCase().startsWith(partialLower)) {
                completions.add(createCompletion(path));
            }
        });

        // Camel-case completions
        baseToFullPath.forEach((base, path) -> {
            String capitals = Completions.extractCapitals(base);
            if (capitals.toLowerCase().startsWith(partialLower)) {
                completions.add(createCompletion(path));
            }
        });

        // Matching full paths
        for (RepoFile p : repoFiles) {
            if (p.toString().toLowerCase().startsWith(partialLower)) {
                completions.add(createCompletion(p));
            }
        }

        return completions;
    }

    private Completion createCompletion(RepoFile file) {
        String replacement = file.toString() + " ";
        return new BasicCompletion(this, replacement, file.getFileName(), file.toString());
    }
}
