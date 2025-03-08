package io.github.jbellis.brokk;

public interface IConsoleIO {
    void toolOutput(String msg);

    default void toolError(String msg) {
        toolErrorRaw("Error: " + msg);
    }

    void toolErrorRaw(String msg);

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

    default void shellOutput(String message) {
        llmOutput("\n" + message);
    }

    default void shellOutputMarkdown(String message) {
        shellOutput(escapeMarkdown(message));
    }

    static String escapeMarkdown(String text) {
        if (text == null) return "";

        return text
                // Backslash itself (escape character)
                .replace("\\", "\\\\")
                // Backticks (code)
                .replace("`", "\\`")
                // Asterisks and underscores (emphasis)
                .replace("*", "\\*")
                .replace("_", "\\_")
                // Square brackets and parentheses (links)
                .replace("[", "\\[")
                .replace("]", "\\]")
                // Hash (headings)
                .replace("#", "\\#");
    }
}
