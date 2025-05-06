package io.github.jbellis.brokk.analyzer;

import java.util.Arrays;
import java.util.List;

public enum Language {
    C_SHARP("cs"),
    JAVA("java"),
    JAVASCRIPT(".js", ".mjs", ".cjs"),
    PYTHON("py"),
    NONE();  // no extensions

    private final List<String> extensions;

    /**
     * Associates this language with one or more file extensions.
     *
     * @param extensions the file extensions for this language
     */
    Language(String... extensions) {
        this.extensions = Arrays.asList(extensions);
    }

    public List<String> getExtensions() {
        return extensions;
    }

    /**
     * Returns the Language enum constant corresponding to the given file extension.
     * Comparison is case-insensitive.
     *
     * @param extension The file extension (e.g., "java", "py").
     * @return The matching Language, or NONE if no match is found or the extension is null/empty.
     */
    public static Language fromExtension(String extension) {
        if (extension == null || extension.isEmpty()) {
            return NONE;
        }
        String lowerExt = extension.toLowerCase();
        // Ensure the extension starts with a dot for consistent matching,
        // as stored extensions might or might not have it.
        // The current Language enum stores extensions without a leading dot.
        String normalizedExt = lowerExt.startsWith(".") ? lowerExt.substring(1) : lowerExt;

        for (Language lang : Language.values()) {
            for (String langExt : lang.extensions) {
                // Stored extensions in the enum do not have a leading dot.
                if (langExt.equals(normalizedExt)) {
                    return lang;
                }
            }
        }
        // No matching language found for the extension
        return NONE;
    }
}

