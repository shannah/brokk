package io.github.jbellis.brokk;

import static java.util.Objects.requireNonNull;
import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.LangChain4jException;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.FinishReason;
import io.github.jbellis.brokk.tools.ToolRegistry;
import io.github.jbellis.brokk.util.LogDescription;
import io.github.jbellis.brokk.util.Messages;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

/** The main orchestrator for sending requests to an LLM, possibly with tools, collecting streaming responses, etc. */
public class Llm {
    private static final Logger logger = LogManager.getLogger(Llm.class);
    private static final ObjectMapper objectMapper =
            new ObjectMapper().setVisibility(PropertyAccessor.FIELD, Visibility.ANY);

    /** Base directory where LLM interaction history logs are stored. */
    public static final String HISTORY_DIR_NAME = "llm-history";

    private IConsoleIO io;
    private final Path taskHistoryDir; // Directory for this specific LLM task's history files
    final IContextManager contextManager;
    private final int MAX_ATTEMPTS = 8; // Keep retry logic for now
    private final StreamingChatModel model;
    private final boolean allowPartialResponses;
    private final boolean tagRetain;

    // Monotonically increasing sequence for emulated tool request IDs
    private final AtomicInteger toolRequestIdSeq = new AtomicInteger();
    private int requestSequence = 1;

    public Llm(
            StreamingChatModel model,
            String taskDescription,
            IContextManager contextManager,
            boolean allowPartialResponses,
            boolean tagRetain) {
        this.model = model;
        this.contextManager = contextManager;
        this.io = contextManager.getIo();
        this.allowPartialResponses = allowPartialResponses;
        this.tagRetain = tagRetain;
        var historyBaseDir = getHistoryBaseDir(contextManager.getProject().getRoot());

        // Create task directory name for this specific LLM interaction
        var timestamp = LocalDateTime.now(java.time.ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss"));
        var taskDesc = LogDescription.getShortDescription(taskDescription);

        // Create the specific directory for this task with uniqueness check
        var baseTaskDirName = String.format("%s %s", timestamp, taskDesc);
        synchronized (Llm.class) {
            int suffix = 1;
            var mutableDirName = historyBaseDir.resolve(baseTaskDirName);
            while (Files.exists(mutableDirName)) {
                var newDirName = baseTaskDirName + "-" + suffix;
                mutableDirName = historyBaseDir.resolve(newDirName);
                suffix++;
            }

            this.taskHistoryDir = mutableDirName;
            try {
                Files.createDirectories(this.taskHistoryDir);
            } catch (IOException e) {
                logger.error("Failed to create task history directory {}", this.taskHistoryDir, e);
                // taskHistoryDir might be null or unusable, logRequest checks for null
            }
        }
    }

    /**
     * Returns the base directory where all LLM task histories are stored for a project.
     *
     * @param projectRoot The root path of the project.
     * @return The Path object representing the base history directory.
     */
    public static Path getHistoryBaseDir(Path projectRoot) {
        return projectRoot.resolve(AbstractProject.BROKK_DIR).resolve(HISTORY_DIR_NAME);
    }

    private static String logFileTimestamp() {
        return LocalDateTime.now(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH-mm.ss"));
    }

    /** Write the request JSON before sending to the model, to a file named "<base>-request.json". */
    private void logRequest(ChatRequest request) {
        try {
            var filename = "%s %03d-request.json".formatted(logFileTimestamp(), requestSequence);
            var requestPath = taskHistoryDir.resolve(filename);
            var requestOptions = new StandardOpenOption[] {
                StandardOpenOption.CREATE, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE
            };
            var requestJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(request);
            logger.trace("Writing pre-send request JSON to {}", requestPath);
            Files.writeString(requestPath, requestJson, requestOptions);
        } catch (IOException e) {
            logger.error("Failed to write pre-send request JSON", e);
        }
    }

    /**
     * Actually performs one streaming call to the LLM, returning once the response is done or there's an error. If
     * 'echo' is true, partial tokens go to console.
     */
    private StreamingResult doSingleStreamingCall(ChatRequest request, boolean echo, boolean addJsonFence)
            throws InterruptedException {
        StreamingResult result;
        try {
            result = doSingleStreamingCallInternal(request, echo, addJsonFence);
        } catch (InterruptedException e) {
            logResult(model, request, null);
            throw e;
        }
        logResult(model, request, result);
        return result;
    }

    private StreamingResult doSingleStreamingCallInternal(ChatRequest request, boolean echo, boolean addJsonFence)
            throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }

        // Pre-log the request JSON before sending it to the model
        logRequest(request);

        // latch for awaiting any response from llm
        var tick = new Semaphore(0);
        var firstToken = new AtomicBoolean(true);
        var completed = new AtomicBoolean(false);
        var cancelled = new AtomicBoolean(false);
        var lock = new ReentrantLock(); // Used by ifNotCancelled

        // Variables to store results from callbacks
        var accumulatedTextBuilder = new StringBuilder();
        var accumulatedReasoningBuilder = new StringBuilder();
        var completedChatResponse = new AtomicReference<@Nullable ChatResponse>();
        var errorRef = new AtomicReference<@Nullable Throwable>();
        var fenceOpen = new AtomicBoolean(false);

        Consumer<Runnable> ifNotCancelled = r -> {
            lock.lock();
            try {
                if (!cancelled.get()) {
                    r.run();
                }
            } catch (RuntimeException e) {
                // litellm is fucking us over again, try to recover
                logger.error(e);
                errorRef.set(e);
                completed.set(true);
            } finally {
                if (firstToken.get()) {
                    firstToken.set(false);
                }
                // signal an event (partial, reasoning, complete, or error)
                tick.release();
                lock.unlock();
            }
        };
        var rawHandler = new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String token) {
                ifNotCancelled.accept(() -> {
                    accumulatedTextBuilder.append(token);
                    if (echo) {
                        if (addJsonFence && !fenceOpen.get()) {
                            io.llmOutput("\n```json\n", ChatMessageType.AI);
                            fenceOpen.set(true);
                        }
                        io.llmOutput(token, ChatMessageType.AI);
                    }
                });
            }

            @Override
            public void onReasoningResponse(String reasoningContent) {
                ifNotCancelled.accept(() -> {
                    // Gate formatting to GPT-5 only, and only after the first reasoning chunk
                    boolean isGpt5 = contextManager.getService().nameOf(model).equals(Service.GPT_5);
                    String out = isGpt5 ? addReasoningNewlinesForGpt5(reasoningContent) : reasoningContent;

                    accumulatedReasoningBuilder.append(out);
                    if (echo) {
                        io.llmOutput(out, ChatMessageType.AI, false, true);
                    }
                });
            }

            @Override
            public void onCompleteResponse(@Nullable ChatResponse response) {
                ifNotCancelled.accept(() -> {
                    if (response == null) {
                        if (completedChatResponse.get() != null) {
                            logger.debug("Got a null response from LC4J after a successful one!?");
                            // ignore the null response
                            return;
                        }
                        // I think this isn't supposed to happen, but seeing it when litellm throws back a 400.
                        // Fake an exception so the caller can treat it like other errors
                        var ex = new LitellmException(
                                "(no further information, the response was null; check litellm logs)");
                        logger.debug(ex);
                        errorRef.set(ex);
                    } else {
                        completedChatResponse.set(response);
                        String tokens =
                                response.tokenUsage() == null ? "null token usage!?" : formatTokensUsage(response);
                        logger.debug("Request complete ({}) with {}", response.finishReason(), tokens);
                    }
                    if (echo && addJsonFence && fenceOpen.get()) {
                        io.llmOutput("\n```", ChatMessageType.AI);
                        fenceOpen.set(false);
                    }
                    completed.set(true);
                });
            }

            @Override
            public void onError(Throwable th) {
                ifNotCancelled.accept(() -> {
                    logger.debug(th);
                    io.systemOutput("LLM Error: " + th.getMessage() + " (retry-able)"); // Immediate feedback for user
                    errorRef.set(th);
                    if (echo && addJsonFence && fenceOpen.get()) {
                        io.llmOutput("\n```", ChatMessageType.AI);
                        fenceOpen.set(false);
                    }
                    completed.set(true);
                });
            }
        };

        var finalHandler =
                contextManager.getService().usesThinkTags(model) ? new ThinkTagInterceptor(rawHandler) : rawHandler;
        model.chat(request, finalHandler);

        try {
            while (!completed.get()) {
                long secs = getLlmResponseTimeoutSeconds(firstToken.get());
                boolean gotSignal = tick.tryAcquire(secs, TimeUnit.SECONDS);
                if (!gotSignal) {
                    lock.lock();
                    try {
                        cancelled.set(true);
                        errorRef.set(new HttpException(504, "LLM timed out"));
                        completed.set(true);
                    } finally {
                        lock.unlock();
                    }
                }
            }
        } catch (InterruptedException e) {
            lock.lock(); // LockNotBeforeTry
            try {
                cancelled.set(true); // Ensure callback stops echoing
            } finally {
                lock.unlock();
            }
            throw e; // Propagate interruption
        }

        // Ensure any open JSON fence is closed (e.g., timeout paths that didn't trigger callbacks)
        if (echo && addJsonFence && fenceOpen.get()) {
            io.llmOutput("\n```", ChatMessageType.AI);
            fenceOpen.set(false);
        }

        // At this point, latch has been counted down and we have a result or an error
        var error = errorRef.get();

        if (error != null) {
            // If no partial text, just return null response
            var partialText = accumulatedTextBuilder.toString();
            var partialReasoning = accumulatedReasoningBuilder.toString();
            if (partialText.isEmpty() && partialReasoning.isEmpty()) {
                return new StreamingResult(null, error);
            }

            // Construct a ChatResponse from accumulated partial text
            var partialResponse = new NullSafeResponse(partialText, partialReasoning, List.of(), null);
            logger.debug(
                    "LLM call resulted in error: {}. Partial text captured: {} chars",
                    error.getMessage(),
                    partialText.length());
            return new StreamingResult(partialResponse, error);
        }

        // Happy path: successful completion, no errors
        var response = completedChatResponse.get(); // Will be null if an error occurred or onComplete got null
        assert response != null : "If no error, completedChatResponse must be set by onCompleteResponse";
        if (echo) {
            io.llmOutput("\n", ChatMessageType.AI);
        }
        return StreamingResult.fromResponse(response, null);
    }

    private long getLlmResponseTimeoutSeconds(boolean firstToken) {
        long firstTokenTimeoutSeconds = Service.getProcessingTier(model) == Service.ProcessingTier.FLEX
                ? Service.FLEX_FIRST_TOKEN_TIMEOUT_SECONDS
                : Service.DEFAULT_FIRST_TOKEN_TIMEOUT_SECONDS;
        return firstToken ? firstTokenTimeoutSeconds : Service.NEXT_TOKEN_TIMEOUT_SECONDS;
    }

    /**
     * GPT-5 reasoning formatting workaround: Insert two newlines before an opening "**" when we are mid-message (not
     * the first chunk). Regex matches "**" not preceded by a newline or word char, followed by a word char, and not
     * part of "***".
     */
    private static String addReasoningNewlinesForGpt5(String text) {
        if (!text.isEmpty()) {
            return text.replaceAll("(?<!\\n)(?<!\\w)\\*\\*(?=\\w)(?!\\*)", "\n\n**");
        }
        return text;
    }

    private static class LitellmException extends LangChain4jException {
        public LitellmException(String message) {
            super(message);
        }
    }

    public static class EmptyResponseError extends LangChain4jException {
        public EmptyResponseError() {
            super("Empty response from LLM");
        }
    }

    private static String formatTokensUsage(ChatResponse response) {
        var tu = (OpenAiTokenUsage) response.tokenUsage();
        var template = "token usage: %,d input (%s cached), %,d output (%s reasoning)";
        var inputDetails = tu.inputTokensDetails();
        var outputDetails = tu.outputTokensDetails();
        return template.formatted(
                tu.inputTokenCount(),
                (inputDetails == null || inputDetails.cachedTokens() == null)
                        ? "?"
                        : "%,d".formatted(inputDetails.cachedTokens()),
                tu.outputTokenCount(),
                (outputDetails == null || outputDetails.reasoningTokens() == null)
                        ? "?"
                        : "%,d".formatted(outputDetails.reasoningTokens()));
    }

    /**
     * Sends a user query to the LLM with streaming. Tools are not used. Writes to conversation history. Optionally
     * echoes partial tokens to the console.
     *
     * @param messages The messages to send
     * @param echo Whether to echo LLM responses to the console as they stream
     * @return The final response from the LLM as a record containing ChatResponse, errors, etc.
     */
    public StreamingResult sendRequest(List<ChatMessage> messages, boolean echo) throws InterruptedException {
        return sendMessageWithRetry(messages, ToolContext.empty(), echo, MAX_ATTEMPTS);
    }

    /** Sends messages to a given model, no tools, no streaming echo. */
    public StreamingResult sendRequest(List<ChatMessage> messages) throws InterruptedException {
        return sendRequest(messages, false);
    }

    /** Sends messages to a model with possible tools and a chosen tool usage policy. */
    public StreamingResult sendRequest(List<ChatMessage> messages, ToolContext toolContext, boolean echo)
            throws InterruptedException {

        var result = sendMessageWithRetry(messages, toolContext, echo, MAX_ATTEMPTS);
        var cr = result.chatResponse();

        // poor man's ToolChoice.REQUIRED (not supported by langchain4j for some providers)
        // Also needed for our emulation if it returns a response without a tool call
        var tools = toolContext.toolSpecifications();
        var toolChoice = toolContext.toolChoice();
        while (result.error == null
                && !tools.isEmpty()
                && (cr != null && cr.toolRequests.isEmpty())
                && toolChoice == ToolChoice.REQUIRED) {
            io.systemOutput("Enforcing tool selection");

            var extraMessages = new ArrayList<>(messages);
            extraMessages.add(requireNonNull(cr.originalResponse).aiMessage());
            extraMessages.add(new UserMessage("At least one tool execution request is REQUIRED. Please call a tool."));

            result = sendMessageWithRetry(extraMessages, toolContext, echo, MAX_ATTEMPTS);
            cr = result.chatResponse();
        }

        return result;
    }

    /**
     * Retries a request up to maxAttempts times on connectivity or empty-result errors, using exponential backoff.
     * Responsible for writeToHistory.
     */
    private StreamingResult sendMessageWithRetry(
            List<ChatMessage> rawMessages, ToolContext toolContext, boolean echo, int maxAttempts)
            throws InterruptedException {
        Throwable lastError = null;
        int attempt = 0;
        var messages = Messages.forLlm(rawMessages);

        StreamingResult response;
        while (attempt++ < maxAttempts) {
            String description = Messages.getText(messages.getLast());
            logger.debug(
                    "Sending request to {} attempt {}: {}",
                    contextManager.getService().nameOf(model),
                    attempt,
                    LogDescription.getShortDescription(description, 12));

            response = doSingleSendMessage(model, messages, toolContext, echo);
            lastError = response.error;
            if (!response.isEmpty() && (lastError == null || allowPartialResponses)) {
                // Success!
                return response.withRetryCount(attempt - 1);
            }

            // don't retry on bad request errors
            if (lastError != null) {
                var msg = requireNonNull(lastError.getMessage());
                if (msg.contains("BadRequestError")
                        || msg.contains("UnsupportedParamsError")
                        || msg.contains("Unable to convert openai tool calls")) {
                    // logged by doSingleStreamingCallInternal, don't be redundant
                    break;
                }
            }

            logger.debug("LLM error == {}, isEmpty == {}. Attempt={}", lastError, response.isEmpty(), attempt);
            if (attempt == maxAttempts) {
                break; // done
            }
            // wait between attempts
            long backoffSeconds = 1L << (attempt - 1);
            backoffSeconds = Math.min(backoffSeconds, 16L);

            // Busywait with countdown
            if (backoffSeconds > 1) {
                io.systemOutput(String.format(
                        "LLM issue on attempt %d/%d (retrying in %d seconds).", attempt, maxAttempts, backoffSeconds));
            } else {
                io.systemOutput(String.format("LLM issue on attempt %d/%d (retrying).", attempt, maxAttempts));
            }
            long endTime = System.currentTimeMillis() + backoffSeconds * 1000;
            while (System.currentTimeMillis() < endTime) {
                long remain = endTime - System.currentTimeMillis();
                if (remain <= 0) break;
                io.systemOutput("Retrying in %.1f seconds...".formatted(remain / 1000.0));
                Thread.sleep(Math.min(remain, 100));
            }
        }

        // If we get here, we failed all attempts
        if (lastError == null) {
            return new StreamingResult(null, new EmptyResponseError(), attempt - 1);
        }
        return new StreamingResult(null, lastError, attempt - 1);
    }

    /**
     * Sends messages to model in a single attempt. If the model doesn't natively support function calling for these
     * tools, we emulate it using a JSON Schema approach. This method now also triggers writing the request to the
     * history file.
     */
    private StreamingResult doSingleSendMessage(
            StreamingChatModel model, List<ChatMessage> messages, ToolContext toolContext, boolean echo)
            throws InterruptedException {
        // Note: writeRequestToHistory is now called *within* this method,
        // right before doSingleStreamingCall, to ensure it uses the final `messagesToSend`.

        var tools = toolContext.toolSpecifications();
        var toolChoice = toolContext.toolChoice();

        var messagesToSend = messages;
        // Preprocess messages *only* if no tools are being requested for this call.
        // This handles the case where prior TERMs exist in history but the current
        // request doesn't involve tools (which makes some providers unhappy if they see tool history).
        if (tools.isEmpty()) {
            messagesToSend = Llm.emulateToolExecutionResults(messages);
            validateEmulatedToolMessages(messagesToSend);
        }

        if (!tools.isEmpty() && contextManager.getService().requiresEmulatedTools(model)) {
            // Emulation handles its own preprocessing and needs the toolContext to validate owner
            return emulateTools(model, messagesToSend, toolContext, echo);
        }

        // If native tools are used, or no tools, send the (potentially preprocessed if tools were empty) messages.
        var requestBuilder = ChatRequest.builder().messages(messagesToSend);
        if (!tools.isEmpty()) {
            logger.debug("Performing native tool calls");
            var paramsBuilder = getParamsBuilder().toolSpecifications(tools);
            if (contextManager.getService().supportsParallelCalls(model)) {
                // can't just blindly call .parallelToolCalls(boolean), litellm will barf if it sees the option at all
                paramsBuilder = paramsBuilder.parallelToolCalls(true);
            }
            if (toolChoice == ToolChoice.REQUIRED && contextManager.getService().supportsToolChoiceRequired(model)) {
                paramsBuilder = paramsBuilder.toolChoice(ToolChoice.REQUIRED);
            }
            requestBuilder.parameters(paramsBuilder.build());
        }

        var request = requestBuilder.build();
        var sr = doSingleStreamingCall(request, echo, false);

        // Pretty-print native tool calls when echo is enabled
        // (For emulated calls, echo means we get the raw json in the response which is not ideal but
        // there's no reason to add a second print of it)
        if (echo && !tools.isEmpty() && !contextManager.getService().requiresEmulatedTools(model)) {
            prettyPrintToolCalls(toolContext.toolOwner(), sr.toolRequests());
        }
        return sr;
    }

    private void prettyPrintToolCalls(Object toolOwner, List<ToolExecutionRequest> requests) {
        if (requests.isEmpty()) {
            return;
        }
        var registry = contextManager.getToolRegistry();
        var rendered = requests.stream()
                .map(tr -> registry.getExplanationForToolRequest(toolOwner, tr))
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining("\n"));
        if (!rendered.isBlank()) {
            io.llmOutput("\nPlanned tool calls:\n" + rendered, ChatMessageType.AI);
        }
    }

    private OpenAiChatRequestParameters.Builder getParamsBuilder() {
        OpenAiChatRequestParameters.Builder builder = OpenAiChatRequestParameters.builder();

        if (this.tagRetain) {
            // this is the only place we add metadata so we can just overwrite what's there
            logger.trace("Adding 'retain' metadata tag to LLM request.");
            Map<String, String> newMetadata = new java.util.HashMap<>();
            newMetadata.put("tags", "retain");
            builder.metadata(newMetadata);
        }

        return builder;
    }

    /**
     * Emulates function calling for models that don't natively support it. We have two approaches: 1. Schema-based: For
     * models that support JSON schema in response_format 2. Text-based: For models without schema support, using text
     * instructions
     */
    private StreamingResult emulateTools(
            StreamingChatModel model, List<ChatMessage> messages, ToolContext toolContext, boolean echo)
            throws InterruptedException {
        var tools = toolContext.toolSpecifications();
        var enhancedTools = ensureThinkToolPresent(tools);
        var toolChoice = toolContext.toolChoice();
        if (contextManager.getService().supportsJsonSchema(model)) {
            return emulateToolsUsingJsonSchema(messages, enhancedTools, toolChoice, echo, toolContext);
        } else {
            return emulateToolsUsingJsonObject(messages, enhancedTools, toolChoice, echo, toolContext);
        }
    }

    /** Common helper for emulating function calling tools using JSON output */
    private StreamingResult emulateToolsCommon(
            List<ChatMessage> messages,
            List<ToolSpecification> tools,
            ToolChoice toolChoice,
            boolean echo,
            Function<List<ChatMessage>, ChatRequest> requestBuilder,
            Function<Throwable, String> retryInstructionsProvider,
            ToolContext toolContext)
            throws InterruptedException {
        assert !tools.isEmpty();

        // Pre-process messages to combine tool results with subsequent user messages for emulation
        List<ChatMessage> initialProcessedMessages = Llm.emulateToolExecutionResults(messages);
        validateEmulatedToolMessages(initialProcessedMessages);

        final int maxTries = 3;
        List<ChatMessage> attemptMessages = new ArrayList<>(initialProcessedMessages);

        ChatRequest lastRequest; // for logging
        StreamingResult finalResult; // what we will return (and have logged)

        for (int attempt = 1; true; attempt++) {
            validateEmulatedToolMessages(attemptMessages);

            // Perform the request for THIS attempt
            lastRequest = requestBuilder.apply(attemptMessages);
            StreamingResult rawResult = doSingleStreamingCall(lastRequest, echo, true);

            // Fast-fail on transport / HTTP errors (no retry)
            if (rawResult.error() != null) {
                finalResult = rawResult; // will be logged below
                break;
            }

            // Now parse the JSON
            try {
                var parseResult = parseJsonToToolRequests(rawResult, objectMapper);

                if (toolChoice == ToolChoice.REQUIRED
                        && parseResult.toolRequests().isEmpty()) {
                    // REQUIRED but none produced – force retry
                    throw new IllegalArgumentException("No 'tool_calls' found in JSON");
                }

                if (echo) {
                    // output the LLM's thinking
                    String textToOutput = parseResult.text();
                    if (textToOutput != null && !textToOutput.isBlank()) {
                        io.llmOutput(textToOutput, ChatMessageType.AI, false, false);
                    }
                }

                // Validate tool call arguments using ToolRegistry; on failure, retry with error feedback
                var registry = contextManager.getToolRegistry();
                var validationErrors = new ArrayList<String>();
                Object toolOwner = requireNonNull(
                        toolContext.toolOwner(), "ToolContext.toolOwner() must be provided for tool validation");
                for (var ter : parseResult.toolRequests()) {
                    try {
                        registry.validateTool(toolOwner, ter);
                    } catch (ToolRegistry.ToolValidationException e) {
                        validationErrors.add(ter.name() + ": " + e.getMessage());
                    }
                }
                if (!validationErrors.isEmpty()) {
                    if (attempt == maxTries) {
                        finalResult = new StreamingResult(
                                null,
                                new IllegalArgumentException(
                                        "Tool call validation failed: " + String.join("; ", validationErrors)),
                                rawResult.retries());
                        break;
                    }
                    if (echo) {
                        io.llmOutput(
                                "\nTool call validation errors:\n- " + String.join("\n- ", validationErrors),
                                ChatMessageType.CUSTOM);
                    }
                    attemptMessages.add(new AiMessage(rawResult.text()));
                    attemptMessages.add(new UserMessage(retryInstructionsProvider.apply(new IllegalArgumentException(
                            "Tool call validation failed: " + String.join("; ", validationErrors)))));
                    continue;
                }

                // we got tool calls, or they're optional -- we're done
                finalResult = new StreamingResult(parseResult, null, rawResult.retries());
                break;
            } catch (IllegalArgumentException parseError) {
                // JSON invalid or lacked tool_calls
                if (attempt == maxTries) {
                    // create dummy result for failure
                    finalResult = new StreamingResult(null, parseError, rawResult.retries());
                    break; // out of retry loop
                }

                // Add the model’s invalid output and user instructions, then retry
                io.llmOutput(
                        "\nRetry " + attempt + "/" + (maxTries - 1)
                                + ": invalid JSON response; requesting proper format.",
                        ChatMessageType.CUSTOM);
                var txt = rawResult.text();
                attemptMessages.add(new AiMessage(txt));
                attemptMessages.add(new UserMessage(retryInstructionsProvider.apply(parseError)));
            }
        }

        // All retries exhausted OR fatal error occurred
        return finalResult;
    }

    /**
     * Preprocesses messages for models requiring tool emulation by combining sequences of ToolExecutionResultMessages
     * with the *subsequent* UserMessage.
     *
     * <p>If a sequence of one or more ToolExecutionResultMessages is immediately followed by a UserMessage, the results
     * are formatted as text and prepended to the UserMessage's content. If the results are followed by a different
     * message type or are at the end of the list. Throws IllegalArgumentException if this condition is violated.
     *
     * @param originalMessages The original list of messages.
     * @return A new list with tool results folded into subsequent UserMessages, or the original list if no
     *     modifications were needed.
     * @throws IllegalArgumentException if ToolExecutionResultMessages are not followed by a UserMessage.
     */
    static List<ChatMessage> emulateToolExecutionResults(List<ChatMessage> originalMessages) {
        var processedMessages = new ArrayList<ChatMessage>();
        var pendingTerms = new ArrayList<ToolExecutionResultMessage>(); // Keep as ArrayList for internal modification
        for (var msg : originalMessages) {
            if (msg instanceof ToolExecutionResultMessage term) {
                // Collect consecutive tool results to group together
                pendingTerms.add(term);
                continue;
            }

            if (!pendingTerms.isEmpty()) {
                // Current message is not a tool result. Check if we have pending results.
                String formattedResults = formatToolResults(pendingTerms);
                if (msg instanceof UserMessage userMessage) {
                    // Combine pending results with this user message
                    String combinedContent = formattedResults + "\n" + Messages.getText(userMessage);
                    UserMessage updatedUserMessage = new UserMessage(userMessage.name(), combinedContent);
                    processedMessages.add(updatedUserMessage);
                    logger.debug("Prepended {} tool result(s) to subsequent user message.", pendingTerms.size());

                    pendingTerms.clear();
                    continue;
                } else {
                    // Create a UserMessage to hold the tool calls
                    processedMessages.add(new UserMessage(formattedResults));
                    pendingTerms.clear();
                    // do not continue, fall through to process the current msg
                }
            }

            if (msg instanceof AiMessage) {
                // pull the tool requests into plaintext, OpenAi is fine with it but it confuses Anthropic and Gemini
                // to see tool requests in the history if there are no tools defined for the current request
                processedMessages.add(new AiMessage(Messages.getRepr(msg)));
                continue;
            }

            // else just add the original message
            processedMessages.add(msg);
        }

        // Handle any trailing tool results - this is invalid.
        if (!pendingTerms.isEmpty()) {
            var formattedResults = formatToolResults(pendingTerms);
            processedMessages.add(new UserMessage(formattedResults));
        }

        validateEmulatedToolMessages(processedMessages);
        return processedMessages;
    }

    /** Verifies that the given messages contain no native ToolExecutionRequests or ToolExecutionResultMessages */
    private static void validateEmulatedToolMessages(List<ChatMessage> messages) {
        assert !containsRawToolResultMessages(messages)
                : "emulateToolExecutionResults failed to fold a ToolExecutionResultMessage: "
                        + summarizeMessages(messages);
        assert !containsAiToolExecutionRequests(messages)
                : "emulateToolExecutionResults left AI tool requests: " + summarizeMessages(messages);
    }

    private static String formatToolResults(
            List<ToolExecutionResultMessage> pendingTerms) { // Changed parameter to List
        return pendingTerms.stream()
                .map(tr ->
                        """
                             <toolcall id="%s" name="%s">
                             %s
                             </toolcall>
                             """
                                .stripIndent()
                                .formatted(tr.id(), tr.toolName(), tr.text()))
                .collect(Collectors.joining("\n"));
    }

    private static boolean containsRawToolResultMessages(List<ChatMessage> messages) {
        return messages.stream().anyMatch(m -> m instanceof ToolExecutionResultMessage);
    }

    private static boolean containsAiToolExecutionRequests(List<ChatMessage> messages) {
        return messages.stream()
                .filter(m -> m instanceof AiMessage)
                .map(m -> (AiMessage) m)
                .anyMatch(AiMessage::hasToolExecutionRequests);
    }

    private static String summarizeMessages(List<ChatMessage> messages) {
        return messages.stream()
                .map(m -> {
                    if (m instanceof SystemMessage) return "System";
                    if (m instanceof UserMessage) return "User";
                    if (m instanceof ToolExecutionResultMessage trm)
                        return "TER(" + trm.toolName() + ":" + trm.id() + ")";
                    if (m instanceof AiMessage ai) return ai.hasToolExecutionRequests() ? "AI(toolCalls)" : "AI";
                    return m.getClass().getSimpleName();
                })
                .collect(Collectors.joining(" -> "));
    }

    /** Emulates function calling for models that support structured output with JSON schema */
    private StreamingResult emulateToolsUsingJsonSchema(
            List<ChatMessage> messages,
            List<ToolSpecification> tools,
            ToolChoice toolChoice,
            boolean echo,
            ToolContext toolContext)
            throws InterruptedException {
        // Build a top-level JSON schema with "tool_calls" as an array of objects
        var toolNames = tools.stream().map(ToolSpecification::name).distinct().toList();
        var schema = buildToolCallsSchema(toolNames);
        var responseFormat = ResponseFormat.builder()
                .type(ResponseFormatType.JSON)
                .jsonSchema(schema)
                .build();
        var requestParams = getParamsBuilder().responseFormat(responseFormat).build();

        Function<Throwable, String> retryInstructionsProvider = (@Nullable Throwable e) ->
                """
                %s
                Please ensure you only return a JSON object matching the schema:
                  {
                    "tool_calls": [
                      {
                        "name": "...",
                        "arguments": { ... }
                      },
                      {
                        "name": "...",
                        "arguments": { ... }
                      }
                    ]
                  }
                """
                        .stripIndent()
                        .formatted(
                                e == null
                                        ? ""
                                        : "Your previous response was invalid or did not contain tool_calls: "
                                                + e.getMessage());

        // We'll add a user reminder to produce a JSON that matches the schema
        var instructions = getInstructions(tools, retryInstructionsProvider);
        var modified = new UserMessage(Messages.getText(messages.getLast()) + "\n\n" + instructions);
        var initialMessages = new ArrayList<>(messages);
        initialMessages.set(initialMessages.size() - 1, modified);
        logger.trace("Modified messages are {}", initialMessages);

        // Build request creator function
        Function<List<ChatMessage>, ChatRequest> requestBuilder = attemptMessages -> ChatRequest.builder()
                .messages(attemptMessages)
                .parameters(requestParams)
                .build();

        return emulateToolsCommon(
                initialMessages, tools, toolChoice, echo, requestBuilder, retryInstructionsProvider, toolContext);
    }

    /** Emulates function calling for models that don't support schema but can output JSON based on text instructions */
    private StreamingResult emulateToolsUsingJsonObject(
            List<ChatMessage> messages,
            List<ToolSpecification> tools,
            ToolChoice toolChoice,
            boolean echo,
            ToolContext toolContext)
            throws InterruptedException {
        Function<Throwable, String> retryInstructionsProvider = (@Nullable Throwable e) ->
                """
                %s
                Respond with a single JSON object containing a `tool_calls` array. Each entry in the array represents one invocation of a tool.
                No additional keys or text are allowed outside of that JSON object.
                Each tool call must have a `name` that matches one of the available tools, and an `arguments` object containing valid parameters as required by that tool.

                Here is the format visualized, where $foo indicates that you will make appropriate substitutions for the given tool call
                {
                  "tool_calls": [
                    {
                      "name": "$tool_name1",
                      "arguments": {
                        "$arg1": "$value1",
                        "$arg2": "$value2",
                        ...
                      }
                    },
                    {
                      "name": "$tool_name2",
                      "arguments": {
                        "$arg3": "$value3",
                        "$arg4": "$value4",
                        ...
                      }
                    }
                  ]
                }
                """
                        .stripIndent()
                        .formatted(e == null ? "" : "Your previous response was not valid: " + e.getMessage());

        // Check if we've already added tool instructions to any message
        boolean instructionsPresent = messages.stream()
                .anyMatch(m -> Messages.getText(m).contains("available tools:")
                        && Messages.getText(m).contains("tool_calls"));

        logger.debug(
                "Tool emulation sending {} messages with instructionsPresent={}", messages.size(), instructionsPresent);

        // Prepare messages, possibly adding instructions
        List<ChatMessage> initialMessages = new ArrayList<>(messages);
        if (!instructionsPresent) {
            var instructions = getInstructions(tools, retryInstructionsProvider);
            var lastMessage = messages.getLast();
            var modified = new UserMessage(Messages.getText(lastMessage) + "\n\n" + instructions);
            initialMessages.set(initialMessages.size() - 1, modified);
            logger.trace("Added tool instructions to last message");
        }

        // Simple request builder for JSON output format
        Function<List<ChatMessage>, ChatRequest> requestBuilder = attemptMessages -> ChatRequest.builder()
                .messages(attemptMessages)
                .parameters(OpenAiChatRequestParameters.builder()
                        .responseFormat(ResponseFormat.builder()
                                .type(ResponseFormatType.JSON)
                                .build())
                        .build())
                .build();

        return emulateToolsCommon(
                initialMessages, tools, toolChoice, echo, requestBuilder, retryInstructionsProvider, toolContext);
    }

    /**
     * Builds a JSON schema describing exactly: { "tool_calls": [ { "name": "oneOfTheTools", "arguments": { ...
     * arbitrary object ...} } ] } We do not attempt fancy anyOf references here (not all providers support them).
     */
    private static JsonSchema buildToolCallsSchema(List<String> toolNames) {
        // name => enum of tool names
        var nameSchema = JsonEnumSchema.builder()
                .enumValues(toolNames)
                .description("Name of the tool to call; must be one of: " + String.join(", ", toolNames))
                .build();

        // arguments => free-form object
        // FIXME this should really use anyOf, but that's not supported by LLMs today
        var argumentsSchema = JsonObjectSchema.builder()
                .description("Tool arguments object (specific structure depends on the tool).")
                .build();

        // each item => { name, arguments }
        var itemSchema = JsonObjectSchema.builder()
                .addProperty("name", nameSchema)
                .addProperty("arguments", argumentsSchema)
                .required("name", "arguments")
                .build();

        // array property "tool_calls"
        var toolCallsArray = JsonArraySchema.builder()
                .description("All tool calls to be made in sequence.")
                .items(itemSchema)
                .build();

        // top-level object
        var rootSchema = JsonObjectSchema.builder()
                .addProperty("tool_calls", toolCallsArray)
                .required("tool_calls")
                .description("Top-level object containing a 'tool_calls' array describing calls to be made.")
                .build();

        return JsonSchema.builder().name("ToolCalls").rootElement(rootSchema).build();
    }

    /**
     * Parse the model's JSON response into a ChatResponse that includes ToolExecutionRequests. Expects the top-level to
     * have a "tool_calls" array (or the root might be that array).
     */
    private NullSafeResponse parseJsonToToolRequests(StreamingResult result, ObjectMapper mapper) {
        // In the primary call path (emulateToolsCommon), if result.error() is null,
        // then result.chatResponse() is guaranteed to be non-null by StreamingResult's invariant.
        // This method is called in that context.
        NullSafeResponse cResponse = castNonNull(result.chatResponse());
        String rawText = requireNonNull(cResponse.text());
        logger.trace("parseJsonToToolRequests: rawText={}", rawText);

        JsonNode root;
        try {
            root = mapper.readTree(rawText);
        } catch (JsonProcessingException e) {
            // Sometimes the model wraps the JSON in markdown fences ```json ... ```
            int firstFence = rawText.indexOf("```");
            // Find the closing fence after the opening one
            int lastFence = rawText.lastIndexOf("```");
            if (lastFence <= firstFence) {
                logger.debug("Invalid JSON", e);
                throw new IllegalArgumentException("Invalid JSON response: " + e.getMessage());
            }

            // Extract text between fences, removing potential language identifier and trimming whitespace
            String fencedText = rawText.substring(firstFence + 3, lastFence).strip();
            // Handle optional language identifier like "json"
            if (com.google.common.base.Ascii.toLowerCase(fencedText).startsWith("json")) {
                fencedText = fencedText.substring(4).stripLeading();
            }

            // Try parsing the fenced content
            try {
                root = mapper.readTree(fencedText);
            } catch (JsonProcessingException e2) {
                // Fenced content is also invalid JSON
                logger.debug("Invalid JSON inside fences", e2);
                throw new IllegalArgumentException("Invalid JSON inside ``` fences: " + e2.getMessage());
            }
        }

        JsonNode toolCallsNode;
        if (root.has("tool_calls") && root.get("tool_calls").isArray()) {
            // happy path, this is what we asked for
            toolCallsNode = root.get("tool_calls");
        } else if (root.isArray()) {
            // some models like to give a top-level array instead
            toolCallsNode = root;
        } else {
            throw new IllegalArgumentException("Response does not contain a 'tool_calls' array");
        }

        // Transform json into tool execution requests, special-casing "think" calls
        var toolExecutionRequests = new ArrayList<ToolExecutionRequest>();
        var thinkReasoning = new ArrayList<String>();

        for (int i = 0; i < toolCallsNode.size(); i++) {
            JsonNode toolCall = toolCallsNode.get(i);
            if (!toolCall.has("name") || !toolCall.has("arguments")) {
                throw new IllegalArgumentException(
                        "Tool call object is missing 'name' or 'arguments' field at index " + i);
            }

            String toolName = toolCall.get("name").asText();
            JsonNode arguments = toolCall.get("arguments");

            if (!arguments.isObject()) {
                throw new IllegalArgumentException("tool_calls[" + i + "] provided non-object arguments " + arguments);
            }
            String argsStr = arguments.toString(); // Preserve raw JSON for execution

            if ("think".equals(toolName)) {
                // Extract reasoning from the "think" tool
                if (!arguments.has("reasoning") || !arguments.get("reasoning").isTextual()) {
                    throw new IllegalArgumentException(
                            "Found 'think' tool call without a textual 'reasoning' argument at index " + i);
                }
                thinkReasoning.add(arguments.get("reasoning").asText());
            }
            var toolExecutionRequest = ToolExecutionRequest.builder()
                    .id(String.valueOf(toolRequestIdSeq.getAndIncrement()))
                    .name(toolName)
                    .arguments(argsStr)
                    .build();
            toolExecutionRequests.add(toolExecutionRequest);
        }
        logger.trace("Generated tool execution requests: {}", toolExecutionRequests);

        String aiMessageText;
        if (thinkReasoning.isEmpty()) {
            aiMessageText = "";
        } else {
            aiMessageText = String.join("\n\n", thinkReasoning); // Merged reasoning becomes the message text
        }

        // Pass the original raw response alongside the parsed one
        return new NullSafeResponse(
                aiMessageText, cResponse.reasoningContent(), toolExecutionRequests, result.originalResponse());
    }

    private static String getInstructions(
            List<ToolSpecification> tools, Function<@Nullable Throwable, String> retryInstructionsProvider) {
        String toolsDescription = tools.stream()
                .map(tool -> {
                    var props = tool.parameters() == null
                            ? Map.<String, JsonSchemaElement>of()
                            : tool.parameters().properties();
                    var parametersInfo = props.entrySet().stream()
                            .map(entry -> {
                                var schema = entry.getValue();
                                String description;
                                String type;

                                // this seems unnecessarily clunky but the common interface does not offer anything
                                // useful
                                switch (schema) {
                                    case JsonStringSchema jsSchema -> {
                                        description = jsSchema.description();
                                        type = "string";
                                    }
                                    case JsonArraySchema jaSchema -> {
                                        description = jaSchema.description();
                                        var itemSchema = jaSchema.items();
                                        String itemType =
                                                switch (itemSchema) {
                                                    case JsonStringSchema __ -> "string";
                                                    case JsonIntegerSchema __ -> "integer";
                                                    case JsonNumberSchema __ -> "number";
                                                    case JsonBooleanSchema __ -> "boolean";
                                                    default ->
                                                        throw new IllegalArgumentException(
                                                                "Unsupported array item type: " + itemSchema);
                                                };
                                        type = "array of %s".formatted(itemType);
                                    }
                                    case JsonIntegerSchema jiSchema -> {
                                        description = jiSchema.description();
                                        type = "integer";
                                    }
                                    case JsonNumberSchema jnSchema -> {
                                        description = jnSchema.description();
                                        type = "number";
                                    }
                                    case JsonBooleanSchema jbSchema -> {
                                        description = jbSchema.description();
                                        type = "boolean";
                                    }
                                    case JsonObjectSchema joSchema -> {
                                        description = joSchema.description();
                                        type = "map";
                                    }
                                    default -> throw new IllegalArgumentException("Unsupported schema type: " + schema);
                                }
                                assert description != null : "null description for " + entry;

                                return """
                                        <parameter name="%s" type="%s" required="%s">
                                        %s
                                        </parameter>
                                        """
                                        .stripIndent()
                                        .formatted(
                                                entry.getKey(),
                                                type,
                                                requireNonNull(tool.parameters())
                                                        .required()
                                                        .contains(entry.getKey()),
                                                description);
                            })
                            .collect(Collectors.joining("\n"));

                    return """
                            <tool name="%s">
                            %s
                            %s
                            </tool>
                            """
                            .stripIndent()
                            .formatted(
                                    tool.name(),
                                    tool.description(),
                                    parametersInfo.isEmpty() ? "(No parameters)" : parametersInfo);
                })
                .collect(Collectors.joining("\n"));

        // if you change this you probably also need to change emulatedToolInstructionsPresent
        return """
                %d available tools:
                %s

                %s

                Include all the tool calls necessary to satisfy the request in a single object!
                """
                .stripIndent()
                .formatted(tools.size(), toolsDescription, retryInstructionsProvider.apply(null));
    }

    /**
     * Ensures the "think" tool is present in the tools list for emulation, but only for models that are *not*
     * designated as "reasoning" models (which are expected to think implicitly). This provides a consistent way for
     * non-reasoning models to reason through complex problems.
     *
     * @param originalTools The original list of tool specifications.
     * @return A new list containing all original tools plus the think tool if not already present and the model is not
     *     a designated reasoning model.
     */
    private List<ToolSpecification> ensureThinkToolPresent(List<ToolSpecification> originalTools) {
        // Check if the think tool is already in the list
        boolean hasThinkTool = originalTools.stream().anyMatch(tool -> "think".equals(tool.name()));
        if (hasThinkTool) {
            return originalTools;
        }

        // Add the think tool only if the model is not a reasoning model
        if (!contextManager.getService().isReasoning(this.model)) {
            logger.debug(
                    "Adding 'think' tool for non-reasoning model {}",
                    contextManager.getService().nameOf(this.model));
            var enhancedTools = new ArrayList<>(originalTools);
            enhancedTools.addAll(contextManager.getToolRegistry().getRegisteredTools(List.of("think")));
            return enhancedTools;
        }
        logger.debug(
                "Skipping 'think' tool for reasoning model {}",
                contextManager.getService().nameOf(this.model));
        return originalTools;
    }

    /**
     * Writes response history (.log) to task-specific files, pairing with pre-sent request JSON via a shared base path.
     */
    private synchronized void logResult(
            StreamingChatModel model, ChatRequest request, @Nullable StreamingResult result) {
        try {
            var formattedRequest = "# Request to %s:\n\n%s\n"
                    .formatted(contextManager.getService().nameOf(model), TaskEntry.formatMessages(request.messages()));
            var formattedTools = request.toolSpecifications() == null
                    ? ""
                    : "# Tools:\n\n"
                            + request.toolSpecifications().stream()
                                    .map(ToolSpecification::name)
                                    .collect(Collectors.joining("\n"));
            var formattedResponse =
                    result == null ? "# Response:\n\nCancelled" : "# Response:\n\n%s".formatted(result.formatted());

            String fileTimestamp = logFileTimestamp();
            String shortDesc =
                    result == null ? "Cancelled" : LogDescription.getShortDescription(result.getDescription());
            var filePath = taskHistoryDir.resolve(
                    String.format("%s %03d-%s.log", fileTimestamp, requestSequence++, shortDesc));
            var options = new StandardOpenOption[] {
                StandardOpenOption.CREATE, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE
            };
            logger.trace("Writing history to file {}", filePath);
            Files.writeString(filePath, formattedRequest + formattedTools + formattedResponse, options);
        } catch (IOException e) {
            logger.error("Failed to write LLM response history file", e);
        }
    }

    public record NullSafeResponse(
            @Nullable String text,
            @Nullable String reasoningContent,
            List<ToolExecutionRequest> toolRequests,
            @Nullable ChatResponse originalResponse) {
        public NullSafeResponse(@Nullable ChatResponse cr) {
            this(
                    cr == null || cr.aiMessage() == null ? null : cr.aiMessage().text(),
                    cr == null || cr.aiMessage() == null ? null : cr.aiMessage().reasoningContent(),
                    cr == null || cr.aiMessage() == null || !cr.aiMessage().hasToolExecutionRequests()
                            ? List.of()
                            : cr.aiMessage().toolExecutionRequests(),
                    cr);
        }

        public boolean isEmpty() {
            var emptyText = text == null || text.isEmpty();
            return emptyText && toolRequests.isEmpty();
        }

        public AiMessage aiMessage() {
            var messageText = text == null ? "" : text;
            return new AiMessage(messageText, reasoningContent, toolRequests);
        }
    }

    public void setOutput(IConsoleIO io) {
        // TODO this should be final but disentangling from ContextManager is difficult
        this.io = io;
    }

    public record RichTokenUsage(int inputTokens, int cachedInputTokens, int thinkingTokens, int outputTokens) {}

    /**
     * The result of a streaming cal. Exactly one of (chatResponse, error) is not null UNLESS if the LLM hangs up
     * abruptly after starting its response. In that case we'll forge a NullSafeResponse with the partial result and
     * also include the error that we got from the HTTP layer. In this case chatResponse and error will both be
     * non-null, but chatResponse.originalResponse will be null.
     *
     * <p>Generally, callers should use the helper methods isEmpty, isPartial, etc. instead of manually inspecting the
     * contents of chatResponse.
     */
    public record StreamingResult(@Nullable NullSafeResponse chatResponse, @Nullable Throwable error, int retries) {
        public StreamingResult(@Nullable NullSafeResponse partialResponse, @Nullable Throwable error) {
            this(partialResponse, error, 0);
        }

        public static StreamingResult fromResponse(@Nullable ChatResponse originalResponse, @Nullable Throwable error) {
            return new StreamingResult(new NullSafeResponse(originalResponse), error);
        }

        public StreamingResult {
            if (error == null) {
                // If there's no error, we must have a chatResponse.
                assert chatResponse != null;
            } else {
                // If there is an error, chatResponse may or may not be present.
                // If chatResponse IS present, its originalResponse MUST be null,
                // indicating it's a partial/synthetic response accompanying an error.
                assert chatResponse == null || chatResponse.originalResponse == null;
            }
        }

        public @Nullable RichTokenUsage tokenUsage() {
            if (originalResponse() == null) {
                return null;
            }
            var usage = (OpenAiTokenUsage) originalResponse().tokenUsage();
            if (usage == null) {
                logger.warn("Response is present but tokenUsage is null. Litellm bug?");
                return null;
            }

            // always present
            int inputTokens = usage.inputTokenCount();
            int cachedInputTokens = 0;
            int thinkingTokens = 0;
            int outputTokens = usage.outputTokenCount();

            // only present if litellm didn't fuck up the streaming-complete response
            var inputDetails = usage.inputTokensDetails();
            var outputDetails = usage.outputTokensDetails();
            if (inputDetails != null && inputDetails.cachedTokens() != null) {
                cachedInputTokens = inputDetails.cachedTokens();
            }
            if (outputDetails != null && outputDetails.reasoningTokens() != null) {
                thinkingTokens = outputDetails.reasoningTokens();
            }

            return new RichTokenUsage(inputTokens, cachedInputTokens, thinkingTokens, outputTokens);
        }

        public String text() {
            if (chatResponse == null) {
                return "";
            }
            var text = chatResponse.text();
            return text == null ? "" : text;
        }

        public boolean isEmpty() {
            return chatResponse == null || chatResponse.isEmpty();
        }

        public @Nullable ChatResponse originalResponse() {
            return chatResponse == null ? null : chatResponse.originalResponse;
        }

        public List<ToolExecutionRequest> toolRequests() {
            return chatResponse == null ? List.of() : chatResponse.toolRequests;
        }

        /** Package-private since unless you are test code you should almost always call aiMessage() instead */
        @VisibleForTesting
        AiMessage originalMessage() {
            return requireNonNull(requireNonNull(chatResponse).originalResponse).aiMessage();
        }

        public boolean isPartial() {
            if (error == null) {
                return castNonNull(originalResponse()).finishReason() == FinishReason.LENGTH;
            }
            return chatResponse != null;
        }

        /** @return the response text if a response is present; else throws */
        public AiMessage aiMessage() {
            requireNonNull(chatResponse);
            return chatResponse.aiMessage();
        }

        public String formatted() {
            if (error != null) {
                String contentToShow;
                // text() helper method returns chatResponse.text() or "" if chatResponse is null.
                if (!text().isEmpty()) {
                    contentToShow = "[Partial response text]\n" + text();
                } else {
                    contentToShow = "[No response content available]";
                }
                return """
                       [Error: %s]
                       %s
                       """
                        .stripIndent()
                        .formatted(formatThrowable(error), contentToShow);
            }
            // If no error, originalResponse is guaranteed to be non-null by the record's invariant.
            return castNonNull(originalResponse()).toString();
        }

        private String formatThrowable(Throwable th) {
            var baos = new ByteArrayOutputStream();
            try (var ps = new PrintStream(baos)) {
                th.printStackTrace(ps);
            }
            return baos.toString(StandardCharsets.UTF_8);
        }

        /**
         * Generates a short description of the result for logging purposes.
         *
         * @return A short description string.
         */
        public String getDescription() {
            if (error != null) {
                return requireNonNull(error.getMessage(), error.toString());
            }

            var cr = castNonNull(chatResponse);
            if (!cr.toolRequests().isEmpty()) {
                return cr.toolRequests().stream()
                        .map(ToolExecutionRequest::name)
                        .collect(Collectors.joining(", "));
            }
            var text = cr.text();
            if (text == null || text.isBlank()) {
                return "[empty response]";
            }

            return text;
        }

        public StreamingResult withRetryCount(int retries) {
            return new StreamingResult(chatResponse, error, retries);
        }
    }
}
