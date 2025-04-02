package io.github.jbellis.brokk.prompts;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.jbellis.brokk.ContextManager;

import java.util.ArrayList;
import java.util.List;

public abstract class BuildPrompts {
    public static final BuildPrompts instance = new BuildPrompts() {};

    public List<ChatMessage> collectMessages(List<String> buildResults) {
        var formattedResults = new ArrayList<String>();
        for (int i = 0; i < buildResults.size(); i++) {
            var result = buildResults.get(i);
            var st = """
            <build id="%d">
            %s
            </build>
            """.stripIndent().formatted(i + 1, result);
            formattedResults.add(st);
        }
        var buildStr = """
        %s
        <goal>
        Review the build outputs here.
        Are we making progress towards solving the errors? Remember to conclude
        with either `BROKK_PROGRESSING` or `BROKK_FLOUNDERING`.
        </goal>
        """.stripIndent().formatted(String.join("\n\n", formattedResults));
        return List.of(new SystemMessage(systemIntro()), new UserMessage(buildStr));
    }

    public String systemIntro() {
        return """
               You are an expert software engineer that can tell if a junior engineer is
               getting closer to solving his build errors, or if he is floundering.
               
               Review the history of build outputs here.  Think carefully about what
               you can determine about his progress or lack thereof, and conclude your
               reasoning with either `BROKK_PROGRESSING` or `BROKK_FLOUNDERING`.
               """.stripIndent();
    }
}
