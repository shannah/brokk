package io.github.jbellis.brokk.history;

import io.github.jbellis.brokk.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Manages the context history with thread-safe operations for undo/redo functionality.
 */
public class ContextHistory {
    private static final int MAX_UNDO_DEPTH = 100;
    
    // All access to history must be synchronized to prevent race conditions between threads
    private List<Context> history = List.of();
    private final List<Context> redoHistory = new ArrayList<>();
    private Context selectedContext = null;

    /**
     * Set the initial context
     */
    public synchronized void setInitialContext(Context initialContext) {
        history = new ArrayList<>(List.of(initialContext));
        selectedContext = initialContext; // The first context is selected by default
    }

    /**
     * Get the complete history list (read-only)
     */
    public synchronized List<Context> getHistory() {
        return List.copyOf(history);
    }

    /**
     * Get a specific context at the given index
     */
    public synchronized Context getContextAt(int index) {
        if (index < 0 || index >= history.size()) {
            return null;
        }
        return history.get(index);
    }

    /**
     * Get the most recent context in history
     */
    public synchronized Context topContext() {
        return history.isEmpty() ? null : history.getLast();
    }

    /**
     * Replace a context in the history with a new one
     */
    public synchronized void replaceContext(Context oldContext, Context newContext) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 1_000) {
            var i = history.indexOf(oldContext);
            if (i == -1) {
                // AutoContext build finished before we added it to history
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                continue;
            }

            history.set(i, newContext);
            // Update selected context if it was replaced
            if (selectedContext == oldContext) {
                selectedContext = newContext;
            }
            break;
        }
    }

    /**
     * Push a new context onto the history stack
     * @param contextGenerator Function to generate the new context from the current one
     * @return The new context that was added, or null if no change occurred
     */
    public synchronized Context pushContext(Function<Context, Context> contextGenerator) {
        Context newContext;
        var currentContext = history.isEmpty() ? null : history.getLast();
        newContext = contextGenerator.apply(currentContext);
        if (newContext == currentContext) {
            return null;
        }

        history.add(newContext);
        if (history.size() > MAX_UNDO_DEPTH) {
            history.removeFirst();
        }
        redoHistory.clear();

        // Set new context as selected by default
        selectedContext = newContext;
        return newContext;
    }

    /**
     * Record for undo operation results
     */
    public record UndoResult(boolean wasUndone, int steps) {
        public static UndoResult none() {
            return new UndoResult(false, 0);
        }
        public static UndoResult success(int steps) {
            return new UndoResult(true, steps);
        }
    }

    /**
     * Undo a number of steps
     * @param stepsToUndo Number of steps to undo
     * @param inverter Function to invert changes in a context
     * @return UndoResult with success status and number of steps undone
     */
    public synchronized UndoResult undo(int stepsToUndo, Function<Context, Context> inverter) {
        int finalStepsToUndo = Math.min(stepsToUndo, history.size() - 1);

        if (history.size() <= 1) {
            return UndoResult.none();
        }

        for (int i = 0; i < finalStepsToUndo; i++) {
            var popped = history.removeLast();
            var redoContext = inverter.apply(popped);
            redoHistory.add(redoContext);
        }

        // Update selected context to the top of the history
        if (!history.isEmpty()) {
            selectedContext = history.getLast();
        }

        return UndoResult.success(finalStepsToUndo);
    }
    
    /**
     * Undo until the specified context is reached
     * @param targetContext The context to undo to
     * @param inverter Function to invert changes in a context
     * @return UndoResult with success status and number of steps undone
     */
    public synchronized UndoResult undoUntil(Context targetContext, Function<Context, Context> inverter) {
        // Find the target context's index
        int targetIndex = history.indexOf(targetContext);
        if (targetIndex < 0) {
            // Target context not found
            return UndoResult.none();
        }

        int currentIndex = history.size() - 1;
        int stepsToUndo = currentIndex - targetIndex;

        // If already at or before target, no need to undo
        if (stepsToUndo <= 0) {
            return UndoResult.none();
        }
        
        // Set the target context as selected before undoing
        selectedContext = targetContext;
        
        return undo(stepsToUndo, inverter);
    }

    /**
     * Redo the last undone operation
     * @param inverter Function to invert changes in a context
     * @return true if an operation was redone, false if none available
     */
    public synchronized boolean redo(Function<Context, Context> inverter) {
        if (redoHistory.isEmpty()) {
            return false;
        }
        var popped = redoHistory.removeLast();
        var undoContext = inverter.apply(popped);
        history.add(undoContext);

        // Set the newly redone context as selected
        selectedContext = undoContext;
        return true;
    }
    
    /**
     * Set the selected context directly
     * @param context The context to select
     * @return true if the context was found and selected, false otherwise
     */
    public synchronized boolean setSelectedContext(Context context) {
        if (context == null) {
            selectedContext = null;
            return true;
        }

        if (history.contains(context)) {
            selectedContext = context;
            return true;
        }
        return false;
    }

    /**
     * Get the currently selected context
     * @return The selected context, or the top context if none is selected
     */
    public synchronized Context getSelectedContext() {
        if (history.isEmpty()) {
            return null;
        }

        if (selectedContext == null || !history.contains(selectedContext)) {
            return history.getLast(); // Default to top if invalid
        }
        return selectedContext;
    }
}
