package io.github.jbellis.brokk;

import com.google.common.collect.Streams;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.output.TokenUsage;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.prompts.ArchitectPrompts;
import io.github.jbellis.brokk.tools.ToolExecutionResult;
import io.github.jbellis.brokk.tools.ToolRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ArchitectAgent {
    private static final Logger logger = LogManager.getLogger(ArchitectAgent.class);

    // Helper record to associate a SearchAgent task Future with its request
    private record SearchTask(ToolExecutionRequest request, Future<ToolExecutionResult> future) {}

    private final ContextManager contextManager;
    private final StreamingChatLanguageModel model;
    private final ToolRegistry toolRegistry;
    private final String goal;
    // History of this agent's interactions
    private final List<ChatMessage> architectMessages = new ArrayList<>();

    private TokenUsage totalUsage = new TokenUsage(0,0);

    /**
     * Constructs a BrokkAgent that can handle multi-step tasks and sub-tasks.
     * @param goal The initial user instruction or goal for the agent.
     */
    public ArchitectAgent(ContextManager contextManager, StreamingChatLanguageModel model, ToolRegistry toolRegistry, String goal) {
        this.contextManager = Objects.requireNonNull(contextManager, "contextManager cannot be null");
        this.model = Objects.requireNonNull(model, "model cannot be null");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry cannot be null");
        this.goal = Objects.requireNonNull(goal, "goal cannot be null");
    }

    /**
     * A tool for finishing the plan with a final answer. Similar to 'answerSearch' in SearchAgent.
     */
    @Tool("Provide a final answer to the multi-step plan. Use this when you're done or have everything you need.")
    public void projectFinished(
            @P("A final explanation or summary addressing all tasks. Format it in Markdown if desired.")
            String finalExplanation
    ) {
        var msg = "Architect Agent project complete: %s".formatted(finalExplanation);
        logger.debug(msg);
        contextManager.getIo().systemOutput(msg);
    }

    /**
     * A tool to abort the plan if you cannot proceed or if it is irrelevant.
     */
    @Tool("Abort the entire plan. Use this if the tasks are impossible or out of scope.")
    public void abortProject(
            @P("Explain why the plan must be aborted.")
            String reason
    ) {
        var msg = "Architect Agent project aborted: %s".formatted(reason);
        logger.debug(msg);
        contextManager.getIo().systemOutput(msg);
    }

    /**
     * A tool that invokes the CodeAgent to solve the current top task using the given instructions.
     * The instructions can incorporate the stack's current top task or anything else.
     */
    @Tool("Invoke the CodeAgent to solve or implement the current task. Provide complete instructions. The plan is visible to the CodeAgent and other tools.")
    public String callCodeAgent(
            @P("Detailed instructions for the CodeAgent, typically referencing the current plan. Code Agent can figure out how to change the code at the syntax level but needs clear instructions of what exactly you want changed")
            String instructions
    ) {
        logger.debug("callCodeAgent invoked with instructions: {}", instructions);
        var result = CodeAgent.runSession(contextManager, model, instructions, false);
        var entry = contextManager.addToHistory(result, true);
        var stopDetails = result.stopDetails();
        String summary = """
        CodeAgent concluded.
        <summary>
        %s
        </summary>
        
        <stop-details>
        %s
        </stop-details>
        """.stripIndent().formatted(entry.summary(), stopDetails);
        logger.debug("Summary for callCodeAgent: {}", summary);
        return summary;
    }

    /**
     * A tool that invokes the SearchAgent to perform searches and analysis based on a query.
     * The SearchAgent will decide which specific search/analysis tools to use (e.g., searchSymbols, getFileContents).
     * The results are added as a context fragment.
     */
    @Tool("Invoke the Search Agent to find information relevant to the given query. Searching is much slower than adding content to the Workspace directly if you know what you are looking for, but the Agent can find things that you don't know the exact name of. ")
    public String callSearchAgent(
            @P("The search query or question for the SearchAgent. Query in English (not just keywords)")
            String query
    ) {
        logger.debug("callSearchAgent invoked with query: {}", query);

        // Instantiate and run SearchAgent
        var searchAgent = new SearchAgent(query, contextManager, model, toolRegistry);
        var searchResult = searchAgent.execute();

        // TODO add result to Conversation History

        assert searchResult != null;
        if (searchResult.stopDetails().reason() != SessionResult.StopReason.SUCCESS) {
            logger.warn("SearchAgent returned null or empty result for query: {}", query);
            return searchResult.stopDetails().toString();
        }

        var relevantClasses = searchResult.output().parsedFragment().sources(contextManager.getProject()).stream()
                .map(CodeUnit::fqName)
                .collect(Collectors.joining(","));
        var stringResult = """
            %s

            Full list of potentially relevant classes:
            %s
            """.stripIndent().formatted(searchResult.output().text(), relevantClasses);

        logger.debug(stringResult);
        return stringResult;
    }

    /**
     * Run the multi-step project until we either produce a final answer, abort, or run out of tasks.
     * This uses an iterative approach, letting the LLM decide which tool to call each time.
     */
    public void execute() {
        contextManager.getIo().systemOutput("Architect Agent engaged: `%s...`".formatted(SessionResult.getShortDescription(goal)));
        var coder = contextManager.getCoder(model, "Architect: " + goal);

        while (true) {
            // 3) Build the prompt messages, including history
            var messages = buildPrompt();

            // 4) Figure out which tools are allowed in this step
            var genericToolNames = List.of(
                    "addEditableFiles",
                    "addReadOnlyFiles",
                    "addUrlContents",
                    "addTextFragment",
                    "addUsagesFragment",
                    "addClassSkeletonsFragment",
                    "addMethodSourcesFragment",
                    "addCallGraphToFragment",
                    "addCallGraphFromFragment",
                    "dropFragments"
            );
            var toolSpecs = new ArrayList<>(toolRegistry.getRegisteredTools(genericToolNames));
            // Add the BrokkAgent-specific tools
            toolSpecs.addAll(toolRegistry.getTools(this, List.of("projectFinished", "abortProject", "callCodeAgent", "callSearchAgent")));

            // 5) Ask the LLM for the next step with tools required
            var response = coder.sendMessage(messages, toolSpecs, ToolChoice.REQUIRED, false);
            if (response.cancelled()) {
                var msg = "Project canceled by user. Stopping now.";
                logger.debug(msg);
                contextManager.getIo().systemOutput(msg);
                return;
            }
            if (response.error() != null) {
                logger.debug("Error from LLM while deciding next action: {}", response.error().getMessage());
                contextManager.getIo().systemOutput("Error from LLM while deciding next action (see debug log for details)");
                return;
            }
            if (response.chatResponse() == null || response.chatResponse().aiMessage() == null) {
                var msg = "Empty LLM response. Stopping plan now.";
                logger.debug(msg);
                contextManager.getIo().systemOutput(msg);
                return;
            }
            logger.debug("LLM response: {}", response);

            totalUsage = TokenUsage.sum(totalUsage, response.chatResponse().tokenUsage());
            // Add the request and response to message history
            var aiMessage = response.chatResponse().aiMessage();
            architectMessages.add(messages.getLast());
            architectMessages.add(aiMessage);

            var toolRequests = aiMessage.toolExecutionRequests();
            logger.debug("Tool requests are {}", toolRequests);

            // execute tool calls in the following order:
            // 1. projectFinished
            // 2. abortProject
            // 4. (everything else)
            // 5. searchAgent
            // 6. codeAgent
            ToolExecutionRequest answerReq = null, abortReq = null;
            var searchAgentReqs = new ArrayList<ToolExecutionRequest>();
            var codeAgentReqs = new ArrayList<ToolExecutionRequest>();
            var otherReqs = new ArrayList<ToolExecutionRequest>();
            for (var req : toolRequests) {
                if (req.name().equals("projectFinished")) {
                    answerReq = req;
                } else if (req.name().equals("abortProject")) {
                    abortReq = req;
                } else if (req.name().equals("callSearchAgent")) {
                    searchAgentReqs.add(req);
                } else if (req.name().equals("callCodeAgent")) {
                    codeAgentReqs.add(req);
                } else {
                    otherReqs.add(req);
                }
            }

            // 6) If we see "projectFinished" or "abortProject", handle it and then exit
            if (answerReq != null) {
                logger.debug("LLM decided to projectFinished. We'll finalize and stop.");
                var result = toolRegistry.executeTool(this, answerReq);
                logger.debug("Plan final answer: {}", result.resultText());
                return;
            }
            if (abortReq != null) {
                logger.debug("LLM decided to abortProject. We'll finalize and stop.");
                var result = toolRegistry.executeTool(this, abortReq);
                logger.debug("Plan aborted: {}", result.resultText());
                return;
            }

            // 7) Execute remaining tool calls in the desired order:

            // "Other" (mostly context manipulations) should all be fast, parallel execution is not needed
            for (var req : otherReqs) {
                var toolResult = toolRegistry.executeTool(req);
                architectMessages.add(ToolExecutionResultMessage.from(req, toolResult.resultText()));
                logger.debug("Executed tool '{}' => result: {}", req.name(), toolResult.resultText());
            }

            // Submit search agent tasks to run in the background
            var searchAgentTasks = new ArrayList<SearchTask>();
            for (var req : searchAgentReqs) {
                Callable<ToolExecutionResult> task = () -> {
                    var result = toolRegistry.executeTool(this, req);
                    logger.debug("Finished SearchAgent task for request: {}", req.name());
                    return result;
                };
                var taskDescription = "SearchAgent: " + SessionResult.getShortDescription(req.arguments());
                var future = contextManager.submitBackgroundTask(taskDescription, task);
                searchAgentTasks.add(new SearchTask(req, future));
            }

            // Collect search results, handling potential interruptions/cancellations
            boolean interrupted = false;
            for (var searchTask : searchAgentTasks) {
                var request = searchTask.request();
                var future = searchTask.future();
                try {
                    // If already interrupted, don't wait, just try to cancel
                    if (interrupted) {
                        future.cancel(true);
                        continue;
                    }
                    var toolResult = future.get(); // Wait for completion
                    architectMessages.add(ToolExecutionResultMessage.from(toolResult.request(), toolResult.resultText()));
                    logger.debug("Collected result for tool '{}' => result: {}", toolResult.request().name(), toolResult.resultText());
                } catch (CancellationException e) {
                    logger.warn("SearchAgent task for request '{}' was cancelled.", request.name());
                    interrupted = true;
                } catch (InterruptedException e) {
                    logger.warn("Interrupted while waiting for SearchAgent task '{}'.", request.name());
                    Thread.currentThread().interrupt(); // Restore interrupt status
                    interrupted = true;
                } catch (ExecutionException e) {
                    logger.warn("Error executing SearchAgent task '{}'", request.name(), e.getCause());
                    var errorMessage = "Error executing SearchAgent '%s': %s".formatted(request.name(), e.getCause().getMessage());
                    architectMessages.add(ToolExecutionResultMessage.from(request, errorMessage));
                }
            }

            // If we were interrupted, cancel remaining futures and exit the main execute loop.
            if (interrupted) {
                logger.debug("ArchitectAgent execution interrupted, cancelling remaining SearchAgent tasks.");
                searchAgentTasks.forEach(p -> p.future().cancel(true)); // Cancel any not already processed/cancelled
                contextManager.getIo().systemOutput("ArchitectAgent cancelled by user");
                return;
            }

            // code agent calls are done serially so they don't stomp on each others' feet
            for (var req : codeAgentReqs) {
                var toolResult = toolRegistry.executeTool(this, req);
                architectMessages.add(ToolExecutionResultMessage.from(req, toolResult.resultText()));
                logger.debug("Executed tool '{}' => result: {}", req.name(), toolResult.resultText());
            }
        }
    }

    /**
     * Build the system/user messages for the LLM:
     *   - System message explaining that this is a multi-step plan agent.
     *   - A user message showing the current stack top, the entire stack,
     *     the top-10 PageRank classes, and any relevant instructions.
     */
    private List<ChatMessage> buildPrompt() {
        // top 10 related classes
        String topClassesRaw = "";
        var analyzer = contextManager.getAnalyzer();
        if (!analyzer.isEmpty()) {
            var ac = contextManager.selectedContext().setAutoContextFiles(10).buildAutoContext();
            topClassesRaw = ac.text();
        }
        var topClassesText = topClassesRaw.isBlank() ? "" : """
        <related_classes>
        With every prompt I will suggest related classes that you may wish to add to the context. Code Agent will not
        see them unless you explicitly add them. If they are not relevant, just ignore them:
        
        %s
        </related_classes>
        """.stripIndent().formatted(topClassesRaw);

        var userMsg = """
        %s

        <goal>
        %s
        </goal>

        Please decide the next tool action(s) to make progress towards resolving the goal.
        
        You are encouraged to call multiple tools simultaneously, especially
        - when searching for relevant code: you can invoke callSearchAgent multiple times at once
        - when manipulating Workspace context: make all desired manipulations at once

        Conversely, it does not make sense to call multiple tools with
        - callCodeAgent, since you want to see what changes get made before proceeding
        - projectFinished or abortProject, since they terminate execution

        When you are done, call projectFinished or abortProject.
        """.stripIndent().formatted(topClassesText, goal);

        // Concatenate system prompts (which should handle incorporating history) and the latest user message
        return Streams.concat(ArchitectPrompts.instance.collectMessages(contextManager, architectMessages).stream(),
                              Stream.of(new UserMessage(userMsg))).toList();
    }
}
