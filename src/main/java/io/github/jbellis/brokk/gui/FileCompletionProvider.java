package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.Completions;
import io.github.jbellis.brokk.RepoFile;
import io.github.jbellis.brokk.GitRepo;
import org.fife.ui.autocomplete.*;
import org.fife.ui.autocomplete.CompletionProvider;
import org.fife.ui.autocomplete.DefaultCompletionProvider;

import javax.swing.*;
import javax.swing.tree.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

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
        // Use the entire text as the partial string (you can refine this if needed)
        String input = tc.getText().trim();
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
        // The replacement text is the full path,
        // summary is the base filename, and description is also the full path.
        String replacement = file.toString();
        String summary = file.getFileName();
        return new BasicCompletion(this, replacement, summary, file.toString());
    }
}
