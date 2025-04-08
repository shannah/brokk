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
            """;

    public static final String OVEREAGER_REMINDER = """
            Pay careful attention to the scope of the user's request. Do what he asks, but no more.
            Do comment new code, but if existing comments are adequate, do not rewrite them.
            """;

    public static String reminderForModel(StreamingChatLanguageModel model) {
        return Models.isLazy(model)
                ? LAZY_REMINDER
                : OVEREAGER_REMINDER;
    }

    public final List<ChatMessage> collectMessages(ContextManager cm, List<ChatMessage> sessionMessages, String reminder) {
        var messages = new ArrayList<ChatMessage>();

        messages.add(new SystemMessage(formatIntro(cm, reminder)));
        messages.addAll(exampleMessages());
        messages.addAll(cm.getReadOnlyMessages());
        messages.addAll(cm.getHistoryMessages()); // Previous full sessions
        messages.addAll(sessionMessages);         // Messages from *this* session
        messages.add(new UserMessage(editReminder(reminder)));
        messages.add(new AiMessage("I will format my edits accordingly."));
        messages.addAll(cm.getEditableMessages());

        return messages;
    }

    protected String formatIntro(ContextManager cm, String reminder) {
        var editableContents = cm.getEditableSummary();
        var readOnlyContents = cm.getReadOnlySummary();
        var styleGuide = cm.getProject().getStyleGuide();

        var workspaceBuilder = new StringBuilder();
        workspaceBuilder.append("- Root: ").append(cm.getRoot().getFileName());
        if (!editableContents.isBlank()) {
            workspaceBuilder.append("\n- Editable files: ").append(editableContents);
        }
        if (!readOnlyContents.isBlank()) {
            workspaceBuilder.append("\n- Read-only snippets: ").append(readOnlyContents);
        }

        return """
                        <instructions>
                        %s
                        </instructions>
                        <workspace>
                        %s
                        </workspace>
                        <style_guide>
                        %s
                        </style_guide>
                """.stripIndent().formatted(systemIntro(reminder), workspaceBuilder.toString(), styleGuide).trim();
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
                
                3. Describe each change with a *SEARCH/REPLACE* block per the examples below.

                All changes to files must use this *SEARCH/REPLACE* block format.

                If a file is read-only or unavailable, ask the user to add it or make it editable.
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
                  
                  ```
                  mathweb/flask/app.py <<<<<<< SEARCH
                  from flask import Flask
                  mathweb/flask/app.py =======
                  import math
                  from flask import Flask
                  mathweb/flask/app.py >>>>>>> REPLACE
                  ```
                  
                  ```
                  mathweb/flask/app.py <<<<<<< SEARCH
                  def factorial(n):
                      "compute factorial"
                      if n == 0:
                          return 1
                      else:
                          return n * factorial(n-1)
                  mathweb/flask/app.py =======
                  mathweb/flask/app.py >>>>>>> REPLACE
                  ```
                  
                  ```
                  mathweb/flask/app.py <<<<<<< SEARCH
                      return str(factorial(n))
                  mathweb/flask/app.py =======
                      return str(math.factorial(n))
                  mathweb/flask/app.py >>>>>>> REPLACE
                  ```
                  """.stripIndent()),
                new UserMessage("Refactor hello() into its own filename."),
                new AiMessage("""
                  To make this change we need to modify `main.py` and make a new filename `hello.py`:
                  
                  1. Make a new hello.py filename with hello() in it.
                  2. Remove hello() from main.py and replace it with an import.
                  
                  Here are the *SEARCH/REPLACE* blocks:
                  ```
                  hello.py <<<<<<< SEARCH
                  hello.py =======
                  def hello():
                      "print a greeting"
                      print("hello")
                  hello.py >>>>>>> REPLACE
                  ```
                  
                  ```
                  main.py <<<<<<< SEARCH
                  def hello():
                      "print a greeting"
                      print("hello")
                  main.py =======
                  from hello import hello
                  main.py >>>>>>> REPLACE
                  ```
                  """.stripIndent())
            );
    }

    private String editReminder(String reminder) {
        return """
               <rules>
               # *SEARCH/REPLACE block* Rules:

               Every *SEARCH/REPLACE* block must use this format:
               1. The opening fence of backticks: ```
               2. The *FULL* filename, verbatim, followed by the start of search block: <<<<<<< SEARCH
               4. A contiguous chunk of lines to search for in the existing source code
               5. The *FULL* filename again, followed by the dividing line: =======
               6. The lines to replace in the source code
               7. The *FULL* filename agin, followed by the end of the replace block: >>>>>>> REPLACE
               8. The closing fence: ```

               Use the *FULL* filename, as shown to you by the user. This appears on each of three lines with the
               SEARCH marker, the dividing line, and the REPLACE marker.  (`<<<<<<< SEARCH`, `=======`, `>>>>>>> REPLACE`.
               The SEARCH and REPLACE lines should end immediately after the SEARCH or REPLACE keyword, respectively.

               Every *SEARCH* block must *EXACTLY MATCH* the existing filename content, character for character,
               including all comments, docstrings, indentation, etc.
               If the filename contains code or other data wrapped in json/xml/quotes or other containers,
               you need to propose edits to the literal contents, including that container markup.

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
               you to create a new filename. To create a new file or replace an *entire* existing file, use a *SEARCH/REPLACE* block with:
               - The filename
               - An empty SEARCH block
               - The new file's full contents in the REPLACE block

               If the user just says something like "ok" or "go ahead" or "do that", they probably want you
               to make SEARCH/REPLACE blocks for the code changes you just proposed.
               The user will say when they've applied your edits.
               If they haven't explicitly confirmed the edits have been applied, they probably want proper SEARCH/REPLACE blocks.
              
               NEVER use smart quotes in your *SEARCH/REPLACE* blocks, not even in comments.  ALWAYS
               use vanilla ascii single and double quotes.
               
               # General
               Always write elegant, well-encapsulated code that is easy to maintain and use without mistakes.
               
               Follow the existing code style, and ONLY EVER RETURN CODE IN A *SEARCH/REPLACE BLOCK*!
               
               
               3. %s
               </rules>
               """.formatted(reminder).stripIndent();
    }
}
