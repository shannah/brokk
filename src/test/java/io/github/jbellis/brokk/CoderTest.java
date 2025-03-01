package io.github.jbellis.brokk;

import dev.langchain4j.agent.tool.ToolSpecification;
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
import java.util.ArrayList;
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
                .modelName("claude-3-5-sonnet-latest")
                .cacheSystemMessages(true)
                .cacheTools(false)
                .logRequests(true)
                .logResponses(true)
                .build();

        // system message
        var systemMessage = new dev.langchain4j.data.message.SystemMessage("""
       SYSTEM: You are a code search agent that helps find relevant code based on queries.
       Even if not explicitly stated, the query should be understood to refer to the current codebase,
       and not a general-knowledge question.
       Your goal is to find code definitions, implementations, and usages that answer the user's query.
       
       USER: Determine the next action(s) to take to search for code related to: what model is used by default.
       It is more efficient to call multiple tools in a single response when you know they will be needed.
       But if you don't have enough information to speculate, you can call just one tool.
       Start with broad searches, and then explore more specific code units once you find a foothold.
       For example, if the user is asking
       [how do Cassandra reads prevent compaction from invalidating the sstables they are referencing]
       then we should start with searchSymbols(".*SSTable.*) or searchSymbols(".*Compaction.*") or searchSymbols(".*reference.*"),
       instead of a more specific pattern like ".*SSTable.*compaction.*" or ".*compaction.*invalidation.*"
       """.stripIndent() + System.currentTimeMillis());

        // tools
        List<ToolSpecification> tools = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tools.add(ToolSpecification.builder().name("tool" + i).description("a tool").build());
        }

        // First call - should not use cache
        var firstResponse = model.generate(List.of(systemMessage, new UserMessage("Hello, how are you?")), tools);
        System.out.println(firstResponse.tokenUsage().inputTokenCount() + " input tokens");
        var firstTokenUsage = (AnthropicTokenUsage) firstResponse.tokenUsage();
        System.out.println("First call cache creation: " + firstTokenUsage.cacheCreationInputTokens());
        System.out.println("First call cache read: " + firstTokenUsage.cacheReadInputTokens());
        assertEquals(0, firstTokenUsage.cacheReadInputTokens(), "First call should not read from cache");
        assertTrue(firstTokenUsage.cacheCreationInputTokens() > 0, "First call should create cache");

        // Second call with the same system message should read from cache
        tools.add(ToolSpecification.builder().name("tool" + 21).description("a tool").build());
        var secondResponse = model.generate(List.of(systemMessage, new UserMessage("But how are you NOW?")), tools);
        var secondTokenUsage = (AnthropicTokenUsage) secondResponse.tokenUsage();
        System.out.println("Second call cache creation: " + secondTokenUsage.cacheCreationInputTokens());
        System.out.println("Second call cache read: " + secondTokenUsage.cacheReadInputTokens());
        assertTrue(secondTokenUsage.cacheReadInputTokens() > 0, "Second call should read tokens from cache");
    }
}
