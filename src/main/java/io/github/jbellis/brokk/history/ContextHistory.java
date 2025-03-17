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
    
    // Access to contextHistory must be synchronized since multiple threads call those methods
    private final AtomicReference<List<Context>> history = new AtomicReference<>(List.of());
    private final List<Context> redoHistory = new ArrayList<>();

    /**
     * Set the initial context
     */
    public void setInitialContext(Context initialContext) {
        history.set(List.of(initialContext));
    }

    /**
     * Get the current history size
     */
    public int size() {
        return history.get().size();
    }

    /**
     * Get the complete history list (read-only)
     */
    public List<Context> getHistory() {
        return history.get();
    }

    /**
     * Get a specific context at the given index
     */
    public Context getContextAt(int index) {
        var hist = history.get();
        if (index < 0 || index >= hist.size()) {
            return null;
        }
        return hist.get(index);
    }

    /**
     * Get the most recent context in history
     */
    public Context topContext() {
        var hist = history.get();
        return hist.isEmpty() ? null : hist.getLast();
    }

    /**
     * Replace a context in the history with a new one
     */
    public synchronized void replaceContext(Context oldContext, Context newContext) {
        var hist = new ArrayList<>(history.get());
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 1_000) {
            var i = hist.indexOf(oldContext);
            if (i == -1) {
                // AutoContext build finished before we added it to history
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                continue;
            }

            hist.set(i, newContext);
            history.set(List.copyOf(hist));
            break;
        }
    }

    /**
     * Push a new context onto the history stack
     * @param contextGenerator Function to generate the new context from the current one
     * @param selectedIndex Currently selected index in the UI, or -1 if top context
     * @return The new context that was added, or null if no change occurred
     */
    public synchronized Context pushContext(Function<Context, Context> contextGenerator, int selectedIndex) {
        Context newContext;
        var hist = new ArrayList<>(history.get());
        try {
            // Check if there's a history selection that's not the current context
            if (selectedIndex >= 0 && selectedIndex < hist.size() - 1) {
                // Truncate history to the selected point (without adding to redo)
                int currentSize = hist.size();
                for (int i = currentSize - 1; i > selectedIndex; i--) {
                    hist.removeLast();
                }
                // Current context is now at the selected point
            }

            var currentContext = hist.isEmpty() ? null : hist.getLast();
            newContext = contextGenerator.apply(currentContext);
            if (newContext == currentContext) {
                return null;
            }

            hist.add(newContext);
            if (hist.size() > MAX_UNDO_DEPTH) {
                hist.removeFirst();
            }
            redoHistory.clear();
        } finally {
            history.set(List.copyOf(hist));
        }
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
        var hist = new ArrayList<>(history.get());
        int finalStepsToUndo = Math.min(stepsToUndo, hist.size() - 1);

        if (hist.size() <= 1) {
            return UndoResult.none();
        }

        for (int i = 0; i < finalStepsToUndo; i++) {
            var popped = hist.removeLast();
            var redoContext = inverter.apply(popped);
            redoHistory.add(redoContext);
        }
        history.set(List.copyOf(hist));

        return UndoResult.success(finalStepsToUndo);
    }

    /**
     * Redo the last undone operation
     * @param inverter Function to invert changes in a context
     * @return true if an operation was redone, false if none available
     */
    public boolean redo(Function<Context, Context> inverter) {
        var hist = new ArrayList<>(history.get());
        if (redoHistory.isEmpty()) {
            return false;
        }
        var popped = redoHistory.removeLast();
        var undoContext = inverter.apply(popped);
        hist.add(undoContext);
        history.set(List.copyOf(hist));
        return true;
    }
}
