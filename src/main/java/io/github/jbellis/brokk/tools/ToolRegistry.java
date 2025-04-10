package io.github.jbellis.brokk.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import io.github.jbellis.brokk.IContextManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Discovers, registers, provides specifications for, and executes tools.
 * Tools are methods annotated with @Tool on registered object instances.
 */
public class ToolRegistry {
    private static final Logger logger = LogManager.getLogger(ToolRegistry.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // Maps tool name to its invocation target (method + instance)
    private final Map<String, ToolInvocationTarget> toolMap = new ConcurrentHashMap<>();

    // Internal record to hold method and the instance it belongs to
    private record ToolInvocationTarget(Method method, Object instance) {}

    /**
     * Creates a new ToolRegistry.
     */
    public ToolRegistry() {
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
                    throw new IllegalArgumentException("Duplicate tool name registration attempted: '%s'".formatted(toolName));
                } else {
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
            // Return failure
            return ToolExecutionResult.failure(request, "Tool not found: " + request.name());
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
                if (!argumentsMap.containsKey(param.getName())) {
                    throw new IllegalArgumentException("Missing required parameter: '%s' in arguments: %s".formatted(param.getName(), request.arguments()));
                }

                Object argValue = argumentsMap.get(param.getName());

                // Convert the argument to the correct type expected by the method parameter
                // Jackson might have already done some conversion (e.g., numbers), but lists/etc. might need care.
                // Using ObjectMapper for type conversion.
                methodArgs[i] = OBJECT_MAPPER.convertValue(argValue, param.getType());
            }

            // 3. Invoke the method
            logger.debug("Invoking tool '{}' with args: {}", request.name(), List.of(methodArgs));
            Object resultObject = method.invoke(instance, methodArgs);
            String resultString = resultObject != null ? resultObject.toString() : "";

            return ToolExecutionResult.success(request, resultString);
        } catch (Exception e) {
            logger.error("Error executing tool {}", request.name(), e);
            return ToolExecutionResult.failure(request, e.getMessage());
        }
    }
}
