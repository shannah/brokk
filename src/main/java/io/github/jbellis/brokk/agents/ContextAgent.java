package io.github.jbellis.brokk.agents;

import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import io.github.jbellis.brokk.*;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.util.Messages;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static java.lang.Math.min;

/**
 * Agent responsible for populating the initial workspace context for the ArchitectAgent.
 * It uses different strategies based on project size, available analysis data, and token limits.
 */
public class ContextAgent {
    private static final Logger logger = LogManager.getLogger(ContextAgent.class);

    private final ContextManager contextManager;
    private final StreamingChatLanguageModel llm;
    private final Llm coder;
    private final String goal;
    private final IConsoleIO io;
    private final IAnalyzer analyzer;

    private final int maxInputTokens;
    // if the entire project fits in the Skip Pruning budget, just include it all and call it good
    private final int skipPruningBudget;
    // if our to-prune context is larger than the Pruning budget then we give up
    private final int budgetPruning;
    // if our pruned context is larger than the Final target budget then we also give up
    private final int finalBudget;

    public ContextAgent(ContextManager contextManager, StreamingChatLanguageModel model, String goal) {
        this.contextManager = contextManager;
        this.llm = model;
        this.coder = contextManager.getLlm(model, "ContextAgent: " + goal); // Coder for LLM interactions
        this.goal = goal;
        this.io = contextManager.getIo();
        try {
            this.analyzer = contextManager.getAnalyzer();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Preserve interrupted status
            throw new RuntimeException("Interrupted while initializing Analyzer for ContextAgent", e);
        }

        this.maxInputTokens = contextManager.getModels().getMaxInputTokens(model);
        this.skipPruningBudget = min(32_000, maxInputTokens / 4);
        this.finalBudget = maxInputTokens / 2;
        this.budgetPruning = (int) (maxInputTokens * 0.9);

        logger.debug("ContextAgent initialized. Budgets: Small={}, Medium={}, FilenameLimit={}", skipPruningBudget, finalBudget, budgetPruning);
    }

    private record RecommendationResult(boolean success, List<ContextFragment> fragments) { }

    /**
     * Executes the context population logic, obtains context recommendations,
     * presents them for selection (if workspace is not empty), and adds them to the workspace.
     * 
     * @return true if context fragments were successfully added, false otherwise
     */
    public boolean execute() throws InterruptedException {
        io.llmOutput("\nExamining initial workspace", ChatMessageType.CUSTOM);
        
        List<ContextFragment> proposals = getRecommendations();
        if (proposals.isEmpty()) {
            io.llmOutput("\nNo additional recommended context found", ChatMessageType.CUSTOM);
            return false;
        }

        List<ContextFragment> selected;
        // If workspace is empty, automatically accept all suggestions
        if (contextManager.topContext().allFragments().findAny().isEmpty()) {
            selected = proposals;
            io.llmOutput("\nAdding all recommended context items to empty workspace", ChatMessageType.CUSTOM);
        } else {
            selected = io.selectContextProposals(proposals);
        }

        if (selected == null || selected.isEmpty()) {
            return false;
        }

        // Either a batch of path fragments, or one/multiple skeleton fragments
        boolean allProjectPath = selected.stream().allMatch(f -> f instanceof ContextFragment.ProjectPathFragment);
        if (allProjectPath) {
            var casted = (List<ContextFragment.ProjectPathFragment>) (List<? extends ContextFragment>) selected;
            contextManager.editFiles(casted);
        } else if (selected.stream().allMatch(f -> f instanceof ContextFragment.SkeletonFragment)) {
            // Merge multiple SkeletonFragments into one
            var skeletons = selected.stream()
                                    .map(ContextFragment.SkeletonFragment.class::cast)
                                    .toList();
            var combined = skeletons.stream()
                                    .flatMap(sf -> sf.skeletons().entrySet().stream())
                                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            contextManager.addVirtualFragment(new ContextFragment.SkeletonFragment(combined));
        } else {
            throw new AssertionError(selected.toString());
        }
        return true;
    }

    /**
     * Determines the best initial context based on project size and token budgets,
     * potentially using LLM inference, and returns recommended context fragments.
     */
    public List<ContextFragment> getRecommendations() throws InterruptedException {
        var allFiles = contextManager.getRepo().getTrackedFiles().stream().sorted().toList();

        var firstResult = analyzer.isEmpty()
                          ? executeWithFileContents(allFiles)
                          : executeWithSummaries(allFiles);
        if (firstResult.success) {
            return firstResult.fragments;
        }

        // do two passes starting with filenames
        var filenameString = getFilenameString(allFiles);
        int filenameTokens = Messages.getApproximateTokens(filenameString);
        logger.debug("Total tokens for filename list: {}", filenameTokens);
        if (filenameTokens > budgetPruning) {
            // too large for us to handle
            logGiveUp("filename list");
            return List.of();
        }

        var prunedFiles = pruneFilenames(allFiles);
        var secondResult = analyzer.isEmpty()
                           ? executeWithFileContents(prunedFiles)
                           : executeWithSummaries(prunedFiles);
        return secondResult.fragments;
    }

    // --- Logic branch for using class summaries ---

    private RecommendationResult executeWithSummaries(List<ProjectFile> allFiles) throws InterruptedException {
        Map<CodeUnit, String> rawSummaries;
        if (contextManager.topContext().allFragments().findAny().isPresent()) {
            var ac = contextManager.topContext().setAutoContextFiles(100).buildAutoContext();
            logger.debug("Non-empty context, using pagerank candidates {} for ContextAgent",
                         ac.fragment().skeletons().keySet().stream().map(CodeUnit::identifier).collect(Collectors.joining(",")));
            rawSummaries = ac.isEmpty() ? Map.of() : ac.fragment().skeletons();
        } else {
            rawSummaries = getProjectSummaries(allFiles);
        }

        int summaryTokens = Messages.getApproximateTokens(String.join("\n", rawSummaries.values()));
        logger.debug("Total tokens for {} summaries: {}", rawSummaries.size(), summaryTokens);

        // Rule 1: Use all summaries if they fit the smallest budget
        if (summaryTokens <= skipPruningBudget) {
            var fragments = skeletonPerSummary(rawSummaries);
            return new RecommendationResult(true, fragments);
        }

        // Rule 2: Ask LLM to pick relevant summaries if all summaries fit the Pruning budget
        if (summaryTokens <= budgetPruning) {
            var relevantSummaries = askLlmToSelectSummaries(rawSummaries);
            int relevantSummaryTokens = Messages.getApproximateTokens(String.join("\n", relevantSummaries.values()));
            if (relevantSummaryTokens <= finalBudget) {
                var fragments = skeletonPerSummary(relevantSummaries);
                return new RecommendationResult(true, fragments);
            } else {
                logGiveUp("summaries");
                return new RecommendationResult(false, List.of());
            }
        }

        return new RecommendationResult(false, List.of());
    }

    /**
     * one SkeletonFragment per summary so ArchitectAgent can easily ask user which ones to include
     */
    private static @NotNull List<ContextFragment> skeletonPerSummary(Map<CodeUnit, String> relevantSummaries) {
        return relevantSummaries.entrySet().stream()
                .map(entry -> (ContextFragment) new ContextFragment.SkeletonFragment(Map.of(entry.getKey(), entry.getValue())))
                .toList();
    }

    /**
     * Collect a structural “skeleton” for every top-level class whose source file
     * is in the supplied collection.
     * <p>
     * We:
     * 1. Grab the CodeUnits belonging to those files (via the analyzer).
     * 2. Collapse inner-classes so we only consider the outermost declaration.
     * 3. Ask the analyzer for a skeleton of each class and keep the non-empty ones.
     */
    private Map<CodeUnit, String> getProjectSummaries(Collection<ProjectFile> files) {
        // turn file list into class list
        var projectClasses = files.stream().parallel()
                .flatMap(f -> analyzer.getClassesInFile(f).stream())
                .collect(Collectors.toSet());
        var coalescedClasses = AnalyzerUtil.coalesceInnerClasses(projectClasses);
        logger.debug("Found {} top-level classes in {} files.",
                     coalescedClasses.size(), files.size());

        // grab skeletons in parallel
        return coalescedClasses.stream().parallel()
                .map(cu -> {
                    var skeletonOpt = analyzer.getSkeleton(cu.fqName());
                    String skeleton = skeletonOpt.isDefined() ? skeletonOpt.get() : "";
                    return Map.entry(cu, skeleton);
                })
                .filter(entry -> !entry.getValue().isEmpty())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private Map<CodeUnit, String> askLlmToSelectSummaries(Map<CodeUnit, String> summaries) throws InterruptedException {
        var promptContent = summaries.entrySet().stream()
                .map(entry -> "Class: %s\n```java\n%s\n```".formatted(entry.getKey().fqName(), entry.getValue()))
                .collect(Collectors.joining("\n\n"));

        var systemMessage = """
                You are an assistant that identifies relevant code summaries based on a goal.
                Given a list of class summaries and a goal, identify which classes are most relevant to achieving the goal.
                Output *only* the fully qualified names of the relevant classes, one per line. Do not include any other text, explanations, or formatting.
                """.stripIndent();
        var userMessage = """
                Goal: %s
                
                Summaries:
                %s
                
                Which of these classes are most relevant to the goal? List their fully qualified names, one per line.
                """.formatted(goal, promptContent).stripIndent();

        var messages = List.of(new SystemMessage(systemMessage), new UserMessage(userMessage));
        logger.debug("Invoking LLM to select relevant summaries (prompt size ~{} tokens)", Messages.getApproximateTokens(promptContent));
        var result = coder.sendRequest(messages);

        if (result.error() != null || result.chatResponse() == null || result.chatResponse().aiMessage() == null) {
            logger.warn("Error or empty response from LLM during summary selection: {}. Aborting context population.",
                        result.error() != null ? result.error().getMessage() : "Empty response");
            return Map.of();
        }

        var responseText = result.chatResponse().aiMessage().text();
        var relevantSummaries = summaries.entrySet().stream()
                .filter(e -> responseText.contains(e.getKey().fqName()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        logger.debug("LLM suggested {} relevant classes", relevantSummaries.size());

        return relevantSummaries;
    }

    // --- Logic branch for using full file contents ---

    private RecommendationResult executeWithFileContents(List<ProjectFile> allFiles) throws InterruptedException {
        if (contextManager.topContext().allFragments().findAny().isPresent()) {
            logger.debug("Non-empty context and no analyzer present, skipping context population");
            return new RecommendationResult(true, List.of());
        }

        var allContentsMap = readFileContents(allFiles);
        int contentTokens = Messages.getApproximateTokens(String.join("\n", allContentsMap.values()));
        logger.debug("Total tokens for {} files' content: {}", allFiles.size(), contentTokens);

        // Rule 1: Use all files if content fits the smallest budget
        if (contentTokens <= skipPruningBudget) {
            var fragments = allFiles.stream()
                    .map(f -> (ContextFragment) new ContextFragment.ProjectPathFragment(f))
                    .toList();
            return new RecommendationResult(true, fragments);
        }

        // Rule 2: Ask LLM to pick relevant files if all content fits the Pruning budget
        if (contentTokens <= budgetPruning) {
            var relevantFiles = askLlmToSelectFiles(allFiles, allContentsMap);
            var relevantContentsMap = readFileContents(relevantFiles);
            int relevantContentTokens = Messages.getApproximateTokens(String.join("\n", relevantContentsMap.values()));
            if (relevantContentTokens <= finalBudget) {
                var fragments = relevantFiles.stream()
                        .map(f -> (ContextFragment) new ContextFragment.ProjectPathFragment(f))
                        .toList();
                return new RecommendationResult(true, fragments);
            } else {
                logGiveUp("pruned file contents");
                return new RecommendationResult(false, List.of());
            }
        }

        logGiveUp("file contents");
        return new RecommendationResult(false, List.of());
    }

    private Map<ProjectFile, String> readFileContents(Collection<ProjectFile> files) {
        return files.stream().parallel()
                .map(file -> {
                    try {
                        return Map.entry(file, file.read());
                    } catch (IOException e) {
                        logger.warn("Could not read content of file: {}. Skipping.", file, e);
                        return Map.entry(file, ""); // Return empty string on error
                    } catch (UncheckedIOException e) {
                        logger.warn("Could not read content of file (Unchecked): {}. Skipping.", file, e);
                        return Map.entry(file, "");
                    }
                })
                .filter(entry -> !entry.getValue().isEmpty()) // Filter out files that couldn't be read
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }


    private List<ProjectFile> askLlmToSelectFiles(List<ProjectFile> allFiles, Map<ProjectFile, String> contentsMap) throws InterruptedException {
        var promptContent = contentsMap.entrySet().stream()
                .map(entry -> "File: %s\n```\n%s\n```".formatted(entry.getKey().toString(), entry.getValue()))
                .collect(Collectors.joining("\n\n"));

        var systemMessage = """
                You are an assistant that identifies relevant files based on a goal.
                Given a list of files with their content and a goal, identify which files are most relevant to achieving the goal.
                Output *only* the full paths of the relevant files, one per line. Do not include any other text, explanations, or formatting.
                """.stripIndent();
        var userMessage = """
                Goal: %s
                
                Files:
                %s
                
                Which of these files are most relevant to the goal? List their full paths, one per line.
                """.formatted(goal, promptContent).stripIndent();

        var messages = List.of(new SystemMessage(systemMessage), new UserMessage(userMessage));
        logger.debug("Invoking LLM to select relevant files (prompt size ~{} tokens)", Messages.getApproximateTokens(promptContent));
        var result = coder.sendRequest(messages);

        if (result.error() != null || result.chatResponse() == null || result.chatResponse().aiMessage() == null) {
            logger.warn("Error or empty response from LLM during file selection: {}. Aborting context population.",
                        result.error() != null ? result.error().getMessage() : "Empty response");
            return List.of();
        }

        var responseText = result.chatResponse().aiMessage().text();

        return allFiles.stream()
                .filter(f -> responseText.contains(f.toString()))
                .toList();
    }

    // --- Logic for Rule 3: Use filenames first ---

    private List<ProjectFile> pruneFilenames(List<ProjectFile> allFiles) throws InterruptedException {
        var filenameString = getFilenameString(allFiles);

        var systemMessage = """
                You are an assistant that identifies potentially relevant files based on a goal.
                Given a list of file paths and a goal, identify which files *might* be relevant to achieving the goal.
                Output *only* the full paths of the potentially relevant files, one per line. Do not include any other text, explanations, or formatting.
                """.stripIndent();
        var userMessage = """
                Goal: %s
                
                File Paths:
                %s
                
                Which of these file paths seem potentially relevant to the goal? List their full paths, one per line.
                """.formatted(goal, filenameString).stripIndent();

        var messages = List.of(new SystemMessage(systemMessage), new UserMessage(userMessage));
        logger.debug("Invoking LLM to select potentially relevant filenames (prompt size ~{} tokens)", Messages.getApproximateTokens(filenameString));
        var result = coder.sendRequest(messages);

        if (result.error() != null || result.chatResponse() == null || result.chatResponse().aiMessage() == null) {
            logger.warn("Error or empty response from LLM during filename selection: {}. Aborting context population.",
                        result.error() != null ? result.error().getMessage() : "Empty response");
            return List.of();
        }

        var responseText = result.chatResponse().aiMessage().text();
        return allFiles.stream()
                .filter(f -> responseText.contains(f.toString()))
                .toList();
    }


    // --- Helper methods ---

    private String getFilenameString(List<ProjectFile> files) {
        return files.stream()
                .map(ProjectFile::toString)
                .collect(Collectors.joining("\n"));
    }

    private void logGiveUp(String itemDescription) {
        logger.debug("Budget exceeded even for {}. Giving up on automatic context population.", itemDescription);
        io.systemOutput("Could not automatically determine relevant context within budget. Architect will start with an empty workspace.");
    }
}
