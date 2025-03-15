package io.github.jbellis.brokk;

public interface IConsoleIO {
    void actionOutput(String msg);

    default void toolError(String msg) {
        toolErrorRaw("Error: " + msg);
    }

    void toolErrorRaw(String msg);

    void llmOutput(String token);

    default void actionComplete() {
    }

    default boolean isSpinning() {
        return false;
    }

    default void clear() {
    }

    default void systemOutput(String message) {
        llmOutput("\n" + message);
    }
}
