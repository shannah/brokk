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

//    public OperationResult cmdUsage(String symbol) {
//        if (symbol.isBlank()) {
//            return OperationResult.error("Please provide a symbol name");
//        }
//        return cm.usageForIdentifier(symbol.trim());
//    }
}
