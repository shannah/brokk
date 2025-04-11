package io.github.jbellis.brokk;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.output.TokenUsage;
import io.github.jbellis.brokk.tools.ToolExecutionResult;
import io.github.jbellis.brokk.tools.ToolRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * BrokkAgent is a high-level multi-step agent that manages a stack of tasks (Strings)
 * and allows the LLM to decide the next action each iteration:
 *   - Push more tasks (subtasks),
 *   - Manipulate the context (add or drop files/fragments),
 *   - Invoke the CodeAgent to solve a task,
 *   - Use search and inspection tools,
 *   - or finalize/abort the plan.
 *
 * Each iteration also provides the top-10 PageRank classes with skeletons, so the LLM
 * can choose to add them to the context if relevant.
 */
public class BrokkAgent {

    private static final Logger logger = LogManager.getLogger(BrokkAgent.class);

    private final ContextManager contextManager;
    private final Coder coder;
    private final StreamingChatLanguageModel model;
    private final ToolRegistry toolRegistry;

    // Task stack (LIFO) containing the tasks or subtasks we want to solve
    private final Deque<String> tasks = new ArrayDeque<>();

    // Keep track of conversation or "agent state" if needed
    private final List<ToolHistoryEntry> actionHistory = new ArrayList<>();

    private TokenUsage totalUsage = new TokenUsage(0,0);

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Constructs a BrokkAgent that can handle multi-step tasks and sub-tasks.
     */
    public BrokkAgent(ContextManager contextManager, Coder coder, StreamingChatLanguageModel model, ToolRegistry toolRegistry) {
        this.contextManager = Objects.requireNonNull(contextManager, "contextManager cannot be null");
        this.coder = Objects.requireNonNull(coder, "coder cannot be null");
        this.model = Objects.requireNonNull(model, "model cannot be null");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry cannot be null");
    }

    /**
     * Public tool for pushing new tasks onto the stack.
     */
    @Tool("Push tasks (Strings) onto the plan stack. The last string in the list becomes the top of the stack.")
    public String pushTasks(
            @P("List of tasks or subtasks you want to add to the top of the plan stack.")
            List<String> newTasks
    ) {
        if (newTasks == null || newTasks.isEmpty()) {
            throw new IllegalArgumentException("No tasks provided to push!");
        }

        // Push them in reverse so the last item in newTasks is top of the stack
        for (int i = newTasks.size() - 1; i >= 0; i--) {
            tasks.push(newTasks.get(i));
        }
        logger.debug("Pushed {} tasks onto the stack: {}", newTasks.size(), newTasks);
        return "Pushed " + newTasks.size() + " new tasks onto the stack.";
    }

    /**
     * A tool for finishing the plan with a final answer. Similar to 'answerSearch' in SearchAgent.
     */
    @Tool("Provide a final answer to the multi-step plan. Use this when you're done or have everything you need.")
    public String answerPlan(
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
    public String abortPlan(
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
    @Tool("Invoke the CodeAgent to solve or implement the specified instructions. Provide user instructions or partial code to fix.")
    public String callCodeAgent(
            @P("Detailed instructions for the CodeAgent, typically referencing the current top task.")
            String instructions
    ) {
        // We'll call CodeAgent.runSession with the provided instructions
        logger.debug("callCodeAgent invoked with instructions: {}", instructions);

        // Run CodeAgent
        var sessionResult = CodeAgent.runSession(contextManager, model, instructions);
        if (sessionResult == null) {
            logger.warn("CodeAgent returned null session result (likely canceled or error).");
            return "CodeAgent was canceled or returned no updates.";
        }
        // Summarize the result for the user
        var stopReason = sessionResult.stopReason();
        var summary = """
            CodeAgent concluded with stop reason: %s
            Original instructions: %s
            """.stripIndent().formatted(stopReason, instructions);
        logger.debug(summary);
        return summary;
    }

    /**
     * Run the multi-step plan until we either produce a final answer, abort, or run out of tasks.
     * This uses an iterative approach, letting the LLM decide which tool to call each time.
     */
    public void executePlan() {
        logger.info("BrokkAgent starting plan with {} tasks in stack", tasks.size());

        while (true) {
            // 1) If no tasks remain, we can stop. The LLM might push tasks again though.
            if (tasks.isEmpty()) {
                logger.debug("No tasks left in the stack. Plan complete (no final answer?).");
                return;
            }
            // 2) Provide top-10 PageRank classes in the prompt each iteration
            String topClassesMarkdown = fetchTop10PagerankClasses();

            // 3) Build the prompt to let the LLM choose a tool
            List<ChatMessage> messages = buildPrompt(topClassesMarkdown);

            // 4) Figure out which tools are allowed in this step
            List<String> allowedToolNames = getAllowedTools();
            var toolSpecs = toolRegistry.getToolSpecifications(allowedToolNames);

            // 5) Ask the LLM for the next step with tools required
            var response = coder.sendMessage(model, messages, toolSpecs, ToolChoice.REQUIRED, false);
            if (response.cancelled()) {
                logger.info("Plan canceled by user. Stopping now.");
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
                if (req.name().equals("answerPlan")) {
                    answerReq = req;
                } else if (req.name().equals("abortPlan")) {
                    abortReq = req;
                } else {
                    otherReqs.add(req);
                }
            }

            // 6) If we see "answerPlan" or "abortPlan", handle it and then exit
            if (answerReq != null) {
                logger.debug("LLM decided to answerPlan. We'll finalize and stop.");
                var result = toolRegistry.executeTool(answerReq);
                addActionHistory(answerReq, result);
                logger.info("Plan final answer: {}", result.resultText());
                return; // done
            }
            if (abortReq != null) {
                logger.debug("LLM decided to abortPlan. We'll finalize and stop.");
                var result = toolRegistry.executeTool(abortReq);
                addActionHistory(abortReq, result);
                logger.info("Plan aborted: {}", result.resultText());
                return; // done
            }

            // 7) If we have other tool calls, execute them. In principle we can do them sequentially.
            for (var request : otherReqs) {
                // If it's a duplicate "pushTasks" or "callCodeAgent" with same arguments, etc.
                // we might skip or handle differently. But let's just do them in order for now.
                var toolResult = toolRegistry.executeTool(request);
                addActionHistory(request, toolResult);

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
    private List<ChatMessage> buildPrompt(String topClassesMarkdown) {
        var systemMsg = new StringBuilder();
        systemMsg.append("""
            You are BrokkAgent, a multi-step plan manager. You have a stack of tasks to complete.
            In each step, you must pick the best tool to call. The main tools are:
              1) pushTasks => add more sub-tasks to the stack
              2) callCodeAgent => do coding/implementation with the CodeAgent
              3) searchSymbols or other search/insp tools => find relevant code
              4) context manipulations => add or drop files/fragments
              5) answerPlan => finalize the plan with a complete explanation/solution
              6) abortPlan => give up if it's unsolvable or irrelevant

            The top of the stack is the next immediate subgoal. But you can also examine or transform the entire stack in any iteration.

            The 10 classes with highest pageRank and their skeletons are shown below.
            If any are relevant, you can add them to context using context tools:
            addClassSkeletonsFragment, addClassSourcesFragment, etc.

            Do NOT just repeat a previous action. If you must refine your approach, do so with new arguments.

            If no more tasks remain or you are done, call answerPlan or abortPlan.
            """.stripIndent());

        // Show the current tasks
        var tasksList = tasks.stream().limit(20).collect(Collectors.joining("\n - "));
        if (tasks.isEmpty()) {
            tasksList = "(none)";
        }

        var userMsg = """
            **Task Stack (top first)**:
            - %s

            **Top-10 PageRank Classes**:
            %s

            Please decide the next tool action. Summarize your approach and call a single tool that best fits.
            """.stripIndent().formatted(tasksList, topClassesMarkdown);

        return List.of(
                new SystemMessage(systemMsg.toString()),
                new UserMessage(userMsg)
        );
    }

    /**
     * Return a list of allowed tool names for each iteration. We allow:
     * - pushTasks
     * - callCodeAgent
     * - answerPlan
     * - abortPlan
     * plus some from SearchTools, plus context manipulations from ContextTools.
     */
    private List<String> getAllowedTools() {
        // Based on the codebase, we might allow: "pushTasks", "callCodeAgent", "answerPlan", "abortPlan"
        // plus the existing search tools from SearchTools: searchSymbols, getUsages, ...
        // plus some context tools from ContextTools, e.g. "addTextFragment", "dropAllContext", ...
        // We'll just list the main ones. The user can expand as needed.
        return List.of(
                "pushTasks",
                "callCodeAgent",
                "answerPlan",
                "abortPlan",
                // from SearchTools
                "searchSymbols",
                "getUsages",
                "getRelatedClasses",
                // from ContextTools
                "addEditableFiles",
                "addReadOnlyFiles",
                "addUrlContents",
                "addTextFragment",
                "dropFragments",
                "dropAllContext"
        );
    }

    /**
     * Provide top-10 pagerank classes as Markdown skeletons.
     */
    private String fetchTop10PagerankClasses() {
        var analyzer = contextManager.getAnalyzer();
        if (analyzer.isEmpty()) {
            return "(Code analyzer not available, can't compute PageRank.)";
        }
        // Use an empty seed map => global pagerank
        Map<String, Double> seeds = new HashMap<>();
        var topUnits = AnalyzerUtil.combinedPagerankFor(analyzer, seeds)
                .stream().limit(10).toList();
        if (topUnits.isEmpty()) {
            return "(No classes found by PageRank.)";
        }
        var sb = new StringBuilder();
        for (var cu : topUnits) {
            sb.append("**Class:** ").append(cu.fqName()).append("\n");
            var sk = analyzer.getSkeleton(cu.fqName());
            if (sk.isDefined()) {
                sb.append(sk.get()).append("\n\n");
            } else {
                sb.append("_(No skeleton available)_\n\n");
            }
        }
        return sb.toString();
    }

    /**
     * Tracks each tool call for debugging.
     */
    private void addActionHistory(ToolExecutionRequest request, ToolExecutionResult result) {
        var entry = new ToolHistoryEntry(request, result);
        actionHistory.add(entry);
        logger.debug("Tool call history updated with: {} => {}", request.name(), result.resultText());
    }

    // A small record for storing the history of each tool call
    private static class ToolHistoryEntry {
        final ToolExecutionRequest request;
        final ToolExecutionResult result;

        ToolHistoryEntry(ToolExecutionRequest request, ToolExecutionResult result) {
            this.request = request;
            this.result = result;
        }
    }
}
