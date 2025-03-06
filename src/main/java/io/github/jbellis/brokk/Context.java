package io.github.jbellis.brokk;

import com.google.common.collect.Streams;
import dev.langchain4j.data.message.ChatMessage;
import io.github.jbellis.brokk.ContextFragment.AutoContext;
import io.github.jbellis.brokk.ContextFragment.SkeletonFragment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Encapsulates all state that will be sent to the model (prompts, filename context, conversation history).
 */
public class Context {
    public static final int MAX_AUTO_CONTEXT_FILES = 100;

    final AnalyzerWrapper analyzer;
    final List<ContextFragment.RepoPathFragment> editableFiles;
    final List<ContextFragment.PathFragment> readonlyFiles;
    final List<ContextFragment.VirtualFragment> virtualFragments;

    final AutoContext autoContext;
    final int autoContextFileCount;
    /** history messages are unusual because they are updated in place.  See comments to addHistory and clearHistory */
    final List<ChatMessage> historyMessages;

    /** backup of original contents for /undo, does not carry forward to Context children */
    final Map<RepoFile, String> originalContents;
    
    /** LLM output text, if this context was created from an LLM interaction */
    final String textarea;

    /** description of the action that created this context */
    final String action;

    /**
     * Default constructor, with empty files/fragments and autoContext on, and a default of 5 files.
     */
    public Context(AnalyzerWrapper analyzer, int autoContextFileCount) {
        this(analyzer, List.of(), List.of(), List.of(), AutoContext.EMPTY, autoContextFileCount, new ArrayList<>(), Map.of(), null, "Welcome to Brokk");
    }

    private Context(
            AnalyzerWrapper analyzer,
            List<ContextFragment.RepoPathFragment> editableFiles,
            List<ContextFragment.PathFragment> readonlyFiles,
            List<ContextFragment.VirtualFragment> virtualFragments,
            AutoContext autoContext,
            int autoContextFileCount,
            List<ChatMessage> historyMessages,
            Map<RepoFile, String> originalContents,
            String textarea,
            String action
    ) {
        assert analyzer != null;
        assert autoContext != null;
        assert autoContextFileCount >= 0;
        assert action != null;
        this.analyzer = analyzer;
        this.editableFiles = List.copyOf(editableFiles);
        this.readonlyFiles = List.copyOf(readonlyFiles);
        this.virtualFragments = List.copyOf(virtualFragments);
        this.autoContext = autoContext;
        this.autoContextFileCount = autoContextFileCount;
        this.historyMessages = historyMessages;
        this.originalContents = originalContents;
        this.textarea = textarea;
        this.action = action;
    }

    /**
     * Creates a new Context with an additional set of editable files. Rebuilds autoContext if toggled on.
     */
    public Context addEditableFiles(Collection<ContextFragment.RepoPathFragment> paths) {
        var toAdd = paths.stream().filter(fragment -> !editableFiles.contains(fragment)).toList();
        if (toAdd.isEmpty()) {
            return this;
        }
        var newEditable = new ArrayList<>(editableFiles);
        newEditable.addAll(toAdd);
        
        String actionDetails = toAdd.stream()
                .map(ContextFragment::description)
                .collect(Collectors.joining(", "));
        String action = "Edit: " + actionDetails;
        return withFragments(newEditable, readonlyFiles, virtualFragments, action);
    }

    public Context addReadonlyFiles(Collection<ContextFragment.PathFragment> paths) {
        var toAdd = paths.stream().filter(fragment -> !readonlyFiles.contains(fragment)).toList();
        if (toAdd.isEmpty()) {
            return this;
        }
        List<ContextFragment.PathFragment> newReadOnly = new ArrayList<>(readonlyFiles);
        newReadOnly.addAll(toAdd);
        
        String actionDetails = toAdd.stream()
                .map(ContextFragment::description)
                .collect(Collectors.joining(", "));
        String action = "Read: " + actionDetails;
        return withFragments(editableFiles, newReadOnly, virtualFragments, action);
    }

    public Context removeEditableFile(ContextFragment.PathFragment fragment) {
        return removeEditableFiles(List.of(fragment));
    }

    public Context removeEditableFiles(List<ContextFragment.PathFragment> fragments) {
        var newEditable = new ArrayList<>(editableFiles);
        newEditable.removeAll(fragments);
        if (newEditable.equals(editableFiles)) {
            return this;
        }
        
        String actionDetails = fragments.stream()
                .map(ContextFragment::description)
                .collect(Collectors.joining(", "));
        String action = "Removed: " + actionDetails;
        return withFragments(newEditable, readonlyFiles, virtualFragments, action);
    }

    public Context removeReadonlyFiles(List<? extends ContextFragment.PathFragment> fragments) {
        List<ContextFragment.PathFragment> newReadOnly = new ArrayList<>(readonlyFiles);
        newReadOnly.removeAll(fragments);
        if (newReadOnly.equals(readonlyFiles)) {
            return this;
        }
        
        String actionDetails = fragments.stream()
                .map(ContextFragment::description)
                .collect(Collectors.joining(", "));
        String action = "Removed: " + actionDetails;
        return withFragments(editableFiles, newReadOnly, virtualFragments, action);
    }

    public Context removeVirtualFragments(List<? extends ContextFragment.VirtualFragment> fragments) {
        var newFragments = new ArrayList<>(virtualFragments);
        newFragments.removeAll(fragments);
        if (newFragments.equals(virtualFragments)) {
            return this;
        }
        
        String actionDetails = fragments.stream()
                .map(ContextFragment::description)
                .collect(Collectors.joining(", "));
        String action = "Removed: " + actionDetails;
        return withFragments(editableFiles, readonlyFiles, newFragments, action);
    }
    
    public Context removeReadonlyFile(ContextFragment.PathFragment path) {
        return removeReadonlyFiles(List.of(path));
    }

    public Context addVirtualFragment(ContextFragment.VirtualFragment fragment) {
        var newFragments = new ArrayList<>(virtualFragments);
        newFragments.add(fragment);
        
        String fragmentText;
        fragmentText = fragment.text();

        String action = "Added: " + fragmentText;
        return withFragments(editableFiles, readonlyFiles, newFragments, action);
    }

    public Context addSearchFragment(ContextFragment.VirtualFragment fragment, String query, String llmOutputText) {
        var newFragments = new ArrayList<>(virtualFragments);
        newFragments.add(fragment);
        return new Context(analyzer, editableFiles, readonlyFiles, newFragments, autoContext, autoContextFileCount, historyMessages, Map.of(), llmOutputText, "Search: " + query);
    }

    public Context convertAllToReadOnly() {
        List<ContextFragment.PathFragment> newReadOnly = new ArrayList<>(readonlyFiles);
        String actionDetails = editableFiles.stream()
                .map(ContextFragment::description)
                .collect(Collectors.joining(", "));
        newReadOnly.addAll(editableFiles);
        
        String action = "Converted to readonly: " + actionDetails;
        
        return withFragments(List.of(), newReadOnly, virtualFragments, action);
    }

    public Context removeBadFragment(ContextFragment f) {
        if (f instanceof ContextFragment.PathFragment pf) {
            var inEditable = editableFiles.contains(pf);
            var inReadonly = readonlyFiles.contains(pf);
            
            if (inEditable) {
                var newEditable = new ArrayList<>(editableFiles);
                newEditable.remove(pf);
                return withFragments(newEditable, readonlyFiles, virtualFragments,
                                     "Removed unreadable: " + pf.description());
            } else if (inReadonly) {
                var newReadonly = new ArrayList<>(readonlyFiles);
                newReadonly.remove(pf);
                return withFragments(editableFiles, newReadonly, virtualFragments,
                                     "Removed unreadable: " + pf.description());
            }
            return this;
        } else if (f instanceof ContextFragment.VirtualFragment vf) {
            var newFragments = new ArrayList<>(virtualFragments);
            if (newFragments.remove(vf)) {
                return withFragments(editableFiles, readonlyFiles, newFragments,
                                     "Removed unreadable: " + vf.description());
            }
            return this;
        } else {
            throw new IllegalArgumentException("Unknown fragment type: " + f);
        }
    }

    /**
     * Sets how many files are pulled out of pagerank results. Uses 2*n in the pagerank call to
     * account for any out-of-project exclusions. Rebuilds autoContext if toggled on.
     */
    public Context setAutoContextFiles(int fileCount) {
        String action;
        if (fileCount == 0 && autoContextFileCount > 0) {
            action = "Disabled auto-context";
        } else if (fileCount > 0 && autoContextFileCount == 0) {
            action = "Enabled auto-context of " + fileCount + " files";
        } else if (fileCount > 0) {
            action = "Auto-context size -> " + fileCount;
        } else {
            // No change in state - auto-context remains disabled
            return this;
        }

        var newContext = new Context(analyzer, editableFiles, readonlyFiles, virtualFragments, autoContext, fileCount, historyMessages, Map.of(), null, null);
        AutoContext newAutoContext = fileCount > 0 ? newContext.buildAutoContext() : AutoContext.DISABLED;
        
        return new Context(
                analyzer,
                editableFiles,
                readonlyFiles,
                virtualFragments,
                newAutoContext,
                fileCount,
                historyMessages,
                Map.of(),
                null,
                action
        );
    }

    /**
     * 1) Gather all classes from each fragment.
     * 2) Compute PageRank with those classes as seeds, requesting up to 2*MAX_AUTO_CONTEXT_FILES
     * 3) Build a multiline skeleton text for the top autoContextFileCount results
     * 4) Return the new AutoContext instance
     */
    private AutoContext buildAutoContext() {
        if (!isAutoContextEnabled()) {
            return AutoContext.DISABLED;
        }
        
        // Collect ineligible classnames from fragments not eligible for auto-context
        var ineligibleSources = Streams.concat(editableFiles.stream(), readonlyFiles.stream(), virtualFragments.stream())
                .filter(f -> !f.isEligibleForAutoContext())
                .flatMap(f -> f.sources(analyzer.get()).stream())
                .collect(Collectors.toSet());

        // Collect initial seeds
        var weightedSeeds = new HashMap<String, Double>();
        // editable files have a weight of 1.0, each
        editableFiles.stream().flatMap(f -> f.sources(analyzer.get()).stream()).forEach(unit -> {
            weightedSeeds.put(unit.reference(), 1.0);
        });
        // everything else splits a weight of 1.0
        Streams.concat(readonlyFiles.stream(), virtualFragments.stream())
                .flatMap(f -> f.sources(analyzer.get()).stream())
                .forEach(unit ->
        {
            weightedSeeds.merge(unit.reference(), 1.0 / (readonlyFiles.size() + virtualFragments.size()), Double::sum);
        });

        // If no seeds, we can't compute pagerank
        if (weightedSeeds.isEmpty()) {
            return AutoContext.EMPTY;
        }

        var pagerankResults = AnalyzerWrapper.combinedPageRankFor(analyzer.get(), weightedSeeds);

        // build skeleton lines
        var skeletons = new ArrayList<SkeletonFragment>();
        for (var fqName : pagerankResults) {
            // Check if the class or its parent is in ineligible classnames
            boolean eligible = !(ineligibleSources.contains(CodeUnit.cls(fqName))
                    || (fqName.contains("$") && ineligibleSources.contains(CodeUnit.cls(fqName.substring(0, fqName.indexOf('$'))))));
            
            if (eligible) {
                var opt = analyzer.get().getSkeleton(fqName);
                if (opt.isDefined()) {
                    var shortName = fqName.substring(fqName.lastIndexOf('.') + 1);
                    skeletons.add(new SkeletonFragment(List.of(shortName), Set.of(CodeUnit.cls(fqName)), opt.get()));
                }
            }
            if (skeletons.size() >= autoContextFileCount) {
                break;
            }
        }
        if (skeletons.isEmpty()) {
            return AutoContext.EMPTY;
        }

        return new AutoContext(skeletons);
    }

    // ---------------------------------------------------------
    // Accessors
    // ---------------------------------------------------------

    public Stream<ContextFragment.RepoPathFragment> editableFiles() {
        return editableFiles.stream();
    }

    public Stream<ContextFragment.PathFragment> readonlyFiles() {
        return readonlyFiles.stream();
    }

    public Stream<ContextFragment.VirtualFragment> virtualFragments() {
        return virtualFragments.stream();
    }

    public Stream<? extends ContextFragment> allFragments() {
        return Streams.concat(editableFiles.stream(),
                              readonlyFiles.stream(),
                              virtualFragments.stream());
    }

    public boolean hasReadonlyFragments() {
        return !readonlyFiles.isEmpty() || !virtualFragments.isEmpty();
    }

    public boolean isAutoContextEnabled() {
        return autoContextFileCount > 0;
    }

    public AutoContext getAutoContext() {
        return autoContext;
    }

    public int getAutoContextFileCount() {
        return autoContextFileCount;
    }

    /**
     * Creates a new context with custom collections and action description,
     * refreshing auto-context if needed
     */
    private Context withFragments(List<ContextFragment.RepoPathFragment> newEditableFiles,
                                  List<ContextFragment.PathFragment> newReadonlyFiles,
                                  List<ContextFragment.VirtualFragment> newVirtualFragments,
                                  String action)
    {
        return new Context(
            analyzer, 
            newEditableFiles, 
            newReadonlyFiles, 
            newVirtualFragments, 
            autoContext,
            autoContextFileCount, 
            historyMessages, 
            Map.of(), 
            null, 
            action
        ).refresh();
    }

    public Context removeAll() {
        String action = "Dropped all context";
        return clearHistory().withFragments(List.of(), List.of(), List.of(), action);
    }

    /**
     * Produces a new Context object with a fresh AutoContext if enabled.
     */
    private Context refresh() {
        AutoContext newAutoContext = isAutoContextEnabled() ? buildAutoContext() : AutoContext.DISABLED;
        return new Context(analyzer, editableFiles, readonlyFiles, virtualFragments, newAutoContext, autoContextFileCount, historyMessages, Map.of(), null, action);
    }

    // Method removed in favor of toFragment(int position)

    public boolean isEmpty() {
        return editableFiles.isEmpty()
            && readonlyFiles.isEmpty()
            && virtualFragments.isEmpty()
            && historyMessages.isEmpty();
    }

    /**
     * Adding to the history DOES NOT create a new context object, it modifies history messages in place
     * for this and any other contexts that share the same history instance.
     * Otherwise popping context off with /undo
     * would clear out the most recent conversation round trip which is not what we want.
     */
    public Context addHistory(List<ChatMessage> newMessages, Map<RepoFile, String> originalContents, String outputText) {
        var newHistory = new ArrayList<>(historyMessages);
        newHistory.addAll(newMessages);
        return new Context(
            analyzer,
            editableFiles,
            readonlyFiles,
            virtualFragments,
            autoContext,
            autoContextFileCount,
            List.copyOf(newHistory),
            originalContents,
            outputText,
            "LLM conversation"
        );
    }

    /**
     * Clearing the history DOES create a new context object so you can /undo it
     */
    public Context clearHistory() {
        return new Context(
            analyzer,
            editableFiles,
            readonlyFiles,
            virtualFragments,
            autoContext,
            autoContextFileCount,
            List.of(),
            Map.of(),
            null,
            "Cleared conversation history"
        );
    }

    public Context withOriginalContents(Map<RepoFile, String> fileContents) {
        return new Context(
                analyzer,
                editableFiles,
                readonlyFiles,
                virtualFragments,
                autoContext,
                autoContextFileCount,
                historyMessages,
                fileContents,
                this.textarea,
                this.action
        );
    }

    /**
     * @return an immutable copy of the history messages
     */
    public List<ChatMessage> getHistory() {
        return List.copyOf(historyMessages);
    }

    /**
     * Get the action that created this context
     */
    public String getAction() {
        return action;
    }

    public Context addUsageFragment(String identifier, Set<CodeUnit> classnames, String code) {
        var fragment = new ContextFragment.UsageFragment(identifier, classnames, code);
        return addVirtualFragment(fragment);
    }

    public Context addSkeletonFragment(List<String> shortClassnames, Set<CodeUnit> classnames, String skeleton) {
        var fragment = new SkeletonFragment(shortClassnames, classnames, skeleton);
        return addVirtualFragment(fragment);
    }

    /**
     * Returns all fragments in display order:
     * 0 => conversation history (if not empty)
     * 1 => autoContext (always present, even when DISABLED)
     * next => read-only (readonlyFiles + virtualFragments)
     * finally => editable
     */
    public List<ContextFragment> getAllFragmentsInDisplayOrder() {
        var result = new ArrayList<ContextFragment>();

        // First include conversation history if not empty
        if (!historyMessages.isEmpty()) {
            result.add(new ContextFragment.ConversationFragment(historyMessages));
        }
        
        // Then include autoContext
        result.add(autoContext);
        
        // then read-only
        result.addAll(readonlyFiles);
        result.addAll(virtualFragments);
        
        // then editable
        result.addAll(editableFiles);

        return result;
    }
}
