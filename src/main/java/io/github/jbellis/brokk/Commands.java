package io.github.jbellis.brokk;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
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

    public void cmdClear() {
        io.clear();
        cm.clearHistory();
    }

//    public OperationResult cmdCommit() {
//        var messages = CommitPrompts.instance.collectMessages(cm);
//        if (messages.isEmpty()) {
//            return OperationResult.error("Nothing to commit");
//        }
//        String commitMsg = coder.sendMessage("Inferring commit suggestion", messages);
//        if (commitMsg.isEmpty()) {
//            return OperationResult.error("LLM did not provide a commit message");
//        }
//        return OperationResult.prefill("$git commit -a -m \"" + commitMsg + "\"");
//    }

//    public OperationResult cmdMode(String args) {
//        var modeArg = args.trim().toUpperCase();
//        if ("EDIT".equals(modeArg)) {
//            cm.setMode(ContextManager.Mode.EDIT);
//            return OperationResult.success("Mode set to EDIT");
//        } else if ("APPLY".equals(modeArg)) {
//            cm.setMode(ContextManager.Mode.APPLY);
//            return OperationResult.success("Mode set to APPLY");
//        } else {
//            return OperationResult.error("Invalid mode. Valid modes: EDIT, APPLY");
//        }
//    }

//    public OperationResult cmdRefresh() {
//        GitRepo.instance.refresh();
//        cm.requestRebuild();
//        io.toolOutput("Code intelligence refreshing in background");
//        return OperationResult.skipShow();
//    }

//    public OperationResult cmdUsage(String symbol) {
//        if (symbol.isBlank()) {
//            return OperationResult.error("Please provide a symbol name");
//        }
//        return cm.usageForIdentifier(symbol.trim());
//    }

//    private void confirmAddRequestedFiles(java.util.Set<RepoFile> missing) {
//        if (missing.isEmpty()) return;
//        var toAdd = new java.util.HashSet<RepoFile>();
//        var toRead = new java.util.HashSet<RepoFile>();
//        var toSummarize = new java.util.HashSet<RepoFile>();
//        boolean keepAsking = true;
//        for (var file : missing) {
//            if (!keepAsking) break;
//            char choice = io.askOptions("Action for " + file + "?", "(A)dd, (R)ead, (S)ummarize, (I)gnore, ig(N)ore all");
//            switch (choice) {
//                case 'a' -> toAdd.add(file);
//                case 'r' -> toRead.add(file);
//                case 's' -> toSummarize.add(file);
//                case 'n' -> keepAsking = false;
//                default -> { }
//            }
//        }
//        if (!toAdd.isEmpty()) {
//            cm.addFiles(toAdd);
//        }
//        if (!toRead.isEmpty()) {
//            cm.addReadOnlyFiles(toRead);
//        }
//        for (var file : toSummarize) {
//            // summarize(file.toString());
//        }
//    }
}
