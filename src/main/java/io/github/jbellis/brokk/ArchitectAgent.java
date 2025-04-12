package io.github.jbellis.brokk;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.output.TokenUsage;
import io.github.jbellis.brokk.prompts.ArchitectPrompts;
import io.github.jbellis.brokk.tools.ToolRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

public class ArchitectAgent {
    private static final Logger logger = LogManager.getLogger(ArchitectAgent.class);

    private final ContextManager contextManager;
    private final StreamingChatLanguageModel model;
    private final ToolRegistry toolRegistry;

    private TokenUsage totalUsage = new TokenUsage(0,0);

    /**
     * Constructs a BrokkAgent that can handle multi-step tasks and sub-tasks.
     */
    public ArchitectAgent(ContextManager contextManager, StreamingChatLanguageModel model, ToolRegistry toolRegistry) {
        this.contextManager = Objects.requireNonNull(contextManager, "contextManager cannot be null");
        this.model = Objects.requireNonNull(model, "model cannot be null");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry cannot be null");
    }

    /**
     * A tool for finishing the plan with a final answer. Similar to 'answerSearch' in SearchAgent.
     */
    @Tool("Provide a final answer to the multi-step plan. Use this when you're done or have everything you need.")
    public String projectFinished(
            @P("A final explanation or summary addressing all tasks. Format it in Markdown if desired.")
            String finalExplanation
    ) {
        logger.debug("Plan concluded with answer: {}", finalExplanation);
        // Return it so the user sees it as a final conclusion
        return finalExplanation;
    }

    /**
     * A tool to abort the plan if you cannot proceed or if it is irrelevant.
     */
    @Tool("Abort the entire plan. Use this if the tasks are impossible or out of scope.")
    public String abortProject(
            @P("Explain why the plan must be aborted.")
            String reason
    ) {
        logger.debug("Plan aborted with reason: {}", reason);
        return "Aborted: " + reason;
    }

    /**
     * A tool that invokes the CodeAgent to solve the current top task using the given instructions.
     * The instructions can incorporate the stack's current top task or anything else.
     */
    @Tool("Invoke the CodeAgent to solve or implement the current task. Provide complete instructions. The plan is visible to the CodeAgent and other tools.")
    public String callCodeAgent(
            @P("Detailed instructions for the CodeAgent, typically referencing the current plan.")
            String instructions
    ) {
        logger.debug("callCodeAgent invoked with instructions: {}", instructions);
        var result = CodeAgent.runSession(contextManager, model, instructions);
        contextManager.addToHistory(result, true);
        var stopReason = result.stopReason();
        String summary = """
        CodeAgent concluded with stop reason: "%s."
        """.formatted(stopReason).stripIndent();
        logger.debug("Summary for callCodeAgent: {}", summary);
        return summary;
    }

    /**
     * A tool that invokes the SearchAgent to perform searches and analysis based on a query.
     * The SearchAgent will decide which specific search/analysis tools to use (e.g., searchSymbols, getFileContents).
     * The results are added as a context fragment.
     */
    @Tool("Invoke the SearchAgent to find information relevant to the given query. It will add its findings to the context automatically.")
    public String callSearchAgent(
            @P("The search query or question for the SearchAgent.")
            String query
    ) {
        logger.debug("callSearchAgent invoked with query: {}", query);

        // Instantiate and run SearchAgent, passing all required arguments
        var searchAgent = new SearchAgent(query, contextManager, model, toolRegistry);
        var searchResult = searchAgent.execute(); // Correct method is execute(), query passed to constructor

        if (searchResult == null || searchResult.text() == null || searchResult.text().isBlank()) {
            logger.warn("SearchAgent returned null or empty result for query: {}", query);
            return "SearchAgent returned no results.";
        }

        // Add the fragment using ContextManager
        var querySummary = contextManager.addSearchFragment(searchResult);

        // The full result text is in the context, so just return a confirmation
        String summary;
        try {
            summary = "SearchAgent completed. Results added to context fragment #%d: %s"
                    .formatted(searchResult.id(), querySummary.get());
        } catch (InterruptedException |ExecutionException e) {
            throw new RuntimeException(e);
        }
        logger.debug(summary);
        return summary;
    }


    /**
     * Run the multi-step project until we either produce a final answer, abort, or run out of tasks.
     * This uses an iterative approach, letting the LLM decide which tool to call each time.
     */
    public void execute() {
        var currentPlan = contextManager.selectedContext().getPlan();
        logger.info("BrokkAgent starting project with plan: {}", currentPlan);

        while (true) {
            // 2) Provide top-10 PageRank classes in the prompt each iteration
            var topClassesPlaintext = fetchTop10PagerankClasses();

            // 3) Build the prompt to let the LLM choose a tool
            var messages = buildPrompt(topClassesPlaintext);

            // 4) Figure out which tools are allowed in this step
            var genericToolNames = List.of(
                    "addEditableFiles",
                    "addReadOnlyFiles",
                    "addUrlContents",
                    "addTextFragment",
                    "addUsagesFragment",
                    "addClassSkeletonsFragment",
                    "addMethodSourcesFragment",
                    "addClassSourcesFragment",
                    "addCallGraphToFragment",
                    "addCallGraphFromFragment",
                    "dropFragments"
            );
            var toolSpecs = new ArrayList<>(toolRegistry.getRegisteredTools(genericToolNames));
            // Add the BrokkAgent-specific tools
            toolSpecs.addAll(toolRegistry.getTools(this.getClass(), List.of("projectFinished", "abortProject", "callCodeAgent", "pushTasks", "callSearchAgent", "popTask")));

            // 5) Ask the LLM for the next step with tools required
            var response = contextManager.getCoder().sendMessage(model, messages, toolSpecs, ToolChoice.REQUIRED, false);
            if (response.cancelled()) {
                logger.info("Project canceled by user. Stopping now.");
                return;
            }
            if (response.error() != null) {
                logger.warn("Error from LLM while deciding next action: {}", response.error().getMessage());
                return;
            }
            if (response.chatResponse() == null || response.chatResponse().aiMessage() == null) {
                logger.warn("Empty LLM response. Stopping plan now.");
                return;
            }

            totalUsage = TokenUsage.sum(totalUsage, response.chatResponse().tokenUsage());
            // parse the tool requests
            var aiMessage = response.chatResponse().aiMessage();
            if (!aiMessage.hasToolExecutionRequests()) {
                logger.debug("No tool requests found in LLM response. Possibly just final text. We'll end plan.");
                return;
            }
            var toolRequests = aiMessage.toolExecutionRequests();

            // If there's an answer or abort, we handle that first and then stop
            ToolExecutionRequest answerReq = null, abortReq = null;
            List<ToolExecutionRequest> otherReqs = new ArrayList<>();
            for (var req : toolRequests) {
                if (req.name().equals("projectFinished")) {
                    answerReq = req;
                } else if (req.name().equals("abortProject")) {
                    abortReq = req;
                } else {
                    otherReqs.add(req);
                }
            }

            // 6) If we see "projectFinished" or "abortProject", handle it and then exit
            if (answerReq != null) {
                logger.debug("LLM decided to projectFinished. We'll finalize and stop.");
                var result = toolRegistry.executeTool(answerReq);
                logger.info("Plan final answer: {}", result.resultText());
                return; // done
            }
            if (abortReq != null) {
                logger.debug("LLM decided to abortProject. We'll finalize and stop.");
                var result = toolRegistry.executeTool(abortReq);
                logger.info("Plan aborted: {}", result.resultText());
                return; // done
            }

            // 7) If we have other tool calls, execute them. In principle we can do them sequentially.
            for (var request : otherReqs) {
                // If it's a duplicate "pushTasks" or "callCodeAgent" with same arguments, etc.
                // we might skip or handle differently. But let's just do them in order for now.
                var toolResult = toolRegistry.executeTool(request);

                // If pushTasks was used, presumably the stack changed. If callCodeAgent was used, we see how it ended.
                // The LLM can keep re-iterating.
                logger.debug("Executed tool '{}' => result: {}", request.name(), toolResult.resultText());
            }
        }
    }

    /**
     * Build the system/user messages for the LLM:
     *   - System message explaining that this is a multi-step plan agent.
     *   - A user message showing the current stack top, the entire stack,
     *     the top-10 PageRank classes, and any relevant instructions.
     */
    private List<ChatMessage> buildPrompt(String topClassesPlaintext) {
        String systemMsg = """
        You are the Architect Agent, a multi-step plan manager. You have an evolving long-range plan.

        In each step, you must pick the best tool to call. The main tools are:
          1) updatePlan => provide the complete updated plan
          2) callSearchAgent => find relevant code so you can decide what to add to the context for the Code Agent
          3) context manipulations => add or drop files/fragments to make them visible to the Code Agent
          4) callCodeAgent => do coding/implementation
          5) projectFinished => finalize the project with a complete explanation/solution
          6) abortProject => give up if it's unsolvable or irrelevant
        
        Search Agent and Code Agent both have tools that you do not have access to for searching
        and code editing, respectively.

        Your current plan and the context (files and code fragments) are visible to all agents.

        You are encouraged to call multiple tools simultaneously, especially
        - when using updatePlan, call the next tools to start working on the new plan at the same time
        - when manipulating context, make all needed manipulations at once
        
        Conversely, it does not make sense to call multiple tools with
        - callCodeAgent, since you want to see what changes get made before proceeding
        - projectFinished or abortProject, since they terminate execution

        When you are done, call projectFinished or abortProject.
        """.stripIndent();

        // Show the current plan
        var currentPlan = contextManager.selectedContext().getPlan();
        var planText = currentPlan != null ? currentPlan.text() : "(none)";

        var userMsg = """
        %s

        With every prompt I will suggest related classes that you may wish to add to the context. Code Agent will not
        see them unless you explicitly add them. If they are not relevant, just ignore them:
        %s

        Please decide the next tool action(s) to make progress towards resolving the current task.
        """.stripIndent().formatted(ArchitectPrompts.instance.collectMessagesNoIntro(contextManager), planText, topClassesPlaintext);

        return List.of(new SystemMessage(systemMsg), new UserMessage(userMsg));
    }

    /**
     * Provide top-10 pagerank classes as Markdown skeletons.
     */
    private String fetchTop10PagerankClasses() {
        var analyzer = contextManager.getAnalyzer();
        if (analyzer.isEmpty()) {
            return "(Code analyzer not available, can't compute PageRank.)";
        }

        var ac = contextManager.selectedContext().setAutoContextFiles(10).buildAutoContext();
        return ac.text();
    }
    /**
     * A tool that updates the complete plan with a new complete plan string.
     * The new plan will be visible to the CodeAgent and other tools.
     * You are encouraged to call other tools in conjunction with updatePlan.
     */
    @Tool("Update the complete plan. Provide the full updated plan string. This will be visible to Code Agent and Search Agent as well as your future decision-making. You should almost always update the plan after invoking other agents.")
    public String updatePlan(
            @P("The new complete plan text") String newPlan
    ) {
        var currentContext = contextManager.selectedContext();
        var newPlanFragment = new ContextFragment.PlanFragment(newPlan);
        var updatedContext = currentContext.withPlan(newPlanFragment);
        contextManager.setSelectedContext(updatedContext);
        logger.debug("Updated plan to: {}", newPlan);
        return "Plan updated.";
    }
}
