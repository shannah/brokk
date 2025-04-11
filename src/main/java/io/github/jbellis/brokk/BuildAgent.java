package io.github.jbellis.brokk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.request.ToolChoice;
import io.github.jbellis.brokk.tools.ToolExecutionResult;
import io.github.jbellis.brokk.tools.ToolRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * An agent that iteratively explores a codebase using specific tools
 * to extract information relevant to the *development* build process.
 */
public class BuildAgent {
    private static final Logger logger = LogManager.getLogger(BuildAgent.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Coder coder;
    // Primarily for listFiles tool via SearchTools
    private final StreamingChatLanguageModel model;
    private final ToolRegistry toolRegistry;

    // Use standard ChatMessage history
    private final List<ChatMessage> chatHistory = new ArrayList<>();

    public BuildAgent(Coder coder, ToolRegistry toolRegistry) {
        assert coder != null : "coder cannot be null";
        assert toolRegistry != null : "toolRegistry cannot be null";
        this.coder = coder;
        this.toolRegistry = toolRegistry;
        // Get Models instance from coder and call instance method
        this.model = coder.contextManager.getModels().quickModel();
    }

    /**
     * Execute the build information gathering process.
     *
     * @return The gathered BuildDetails record, or null if the process fails or is interrupted.
     */
    public BuildDetails execute() {
        // 1. Initial step: List files in the root directory to give the agent a starting point
        ToolExecutionRequest initialRequest = ToolExecutionRequest.builder()
                                                                  .name("listFiles")
                                                                  .arguments("{\"directoryPath\": \".\"}") // Request root dir
                                                                  .build();
        ToolExecutionResult initialResult = toolRegistry.executeTool(initialRequest);
        ToolExecutionResultMessage initialResultMessage = initialResult.toExecutionResultMessage();

        // Add the initial result to history (no AI request for this one)
        chatHistory.add(initialResultMessage);
        logger.debug("Initial tool result added to history: {}", initialResultMessage.text());

        // 2. Iteration Loop
        while (true) {
            // 3. Build Prompt
            List<ChatMessage> messages = buildPrompt();

            // 4. Call LLM
            // Get specifications for ALL tools the agent might use in this turn.
            var tools = new ArrayList<>(toolRegistry.getRegisteredTools(List.of("listFiles", "searchFilenames", "searchSubstrings", "getFileContents")));
            if (chatHistory.size() > 1) {
                // allow terminal tools
                tools.addAll(toolRegistry.getTools(this, List.of("reportBuildDetails", "abortBuildDetails")));
            }

            // Call the Coder to get the LLM's response, including potential tool calls
            var result = coder.sendMessage(model, messages, tools, ToolChoice.REQUIRED, false);

            if (result.cancelled()) {
                logger.error("Unexpected request cancellation in build agent");
                return null;
            }
            if (result.error() != null) {
                logger.error("LLM error in BuildInfoAgent: " + result.error().getMessage());
                return null;
            }
            var response = result.chatResponse();
            if (response == null || response.aiMessage() == null || !response.aiMessage().hasToolExecutionRequests()) {
                // This shouldn't happen with ToolChoice.REQUIRED and Coder retries, but handle defensively.
                logger.error("LLM response did not contain expected tool call in BuildInfoAgent.");
                return null;
            }

            AiMessage aiMessage = response.aiMessage();
            chatHistory.add(aiMessage); // Add AI request message to history

            // 5. Process Tool Execution Requests
            List<ToolExecutionRequest> requests = aiMessage.toolExecutionRequests();
            logger.debug("LLM requested {} tools", requests.size());

            // Prioritize terminal actions (report or abort)
            ToolExecutionRequest reportRequest = null;
            ToolExecutionRequest abortRequest = null;
            List<ToolExecutionRequest> otherRequests = new ArrayList<>();

            for (var request : requests) {
                String toolName = request.name();
                logger.debug("Processing requested tool: {} with args: {}", toolName, request.arguments());
                if (toolName.equals("reportBuildDetails")) {
                    reportRequest = request;
                } else if (toolName.equals("abortBuildDetails")) {
                    abortRequest = request;
                } else {
                    otherRequests.add(request);
                }
            }

            // 6. Handle Terminal Actions (if any)
            if (reportRequest != null) {
                logger.debug("BuildInfoAgent is terminating and reporting details.");
                try {
                    Map<String, Object> args = OBJECT_MAPPER.readValue(reportRequest.arguments(), new TypeReference<>() {});
                    List<String> buildfiles = getListArgument(args, "buildfiles");
                    List<String> dependencies = getListArgument(args, "dependencies");
                    String buildLintCommand = getStringArgument(args, "buildLintCommand");
                    String testAllCommand = getStringArgument(args, "testAllCommand");
                    String instructions = getStringArgument(args, "instructions");

                    return new BuildDetails(buildfiles, dependencies, buildLintCommand, testAllCommand, instructions);
                } catch (JsonProcessingException e) {
                    logger.error("Failed to parse arguments for reportBuildDetails: {}", reportRequest.arguments(), e);
                    return null; // Failed to create the final result
                }
            } else if (abortRequest != null) {
                String explanation = "No explanation provided.";
                try {
                    Map<String, Object> args = OBJECT_MAPPER.readValue(abortRequest.arguments(), new TypeReference<>() {});
                    explanation = getStringArgument(args, "explanation");
                } catch (JsonProcessingException e) {
                    logger.error("Failed to parse arguments for abort tool: {}", abortRequest.arguments(), e);
                }
                logger.warn("BuildInfoAgent aborted by LLM: {}", explanation);
                return null; // Return null to indicate abortion
            }

            // 7. Execute Non-Terminal Tools (if no terminal action was taken or if terminal action was ignored)
            // Only proceed if no terminal action *was actually executed* this turn
            for (var request : otherRequests) {
                String toolName = request.name();
                logger.debug(String.format("Agent action: %s (%s)", toolName, request.arguments()));
                ToolExecutionResult execResult = toolRegistry.executeTool(request);
                ToolExecutionResultMessage resultMessage = execResult.toExecutionResultMessage();

                // Record individual tool result history
                chatHistory.add(resultMessage);
                logger.debug("Tool result added to history: {}", resultMessage.text());
            }
        }
    }

    // Helper to safely extract List<String> from arguments map
    private List<String> getListArgument(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value instanceof List<?> list) {
            // Check if elements are Strings, convert if necessary (though Jackson usually handles this)
            return list.stream()
                       .map(String::valueOf) // Ensure elements are strings
                       .toList();
        }
        logger.warn("Argument '{}' was not a List<String> or was missing, defaulting to empty list.", key);
        return List.of(); // Return empty list if missing or wrong type
    }

    // Helper to safely extract String from arguments map
    private String getStringArgument(Map<String, Object> args, String key) {
        Object value = args.get(key);
         // Check for null or empty string explicitly
         if (value instanceof String s && !s.isBlank()) {
             return s;
         }
         logger.warn("Argument '{}' was not a non-blank String or was missing, defaulting to empty string.", key);
         return ""; // Return empty string if missing, null, or wrong type
     }


    /**
     * Build the prompt for the LLM, including system message and history.
     */
    private List<ChatMessage> buildPrompt() {
        List<ChatMessage> messages = new ArrayList<>();

        // System Prompt
        messages.add(new SystemMessage("""
        You are an agent tasked with finding build information for the *development* environment of a software project.
        Your goal is to identify dependencies, plugins, repositories, development profile details, key build commands (clean, compile/build, test all, test specific), how to invoke those commands correctly, and the main application entry point.
        Focus *only* on details relevant to local development builds/profiles, explicitly ignoring production-specific configurations unless they are the only ones available.

        Use the tools to examine build files (like `pom.xml`, `build.gradle`, etc.), configuration files, and linting files.
        The information you gather will be handed off to agents that do not have access to these files, so
        make sure your instructions are comprehensive.
        
        Remember to request the `reportBuildDetails` tool to finalize the process ONLY once all information is collected.
        """.stripIndent()));

        // Add existing history
        messages.addAll(chatHistory);

        // Add final user message indicating the goal (redundant with system prompt but reinforces)
        messages.add(new UserMessage("Gather the development build details based on the project files and previous findings. Use the available tools to explore and collect the information, then report it using 'reportBuildDetails'."));

        return messages;
    }

    /**
     * Static inner class to hold the build-specific tool(s).
     * This tool's purpose is primarily to act as a termination signal and data structure definition for the LLM.
     */
    public static class BuildInfoTools {
        @Tool(value = "Report the gathered build details when ALL information is collected. DO NOT call this method before then.")
        public String reportBuildDetails(
                @P("List of build files involved in the build, including module build files") List<String> buildfiles,
                @P("List of identified third-party dependencies") List<String> dependencies,
                @P("Command to build or lint incrementally, e.g. mvn compile, cargo check, pyflakes .") String buildLintCommand,
                @P("Command to run all tests") String testAllCommand,
                @P("""
                Instructions and details about the build process, including environment configurations
                and any idiosyncracies observed. Include information on how to run other test configurations, especially 
                individual tests but also at other levels e.g. package or namespace;
                also include information on other pre-commit tools like code formatters or static analysis tools.
                """) String instructions
        ) {
            logger.debug("reportBuildDetails tool called by LLM (intercepted by agent).");
            return "Build details report received by agent.";
        }

        @Tool(value = "Abort the process if you cannot determine the build details or the project structure is unsupported.")
        public String abortBuildDetails(
                @P("Explanation of why the build details cannot be determined") String explanation
        ) {
            // Like reportBuildDetails, this is mainly a signal intercepted by the agent.
            logger.debug("abort tool called by LLM with explanation: {}", explanation);
            return "Abort signal received by agent.";
        }
    }

    /** Holds semi-structured information about a project's build process */
    public record BuildDetails(
            List<String> buildfiles,
            List<String> dependencies,
            String buildLintCommand,
            String testAllCommand,
            String instructions
    ) {
        public static final BuildDetails EMPTY = new BuildDetails(List.of(), List.of(), "", "", "");
    }
}
