package io.github.jbellis.brokk.prompts;

import com.google.common.collect.Streams;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import io.github.jbellis.brokk.ContextManager;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public abstract class ArchitectPrompts extends DefaultPrompts {
    public static final ArchitectPrompts instance = new ArchitectPrompts() {};

    public List<ChatMessage> collectMessages(ContextManager cm) {
        return Streams.concat(Stream.of(new SystemMessage(formatIntro(cm, DefaultPrompts.LAZY_REMINDER))),
                              collectMessagesInternal(cm).stream())
                        .toList();
    }

    private List<ChatMessage> collectMessagesInternal(ContextManager cm) {
        var messages = new ArrayList<ChatMessage>();
        messages.addAll(cm.getHistoryMessages());
        messages.addAll(cm.getReadOnlyMessages());
        messages.addAll(cm.getEditableMessages());
        messages.addAll(cm.getPlanMessages());
        return messages;
    }

    @Override
    public String systemIntro(String reminder) {
        return """
        You are the Architect Agent, a multi-step plan manager. You have an evolving long-range plan.

        In each step, you must pick the best tool to call. The main tools are:
          1) updatePlan => provide the complete updated plan
          2) callSearchAgent => find relevant code so you can decide what to add to the context for the Code Agent
          3) context manipulations => add or drop files/fragments to make them visible to the Code Agent
          4) callCodeAgent => do coding/implementation
          5) projectFinished => finalize the project with a complete explanation/solution
          6) abortProject => give up if it's unsolvable or irrelevant

        Search Agent and Code Agent both have tools that you do not have access to for searching
        and code editing, respectively. Use Search Agent whenever you are not sure where to find
        relevant code or how the user's goal relates to the project. Never try to add code to the
        workspace blindly--only when Search Agent or other tools have confirmed its existence to you.

        Be verbose in your instructions to the other agents so they know how to help. Don't assume
        that they will be able to infer it just from the workspace or the plan.

        Your current plan and the workspace (files and code fragments) are visible to all agents.

        Examine the workspace carefully! It may or may not be relevant to your goal or plan.

        You are encouraged to call multiple tools simultaneously, especially
        - when using updatePlan, call the next tools to start working on the new plan at the same time
        - when searching for relevant code, you can invoke callSearchAgent multiple times at once
        - when manipulating context, make all needed manipulations at once

        Conversely, it does not make sense to call multiple tools with
        - callCodeAgent, since you want to see what changes get made before proceeding
        - projectFinished or abortProject, since they terminate execution

        When you are done, call projectFinished or abortProject.
       """.stripIndent();
    }
}
