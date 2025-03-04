package io.github.jbellis.brokk;

public interface IConsoleIO {
    void toolOutput(String msg);

    default void toolError(String msg) {
        toolErrorRaw("Error: " + msg);
    }

    void toolErrorRaw(String msg);

    boolean confirmAsk(String msg);

    void llmOutput(String token);

    default void spin(String s) {
        toolOutput(s);
    }

    default void spinComplete() {
    }

    default boolean isSpinning() {
        return false;
    }

    default void clear() {
    }

    default char askOptions(String label, String options) {
        return 'y';
    }

    default void shellOutput(String message) {
        llmOutput("\n" + message);
    }
}
