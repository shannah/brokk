package io.github.jbellis.brokk;

public interface IConsoleIO {
    void actionOutput(String msg);

    default void actionComplete() {
    }

    default void toolError(String msg) {
        toolErrorRaw("Error: " + msg);
    }

    void toolErrorRaw(String msg);

    void llmOutput(String token);
    
    default void clear() {
    }

    default void systemOutput(String message) {
        llmOutput("\n" + message);
    }
}
