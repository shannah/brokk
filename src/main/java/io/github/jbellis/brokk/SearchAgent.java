package io.github.jbellis.brokk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.output.TokenUsage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import scala.Tuple2;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.zip.CRC32;

import static java.lang.Math.min;

/**
 * SearchAgent implements an iterative, agentic approach to code search.
 * It uses multiple steps of searching and reasoning to find code elements relevant to a query.
 */
public class SearchAgent {
    private final Logger logger = LogManager.getLogger(SearchAgent.class);
    private static final int TOKEN_BUDGET = 64000; // 64K context window for models like R1

    private final Analyzer analyzer;
    private final ContextManager contextManager;
    private final Coder coder;
    private final IConsoleIO io;

    // Budget and action control state
    private boolean allowSearch = true;
    private boolean allowInspect = true;
    private boolean allowPagerank = true;
    private boolean allowAnswer = true;
    private boolean allowSubstringSearch = false; // Starts disabled until searchSymbols is called
    private boolean symbolsFound = false;

    // Search state
    private final String query;
    private final List<ToolCall> actionHistory = new ArrayList<>();
    private final List<Tuple2<String, String>> knowledge = new ArrayList<>();

    private TokenUsage totalUsage = new TokenUsage(0, 0);

    public SearchAgent(String query, ContextManager contextManager, Coder coder, IConsoleIO io) {
        this.query = query;
        this.contextManager = contextManager;
        this.analyzer = contextManager.getAnalyzer();
        this.coder = coder;
        this.io = io;
    }

    /**
     * Execute the search process, iterating through queries until completion.
     * @return The final set of discovered code units
     */
    public ContextFragment.VirtualFragment execute() {
        // Initialize
        var contextWithClasses = contextManager.currentContext().allFragments().map(f -> {
            String text;
            try {
                text = f.text();
            } catch (IOException e) {
                contextManager.removeBadFragment(f, e);
                return null;
            }
            return """
            <fragment description="%s" sources="%s">
            %s
            </fragment>
            """.stripIndent().formatted(f.description(),
                                        (f.sources(analyzer).stream().map(CodeUnit::reference).collect(Collectors.joining(", "))),
                                        text);
        }).filter(Objects::nonNull).collect(Collectors.joining("\n"));
        if (!contextWithClasses.isBlank()) {
            io.shellOutput("Evaluating context");
            var messages = new ArrayList<ChatMessage>();
            messages.add(new SystemMessage("""
            You are an expert software architect.
            evaluating which code fragments are relevant to a user query.
            Review the following list of code fragments and select the ones most relevant to the query.
            Make sure to include the fully qualified source (class, method, etc) as well as the code.
            """.stripIndent()));
            messages.add(new UserMessage("<query>%s</query>\n\n".formatted(query) + contextWithClasses));
            var response = coder.sendMessage(coder.models.searchModel(), messages);
            knowledge.add(new Tuple2<>("Initial context", response.aiMessage().text()));
        }

        while (true) {
            // If thread interrupted, bail out
            if (Thread.interrupted()) {
                return null;
            }

            // Check if action history is now more than our budget
            if (actionHistorySize() > 0.95 * TOKEN_BUDGET) {
                logger.debug("Stopping search because action history exceeds context window size");
                break;
            }

            // Special handling based on previous steps
            updateActionControlsBasedOnContext();

            // Decide what action to take for this query
            var tools = determineNextActions();
            if (tools.isEmpty()) {
                logger.debug("No valid actions determined");
                break;
            }

            // Print some debug/log info
            var explanation = tools.stream().map(st -> getExplanationForTool(st.getRequest().name(), st)).collect(Collectors.joining("\n"));
            io.shellOutput(explanation);
            logger.debug("{}; token usage: {}", explanation, totalUsage);
            logger.debug("Actions: {}", tools);

            // Execute the steps
            var results = tools.stream().parallel().peek(step -> {
                step.execute();
                logger.debug("Result: {}", step);
            }).toList();

            // Check if we should terminate
            String firstToolName = tools.getFirst().getRequest().name();
            if (firstToolName.equals("answer")) {
                logger.debug("Search complete");
                assert tools.size() == 1 : tools;

                try {
                    ToolCall answerCall = tools.getFirst();
                    var arguments = answerCall.argumentsMap();
                    @SuppressWarnings("unchecked")
                    var classNames = (List<String>) arguments.get("classNames");
                    logger.debug("Relevant classes are {}", classNames);

                    // Coalesce inner classes whose parents are also present
                    var coalesced = classNames.stream()
                            .filter(c -> classNames.stream().noneMatch(c2 -> c.startsWith(c2 + "$")))
                            .toList();

                    var sources = coalesced.stream()
                            .map(CodeUnit::cls)
                            .collect(Collectors.toSet());
                    return new ContextFragment.SearchFragment(query, results.getFirst().result, sources);
                } catch (Exception e) {
                    // something went wrong parsing out the classnames
                    logger.error("Error creating SearchFragment", e);
                    return new ContextFragment.StringFragment(query, results.getFirst().result);
                }
            } else if (firstToolName.equals("abort")) {
                logger.debug("Search aborted");
                assert tools.size() == 1 : tools;
                return new ContextFragment.StringFragment(query, results.getFirst().result);
            }

            // Record the steps in history
            actionHistory.addAll(results);
        }

        logger.debug("Search ended because we hit the action-history cutoff or no valid actions remained.");
        return new ContextFragment.SearchFragment(query,
                                                  "No final answer provided before hitting the 80% action-history cutoff.",
                                                  Set.of());
    }

    private int actionHistorySize() {
        var toIndex = min(0, actionHistory.size() - 1);
        var historyString = IntStream.range(0, toIndex)
                .mapToObj(actionHistory::get)
                .map(h -> formatHistory(h, -1))
                .collect(Collectors.joining());
        return Models.getApproximateTokens(historyString);
    }

    /**
     * Update action controls based on current search context.
     */
    private void updateActionControlsBasedOnContext() {
        // If we just started, don't allow answer yet
        allowAnswer = !actionHistory.isEmpty();

        allowSearch = true;
        allowInspect = true;
        allowPagerank = true;
        // don't reset allowSubstringSearch
    }

    /**
     * Gets an explanation for a tool name with parameter information
     */
    private String getExplanationForTool(String toolName, ToolCall toolCall) {
        String paramInfo = getToolParameterInfo(toolCall);
        String baseExplanation = switch (toolName) {
            case "searchSymbols" -> "Searching for symbols";
            case "searchSubstrings" -> "Searching for substrings";
            case "getUsages" -> "Finding usages";
            case "getRelatedClasses" -> "Finding related code";
            case "getClassSkeletons" -> "Getting class overview";
            case "getClassSources" -> "Fetching class source";
            case "getMethodSources" -> "Fetching method source";
            case "answer" -> "Answering the question";
            case "abort" -> "Abort the search";
            default -> {
                logger.debug("Unknown tool name: {}", toolName);
                yield  "Processing request";
            }
        };

        return paramInfo.isBlank() ? baseExplanation : baseExplanation + " (" + paramInfo + ")";
    }

    /**
     * Gets human-readable parameter information from a tool call
     */
    /**
     * Formats a list parameter for display in tool parameter info.
     * Returns the first item if there's only one, or a count summary if there are multiple.
     */
    private String formatListParameter(Map<String, Object> arguments, String paramName) {
        @SuppressWarnings("unchecked")
        List<String> items = (List<String>) arguments.get(paramName);
        if (items != null && !items.isEmpty()) {
            return items.size() == 1 ? items.getFirst() : String.join(", ", items);
        }
        return "";
    }

    private String getToolParameterInfo(ToolCall toolCall) {
        if (toolCall == null) return "";

        try {
            var arguments = toolCall.argumentsMap();

            return switch (toolCall.getRequest().name()) {
                case "searchSymbols", "searchSubstrings" -> formatListParameter(arguments, "patterns");
                case "getUsages" -> formatListParameter(arguments, "symbols");
                case "getRelatedClasses", "getClassSkeletons",
                     "getClassSources" -> formatListParameter(arguments, "classNames");
                case "getMethodSources" -> formatListParameter(arguments, "methodNames");
                case "answer", "abort" -> "finalizing";
                default -> throw new IllegalArgumentException("Unknown tool name " + toolCall.getRequest().name());
            };
        } catch (Exception e) {
            logger.error("Error getting parameter info", e);
            return "";
        }
    }

    /**
     * Determine the next action to take for the current query.
     */
    private List<ToolCall> determineNextActions() {
        List<ChatMessage> messages = buildPrompt();

        // Ask LLM for next action with tools
        var tools = createToolSpecifications();
        var response = coder.sendMessage(coder.models.searchModel(), null, messages, tools);
        totalUsage = TokenUsage.sum(totalUsage, response.tokenUsage());

        // Parse response into potentially multiple actions
        return parseResponse(response.aiMessage());
    }

    /**
     * Create tool specifications for the LLM based on allowed actions.
     */
    private List<ToolSpecification> createToolSpecifications() {
        List<ToolSpecification> tools = new ArrayList<>();

        if (true || allowSearch) {
            tools.add(dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationFrom(
                    getMethodByName("searchSymbols")));
            tools.add(dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationFrom(
                    getMethodByName("getUsages")));
        }

        if (true || allowSubstringSearch) {
            tools.add(dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationFrom(
                    getMethodByName("searchSubstrings")));
        }

        if (true || allowPagerank) {
            tools.add(dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationFrom(
                    getMethodByName("getRelatedClasses")));
        }

        if (true || allowInspect) {
            tools.add(dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationFrom(
                    getMethodByName("getClassSkeletons")));
            tools.add(dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationFrom(
                    getMethodByName("getClassSources")));
            tools.add(dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationFrom(
                    getMethodByName("getMethodSources")));
        }

        if (true || allowAnswer) {
            tools.add(dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationFrom(
                    getMethodByName("answer")));

            // Always allow abort when answer is allowed
            tools.add(dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationFrom(
                    getMethodByName("abort")));
        }

        return tools;
    }

    /**
     * Helper method to get a Method by name.
     *
     * @param methodName The name of the method to find
     * @return The Method object or null if not found
     */
    private java.lang.reflect.Method getMethodByName(String methodName) {
        for (java.lang.reflect.Method method : getClass().getMethods()) {
            if (method.getName().equals(methodName)) {
                return method;
            }
        }
        throw new IllegalArgumentException("Method not found: " + methodName);
    }

    /**
     * Build the system prompt for determining the next action.
     */
    private List<ChatMessage> buildPrompt() {
        List<ChatMessage> messages = new ArrayList<>();

        // System prompt outlining capabilities
        var systemPrompt = new StringBuilder();
        systemPrompt.append("""
        You are a code search agent that helps find relevant code based on queries.
        Even if not explicitly stated, the query should be understood to refer to the current codebase,
        and not a general-knowledge question.
        Your goal is to find code definitions, implementations, and usages that answer the user's query.
        """.stripIndent());

        // Add knowledge gathered during search
        if (!knowledge.isEmpty()) {
            var collected = knowledge.stream().map(t -> systemPrompt.append("""
            <entry description="%s">
            %s
            </entry>
            """.stripIndent().formatted(t._1, t._2)))
                    .collect(Collectors.joining("\n"));
            systemPrompt.append("\n<knowledge>\n%s\n</knowledge>\n".formatted(collected));
        }

        var sysPromptStr = systemPrompt.toString();
        messages.add(new SystemMessage(sysPromptStr));
        // log checksum of system prompt
        var crc32 = new CRC32();
        crc32.update(sysPromptStr.getBytes());
        long checksum = crc32.getValue();
        logger.debug("System prompt length / checksum: {} / {}", sysPromptStr.length(), checksum);

        // Add action history to user message
        StringBuilder userActionHistory = new StringBuilder();
        if (!actionHistory.isEmpty()) {
            userActionHistory.append("\n<action-history>\n");
            for (int i = 0; i < actionHistory.size(); i++) {
                var step = actionHistory.get(i);
                userActionHistory.append(formatHistory(step, i + 1));
            }
            userActionHistory.append("</action-history>\n");
        }

        var instructions = """
        <query>
        %s>
        </query>
        
        Determine the next tool to call to search for code related to the query.
        Round trips are expensive! If you have multiple search terms to learn about, group them in a single call.
        Of course, `abort` and `answer` tools cannot be composed with others.
        Each tool call must include a `learnings` parameter that records what you learned
        from the MOST RECENT action in enough detail that you never have to repeat that action
        This will be the ONLY content from that action saved in history, you will not see the full response again,
        so make it comprehensive!
          - Bad: Found several classes and methods related to the query
          - Good: Found classes org.foo.bar.X and org.foo.baz.Y, and methods org.foo.qux.Z.method1 and org.foo.fizz.W.method2 related to the query
        """.formatted(query);
        if (symbolsFound) {
            // Switch to beast mode if we're running out of time
            if (actionHistorySize() > 0.8 * TOKEN_BUDGET) {
                instructions = """
                <beast-mode>
                🔥 MAXIMUM PRIORITY OVERRIDE! 🔥
                - YOU MUST FINALIZE RESULTS NOW WITH AVAILABLE INFORMATION
                - USE DISCOVERED CODE UNITS TO PROVIDE BEST POSSIBLE ANSWER
                - FAILURE IS NOT AN OPTION
                </beast-mode>
                """.stripIndent();
                // Force finalize only
                allowAnswer = true;
                allowSearch = false;
                allowSubstringSearch = false;
                allowInspect = false;
                allowPagerank = false;
            }
        } else {
            instructions += """
            Start with broad searches, and then explore more specific code units once you find a foothold.
            For example, if the user is asking
            [how do Cassandra reads prevent compaction from invalidating the sstables they are referencing]
            then we should start with searchSymbols([".*SSTable.*", ".*Compaction.*", ".*reference.*"],
            instead of a more specific pattern like ".*SSTable.*compaction.*" or ".*compaction.*invalidation.*".
            Remember to review your previous steps -- the search results won't change so don't repeat yourself.
            """;
        }
        messages.add(new UserMessage(userActionHistory + instructions.stripIndent()));

        return messages;
    }

    private String formatHistory(ToolCall step, int i) {
        // Strip learnings from the arguments map in the history
        var arguments = new HashMap<>(step.argumentsMap());
        arguments.remove("learnings");

        return """
        <step sequence="%d" tool="%s">
         <arguments>
         %s
         </arguments>
         %s
        </step>
        """.stripIndent().formatted(
            i,
            step.request.name(),
            arguments,
            step.learnings != null ?
                "<learnings>\n%s\n</learnings>".formatted(step.learnings) :
                "<result>\n%s\n</result>".formatted(step.result)
        );
    }
    
    /**
     * Updates the learnings of the most recent history entry.
     */
    private void updateHistory(String learnings) {
        if (!actionHistory.isEmpty()) {
            actionHistory.getLast().learnings = learnings;
        }
    }

    /**
     * Parse the LLM response into a list of ToolCall objects.
     * This method handles both direct text responses and tool execution responses.
     * ANSWER or ABORT tools will be sorted at the end.
     */
    private List<ToolCall> parseResponse(AiMessage response) {
        if (!response.hasToolExecutionRequests()) {
            var dummyTer = ToolExecutionRequest.builder().name("MISSING_TOOL_CALL").build();
            var errorCall = new ToolCall(dummyTer, "Error: No tool execution requests found in response");
            return List.of(errorCall);
        }

        // Process each tool execution request
        var toolCalls = response.toolExecutionRequests().stream()
                .map(ToolCall::new)
                .toList();

        // If we have an Answer or Abort action, just return that
        var answerTools = toolCalls.stream()
                .filter(t -> t.getRequest().name().equals("answer") || t.getRequest().name().equals("abort"))
                .toList();

        if (!answerTools.isEmpty()) {
            return List.of(answerTools.getFirst());
        }

        return toolCalls;
    }

    @Tool("Search for symbols (class/method/field definitions) using Joern. This should usually be the first step in a search.")
    public String searchSymbols(
            @P(value = "Case-insensitive Joern regex patterns to search for code symbols. Since ^ and $ are implicitly included, YOU MUST use explicit wildcarding (e.g., .*Foo.*, Abstract.*, [a-z]*DAO) unless you really want exact matches.")
            List<String> patterns,
            @P(value = "Summary of what you learned from THE MOST RECENT step taken")
            String learnings
    ) {
        if (patterns.isEmpty()) {
            return "Cannot search definitions: patterns list is empty";
        }
        if (learnings.isBlank()) {
            return "Cannot search definitions: learnings summary is empty";
        }

        updateHistory(learnings);

        // Enable substring search after the first successful searchSymbols call
        allowSubstringSearch = true;

        Set<CodeUnit> allDefinitions = new HashSet<>();
        for (String pattern : patterns) {
            if (!pattern.isBlank()) {
                allDefinitions.addAll(analyzer.getDefinitions(pattern));
            }
        }

        if (allDefinitions.isEmpty()) {
            return "No definitions found for patterns: " + String.join(", ", patterns);
        }

        symbolsFound = true;
        logger.debug("Raw definitions: {}", allDefinitions);

        var references = new ArrayList<String>();
        for (CodeUnit definition : allDefinitions) {
            references.add(definition.reference());
        }

        // Compress results using longest common package prefix
        if (!references.isEmpty()) {
            var compressionResult = compressSymbolsWithPackagePrefix(references);
            String commonPrefix = compressionResult._1();
            List<String> compressedSymbols = compressionResult._2();
            
            if (!commonPrefix.isEmpty()) {
                return "Relevant symbols [Common package prefix: " + commonPrefix + "] " +
                       "(IMPORTANT: you MUST use full symbol names including this prefix for subsequent tool calls): " +
                       String.join(", ", compressedSymbols);
            }
        }

        return "Relevant symbols: " + String.join(", ", references);
    }

    /**
     * Compresses a list of fully qualified symbol names by finding the longest common package prefix
     * and removing it from each symbol.
     * 
     * @param symbols A list of fully qualified symbol names
     * @return A tuple containing: 1) the common package prefix, 2) the list of compressed symbol names
     */
    private Tuple2<String, List<String>> compressSymbolsWithPackagePrefix(List<String> symbols) {
        if (symbols.isEmpty()) {
            return new Tuple2<>("", List.of());
        }
        
        // Find the package parts of each symbol
        List<String[]> packageParts = symbols.stream()
            .map(s -> s.split("\\."))
            .collect(Collectors.toList());
        
        // Find longest common prefix of package parts
        String[] firstParts = packageParts.getFirst();
        int maxPrefixLength = 0;
        
        // Only consider package parts (stop before the class/method name)
        // Assume the last element is always the class or method name
        for (int i = 0; i < firstParts.length - 1; i++) {
            boolean allMatch = true;
            
            for (String[] parts : packageParts) {
                if (i >= parts.length - 1 || !parts[i].equals(firstParts[i])) {
                    allMatch = false;
                    break;
                }
            }
            
            if (allMatch) {
                maxPrefixLength = i + 1;
            } else {
                break;
            }
        }
        
        // If we have a common prefix
        if (maxPrefixLength > 0) {
            // Build the common prefix string
            String commonPrefix = String.join(".",
                Arrays.copyOfRange(firstParts, 0, maxPrefixLength)) + ".";
            
            // Remove the common prefix from each symbol
            List<String> compressedSymbols = symbols.stream()
                .map(s -> s.startsWith(commonPrefix) ? s.substring(commonPrefix.length()) : s)
                .collect(Collectors.toList());
            
            return new Tuple2<>(commonPrefix, compressedSymbols);
        }
        
        // No common prefix found
        return new Tuple2<>("", symbols);
    }
    
    /**
     * Extract text occurrences in response that match references from the originals
     */
    private static Set<String> extractMatches(String response, Set<String> originals) {
        Set<String> relevantDefinitions = new HashSet<>();
        for (var ref : originals) {
            // Look for the reference with word boundaries to avoid partial matches
            var p = Pattern.compile("\\b" + Pattern.quote(ref) + "\\b");
            var matcher = p.matcher(response);
            if (matcher.find()) {
                relevantDefinitions.add(ref);
            }
        }
        return relevantDefinitions;
    }

    /**
     * Search for usages of symbols.
     */
    @Tool("Find where symbols are used in code. Use this to discover how classes, methods, or fields are actually used throughout the codebase.")
    public String getUsages(
            @P(value = "Fully qualified symbol names (package name, class name, optional member name) to find usages for")
            List<String> symbols,
            @P(value = "Summary of what you learned from THE MOST RECENT step taken")
            String learnings
    ) {
        if (symbols.isEmpty()) {
            return "Cannot search usages: symbols list is empty";
        }
        if (learnings.isBlank()) {
            return "Cannot search usages: learnings summary is empty";
        }
        
        updateHistory(learnings);

        List<CodeUnit> allUses = new ArrayList<>();
        for (String symbol : symbols) {
            if (!symbol.isBlank()) {
                allUses.addAll(analyzer.getUses(symbol));
            }
        }

        if (allUses.isEmpty()) {
            return "No usages found for: " + String.join(", ", symbols);
        }

        var processedUsages = AnalyzerWrapper.processUsages(analyzer, allUses).code();
        return "Usages of " + String.join(", ", symbols) + ":\n\n" + processedUsages;
    }

    /**
     * Find related code using PageRank.
     */
    @Tool("Find related classes. Use this for exploring and also when you're almost done and want to double-check that you haven't missed anything.")
    public String getRelatedClasses(
            @P(value = "List of fully qualified class names.")
            List<String> classNames,
            @P(value = "Summary of what you learned from THE MOST RECENT step taken")
            String learnings
    ) {
        if (classNames.isEmpty()) {
            return "Cannot search pagerank: classNames is empty";
        }
        if (learnings.isBlank()) {
            return "Cannot search pagerank: learnings summary is empty";
        }
        
        updateHistory(learnings);

        // Create map of seeds from discovered units
        HashMap<String, Double> weightedSeeds = new HashMap<>();
        for (String className : classNames) {
            weightedSeeds.put(className, 1.0);
        }

        var pageRankResults = AnalyzerWrapper.combinedPageRankFor(analyzer, weightedSeeds);

        if (pageRankResults.isEmpty()) {
            return "No related code found via PageRank";
        }

        // Check if we need to filter by relevance (if results are > 10% of token budget)
        return pageRankResults.stream().limit(100).collect(Collectors.joining(", "));
    }

    /**
     * Get the skeletons (structures) of classes.
     */
    @Tool("Get an overview of classes' contents, including fields and method signatures. Use this to understand class structures without fetching full source code.")
    public String getClassSkeletons(
            @P(value = "Fully qualified class names to get the skeleton structures for")
            List<String> classNames,
            @P(value = "Summary of what you learned from THE MOST RECENT step taken")
            String learnings
    ) {
        if (classNames.isEmpty()) {
            return "Cannot get skeletons: class names list is empty";
        }
        
        updateHistory(learnings);

        StringBuilder result = new StringBuilder();
        Set<String> processedSkeletons = new HashSet<>();

        for (String className : classNames) {
            if (!className.isBlank()) {
                var skeletonOpt = analyzer.getSkeleton(className);
                if (skeletonOpt.isDefined()) {
                    String skeleton = skeletonOpt.get();
                    if (!processedSkeletons.contains(skeleton)) {
                        processedSkeletons.add(skeleton);
                        if (result.length() > 0) {
                            result.append("\n\n");
                        }
                        result.append(skeleton);
                    }
                }
            }
        }

        if (result.length() == 0) {
            return "No skeletons found for classes: " + String.join(", ", classNames);
        }

        return result.toString();
    }

    /**
     * Get the full source code of classes.
     */
    @Tool("Get the full source code of classes. This is expensive, so prefer using skeletons or method sources when possible. Use this when you need the complete implementation details, or if you think multiple methods in the classes may be relevant.")
    public String getClassSources(
            @P(value = "Fully qualified class names to retrieve the full source code for")
            List<String> classNames,
            @P(value = "Summary of what you learned from THE MOST RECENT step taken")
            String learnings
    ) {
        if (classNames.isEmpty()) {
            return "Cannot get class sources: class names list is empty";
        }
        
        updateHistory(learnings);

        StringBuilder result = new StringBuilder();
        Set<String> processedSources = new HashSet<>();

        for (String className : classNames) {
            if (!className.isBlank()) {
                String classSource = analyzer.getClassSource(className);
                if (classSource != null && !classSource.isEmpty() && !processedSources.contains(classSource)) {
                    processedSources.add(classSource);
                    if (result.length() > 0) {
                        result.append("\n\n");
                    }
                    result.append("Source code of ").append(className).append(":\n\n").append(classSource);
                }
            }
        }

        if (result.length() == 0) {
            return "No sources found for classes: " + String.join(", ", classNames);
        }

        return result.toString();
    }

    /**
     * Get the source code of methods.
     */
    @Tool("Get the source code of specific methods. Use this to examine the implementation of particular methods without retrieving the entire classes.")
    public String getMethodSources(
            @P(value = "Fully qualified method names (package name, class name, method name) to retrieve sources for")
            List<String> methodNames,
            @P(value = "Summary of what you learned from THE MOST RECENT step taken")
            String learnings
    ) {
        if (methodNames.isEmpty()) {
            return "Cannot get method sources: method names list is empty";
        }
        
        updateHistory(learnings);

        StringBuilder result = new StringBuilder();
        Set<String> processedMethodSources = new HashSet<>();

        for (String methodName : methodNames) {
            if (!methodName.isBlank()) {
                var methodSourceOpt = analyzer.getMethodSource(methodName);
                if (methodSourceOpt.isDefined()) {
                    String methodSource = methodSourceOpt.get();
                    if (!processedMethodSources.contains(methodSource)) {
                        processedMethodSources.add(methodSource);
                        if (result.length() > 0) {
                            result.append("\n\n");
                        }
                        result.append(methodSource);
                    }
                }
            }
        }

        if (result.length() == 0) {
            return "No sources found for methods: " + String.join(", ", methodNames);
        }

        return result.toString();
    }

    @Tool("Provide a final answer to the query. Use this when you have enough information to fully address the query.")
    public String answer(
            @P(value = "Comprehensive explanation that answers the query. Include relevant source code snippets and explain how they relate to the query.")
            String explanation,
            @P(value = "List of fully qualified class names (FQCNs) of all classes relevant to the explanation.")
            List<String> classNames
    ) {
        if (explanation.isBlank()) {
            throw new IllegalArgumentException("Empty or missing explanation parameter");
        }

        logger.debug("Answer: {}", explanation);
        logger.debug("Referenced classes: {}", classNames);

        return explanation;
    }

    @Tool("Search for classes whose text contents match Java regular expression patterns. This is slower than searchSymbols but can find usages of external dependencies and comment strings.")
    public String searchSubstrings(
            @P(value = "Java-style regex patterns to search for within file contents. Unlike searchSymbols this does not automatically include any implicit anchors or case insensitivity.")
            List<String> patterns,
            @P(value = "Summary of what you learned from THE MOST RECENT step taken")
            String learnings
    ) {
        if (patterns.isEmpty()) {
            return "Cannot search substrings: patterns list is empty";
        }
        if (learnings.isBlank()) {
            return "Cannot search substrings: learnings summary is empty";
        }
        
        updateHistory(learnings);

        logger.debug("Searching file contents for patterns: {}", patterns);

        try {
            // Compile all regex patterns
            List<Pattern> compiledPatterns = patterns.stream()
                    .filter(p -> !p.isBlank())
                    .map(Pattern::compile)
                    .toList();

            if (compiledPatterns.isEmpty()) {
                return "No valid patterns provided";
            }

            // Get all tracked files from GitRepo and process them functionally
            var matchingClasses = GitRepo.instance.getTrackedFiles().parallelStream().map(file -> {
                        try {
                            RepoFile repoFile = new RepoFile(GitRepo.instance.getRoot(), file.toString());
                            String fileContents = new String(Files.readAllBytes(repoFile.absPath()));

                            // Return the repoFile if its contents match any of the patterns, otherwise null
                            for (Pattern compiledPattern : compiledPatterns) {
                                if (compiledPattern.matcher(fileContents).find()) {
                                    return repoFile;
                                }
                            }
                            return null;
                        } catch (Exception e) {
                            logger.debug("Error processing file {}: {}", file, e.getMessage());
                            return null;
                        }
                    })
                    .filter(Objects::nonNull) // Filter out nulls (files with errors or no matches)
                    .flatMap(repoFile -> {
                        try {
                            // For each matching file, get all non-inner classes and flatten them into the stream
                            return analyzer.getClassesInFile(repoFile).stream()
                                    .map(CodeUnit::reference)
                                    .filter(reference -> !reference.contains("$"));
                        } catch (Exception e) {
                            logger.debug("Error getting classes for file {}: {}", repoFile, e.getMessage());
                            return Stream.empty();
                        }
                    })
                    .collect(Collectors.toSet()); // Collect to a set to eliminate duplicates

            if (matchingClasses.isEmpty()) {
                return "No classes found with content matching patterns: " + String.join(", ", patterns);
            }

            return "Classes with content matching patterns: " + String.join(", ", matchingClasses);
        } catch (Exception e) {
            logger.error("Error searching file contents", e);
            return "Error searching file contents: " + e.getMessage();
        }
    }

    @Tool("Abort the search process when you determine the question is not relevant to this codebase or when an answer cannot be found. Use this as a last resort when you're confident no useful answer can be provided.")
    public String abort(
            @P(value = "Explanation of why the question cannot be answered or is not relevant to this codebase")
            String explanation
    ) {
        if (explanation.isBlank()) {
            throw new IllegalArgumentException("Empty or missing explanation parameter");
        }

        logger.debug("Search aborted: {}", explanation);

        return "SEARCH ABORTED: " + explanation;
    }

    /**
     * Represents a single tool execution request and its result.
     */
    public class ToolCall {
        private final ToolExecutionRequest request;
        private String result; // initially null
        private String learnings; // initially null

        public ToolCall(ToolExecutionRequest request) {
            this(request, null);
        }

        public ToolCall(ToolExecutionRequest request, String result) {
            this.request = request;
            this.result = result;
        }

        public ToolExecutionRequest getRequest() {
            return request;
        }
        
        /**
         * Parse the JSON arguments into a map
         */
        public Map<String, Object> argumentsMap() {
            try {
                ObjectMapper mapper = new ObjectMapper();
                return mapper.readValue(request.arguments(), new TypeReference<>() {});
            } catch (JsonProcessingException e) {
                logger.error("Error parsing arguments", e);
                return Map.of();
            }
        }

        /**
         * Executes the tool call by using reflection.  `result` will be modified.
         */
        public void execute() {
            if (result != null) {
                return;
            }
            try {
                // Use TER's tool name as the method name
                String methodName = request.name();
                java.lang.reflect.Method method = getMethodByName(methodName);

                // Prepare arguments array from method parameters
                var arguments = argumentsMap();
                var params = method.getParameters();
                Object[] args = new Object[params.length];
                for (int i = 0; i < params.length; i++) {
                    String paramName = params[i].getName(); // requires compilation with -parameters
                    Object value = arguments.get(paramName);
                    if (value == null) {
                        throw new IllegalArgumentException("Missing required parameter: " + paramName);
                    }
                    args[i] = value;
                }

                // Invoke the method
                Object res = method.invoke(SearchAgent.this, args);
                result = res != null ? res.toString() : "";
            } catch (Exception e) {
                logger.error("Tool method invocation error", e);
                result = "Error: " + e.getMessage();
            }
        }

        @Override
        public String toString() {
            return "ToolCall{" +
                    "toolName=" + request.name() +
                    ", arguments=" + request.arguments() +
                    ", result=" + (result != null ? "'" + result + "'" : "null") +
                    ", learnings=" + (learnings != null ? "'" + learnings + "'" : "null") +
                    '}';
        }
    }
}
