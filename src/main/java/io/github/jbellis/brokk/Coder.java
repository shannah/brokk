package io.github.jbellis.brokk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.Response;
import io.github.jbellis.brokk.prompts.DefaultPrompts;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

public class Coder {
    private final Logger logger = LogManager.getLogger(Coder.class);

    private final IConsoleIO io;
    private final Path historyFile;
    private final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public final Models models;
    final IContextManager contextManager;

    public Coder(Models models, IConsoleIO io, Path sourceRoot, IContextManager contextManager) {
        this.models = models;
        this.io = io;
        this.contextManager = contextManager;
        this.historyFile = sourceRoot.resolve(".brokk").resolve("conversations.md");
    }

    /**
     * The main entry point that makes one or more requests to the LLM with reflection.
     *
     * @param userInput The original user message you want to send.
     */
    public void runSession(StreamingChatLanguageModel model, String userInput) {
        // Track original contents of files before any changes
        var originalContents = new HashMap<RepoFile, String>(); 
        List<ChatMessage> pendingHistory = new ArrayList<>();
        if (!isLlmAvailable()) {
            io.toolError("No LLM available (missing API keys)");
            return;
        }

        // Collect messages from context
        var messages = DefaultPrompts.instance.collectMessages((ContextManager) contextManager);
        var requestMsg = new UserMessage("<goal>\n%s\n</goal>".formatted(userInput.trim()));

        // Reflection loop: up to reflectionManager.maxReflections passes
        var reflectionManager = new ReflectionManager(io, this);
        while (true) {
            messages.add(requestMsg);

            // Actually send the message to the LLM and get the response
            logger.debug("Sending to LLM [only last message shown]: {}", requestMsg);
            var llmResponse = sendStreaming(model, messages, true);
            logger.debug("response:\n{}", llmResponse);
            if (llmResponse == null) {
                // Interrupted or error.  sendMessage is responsible for giving feedback to user
                break;
            }

            var llmText = llmResponse.content().text();
            if (llmText.isBlank()) {
                io.toolError("Empty response from LLM, will retry");
                continue;
            }

            // Add the request/response to pending history
            pendingHistory.addAll(List.of(requestMsg, llmResponse.content()));
            messages.add(llmResponse.content());

            // Gather all edit blocks in the reply
            var parseResult = EditBlock.findOriginalUpdateBlocks(llmText, contextManager.getEditableFiles());
            if (parseResult.parseError() != null) {
                io.toolErrorRaw(parseResult.parseError());
                requestMsg = new UserMessage(parseResult.parseError());
                continue;
            }

            var blocks = parseResult.blocks();
            logger.debug("Parsed {} blocks", blocks.size());

            // ask user if he wants to add any files referenced in search/replace blocks that are not editable
            var blocksNotEditable = blocks.stream()
                    .filter(block -> block.filename() != null)
                    .filter(block -> !contextManager.getEditableFiles().contains(contextManager.toFile(block.filename())))
                    .toList();
            var uniqueFilenames = blocksNotEditable.stream()
                    .map(EditBlock.SearchReplaceBlock::filename)
                    .distinct()
                    .toList();
            var confirmedFilenames = uniqueFilenames.stream()
                    .filter(filename -> io.confirmAsk("Add as editable %s?".formatted(filename)))
                    .toList();
            var blocksToAdd = blocksNotEditable.stream()
                    .filter(block -> confirmedFilenames.contains(block.filename()))
                    .toList();
            var filesToAdd = confirmedFilenames.stream()
                    .map(contextManager::toFile)
                    .toList();
            logger.debug("files to add: {}", filesToAdd);
            if (!filesToAdd.isEmpty()) {
                contextManager.addFiles(filesToAdd);
            }
            // Filter out blocks that the user declined adding
            blocks = blocks.stream()
                    .filter(block -> !blocksNotEditable.contains(block) || blocksToAdd.contains(block))
                    .toList();
            if (blocks.isEmpty()) {
                break;
            }

            // Attempt to apply any code edits from the LLM
            var editResult = EditBlock.applyEditBlocks(contextManager, io, blocks);
            editResult.originalContents().forEach(originalContents::putIfAbsent);
            logger.debug("Failed blocks: {}", editResult.blocks());

            // Check for parse/match failures first 
            var parseReflection = reflectionManager.getParseReflection(editResult.blocks(), blocks, contextManager);
            if (!parseReflection.isEmpty()) {
                io.toolOutput("Attempting to fix parse/match errors...");
                model = models.applyModel();
                requestMsg = new UserMessage(parseReflection);
                continue;
            }

            // If parsing succeeded, check build
            var buildReflection = reflectionManager.getBuildReflection(contextManager);
            if (buildReflection.isEmpty()) {
                break;
            }
            // If the reflection manager has also signaled "stop" (maybe user said no),
            // or we've reached reflectionManager's maximum tries:
            if (!reflectionManager.shouldContinue()) {
                break;
            }
            io.toolOutput("Attempting to fix build errors...");
            // Use EDIT model (smarter) for build fixes
            model = models.editModel();
            requestMsg = new UserMessage(buildReflection);
        }

        // Add all pending messages to history in one batch
        if (!pendingHistory.isEmpty()) {
            contextManager.addToHistory(pendingHistory, originalContents);
        }
    }

    public boolean isLlmAvailable() {
        return !(models.editModel() instanceof Models.UnavailableStreamingModel);
    }

    /**
     * Sends a user query to the LLM (with streaming),
     * writes to conversation history, etc.
     *
     * @param model    The LLM model to use
     * @param messages The messages to send
     * @param echo     Whether to echo LLM responses to the console
     * @return The final response from the LLM as a string
     */
    public Response<AiMessage> sendStreaming(StreamingChatLanguageModel model, List<ChatMessage> messages, boolean echo) {
        if (echo) {
            io.toolOutput("Request sent");
        }

        var latch = new CountDownLatch(1);
        var streamThread = Thread.currentThread();
        AtomicBoolean canceled = new AtomicBoolean(false);
        var lock = new ReentrantLock();
        Consumer<Runnable> ifNotCancelled = (r -> {
            lock.lock();
            try {
                if (canceled.get()) return;
                r.run();
            } finally {
                lock.unlock();
            }
        });

        // Write request with tools to history
        writeRequestToHistory(messages, List.of());

        var atomicResponse = new AtomicReference<Response<AiMessage>>();
        model.generate(messages, new StreamingResponseHandler<>() {
            @Override
            public void onNext(String token) {
                ifNotCancelled.accept(() -> {
                    if (echo) {
                        io.llmOutput(token);
                    }
                });
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
                ifNotCancelled.accept(() -> {
                    if (echo) {
                        io.llmOutput("\n");
                    }
                    atomicResponse.set(response);
                    writeToHistory("Response", response.toString());
                    latch.countDown();
                });
            }

            @Override
            public void onError(Throwable error) {
                ifNotCancelled.accept(() -> {
                    writeToHistory("Error", error.getMessage());
                    io.toolErrorRaw("LLM error: " + error.getMessage());
                    streamThread.interrupt();
                });
            }
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            lock.lock();
            canceled.set(true);
            lock.unlock();
            io.toolErrorRaw("\nInterrupted!");
            return null;
        }
        return atomicResponse.get();
    }

    /**
     * Send a message to the default quick model
     * 
     * @param messages The messages to send
     * @return The LLM response as a string
     */
    public String sendMessage(List<ChatMessage> messages) {
        return sendMessage((String) null, messages);
    }

    /**
     * Send a message to the default quick model with a description
     * 
     * @param description Description of the request (logged to console)
     * @param messages The messages to send
     * @return The LLM response as a string
     */
    public String sendMessage(String description, List<ChatMessage> messages) {
        var R = sendMessage(models.quickModel(), description, messages, List.of());
        return R.aiMessage().text().trim();
    }

    public ChatResponse sendMessage(ChatLanguageModel model, List<ChatMessage> messages) {
        return sendMessage(model, null, messages, List.of());
    }
    

    /**
     * Send a message to a specific model with tool support
     *
     * @param model       The model to use
     * @param description Description of the request (logged to console)
     * @param messages    The messages to send
     * @param tools       List of tools to enable for the LLM
     * @return The LLM response as a string
     */
    public ChatResponse sendMessage(ChatLanguageModel model, String description, List<ChatMessage> messages, List<ToolSpecification> tools) {
        if (description != null) {
            io.toolOutput(description);
        }

        var response = sendMessageInternal(model, messages, tools, false);
        // poor man's ToolChoice.REQUIRED (not supported by langchain4j for Anthropic)
        // Also needed for our DeepSeek emulation if it returns a response without a tool call
        while (!tools.isEmpty() && !response.aiMessage().hasToolExecutionRequests()) {
            if (io.isSpinning()) {
                io.spin("Enforcing tool selection");
            }
            var extraMessages = new ArrayList<>(messages);
            extraMessages.add(response.aiMessage());

            // Add a stronger instruction for DeepSeek models
            if (model.toString().toLowerCase().contains("deepseek")) {
                extraMessages.add(new UserMessage("You MUST call one of the available tools. Format your response as JSON with a tool_calls array."));
            } else {
                extraMessages.add(new UserMessage("At least one tool execution request is required"));
            }

            response = sendMessageInternal(model, extraMessages, tools, true);
        }

        if (model instanceof AnthropicChatModel) {
            var tu = (AnthropicTokenUsage) response.tokenUsage();
            writeToHistory("Cache usage", "%s, %s".formatted(tu.cacheCreationInputTokens(), tu.cacheReadInputTokens()));
            logger.debug("Cache usage: %s, %s".formatted(tu.cacheCreationInputTokens(), tu.cacheReadInputTokens()));
        }

        return response;
    }

    private ChatResponse sendMessageInternal(ChatLanguageModel model, List<ChatMessage> messages, List<ToolSpecification> tools, boolean isRetry) {
        writeRequestToHistory(messages, tools);
        var builder = ChatRequest.builder().messages(messages);
        
        if (!tools.isEmpty()) {
            // Check if this is a DeepSeek model that needs function calling emulation
            if (models.nameOf(model).toLowerCase().contains("deepseek")) {
                return emulateToolsUsingStructuredOutput(model, messages, tools, isRetry);
            }
            
            // For models with native function calling
            var params = ChatRequestParameters.builder()
                    .toolSpecifications(tools)
                    .build();
            builder = builder.parameters(params);
        }
        
        var request = builder.build();
        var response = model.chat(request);
        writeToHistory("Response", response.toString());
        return response;
    }
    
    /**
     * Emulates function calling for models that support structured output but not native function calling
     * Used primarily for DeepSeek models
     */
    private ChatResponse emulateToolsUsingStructuredOutput(ChatLanguageModel model, List<ChatMessage> messages, List<ToolSpecification> tools, boolean isRetry) {
        ObjectMapper mapper = new ObjectMapper();

        logger.debug("sending {} messages with {}", messages.size(), isRetry);
        if (!isRetry) {
            // Inject  instructions for the model re how to format function calls
            messages = new ArrayList<>(messages); // so we can modify it
            String toolsDescription = tools.stream()
                    .map(tool -> {
                        StringBuilder sb = new StringBuilder();
                        sb.append("- ").append(tool.name()).append(": ").append(tool.description());

                        // Add parameters information if available
                        if (tool.parameters() != null && tool.parameters().properties() != null) {
                            sb.append("\n  Parameters:");
                            for (var entry : tool.parameters().properties().entrySet()) {
                                sb.append("\n    - ").append(entry.getKey());

                                // Try to extract description if available
                                try {
                                    JsonNode node = mapper.valueToTree(entry.getValue());
                                    if (node.has("description")) {
                                        sb.append(": ").append(node.get("description").asText());
                                    }
                                } catch (Exception e) {
                                    // Ignore, just don't add the description
                                }
                            }
                        }
                        return sb.toString();
                    })
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("");

            var jsonPrompt = """
                You must respond in JSON format with one or more tool calls.
                Available tools:
                %s

                Response format:
                {
                  "tool_calls": [
                    {
                      "name": "tool_name",
                      "arguments": {
                        "arg1": "value1",
                        "arg2": "value2"
                      }
                    }
                  ]
                }
                """.formatted(toolsDescription);
            // Add the system message to the beginning of the messages list
            messages.set(messages.size() - 1, new UserMessage(Models.getText(messages.getLast()) + "\n\n" + jsonPrompt));
            logger.debug("Modified messages are {}", messages);
        }

        // Create a request with JSON response format
        var requestParams = ChatRequestParameters.builder()
                .responseFormat(ResponseFormat.builder()
                        .type(ResponseFormatType.JSON)
                        .build())
                .build();

        var request = ChatRequest.builder()
                .messages(messages)
                .parameters(requestParams)
                .build();

        var response = model.chat(request);

        // Parse the JSON response and convert it to a tool execution request
        try {
            String jsonResponse = response.aiMessage().text();
            logger.debug("Raw JSON response from model: {}", jsonResponse);
                        JsonNode root = mapper.readTree(jsonResponse);

            // Check for tool_calls array format
            if (root.has("tool_calls") && root.get("tool_calls").isArray()) {
                JsonNode toolCalls = root.get("tool_calls");

                // Create list of tool execution requests
                var toolExecutionRequests = new ArrayList<ToolExecutionRequest>();

                for (int i = 0; i < toolCalls.size(); i++) {
                    JsonNode toolCall = toolCalls.get(i);
                    if (toolCall.has("name") && toolCall.has("arguments")) {
                        String toolName = toolCall.get("name").asText();
                        JsonNode arguments = toolCall.get("arguments");
                        
                        String argumentsJson;
                        if (arguments.isObject()) {
                            argumentsJson = arguments.toString();
                        } else {
                            // Handle case where arguments might be a string instead of object
                            try {
                                JsonNode parsedArgs = mapper.readTree(arguments.asText());
                                argumentsJson = parsedArgs.toString();
                            } catch (Exception e) {
                                // If parsing fails, use as-is
                                argumentsJson = arguments.toString();
                            }
                        }
                        
                        var toolExecutionRequest = ToolExecutionRequest.builder()
                                .id(String.valueOf(i))
                                .name(toolName)
                                .arguments(argumentsJson)
                                .build();
                        
                        toolExecutionRequests.add(toolExecutionRequest);
                    }
                }

                assert !toolExecutionRequests.isEmpty();
                logger.debug("Generated tool execution requests: {}", toolExecutionRequests);

                // Create a properly formatted AiMessage with tool execution requests
                var aiMessage = new AiMessage("[json]", toolExecutionRequests);

                return ChatResponse.builder().aiMessage(aiMessage).build();
            }
            // If no tool_call found, return original response
            logger.debug("No tool calls found in response, returning original");
            return response;
        } catch (JsonProcessingException e) {
            logger.error("Error processing JSON response: " + e.getMessage(), e);
            // Return the original response if parsing fails
            // TODO better fix for internal errors?
            return response;
        }
    }

    private void writeRequestToHistory(List<ChatMessage> messages, List<ToolSpecification> tools) {
        String requestText = messages.stream()
                .map(m -> "%s: %s\n".formatted(m.type(), Models.getText(m)))
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
                
        if (!tools.isEmpty()) {
            requestText += "\nTools:\n" + tools.stream()
                .map(t -> "- %s: %s".formatted(t.name(), t.description()))
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
        }
        
        writeToHistory("Request", requestText);
    }

    private void writeToHistory(String header, String text) {
        var formatted = "\n# %s at %s\n\n%s\n".formatted(header, LocalDateTime.now().format(dtf), text);

        try {
            Files.createDirectories(historyFile.getParent());
            Files.writeString(historyFile, formatted, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            io.toolErrorRaw("Failed to write to history: " + e.getMessage());
        }
    }
}
