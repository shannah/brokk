package io.github.jbellis.brokk.prompts;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.List;

public class EditBlockPrompts {
    public static EditBlockPrompts instance = new EditBlockPrompts();

    private EditBlockPrompts() {}

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

    protected String genericInstructions() {
        return """
        Every *SEARCH* block must *EXACTLY MATCH* the existing filename content, character for character,
        including all comments, docstrings, indentation, etc.
        If the file contains code or other data wrapped in json/xml/quotes or other containers,
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
       
        Follow the existing code style, and ONLY EVER RETURN CHANGES IN A *SEARCH/REPLACE BLOCK*!
        """.stripIndent();
    }

    public String instructions(String reminder) {
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

        %s
       
        %s
        </rules>
        """.stripIndent().formatted(genericInstructions(), reminder);
    }
}
