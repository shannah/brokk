package io.github.jbellis.brokk.agents;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import io.github.jbellis.brokk.*;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.prompts.CodePrompts;
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
import java.util.stream.Stream;

import static java.lang.Math.min;

/**
 * Agent responsible for populating the initial workspace context for the ArchitectAgent.
 * It uses different strategies based on project size, available analysis data, and token limits.
 */
public class ContextAgent {
    private static final Logger logger = LogManager.getLogger(ContextAgent.class);

    private final ContextManager contextManager;
    private final Llm llm;
    private final String goal;
    private final IConsoleIO io;
    private final IAnalyzer analyzer;
    private final boolean fullWorkspace;

    // if the entire project fits in the Skip Pruning budget, just include it all and call it good
    private final int skipPruningBudget;
    // if our to-prune context is larger than the Pruning budget then we give up
    private final int budgetPruning;
    // if our pruned context is larger than the Final target budget then we also give up
    private final int finalBudget;

    public ContextAgent(ContextManager contextManager, StreamingChatLanguageModel model, String goal, boolean fullWorkspace) throws InterruptedException {
        this.contextManager = contextManager;
        this.llm = contextManager.getLlm(model, "ContextAgent: " + goal); // Coder for LLM interactions
        this.goal = goal;
        this.io = contextManager.getIo();
        this.analyzer = contextManager.getAnalyzer();
        this.fullWorkspace = fullWorkspace;

        int maxInputTokens = contextManager.getModels().getMaxInputTokens(model);
        this.skipPruningBudget = min(32_000, maxInputTokens / 4);
        this.finalBudget = maxInputTokens / 2;
        this.budgetPruning = (int) (maxInputTokens * 0.9);

        logger.debug("ContextAgent initialized. Budgets: Small={}, Medium={}, FilenameLimit={}", skipPruningBudget, finalBudget, budgetPruning);
    }

    private record RecommendationResult(boolean success, List<ContextFragment> fragments) {
    }

    /**
     * Executes the context population logic, obtains context recommendations,
     * presents them for selection (if workspace is not empty), and adds them to the workspace.
     *
     * @return true if context fragments were successfully added, false otherwise
     */
    public boolean execute() throws InterruptedException {
        io.llmOutput("\nExamining initial workspace", ChatMessageType.CUSTOM);

        // Execute without a specific limit on recommendations
        List<ContextFragment> proposals = getRecommendations(null);
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
     *
     * @param topK Optional limit on the number of recommendations to return. If null, no limit is applied (unless LLM limits).
     */
    public List<ContextFragment> getRecommendations(Integer topK) throws InterruptedException {
        var workspaceRepresentation = getWorkspaceRepresentation();
        var allFiles = contextManager.getRepo().getTrackedFiles().stream().sorted().toList();

        // Try summaries first if analyzer is available
        if (!analyzer.isEmpty()) {
            var summaryResult = executeWithSummaries(allFiles, topK, workspaceRepresentation);
            if (summaryResult.success) {
                return summaryResult.fragments;
            }
            // If summaries failed (e.g., too large even for pruning), fall through to filename-based pruning
        } else {
            // If no analyzer, try full file contents directly
            var contentResult = executeWithFileContents(allFiles, topK, workspaceRepresentation);
            if (contentResult.success) {
                return contentResult.fragments;
            }
            // If contents failed, fall through to filename-based pruning
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
        if (prunedFiles.isEmpty()) {
            logger.debug("Filename pruning resulted in an empty list. No context found.");
            return List.of();
        }

        // After pruning filenames, try again with summaries or contents
        if (!analyzer.isEmpty()) {
            return executeWithSummaries(prunedFiles, topK, workspaceRepresentation).fragments;
        } else {
            return executeWithFileContents(prunedFiles, topK, workspaceRepresentation).fragments;
        }
    }

    // --- Logic for getting workspace representation ---

    private Object getWorkspaceRepresentation() {
        if (fullWorkspace) {
            // Return full messages if requested
            return contextManager.getWorkspaceContentsMessages(false);
        } else {
            // Return summary string otherwise
            return CodePrompts.formatWorkspaceSummary(contextManager, false);
        }
    }


    // --- Logic branch for using class summaries ---

    private RecommendationResult executeWithSummaries(List<ProjectFile> filesToConsider, Integer topK, Object workspaceRepresentation) throws InterruptedException {
        Map<CodeUnit, String> rawSummaries;
        // If the workspace isn't empty, use pagerank candidates for initial summaries
        if (!contextManager.getEditableFiles().isEmpty() || !contextManager.getReadonlyFiles().isEmpty()) {
           var ac = contextManager.topContext().setAutoContextFiles(100).buildAutoContext();
            logger.debug("Non-empty context, using pagerank candidates {} for ContextAgent",
                         ac.fragment().skeletons().keySet().stream().map(CodeUnit::identifier).collect(Collectors.joining(",")));
           logger.debug("Non-empty context, using pagerank candidates {} for ContextAgent",
                        ac.fragment().skeletons().keySet().stream().map(CodeUnit::identifier).collect(Collectors.joining(",")));
           rawSummaries = ac.isEmpty() ? Map.of() : ac.fragment().skeletons();
        } else {
            // If workspace is empty, get summaries for the provided files (all or pruned)
            rawSummaries = getProjectSummaries(filesToConsider);
        }

        int summaryTokens = Messages.getApproximateTokens(String.join("\n", rawSummaries.values()));
        logger.debug("Total tokens for {} summaries (from {} files): {}", rawSummaries.size(), filesToConsider.size(), summaryTokens);

        // Rule 1: Use all available summaries if they fit the smallest budget
        if (summaryTokens <= skipPruningBudget && (topK == null || rawSummaries.size() <= topK)) {
            var fragments = skeletonPerSummary(rawSummaries);
            return new RecommendationResult(true, fragments);
        }

        // Rule 2: Ask LLM to pick relevant summaries if all summaries fit the Pruning budget
        if (summaryTokens <= budgetPruning) {
            var relevantSummaries = askLlmToSelectSummaries(rawSummaries, topK, workspaceRepresentation);
            int relevantSummaryTokens = Messages.getApproximateTokens(String.join("\n", relevantSummaries.values()));
            if (relevantSummaryTokens <= finalBudget) {
                var fragments = skeletonPerSummary(relevantSummaries);
                return new RecommendationResult(true, fragments);
            } else {
                logGiveUp("summaries");
                return new RecommendationResult(false, List.of());
            }
        }

        // If summaries are too large even for pruning, signal failure for this branch
        logGiveUp("summaries (too large for pruning budget)");
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

    private Map<CodeUnit, String> askLlmToSelectSummaries(Map<CodeUnit, String> summaries, Integer topK, Object workspaceRepresentation) throws InterruptedException {
        var summariesText = summaries.entrySet().stream()
                .map(entry -> "Class: %s\n```java\n%s\n```".formatted(entry.getKey().fqName(), entry.getValue()))
                .collect(Collectors.joining("\n\n"));

        var systemMessage = new StringBuilder("""
                 You are an assistant that identifies relevant code summaries based on a goal.
                 Given a list of class summaries and a goal, identify which classes are most relevant to achieving the goal.
                 Output *only* the fully qualified names of the relevant classes, one per line, ordered from most to least relevant.
                 Do not include any other text, explanations, or formatting.
                 """.stripIndent());
        if (topK != null) {
            systemMessage.append("\nLimit your response to the top %d most relevant classes.".formatted(topK));
        }

        var finalSystemMessage = new SystemMessage(systemMessage.toString());
        List<ChatMessage> messages;
        int promptTokens;

        if (workspaceRepresentation instanceof String workspaceSummary) {
            var userMessageText = """
                    <goal>
                    %s
                    </goal>
                    
                    <workspace>
                    %s
                    </workspace>
                    
                    <summaries>
                    %s
                    </summaries>
                    
                    Which of these classes are most relevant to the goal? Take into consideration what is already in the workspace, and list their fully qualified names, one per line.
                    """.formatted(goal, workspaceSummary, summariesText).stripIndent();
            messages = List.of(finalSystemMessage, new UserMessage(userMessageText));
            promptTokens = Messages.getApproximateTokens(userMessageText) + Messages.getApproximateTokens(finalSystemMessage.text());
        } else if (workspaceRepresentation instanceof List workspaceMessages) {
            @SuppressWarnings("unchecked") // Checked by instanceof
            List<ChatMessage> castedMessages = (List<ChatMessage>) workspaceMessages;
            var userPrompt = """
                    <goal>
                    %s
                    </goal>
                    
                    <summaries>
                    %s
                    </summaries>
                    
                    Which of these classes are most relevant to the goal? Take into consideration what is already in the workspace (provided as prior messages), and list their fully qualified names, one per line.
                    """.formatted(goal, summariesText).stripIndent();

            // Combine system message, workspace messages, and the final user prompt
            messages = Stream.concat(Stream.of(finalSystemMessage),
                                     Stream.concat(castedMessages.stream(), Stream.of(new UserMessage(userPrompt))))
                             .toList();
            promptTokens = messages.stream().mapToInt(m -> Messages.getApproximateTokens(Messages.getText(m))).sum();
        } else {
            throw new IllegalArgumentException("Unsupported workspace representation type: " + workspaceRepresentation.getClass());
        }


        logger.debug("Invoking LLM to select relevant summaries (prompt size ~{} tokens)", promptTokens);
        var result = llm.sendRequest(messages);

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

// --- Logic branch for using full file contents (when analyzer is not available) ---

    private RecommendationResult executeWithFileContents(List<ProjectFile> filesToConsider, Integer topK, Object workspaceRepresentation) throws InterruptedException {
        // If the workspace isn't empty, don't suggest adding whole files if no analyzer available
        if (!contextManager.getEditableFiles().isEmpty() || !contextManager.getReadonlyFiles().isEmpty()) {
            logger.debug("Non-empty context and no analyzer present, skipping file content suggestions");
            return new RecommendationResult(true, List.of()); // Indicate success but no *new* fragments needed
        }

        var contentsMap = readFileContents(filesToConsider);
        int contentTokens = Messages.getApproximateTokens(String.join("\n", contentsMap.values()));
        logger.debug("Total tokens for {} files' content: {}", contentsMap.size(), contentTokens);

        // Rule 1: Use all available files if content fits the smallest budget
        if (contentTokens <= skipPruningBudget && (topK == null || filesToConsider.size() <= topK)) {
            var fragments = filesToConsider.stream()
                    .map(f -> (ContextFragment) new ContextFragment.ProjectPathFragment(f))
                    .toList();
            return new RecommendationResult(true, fragments);
        }

        // Rule 2: Ask LLM to pick relevant files if all content fits the Pruning budget
        if (contentTokens <= budgetPruning) {
            var relevantFiles = askLlmToSelectFiles(filesToConsider, contentsMap, topK, workspaceRepresentation);
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


    private List<ProjectFile> askLlmToSelectFiles(List<ProjectFile> filesToConsider, Map<ProjectFile, String> contentsMap, Integer topK, Object workspaceRepresentation) throws InterruptedException {
        var filesText = contentsMap.entrySet().stream()
                .map(entry -> "<file path='%s'>\n%s\n</file>".formatted(entry.getKey().toString(), entry.getValue()))
                .collect(Collectors.joining("\n\n"));

        var systemMessage = new StringBuilder("""
                 You are an assistant that identifies relevant files based on a goal.
                 Given a list of files with their content and a goal, identify which files are most relevant to achieving the goal.
                 Output *only* the full paths of the relevant files, one per line, ordered from most to least relevant.
                 Do not include any other text, explanations, or formatting.
                 """.stripIndent());
        if (topK != null) {
            systemMessage.append("\nLimit your response to the top %d most relevant files.".formatted(topK));
        }

        var finalSystemMessage = new SystemMessage(systemMessage.toString());
        List<ChatMessage> messages;
        int promptTokens;

        if (workspaceRepresentation instanceof String workspaceSummary) {
            var userMessageText = """
                    <goal>
                    %s
                    </goal>
                    
                    <workspace>
                    %s
                    </workspace>
                    
                    <files>
                    %s
                    </files>
                    
                    Which of these files are most relevant to the goal? Take into consideration what is already in the workspace, and list their full paths, one per line.
                    """.formatted(goal, workspaceSummary, filesText).stripIndent();
            messages = List.of(finalSystemMessage, new UserMessage(userMessageText));
            promptTokens = Messages.getApproximateTokens(userMessageText) + Messages.getApproximateTokens(finalSystemMessage.text());
        } else if (workspaceRepresentation instanceof List workspaceMessages) {
            @SuppressWarnings("unchecked") // Checked by instanceof
            List<ChatMessage> castedMessages = (List<ChatMessage>) workspaceMessages;
            var userPrompt = """
                    <goal>
                    %s
                    </goal>
                    
                    <files>
                    %s
                    </files>
                    
                    Which of these files are most relevant to the goal? Take into consideration what is already in the workspace (provided as prior messages), and list their full paths, one per line.
                    """.formatted(goal, filesText).stripIndent();

            messages = Stream.concat(Stream.of(finalSystemMessage),
                                     Stream.concat(castedMessages.stream(), Stream.of(new UserMessage(userPrompt))))
                             .toList();
            promptTokens = messages.stream().mapToInt(m -> Messages.getApproximateTokens(Messages.getText(m))).sum();
        } else {
            throw new IllegalArgumentException("Unsupported workspace representation type: " + workspaceRepresentation.getClass());
        }

        logger.debug("Invoking LLM to select relevant files (prompt size ~{} tokens)", promptTokens);
        var result = llm.sendRequest(messages);

        if (result.error() != null || result.chatResponse() == null || result.chatResponse().aiMessage() == null) {
            logger.warn("Error or empty response from LLM during file selection: {}. Aborting context population.",
                        result.error() != null ? result.error().getMessage() : "Empty response");
            return List.of();
        }

        var responseText = result.chatResponse().aiMessage().text();

        return filesToConsider.stream()
                .filter(f -> responseText.contains(f.toString()))
                .toList();
    }

    // --- Logic for pruning based on filenames only (used as a fallback) ---

    private List<ProjectFile> pruneFilenames(List<ProjectFile> allFiles) throws InterruptedException {
        var filenameString = getFilenameString(allFiles);

        var systemMessage = """
                You are an assistant that identifies potentially relevant files based on a goal.
                Given a list of file paths and a goal, identify which files *might* be relevant to achieving the goal.
                Output *only* the full paths of the potentially relevant files, one per line. Do not include any other text, explanations, or formatting.
                """.stripIndent();
        // we never include the full workspace here since we're in context-constrained mode
        var userMessage = """
                <goal>
                %s
                </goal>
                
                <workspace>
                %s
                </workspace>
                
                <files>
                %s
                </files>
                
                Which of these file paths seem potentially relevant to the goal? List their full paths, one per line.
                """.formatted(goal, CodePrompts.formatWorkspaceSummary(contextManager, false), filenameString).stripIndent();

        var messages = List.of(new SystemMessage(systemMessage), new UserMessage(userMessage));
        logger.debug("Invoking LLM to select potentially relevant filenames (prompt size ~{} tokens)", Messages.getApproximateTokens(filenameString));
        var result = llm.sendRequest(messages);

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
