package io.github.jbellis.brokk.agents;

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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
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
    private final int budgetSmall;
    private final int budgetMedium;
    private final int budgetFilenameLimit;

    public ContextAgent(ContextManager contextManager, StreamingChatLanguageModel model, String goal) {
        this.contextManager = contextManager;
        this.llm = model;
        this.coder = contextManager.getCoder(model, "ContextAgent: " + goal); // Coder for LLM interactions
        this.goal = goal;
        this.io = contextManager.getIo();
        try {
            this.analyzer = contextManager.getAnalyzer();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Preserve interrupted status
            throw new RuntimeException("Interrupted while initializing Analyzer for ContextAgent", e);
        }

        this.maxInputTokens = contextManager.getModels().getMaxInputTokens(model);
        // Rule 1: Smallest budget, add everything if it fits
        this.budgetSmall = min(32_000, maxInputTokens / 4);
        // Rule 2: Medium budget, ask LLM to pick relevant if all data fits
        this.budgetMedium = maxInputTokens / 2;
        // Rule 3: Filename budget, use filenames first if data is too large
        this.budgetFilenameLimit = (int) (maxInputTokens * 0.9);

        logger.debug("ContextAgent initialized. Budgets: Small={}, Medium={}, FilenameLimit={}", budgetSmall, budgetMedium, budgetFilenameLimit);
    }

    /**
     * Executes the context population logic.
     * This method determines the best initial context based on project size and token budgets,
     * potentially using LLM inference, and adds the selected content to the workspace.
     */
    public void execute() throws InterruptedException {
        // create a List from the Set
        var allFilesList = new ArrayList<>(contextManager.getRepo().getTrackedFiles());

        if (analyzer.isEmpty()) {
            executeWithFileContents(allFilesList);
        } else {
            executeWithSummaries(allFilesList);
        }
    }

    // --- Logic branch for using class summaries ---

    private void executeWithSummaries(List<ProjectFile> allFiles) throws InterruptedException {
        Map<CodeUnit, String> rawSummaries;
        if (contextManager.topContext().allFragments().findAny().isPresent()) {
            var ac = contextManager.topContext().setAutoContextFiles(100).buildAutoContext();
            logger.debug("Non-empty context, using pagerank candidates {} for ContextAgent",
                         ac.fragment().skeletons().keySet().stream().map(CodeUnit::identifier).collect(Collectors.joining(",")));
            rawSummaries = ac.fragment().skeletons();
        } else {
            rawSummaries = getProjectSummaries(allFiles);
        }

        int summaryTokens = Messages.getApproximateTokens(String.join("\n", rawSummaries.values()));
        logger.debug("Total tokens for {} summaries: {}", rawSummaries.size(), summaryTokens);

        // Rule 1: Use all summaries if they fit the smallest budget
        if (summaryTokens <= budgetSmall) {
            addSummariesToWorkspace(rawSummaries, "all summaries");
            return;
        }

        // Rule 2: Ask LLM to pick relevant summaries if all summaries fit the medium budget
        if (summaryTokens <= budgetMedium) {
            askLlmToSelectSummaries(rawSummaries);
            return;
        }

        // Rule 3: Use filenames first if summaries are too large
        var filenameString = getFilenameString(allFiles);
        int filenameTokens = Messages.getApproximateTokens(filenameString);
        logger.debug("Total tokens for filename list: {}", filenameTokens);

        if (filenameTokens <= budgetFilenameLimit) {
            executeWithFilenamesFirst(allFiles, true); // true indicates we aim for summaries
        } else {
            // Rule 4: Filenames too large, give up
            logGiveUp("filename list");
        }
    }

    /**
     * Collect a structural “skeleton” for every top-level class whose source file
     * is in the supplied collection.
     *
     * We:
     *   1. Grab the CodeUnits belonging to those files (via the analyzer).
     *   2. Collapse inner-classes so we only consider the outermost declaration.
     *   3. Ask the analyzer for a skeleton of each class and keep the non-empty ones.
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

    private void askLlmToSelectSummaries(Map<CodeUnit, String> summaries) throws InterruptedException {
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
            return;
        }

        var responseText = result.chatResponse().aiMessage().text();
        var relevantClassNames = parseLlmResponseLines(responseText);
        logger.debug("LLM suggested {} relevant classes: {}", relevantClassNames.size(), relevantClassNames);

        var relevantSummaries = summaries.entrySet().stream()
                .filter(entry -> relevantClassNames.contains(entry.getKey().fqName()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        if (relevantSummaries.isEmpty()) {
            logger.debug("LLM did not identify any relevant summaries. Skipping context population.");
            return;
        }

        // Check budget *after* LLM selection
        int relevantSummaryTokens = Messages.getApproximateTokens(String.join("\n", relevantSummaries.values()));
        if (relevantSummaryTokens <= budgetSmall) { // Re-use small budget here
            addSummariesToWorkspace(relevantSummaries, "LLM-selected summaries");
        } else {
            logger.debug("LLM-selected summaries ({} tokens) still exceed budget ({}). Aborting.", relevantSummaryTokens, budgetSmall);
            logGiveUp("LLM-selected summaries");
        }
    }

    private void addSummariesToWorkspace(Map<CodeUnit, String> summaries, String description) {
        var fragment = new ContextFragment.SkeletonFragment(summaries);
        contextManager.addVirtualFragment(fragment);
        int tokens = Messages.getApproximateTokens(String.join("\n", summaries.values()));
        logger.debug("Added skeleton fragment ({}) to workspace ({} tokens)", description, tokens);
        io.systemOutput("Added %s for %d classes to workspace".formatted(description, summaries.size()));
    }


    // --- Logic branch for using full file contents ---

    private void executeWithFileContents(List<ProjectFile> allFiles) throws InterruptedException {
        if (contextManager.topContext().allFragments().findAny().isPresent()) {
            logger.debug("Non-empty context and no anlyzer present, skipping context population");
            return;
        }

        var allContentsMap = readFileContents(allFiles);
        int contentTokens = Messages.getApproximateTokens(String.join("\n", allContentsMap.values()));
        logger.debug("Total tokens for {} files' content: {}", allFiles.size(), contentTokens);

        // Rule 1: Use all files if content fits the smallest budget
        if (contentTokens <= budgetSmall) {
            addFilesToWorkspace(allFiles, "all files");
            return;
        }

        // Rule 2: Ask LLM to pick relevant files if all content fits the medium budget
        if (contentTokens <= budgetMedium) {
            askLlmToSelectFiles(allFiles, allContentsMap);
            return;
        }

        // Rule 3: Use filenames first if content is too large
        var filenameString = getFilenameString(allFiles);
        int filenameTokens = Messages.getApproximateTokens(filenameString);
        logger.debug("Total tokens for filename list: {}", filenameTokens);

        if (filenameTokens <= budgetFilenameLimit) {
            executeWithFilenamesFirst(allFiles, false); // false indicates we aim for file contents
        } else {
            // Rule 4: Filenames too large, give up
            logGiveUp("filename list");
        }
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


    private void askLlmToSelectFiles(List<ProjectFile> allFiles, Map<ProjectFile, String> contentsMap) throws InterruptedException {
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
            return;
        }

        var responseText = result.chatResponse().aiMessage().text();
        var relevantFilePaths = parseLlmResponseLines(responseText);
        logger.debug("LLM suggested {} relevant file paths: {}", relevantFilePaths.size(), relevantFilePaths);

        // Create a map of path string to ProjectFile for quick lookup
        var pathToProjectFileMap = allFiles.stream()
                .collect(Collectors.toMap(ProjectFile::toString, f -> f));

        var relevantFiles = relevantFilePaths.stream()
                .map(pathToProjectFileMap::get)
                .filter(java.util.Objects::nonNull) // Filter out any paths not found in the original list
                .toList();

        if (relevantFiles.isEmpty()) {
            logger.debug("LLM did not identify any relevant files or paths were incorrect. Skipping context population.");
            return;
        }

        // Check budget *after* LLM selection
        var relevantContentsMap = readFileContents(relevantFiles); // Read only relevant files now
        int relevantContentTokens = Messages.getApproximateTokens(String.join("\n", relevantContentsMap.values()));
        if (relevantContentTokens <= budgetSmall) { // Re-use small budget here
            addFilesToWorkspace(relevantFiles, "LLM-selected files");
        } else {
            logger.debug("LLM-selected file contents ({} tokens) still exceed budget ({}). Aborting.", relevantContentTokens, budgetSmall);
            logGiveUp("LLM-selected file contents");
        }
    }

    private void addFilesToWorkspace(List<ProjectFile> files, String description) {
        contextManager.addReadOnlyFiles(files);
        // Estimate tokens again based on actual files added (might differ slightly if some failed read previously)
        var addedContents = readFileContents(files);
        int tokens = Messages.getApproximateTokens(String.join("\n", addedContents.values()));
        logger.debug("Added {} read-only files ({}) to workspace ({} tokens)", files.size(), description, tokens);
        io.systemOutput("Added content of %d %s to workspace".formatted(files.size(), description));
    }

    // --- Logic for Rule 3: Use filenames first ---

    private void executeWithFilenamesFirst(List<ProjectFile> allFiles, boolean aimForSummaries) throws InterruptedException {
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
            return;
        }

        var responseText = result.chatResponse().aiMessage().text();
        var relevantFilePaths = parseLlmResponseLines(responseText);
        logger.debug("LLM suggested {} potentially relevant file paths: {}", relevantFilePaths.size(), relevantFilePaths);

        // Create a map of path string to ProjectFile for quick lookup
        var pathToProjectFileMap = allFiles.stream()
                .collect(Collectors.toMap(ProjectFile::toString, f -> f));

        var relevantFiles = relevantFilePaths.stream()
                .map(pathToProjectFileMap::get)
                .filter(java.util.Objects::nonNull) // Filter out any paths not found in the original list
                .toList();

        if (relevantFiles.isEmpty()) {
            logger.debug("LLM did not identify any potentially relevant files or paths were incorrect based on filename list. Skipping context population.");
            return;
        }

        logger.debug("Proceeding to check budgets for the {} potentially relevant files.", relevantFiles.size());

        // --- Stage 2: Apply Rules 1 & 2 to the subset ---
        if (aimForSummaries) {
            var subsetSummaries = getProjectSummaries(relevantFiles);
            if (subsetSummaries.isEmpty()) {
                logger.debug("No summaries found for potentially relevant files. Aborting.");
                return;
            }
            int subsetTokens = Messages.getApproximateTokens(String.join("\n", subsetSummaries.values()));
            logger.debug("Tokens for summaries of potentially relevant files: {}", subsetTokens);
            if (subsetTokens <= budgetSmall) { // Rule 1 check on subset
                addSummariesToWorkspace(subsetSummaries, "summaries from LLM-selected files");
            } else if (subsetTokens <= budgetMedium) { // Rule 2 check on subset
                logger.debug("Subset summaries ({}) exceed small budget ({}), asking LLM to select again.", subsetTokens, budgetSmall);
                askLlmToSelectSummaries(subsetSummaries); // Pass subset to select *from*
            } else {
                logger.debug("Summaries of potentially relevant files ({}) exceed medium budget ({}). Aborting.", subsetTokens, budgetMedium);
                logGiveUp("summaries of potentially relevant files");
            }
        } else { // Aim for full contents
            var subsetContentsMap = readFileContents(relevantFiles);
            if (subsetContentsMap.isEmpty()) {
                logger.debug("Could not read content for potentially relevant files. Aborting.");
                return;
            }
            int subsetTokens = Messages.getApproximateTokens(String.join("\n", subsetContentsMap.values()));
            logger.debug("Tokens for content of potentially relevant files: {}", subsetTokens);
            if (subsetTokens <= budgetSmall) { // Rule 1 check on subset
                addFilesToWorkspace(relevantFiles, "content from LLM-selected files");
            } else if (subsetTokens <= budgetMedium) { // Rule 2 check on subset
                logger.debug("Subset content ({}) exceeds small budget ({}), asking LLM to select again.", subsetTokens, budgetSmall);
                askLlmToSelectFiles(relevantFiles, subsetContentsMap); // Pass subset to select *from*
            } else {
                logger.debug("Content of potentially relevant files ({}) exceed medium budget ({}). Aborting.", subsetTokens, budgetMedium);
                logGiveUp("content of potentially relevant files");
            }
        }
    }


    // --- Helper methods ---

    private String getFilenameString(List<ProjectFile> files) {
        return files.stream()
                .map(ProjectFile::toString)
                .collect(Collectors.joining("\n"));
    }

    private List<String> parseLlmResponseLines(String response) {
        return response.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .toList();
    }

    private void logGiveUp(String itemDescription) {
        logger.debug("Budget exceeded even for {}. Giving up on automatic context population.", itemDescription);
        io.systemOutput("Could not automatically determine relevant context within budget. Architect will start with an empty workspace.");
    }
}
