package io.github.jbellis.brokk.prompts;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.Models;

import java.util.ArrayList;
import java.util.List;

public abstract class DefaultPrompts {
    public static final DefaultPrompts instance = new DefaultPrompts() {
    };

    public static final String LAZY_REMINDER = """
            You are diligent and tireless!
            You NEVER leave comments describing code without implementing it!
            You always COMPLETELY IMPLEMENT the needed code without pausing to ask if you should continue!
            """.stripIndent();

    public static final String OVEREAGER_REMINDER = """
            Do comment new code, but if existing comments are adequate, do not rewrite them.
            Do not comment on your modifications, only on the resulting code in isolation.
            This means that comments like "added X" or "changed Y" or "moved Z" are NOT WELCOME.
            """.stripIndent();

    public static final String ARCHITECT_REMINDER = """
            Pay careful attention to the scope of the user's request. Attempt to do everything required
            to fulfil the user's direct requests, but avoid surprising him with unexpected actions.
            For example, if the user asks you a question, you should do your best to answer their question first, 
            before immediately jump into taking further action.
            """.stripIndent();

    // Now takes a Models instance
    public static String reminderForModel(Models models, StreamingChatLanguageModel model) {
        return models.isLazy(model)
                ? LAZY_REMINDER
                : OVEREAGER_REMINDER;
    }

    public final List<ChatMessage> collectMessages(ContextManager cm, List<ChatMessage> sessionMessages, String reminder) {
        var messages = new ArrayList<ChatMessage>();

        messages.add(new SystemMessage(formatIntro(cm, reminder)));
        messages.addAll(exampleMessages());
        messages.addAll(cm.getHistoryMessages());
        messages.addAll(sessionMessages);
        messages.addAll(cm.getWorkspaceContentsMessages());
        messages.add(new UserMessage(editReminder(reminder)));
        messages.add(new AiMessage("I will format my edits accordingly."));
        messages.addAll(cm.getPlanMessages());

        return messages;
    }

    /**
     * Generates a concise summary of the workspace contents.
     * @param cm The ContextManager.
     * @return A string summarizing editable files, read-only snippets, etc.
     */
    public static String formatWorkspaceSummary(ContextManager cm) {
        var editableContents = cm.getEditableSummary();
        var readOnlyContents = cm.getReadOnlySummary();
        var workspaceBuilder = new StringBuilder();
        workspaceBuilder.append("- Root: ").append(cm.getRoot().getFileName());
        if (!editableContents.isBlank()) {
            workspaceBuilder.append("\n- Editable files: ").append(editableContents);
        }
        if (!readOnlyContents.isBlank()) {
            workspaceBuilder.append("\n- Read-only snippets: ").append(readOnlyContents);
        }
        return workspaceBuilder.toString();
    }


    protected String formatIntro(ContextManager cm, String reminder) {
        var workspaceSummary = formatWorkspaceSummary(cm);
        var styleGuide = cm.getProject().getStyleGuide();

        return """
                        <instructions>
                        %s
                        </instructions>
                        <workspace-summary>
                        %s
                        </workspace-summary>
                        <style_guide>
                        %s
                        </style_guide>
                """.stripIndent().formatted(systemIntro(reminder), workspaceSummary, styleGuide).trim();
    }

    public String systemIntro(String reminder) {
        return """
                Act as an expert software developer.
                Always use best practices when coding.
                Respect and use existing conventions, libraries, etc. that are already present in the code base.
                
                %s
                
                Take requests for changes to the supplied code.
                If the request is ambiguous, ask questions.
                
                Once you understand the request you MUST:
                
                1. Decide if you need to propose *SEARCH/REPLACE* edits for any code whose source is not available.
                   You can create new files without asking!
                   But if you need to propose changes to code you can't see,
                   you *MUST* tell the user their full filename names and ask them to *add the files to the chat*;
                   end your reply and wait for their approval.
                   But if you only need to change individual functions whose code you can see,
                   you may do so without having the entire file in the Workspace.
                
                2. Explain the needed changes in a few short sentences.
                
                3. Describe each change with a *SEARCH/REPLACE* block.

                All changes to files must use this *SEARCH/REPLACE* block format.

                If a file is read-only or unavailable, ask the user to add it or make it editable.
                
                If you are struggling to use a dependency or API correctly, stop and ask the user for help.
                """.formatted(reminder).stripIndent();
    }

    public List<ChatMessage> exampleMessages() {
        return List.of(
                new UserMessage("Change get_factorial() to use math.factorial"),
                new AiMessage("""
                  To make this change we need to modify `mathweb/flask/app.py` to:
                  
                  1. Import the math package.
                  2. Remove the existing factorial() function.
                  3. Update get_factorial() to call math.factorial instead.
                  
                  Here are the *SEARCH/REPLACE* blocks:

                  <<<<<<< SEARCH mathweb/flask/app.py
                  from flask import Flask
                  ======= mathweb/flask/app.py
                  import math
                  from flask import Flask
                  >>>>>>> REPLACE mathweb/flask/app.py

                  <<<<<<< SEARCH mathweb/flask/app.py
                  def factorial(n):
                      "compute factorial"
                      if n == 0:
                          return 1
                      else:
                          return n * factorial(n-1)
                  ======= mathweb/flask/app.py
                  >>>>>>> REPLACE mathweb/flask/app.py

                  <<<<<<< SEARCH mathweb/flask/app.py
                      return str(factorial(n))
                  ======= mathweb/flask/app.py
                      return str(math.factorial(n))
                  >>>>>>> REPLACE mathweb/flask/app.py
                  """.stripIndent()),
                new UserMessage("Refactor hello() into its own filename."),
                new AiMessage("""
                  To make this change we need to modify `main.py` and make a new filename `hello.py`:

                  1. Make a new hello.py filename with hello() in it.
                  2. Remove hello() from main.py and replace it with an import.

                  Here are the *SEARCH/REPLACE* blocks:
                  <<<<<<< SEARCH hello.py
                  ======= hello.py
                  def hello():
                      "print a greeting"
                      print("hello")
                  >>>>>>> REPLACE hello.py

                  <<<<<<< SEARCH main.py
                  def hello():
                      "print a greeting"
                      print("hello")
                  ======= main.py
                  from hello import hello
                  >>>>>>> REPLACE main.py
                  """.stripIndent())
            );
    }

    private String editReminder(String reminder) {
        return """
        <rules>
        # *SEARCH/REPLACE blocks*

        *SEARCH/REPLACE* blocks describe how to edit files. They are composed of
        3 delimiting markers. Each marker repeats the FULL filename being edited.
        For example,
        <<<<<<<< SEARCH io/github/jbellis/Foo.java
        ======== io/github/jbellis/Foo.java
        >>>>>>>> REPLACE io/github/jbellis/Foo.java
        
        These markers (the hardcoded tokens, plus the filename) are referred to as the search, dividing,
        and replace markers, respectively.

        Every *SEARCH/REPLACE* block must use this format:
        1. The search marker, followed by the full filename: <<<<<<< SEARCH $filename
        2. A contiguous chunk of lines to search for in the existing source code
        3. The dividing marker, followed by the full filename: ======= $filename
        4. The lines to replace in the source code
        5. The replace marker, followed by the full filename: >>>>>>> REPLACE $filename

        Use the *FULL* filename, as shown to you by the user. This appears on each of the three marker lines.
        No other text should appear on the marker lines.

        Every *SEARCH* block must *EXACTLY MATCH* the existing filename content, character for character,
        including all comments, docstrings, indentation, etc.
        If the filename contains code or other data wrapped in json/xml/quotes or other containers,
        you need to propose edits to the literal contents, including that container markup.
        
        *SEARCH* and *REPLACE* blocks must both contain ONLY the lines to be matched or edited.
        This means no +/- diff markers in particular!

        *SEARCH/REPLACE* blocks will *fail* to apply if the SEARCH text matches multiple occurrences.
        Include enough lines to uniquely match each set of lines that need to change.

        Keep *SEARCH/REPLACE* blocks concise.
        Break large changes into a series of smaller blocks that each change a small portion.
        Include just the changing lines, plus a few surrounding lines if needed for uniqueness.
        You should not need to include the entire function or block to change a line or two.
       
        Avoid generating overlapping *SEARCH/REPLACE* blocks, combine them into a single edit.

        If you want to move code within a filename, use 2 blocks: one to delete from the old location,
        and one to insert in the new location.

        Pay attention to which filenames the user wants you to edit, especially if they are asking
        you to create a new filename. To create a new file or replace an *entire* existing file, use a *SEARCH/REPLACE* 
        block with nothing in between the search and divider marker lines, and the new file's full contents between
        the divider and replace marker lines.
 
        If the user just says something like "ok" or "go ahead" or "do that", they probably want you
        to make SEARCH/REPLACE blocks for the code changes you just proposed.
        The user will say when they've applied your edits.
        If they haven't explicitly confirmed the edits have been applied, they probably want proper SEARCH/REPLACE blocks.
      
        NEVER use smart quotes in your *SEARCH/REPLACE* blocks, not even in comments.  ALWAYS
        use vanilla ascii single and double quotes.
        
        # General
        Always write elegant, well-encapsulated code that is easy to maintain and use without mistakes.
       
        Follow the existing code style, and ONLY EVER RETURN CODE IN A *SEARCH/REPLACE BLOCK*!
       
        %s
        </rules>
        """.formatted(reminder).stripIndent();
    }
}
