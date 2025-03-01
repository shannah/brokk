package io.github.jbellis.brokk;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CoderTest {

    @Test
    @EnabledIfSystemProperty(named = "user.home", matches = ".+")
    void testAnthropicCaching() throws IOException {
        // Read API key from file
        Path keyPath = Paths.get(System.getProperty("user.home"), ".secrets", "anthropic_api_key");
        if (!Files.exists(keyPath)) {
            System.out.println("Skipping test: API key file not found at " + keyPath);
            return;
        }
        
        String apiKey = Files.readString(keyPath).trim();
        
        // Setup an Anthropic model with caching enabled
        ChatLanguageModel model = AnthropicChatModel.builder()
                .apiKey(apiKey)
                .modelName("claude-3-haiku-20240307")
                .cacheSystemMessages(true)
                .logRequests(true)
                .logResponses(true)
                .build();

        // Create system and user messages
        var systemMessage = new dev.langchain4j.data.message.SystemMessage("""
       SYSTEM: You are a code search agent that helps find relevant code based on queries.
       Even if not explicitly stated, the query should be understood to refer to the current codebase,
       and not a general-knowledge question.
       Your goal is to find code definitions, implementations, and usages that answer the user's query.
       
       <original-query>
       what model is used by default
       </original-query>
       
       
       USER: Determine the next action(s) to take to search for code related to: what model is used by default.
       It is more efficient to call multiple tools in a single response when you know they will be needed.
       But if you don't have enough information to speculate, you can call just one tool.
       Start with broad searches, and then explore more specific code units once you find a foothold.
       For example, if the user is asking
       [how do Cassandra reads prevent compaction from invalidating the sstables they are referencing]
       then we should start with searchSymbols(".*SSTable.*) or searchSymbols(".*Compaction.*") or searchSymbols(".*reference.*"),
       instead of a more specific pattern like ".*SSTable.*compaction.*" or ".*compaction.*invalidation.*"
       
       
       Tools:
       - searchSymbols: Search for symbols (class/method/field definitions) using Joern. This should usually be the first step in a search.
       - getUsages: Find where a symbol is used in code. Use this to discover how a class, method, or field is actually used throughout the codebase.
       - getRelatedClasses: Find related classes. Use this for exploring and also when you're almost done and want to double-check that you haven't missed anything.
       - getClassSkeleton: Get an overview of a class's contents, including fields and method signatures. Use this to understand a class's structure without fetching its full source code.
       - getClassSource: Get the full source code of a class. This is expensive, so prefer using skeleton or method sources when possible. Use this when you need the complete implementation details, or if you think multiple methods in the class may be relevant.
       - getMethodSource: Get the source code of a specific method. Use this to examine the implementation of a particular method without retrieving the entire class.
       SYSTEM: You are a code search agent that helps find relevant code based on queries.
       Even if not explicitly stated, the query should be understood to refer to the current codebase,
       and not a general-knowledge question.
       Your goal is to find code definitions, implementations, and usages that answer the user's query.
       
       <original-query>
       what model is used by default
       </original-query>
       
       
       USER: Determine the next action(s) to take to search for code related to: what model is used by default.
       It is more efficient to call multiple tools in a single response when you know they will be needed.
       But if you don't have enough information to speculate, you can call just one tool.
       Start with broad searches, and then explore more specific code units once you find a foothold.
       For example, if the user is asking
       [how do Cassandra reads prevent compaction from invalidating the sstables they are referencing]
       then we should start with searchSymbols(".*SSTable.*) or searchSymbols(".*Compaction.*") or searchSymbols(".*reference.*"),
       instead of a more specific pattern like ".*SSTable.*compaction.*" or ".*compaction.*invalidation.*"
       
       
       Tools:
       - searchSymbols: Search for symbols (class/method/field definitions) using Joern. This should usually be the first step in a search.
       - getUsages: Find where a symbol is used in code. Use this to discover how a class, method, or field is actually used throughout the codebase.
       - getRelatedClasses: Find related classes. Use this for exploring and also when you're almost done and want to double-check that you haven't missed anything.
       - getClassSkeleton: Get an overview of a class's contents, including fields and method signatures. Use this to understand a class's structure without fetching its full source code.
       - getClassSource: Get the full source code of a class. This is expensive, so prefer using skeleton or method sources when possible. Use this when you need the complete implementation details, or if you think multiple methods in the class may be relevant.
       - getMethodSource: Get the source code of a specific method. Use this to examine the implementation of a particular method without retrieving the entire class.
       SYSTEM: You are a code search agent that helps find relevant code based on queries.
       Even if not explicitly stated, the query should be understood to refer to the current codebase,
       and not a general-knowledge question.
       Your goal is to find code definitions, implementations, and usages that answer the user's query.
       
       <original-query>
       what model is used by default
       </original-query>
       
       
       USER: Determine the next action(s) to take to search for code related to: what model is used by default.
       It is more efficient to call multiple tools in a single response when you know they will be needed.
       But if you don't have enough information to speculate, you can call just one tool.
       Start with broad searches, and then explore more specific code units once you find a foothold.
       For example, if the user is asking
       [how do Cassandra reads prevent compaction from invalidating the sstables they are referencing]
       then we should start with searchSymbols(".*SSTable.*) or searchSymbols(".*Compaction.*") or searchSymbols(".*reference.*"),
       instead of a more specific pattern like ".*SSTable.*compaction.*" or ".*compaction.*invalidation.*"
       
       
       Tools:
       - searchSymbols: Search for symbols (class/method/field definitions) using Joern. This should usually be the first step in a search.
       - getUsages: Find where a symbol is used in code. Use this to discover how a class, method, or field is actually used throughout the codebase.
       - getRelatedClasses: Find related classes. Use this for exploring and also when you're almost done and want to double-check that you haven't missed anything.
       - getClassSkeleton: Get an overview of a class's contents, including fields and method signatures. Use this to understand a class's structure without fetching its full source code.
       - getClassSource: Get the full source code of a class. This is expensive, so prefer using skeleton or method sources when possible. Use this when you need the complete implementation details, or if you think multiple methods in the class may be relevant.
       - getMethodSource: Get the source code of a specific method. Use this to examine the implementation of a particular method without retrieving the entire class.
       """.stripIndent());
        var userMessage = new UserMessage("Hello, how are you?");

        // First call - should not use cache
        var firstResponse = model.generate(List.of(systemMessage, userMessage));
        System.out.println(firstResponse.tokenUsage().inputTokenCount() + " input tokens");
        var firstTokenUsage = (AnthropicTokenUsage) firstResponse.tokenUsage();
        System.out.println("First call cache creation: " + firstTokenUsage.cacheCreationInputTokens());
        System.out.println("First call cache read: " + firstTokenUsage.cacheReadInputTokens());
        assertEquals(0, firstTokenUsage.cacheReadInputTokens(), "First call should not read from cache");
        assertTrue(firstTokenUsage.cacheCreationInputTokens() > 0, "First call should create cache");

        // Second call with the same messages - should use cache
        var secondResponse = model.generate(List.of(systemMessage, userMessage));
        var secondTokenUsage = (AnthropicTokenUsage) secondResponse.tokenUsage();
        System.out.println("Second call cache creation: " + secondTokenUsage.cacheCreationInputTokens());
        System.out.println("Second call cache read: " + secondTokenUsage.cacheReadInputTokens());
        assertTrue(secondTokenUsage.cacheReadInputTokens() > 0, "Second call should read tokens from cache");
    }
}
