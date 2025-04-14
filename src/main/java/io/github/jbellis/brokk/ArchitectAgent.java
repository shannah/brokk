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
import io.github.jbellis.brokk.tools.ToolRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ArchitectAgent {
    private static final Logger logger = LogManager.getLogger(ArchitectAgent.class);

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
            @P("Detailed instructions for the CodeAgent, typically referencing the current plan. Code Agent can figure out how to change the code at the syntax level but needs clear instructions of what exactly you want changed")
            String instructions
    ) {
        logger.debug("callCodeAgent invoked with instructions: {}", instructions);
        var result = CodeAgent.runSession(contextManager, model, instructions, false);
        contextManager.addToHistory(result, true);
        var stopDetails = result.stopDetails();
        String summary = """
        CodeAgent concluded: %s
        """.formatted(stopDetails).stripIndent();
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
            @P("The search query or question for the SearchAgent. Query in English (not just keywords)")
            String query
    ) {
        logger.debug("callSearchAgent invoked with query: {}", query);

        // Instantiate and run SearchAgent
        var searchAgent = new SearchAgent(query, contextManager, model, toolRegistry);
        var searchResult = searchAgent.execute();

        // TODO add result to Conversation History

        assert searchResult != null;
        if (searchResult instanceof ContextFragment.StringFragment) {
            logger.warn("SearchAgent returned null or empty result for query: {}", query);
            return searchResult.text();
        }

        var relevantClasses = searchResult.sources(contextManager.getProject()).stream()
                .map(CodeUnit::fqName)
                .collect(Collectors.joining(","));
        var stringResult = """
            %s

            Full list of potentially relevant classes:
            %s
            """.stripIndent().formatted(searchResult.text(), relevantClasses);

        logger.debug(stringResult);
        return stringResult;
    }


    /**
     * Run the multi-step project until we either produce a final answer, abort, or run out of tasks.
     * This uses an iterative approach, letting the LLM decide which tool to call each time.
     */
    public void execute() {
        var currentPlan = contextManager.selectedContext().getPlan();
        logger.debug("BrokkAgent starting project with plan: {}", currentPlan);

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
            var response = contextManager.getCoder().sendMessage(model, messages, toolSpecs, ToolChoice.REQUIRED, false);
            if (response.cancelled()) {
                logger.debug("Project canceled by user. Stopping now.");
                return;
            }
            if (response.error() != null) {
                logger.debug("Error from LLM while deciding next action: {}", response.error().getMessage());
                return;
            }
            if (response.chatResponse() == null || response.chatResponse().aiMessage() == null) {
                logger.debug("Empty LLM response. Stopping plan now.");
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
            // 3. updatePlan
            // 4. (everything else)
            // 5. searchAgent
            // 6. codeAgent
            ToolExecutionRequest answerReq = null, abortReq = null;
            var updatePlanReqs = new ArrayList<ToolExecutionRequest>();
            var searchAgentReqs = new ArrayList<ToolExecutionRequest>();
            var codeAgentReqs = new ArrayList<ToolExecutionRequest>();
            var otherReqs = new ArrayList<ToolExecutionRequest>();
            for (var req : toolRequests) {
                if (req.name().equals("projectFinished")) {
                    answerReq = req;
                } else if (req.name().equals("abortProject")) {
                    abortReq = req;
                } else if (req.name().equals("updatePlan")) {
                    updatePlanReqs.add(req);
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
            // First updatePlan, then any other tools, then callSearchAgent, then callCodeAgent.
            // First updatePlan, then any other tools, then callSearchAgent, then callCodeAgent.
            for (var req : updatePlanReqs) {
                var toolResult = toolRegistry.executeTool(this, req);
                architectMessages.add(ToolExecutionResultMessage.from(req, toolResult.resultText()));
                logger.debug("Executed tool '{}' => result: {}", req.name(), toolResult.resultText());
            }
            for (var req : otherReqs) {
                var toolResult = toolRegistry.executeTool(req);
                architectMessages.add(ToolExecutionResultMessage.from(req, toolResult.resultText()));
                logger.debug("Executed tool '{}' => result: {}", req.name(), toolResult.resultText());
            }
            for (var req : searchAgentReqs) {
                var toolResult = toolRegistry.executeTool(this, req);
                architectMessages.add(ToolExecutionResultMessage.from(req, toolResult.resultText()));
                logger.debug("Executed tool '{}' => result: {}", req.name(), toolResult.resultText());
            }
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
        """;

        // plan
        var currentPlan = contextManager.selectedContext().getPlan();
        var planText = currentPlan != null ? currentPlan.text() : "(none)";

        var userMsg = """
            %s

            <goal>
            %s
            </goal>

            <plan>
            %s
            </plan>

            Please decide the next tool action(s) to make progress towards resolving the current task.
            """.stripIndent().formatted(topClassesText, goal, planText);

        // Concatenate system prompts (which should handle incorporating history) and the latest user message
        return Streams.concat(ArchitectPrompts.instance.collectMessages(contextManager, architectMessages).stream(),
                              Stream.of(new UserMessage(userMsg))).toList();
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
