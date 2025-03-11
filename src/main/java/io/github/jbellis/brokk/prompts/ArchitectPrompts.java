package io.github.jbellis.brokk.prompts;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import io.github.jbellis.brokk.ContextManager;

import java.util.ArrayList;
import java.util.List;

public abstract class ArchitectPrompts extends DefaultPrompts {
    public static final ArchitectPrompts instance = new ArchitectPrompts() {};

    @Override
    public List<ChatMessage> collectMessages(ContextManager cm) {
        // like the default, but omits the edit instructions and examples
        var messages = new ArrayList<ChatMessage>();

        messages.add(new SystemMessage(formatIntro(cm)));
        messages.addAll(cm.getReadOnlyMessages());
        messages.addAll(cm.getHistoryMessages());
        messages.addAll(cm.getEditableMessages());

        return messages;
    }

    @Override
    public String systemIntro() {
        return """
               Act as an expert software engineer. Study the change request and the current code.
               Describe how to modify the code to complete the request. You need only describe the changes,
               you do not need to give entire files or classes unless that's the easiest way to describe
               what you propose.
               YOU MUST spell out each change to be made.
               DO NOT skip steps or leave out corner cases.
               DO NOT give multiple options for solving a problem; pick the best one every time!
               """.stripIndent();
    }
}
