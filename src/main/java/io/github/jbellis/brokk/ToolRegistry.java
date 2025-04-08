package io.github.jbellis.brokk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Discovers, registers, provides specifications for, and executes tools.
 * Tools are methods annotated with @Tool on registered object instances.
 */
public class ToolRegistry {
    private static final Logger logger = LogManager.getLogger(ToolRegistry.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // Maps tool name to its invocation target (method + instance)
    private final Map<String, ToolInvocationTarget> toolMap = new ConcurrentHashMap<>();
    private final IContextManager contextManager; // Example dependency needed by tools

    // Internal record to hold method and the instance it belongs to
    private record ToolInvocationTarget(Method method, Object instance) {}

    /**
     * Creates a new ToolRegistry.
     * @param contextManager A shared context manager, potentially needed by tools.
     */
    public ToolRegistry(IContextManager contextManager) {
        this.contextManager = Objects.requireNonNull(contextManager, "contextManager cannot be null");
        // Could add other shared dependencies here if needed by many tools
    }

    /**
     * Registers all methods annotated with @Tool from the given object instance.
     * @param toolProviderInstance An instance of a class containing methods annotated with @Tool.
     */
    public void register(Object toolProviderInstance) {
        Objects.requireNonNull(toolProviderInstance, "toolProviderInstance cannot be null");
        Class<?> clazz = toolProviderInstance.getClass();

        for (Method method : clazz.getMethods()) {
            if (method.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class)) {
                dev.langchain4j.agent.tool.Tool toolAnnotation = method.getAnnotation(dev.langchain4j.agent.tool.Tool.class);
                String toolName = toolAnnotation.name().isEmpty() ? method.getName() : toolAnnotation.name();

                if (toolMap.containsKey(toolName)) {
                    logger.warn("Duplicate tool name registration attempted: '{}'. Ignoring registration from {}",
                                toolName, clazz.getName());
                } else {
                    // Check if the method requires IContextManager and if we have one
                    if (Stream.of(method.getParameterTypes()).anyMatch(paramType -> paramType.equals(IContextManager.class)) && this.contextManager == null) {
                        logger.warn("Tool '{}' requires IContextManager, but none was provided to the registry. Skipping registration.", toolName);
                        continue;
                    }
                    // Could add checks for other required dependencies here

                    logger.debug("Registering tool: '{}' from class {}", toolName, clazz.getName());
                    toolMap.put(toolName, new ToolInvocationTarget(method, toolProviderInstance));
                }
            }
        }
    }

    /**
     * Generates ToolSpecifications for the given list of tool names.
     * @param toolNames A list of tool names to get specifications for.
     * @return A list of ToolSpecification objects. Returns an empty list if a name is not found.
     */
    public List<ToolSpecification> getToolSpecifications(List<String> toolNames) {
        return toolNames.stream()
                .map(toolMap::get)
                .filter(Objects::nonNull)
                .map(target -> ToolSpecifications.toolSpecificationFrom(target.method()))
                .collect(Collectors.toList());
    }

    /**
     * Executes a tool based on the provided ToolExecutionRequest.
     * Handles argument parsing and method invocation via reflection.
     * @param request The ToolExecutionRequest from the LLM.
     * @return A ToolExecutionResult indicating success or failure.
     */
    public ToolExecutionResult executeTool(ToolExecutionRequest request) {
        ToolInvocationTarget target = toolMap.get(request.name());
        if (target == null) {
            logger.error("Tool not found: {}", request.name());
            // Return failure, ActionType is unknown, maybe OTHER?
            return ToolExecutionResult.failure(request, ToolExecutionResult.ActionType.OTHER, "Tool not found: " + request.name());
        }

        Method method = target.method();
        Object instance = target.instance();

        try {
            // 1. Parse JSON arguments from the request
            Map<String, Object> argumentsMap = OBJECT_MAPPER.readValue(request.arguments(), new TypeReference<HashMap<String, Object>>() {});

            // 2. Prepare the arguments array for Method.invoke
            Parameter[] parameters = method.getParameters();
            Object[] methodArgs = new Object[parameters.length];

            for (int i = 0; i < parameters.length; i++) {
                Parameter param = parameters[i];
                dev.langchain4j.agent.tool.P paramAnnotation = param.getAnnotation(dev.langchain4j.agent.tool.P.class);
                String paramName = paramAnnotation != null ? paramAnnotation.value() : param.getName(); // Requires -parameters flag for getName()

                if (!argumentsMap.containsKey(paramName)) {
                     // Check if the parameter is an expected dependency like IContextManager
                     if (param.getType().equals(IContextManager.class) && this.contextManager != null) {
                         methodArgs[i] = this.contextManager;
                         continue;
                     }
                     // Could add similar checks for other injectable dependencies here

                    throw new IllegalArgumentException("Missing required parameter: '" + paramName + "' in arguments: " + request.arguments());
                }

                Object argValue = argumentsMap.get(paramName);

                // Convert the argument to the correct type expected by the method parameter
                // Jackson might have already done some conversion (e.g., numbers), but lists/etc. might need care.
                // Using ObjectMapper for type conversion.
                methodArgs[i] = OBJECT_MAPPER.convertValue(argValue, param.getType());
            }

            // 3. Invoke the method
            logger.debug("Invoking tool '{}' with args: {}", request.name(), List.of(methodArgs));
            Object resultObject = method.invoke(instance, methodArgs);
            String resultString = resultObject != null ? resultObject.toString() : "";

            // Determine ActionType - simple heuristic for now
            // TODO: Could enhance this, maybe via an annotation on the tool method?
            ToolExecutionResult.ActionType actionType = determineActionType(request.name());

            return ToolExecutionResult.success(request, actionType, resultString);

        } catch (JsonProcessingException e) {
            logger.error("Error parsing arguments for tool {}: {}", request.name(), e.getMessage());
            return ToolExecutionResult.failure(request, determineActionType(request.name()), "Error parsing arguments: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            logger.error("Argument mismatch for tool {}: {}", request.name(), e.getMessage());
            return ToolExecutionResult.failure(request, determineActionType(request.name()), "Argument error: " + e.getMessage());
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            logger.error("Error executing tool {}: {}", request.name(), cause.getMessage(), cause);
            return ToolExecutionResult.failure(request, determineActionType(request.name()), cause.getMessage());
        } catch (IllegalAccessException e) {
            logger.error("Access error executing tool {}: {}", request.name(), e.getMessage(), e);
            return ToolExecutionResult.failure(request, determineActionType(request.name()), "Access error: " + e.getMessage());
        } catch (Exception e) { // Catch any other unexpected errors
            logger.error("Unexpected error executing tool {}: {}", request.name(), e.getMessage(), e);
            return ToolExecutionResult.failure(request, determineActionType(request.name()), "Unexpected error: " + e.getMessage());
        }
    }

    /**
     * Simple heuristic to guess the action type based on tool name prefixes/suffixes.
     * This could be improved (e.g., using annotations on tool methods).
     */
    private ToolExecutionResult.ActionType determineActionType(String toolName) {
        if (toolName.startsWith("get") || toolName.startsWith("search") || toolName.startsWith("find")) {
            return ToolExecutionResult.ActionType.QUERY;
        }
        if (toolName.startsWith("replace") || toolName.startsWith("update") || toolName.startsWith("set")) {
            return ToolExecutionResult.ActionType.MODIFY;
        }
        if (toolName.startsWith("create") || toolName.startsWith("add")) {
            return ToolExecutionResult.ActionType.CREATE;
        }
        if (toolName.startsWith("delete") || toolName.startsWith("remove")) {
            return ToolExecutionResult.ActionType.DELETE;
        }
        if (toolName.equals("answer") || toolName.equals("abort") || toolName.equals("explain")) {
            return ToolExecutionResult.ActionType.CONTROL;
        }
        // Default guess
        return ToolExecutionResult.ActionType.OTHER;
    }
}
