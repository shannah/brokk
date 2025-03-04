package io.github.jbellis.brokk;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.jbellis.brokk.ContextManager.OperationResult;
import io.github.jbellis.brokk.prompts.ArchitectPrompts;
import io.github.jbellis.brokk.prompts.CommitPrompts;
import io.github.jbellis.brokk.prompts.PreparePrompts;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Commands handles actions for the Brokk application.
 *
 * This version has removed the legacy slash-command handling.
 * Instead, individual command methods (e.g., cmdAdd, cmdReadOnly, etc.)
 * are intended to be directly invoked by the Swing menu or UI actions.
 */
public class Commands {

    private final Logger logger = LogManager.getLogger(Commands.class);

    private final ContextManager cm;
    private IConsoleIO io;
    private Coder coder;

    public Commands(ContextManager contextManager) {
        this.cm = contextManager;
    }

    /**
     * Called after the Coder and IConsoleIO are available.
     */
    public void resolveCircularReferences(IConsoleIO io, Coder coder) {
        this.io = io;
        this.coder = coder;
    }

    // ------------------------------------------------------------------
    // Command handler methods (invoked directly via menu actions)
    // ------------------------------------------------------------------

    public OperationResult cmdClear() {
        io.clear();
        return cm.clearHistory();
    }

    public OperationResult cmdCommit() {
        var messages = CommitPrompts.instance.collectMessages(cm);
        if (messages.isEmpty()) {
            return OperationResult.error("Nothing to commit");
        }
        String commitMsg = coder.sendMessage("Inferring commit suggestion", messages);
        if (commitMsg.isEmpty()) {
            return OperationResult.error("LLM did not provide a commit message");
        }
        return OperationResult.prefill("$git commit -a -m \"" + commitMsg + "\"");
    }

    public OperationResult cmdMode(String args) {
        var modeArg = args.trim().toUpperCase();
        if ("EDIT".equals(modeArg)) {
            cm.setMode(ContextManager.Mode.EDIT);
            return OperationResult.success("Mode set to EDIT");
        } else if ("APPLY".equals(modeArg)) {
            cm.setMode(ContextManager.Mode.APPLY);
            return OperationResult.success("Mode set to APPLY");
        } else {
            return OperationResult.error("Invalid mode. Valid modes: EDIT, APPLY");
        }
    }

    public OperationResult cmdCopy(String args) {
        String content;
        if (args.isBlank()) {
            var msgs = ArchitectPrompts.instance.collectMessages(cm);
            var combined = new StringBuilder();
            msgs.forEach(m -> {
                if (!(m instanceof AiMessage)) {
                    combined.append(Models.getText(m)).append("\n\n");
                }
            });
            combined.append("\n<goal>\n\n</goal>");
            content = combined.toString();
        } else {
            var frag = cm.currentContext().toFragment(args.trim());
            if (frag == null) {
                return OperationResult.error("No matching fragment found for: " + args);
            }
            try {
                content = frag.text();
            } catch (Exception e) {
                cm.removeBadFragment(frag, new java.io.IOException(e));
                return OperationResult.success(); // error already handled
            }
        }
        try {
            var sel = new java.awt.datatransfer.StringSelection(content);
            var cb = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
            cb.setContents(sel, sel);
            io.toolOutput("Content copied to clipboard");
        } catch (Exception e) {
            return OperationResult.error("Failed to copy: " + e.getMessage());
        }
        return OperationResult.skipShow();
    }

    public OperationResult cmdHelp() {
        var sb = new StringBuilder();
        sb.append("Available commands:\n");
        sb.append("<> denotes required, [] denotes optional parameters\n");
        sb.append("Examples:\n");
        sb.append("  Add: triggers file selection to add files to context\n");
        sb.append("  Read-only: triggers file selection for read-only context\n");
        sb.append("  Clear: clears the conversation history\n");
        sb.append("  Commit: generates a commit message\n");
        sb.append("  Mode: sets the LLM mode (EDIT or APPLY)\n");
        sb.append("  Copy: copies context to clipboard\n");
        sb.append("  Refresh: refreshes code intelligence\n");
        io.toolOutput(sb.toString());
        return OperationResult.skipShow();
    }

    public OperationResult cmdRefresh() {
        GitRepo.instance.refresh();
        cm.requestRebuild();
        io.toolOutput("Code intelligence refreshing in background");
        return OperationResult.skipShow();
    }

    public OperationResult cmdSearch(String query) {
        if (query.isBlank()) {
            return OperationResult.error("Please provide a search query");
        }
        SearchAgent agent = new SearchAgent(query, cm, coder, io);
        io.spin("searching");
        var result = agent.execute();
        io.spinComplete();
        if (result == null) {
            return OperationResult.success("Interrupted!");
        }
        io.llmOutput(result.text() + "\n");
        cm.addSearchFragment(result);
        return OperationResult.success();
    }

    public OperationResult cmdUndo() {
        return cm.undoContext();
    }

    public OperationResult cmdRedo() {
        return cm.redoContext();
    }

    public OperationResult cmdUsage(String symbol) {
        if (symbol.isBlank()) {
            return OperationResult.error("Please provide a symbol name");
        }
        return cm.usageForIdentifier(symbol.trim());
    }

    public OperationResult cmdSummarize(String input) {
        if (input.isBlank()) {
            return OperationResult.error("Provide a file path or fragment reference");
        }
        return summarize(input.trim());
    }

    private OperationResult summarize(String input) {
        var fragments = new java.util.HashSet<CodeUnit>();
        var frag = cm.currentContext().toFragment(input);
        if (frag != null) {
            fragments.addAll(frag.sources(cm.getAnalyzer()));
        } else {
            for (var raw : Completions.parseQuotedFilenames(input)) {
                var matches = Completions.expandPath(cm.getRoot(), raw);
                for (var f : matches) {
                    if (f instanceof RepoFile rf) {
                        cm.getAnalyzer().getClassesInFile(rf).forEach(fragments::add);
                    }
                }
            }
        }
        boolean success = cm.summarizeClasses(fragments);
        if (!success) {
            return OperationResult.error("Unable to read source to summarize");
        }
        return OperationResult.success();
    }

    public OperationResult cmdPrepare(String msg) {
        if (msg.isBlank()) {
            return OperationResult.error("Please provide a message");
        }
        var messages = PreparePrompts.instance.collectMessages(cm);
        var st = """
                <task>
                %s
                </task>
                <goal>
                Evaluate if you have the right summaries and files. Do not write code yet; just summarize what's needed.
                </goal>
                """.formatted(msg.trim());
        messages.add(new UserMessage(st));
        var response = coder.sendStreaming(cm.getCurrentModel(coder.models), messages, true);
        if (response != null) {
            cm.addToHistory(List.of(messages.get(messages.size() - 1), response.aiMessage()));
            var missing = cm.findMissingFileMentions(response.aiMessage().text());
            confirmAddRequestedFiles(missing);
        }
        return OperationResult.success();
    }

    private void confirmAddRequestedFiles(java.util.Set<RepoFile> missing) {
        if (missing.isEmpty()) return;
        var toAdd = new java.util.HashSet<RepoFile>();
        var toRead = new java.util.HashSet<RepoFile>();
        var toSummarize = new java.util.HashSet<RepoFile>();
        boolean keepAsking = true;
        for (var file : missing) {
            if (!keepAsking) break;
            char choice = io.askOptions("Action for " + file + "?", "(A)dd, (R)ead, (S)ummarize, (I)gnore, ig(N)ore all");
            switch (choice) {
                case 'a' -> toAdd.add(file);
                case 'r' -> toRead.add(file);
                case 's' -> toSummarize.add(file);
                case 'n' -> keepAsking = false;
                default -> { }
            }
        }
        if (!toAdd.isEmpty()) {
            cm.addFiles(toAdd);
        }
        if (!toRead.isEmpty()) {
            cm.addReadOnlyFiles(toRead);
        }
        for (var file : toSummarize) {
            summarize(file.toString());
        }
    }

    public OperationResult cmdSend(String args) {
        var lastShellOutput = cm.getLastShellOutput();
        if (lastShellOutput == null) {
            return OperationResult.error("No shell output available to send");
        }
        if (!args.isBlank()) {
            cm.setConstructedMessage(args.trim() + "\n\n" + lastShellOutput);
        } else {
            cm.setConstructedMessage(lastShellOutput);
        }
        return OperationResult.skipShow();
    }

    /**
     * Processes user input that starts with "$" or "$$".
     * For "$$" the shell command is executed and its output captured as a snippet.
     * For "$" the shell command is executed and its output is displayed.
     * Slash commands (starting with "/") are no longer supported and will return an error.
     */
    public OperationResult handleCommand(String input) {
        if (input.startsWith("$$")) {
            var command = input.substring(2).trim();
            io.toolOutput("Executing: " + command);
            var result = Environment.instance.captureShellCommand(command);
            if (!result.message().isBlank()) {
                cm.addStringFragment(command, result.message());
            }
            return result;
        } else if (input.startsWith("$")) {
            var command = input.substring(1).trim();
            io.toolOutput("Executing: " + command);
            var result = Environment.instance.captureShellCommand(command);
            cm.setLastShellOutput(result.message().isBlank() ? null : result.message());
            if (result.status() == ContextManager.OperationStatus.SUCCESS) {
                io.llmOutput(result.message().isBlank() ? "[operation completed with no output]" : result.message());
            }
            return result;
        } else if (input.startsWith("/")) {
            return OperationResult.error("Slash commands are disabled. Please use the menu options instead.");
        }
        return OperationResult.skipShow();
    }
}
