package io.github.jbellis.brokk.prompts;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.jbellis.brokk.ContextManager;

import java.util.List;

public class SummarizerPrompts {
    public static final SummarizerPrompts instance = new SummarizerPrompts() {};
    
    private SummarizerPrompts() {}

    public List<ChatMessage> collectMessages(String actionTxt, int wordBudget) {
        assert actionTxt != null;
        assert !actionTxt.isBlank();
        
        var context = """
        <text>
        %s
        </text>
        """.stripIndent().formatted(actionTxt);

        var instructions = """
        <goal>
        Here is my text, please summarize it in %d words or fewer.
        </goal>
        """.stripIndent().formatted(wordBudget);
        return List.of(new SystemMessage(systemIntro()),
                       new UserMessage(context + "\n\n" + instructions));
    }

    public String systemIntro() {
        return """
               You are an expert software engineer that generates concise summaries of code-related text.

               Reply only with the summary, without any additional text, explanations, or line breaks.
               """.stripIndent();
    }
}
