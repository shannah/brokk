package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.Project;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Objects;


public interface Language {
    List<String> getExtensions();
    String name();
    IAnalyzer createAnalyzer(Project project);
    IAnalyzer loadAnalyzer(Project project);

    default boolean isCpg() {
        return false;
    }

    // --- Concrete Language Instances ---

    Language C_SHARP = new Language() {
        private final List<String> extensions = List.of("cs");
        @Override public List<String> getExtensions() { return extensions; }
        @Override public String name() { return "C_SHARP"; }
        @Override public String toString() { return name(); } // For compatibility
        @Override public IAnalyzer createAnalyzer(Project project) {
            return new CSharpAnalyzer(project, project.getBuildDetails().excludedDirectories());
        }
        @Override public IAnalyzer loadAnalyzer(Project project) {return createAnalyzer(project);}
    };

    Language JAVA = new Language() {
        private final List<String> extensions = List.of("java");
        @Override public List<String> getExtensions() { return extensions; }
        @Override public String name() { return "JAVA"; }
        @Override public String toString() { return name(); }
        @Override public IAnalyzer createAnalyzer(Project project) {
            var analyzer = new JavaAnalyzer(project.getRoot(), project.getBuildDetails().excludedDirectories());
            analyzer.writeCpg(getAnalyzerPath(project));
            return analyzer;
        }

        private static @NotNull Path getAnalyzerPath(Project project) {
            return project.getRoot().resolve(".brokk").resolve("joern.cpg");
        }

        @Override public JavaAnalyzer loadAnalyzer(Project project) {
            return new JavaAnalyzer(project.getRoot(), getAnalyzerPath(project));
        }
        @Override
        public boolean isCpg() { return true; }
    };

    Language JAVASCRIPT = new Language() {
        private final List<String> extensions = List.of("js", "mjs", "cjs", "jsx");
        @Override public List<String> getExtensions() { return extensions; }
        @Override public String name() { return "JAVASCRIPT"; }
        @Override public String toString() { return name(); }
        @Override public IAnalyzer createAnalyzer(Project project) {
            return new JavascriptAnalyzer(project, project.getBuildDetails().excludedDirectories());
        }
        @Override public IAnalyzer loadAnalyzer(Project project) {return createAnalyzer(project);}
    };

    Language PYTHON = new Language() {
        private final List<String> extensions = List.of("py");
        @Override public List<String> getExtensions() { return extensions; }
        @Override public String name() { return "PYTHON"; }
        @Override public String toString() { return name(); }
        @Override public IAnalyzer createAnalyzer(Project project) {
            return new PythonAnalyzer(project, project.getBuildDetails().excludedDirectories());
        }
        @Override public IAnalyzer loadAnalyzer(Project project) {return createAnalyzer(project);}
    };

    Language C_CPP = new Language() {
        private final List<String> extensions = List.of("c", "h", "cpp", "hpp", "cc", "hh", "cxx", "hxx");
        @Override public List<String> getExtensions() { return extensions; }
        @Override public String name() { return "C_CPP"; }
        @Override public String toString() { return name(); }
        @Override public IAnalyzer createAnalyzer(Project project) {
            var analyzer = new CppAnalyzer(project.getRoot(), project.getBuildDetails().excludedDirectories());
            analyzer.writeCpg(getAnalyzerPath(project));
            return analyzer;
        }

        private static @NotNull Path getAnalyzerPath(Project project) {
            return project.getRoot().resolve(".brokk").resolve("joern.cpg");
        }

        @Override public CppAnalyzer loadAnalyzer(Project project) {
            return new CppAnalyzer(project.getRoot(), getAnalyzerPath(project));
        }
        @Override
        public boolean isCpg() { return true; }
    };

    Language NONE = new Language() {
        private final List<String> extensions = Collections.emptyList();
        @Override public List<String> getExtensions() { return extensions; }
        @Override public String name() { return "NONE"; }
        @Override public String toString() { return name(); }
        @Override public IAnalyzer createAnalyzer(Project project) {
            return new DisabledAnalyzer();
        }
        @Override public IAnalyzer loadAnalyzer(Project project) {return createAnalyzer(project);}
    };

    // --- Infrastructure for fromExtension and enum-like static methods ---

    List<Language> ALL_LANGUAGES = List.of(C_SHARP,
                                           JAVA,
                                           JAVASCRIPT,
                                           PYTHON,
                                           C_CPP,
                                           NONE);

    /**
     * Returns the Language constant corresponding to the given file extension.
     * Comparison is case-insensitive.
     *
     * @param extension The file extension (e.g., "java", "py").
     * @return The matching Language, or NONE if no match is found or the extension is null/empty.
     */
    static Language fromExtension(String extension) {
        if (extension == null || extension.isEmpty()) {
            return NONE;
        }
        String lowerExt = extension.toLowerCase();
        // Ensure the extension does not start with a dot for consistent matching.
        String normalizedExt = lowerExt.startsWith(".") ? lowerExt.substring(1) : lowerExt;

        for (Language lang : ALL_LANGUAGES) {
            for (String langExt : lang.getExtensions()) {
                if (langExt.equals(normalizedExt)) {
                    return lang;
                }
            }
        }
        return NONE;
    }

    /**
     * Returns an array containing all the defined Language constants, in the order
     * they are declared. This method is provided for compatibility with Enum.values().
     *
     * @return an array containing all the defined Language constants.
     */
    static Language[] values() {
        return ALL_LANGUAGES.toArray(new Language[0]);
    }

    /**
     * Returns the Language constant with the specified name.
     * The string must match exactly an identifier used to declare a Language constant.
     * (Extraneous whitespace characters are not permitted.)
     * This method is provided for compatibility with Enum.valueOf(String).
     *
     * @param name the name of the Language constant to be returned.
     * @return the Language constant with the specified name.
     * @throws IllegalArgumentException if this language type has no constant with the specified name.
     * @throws NullPointerException if name is null.
     */
    static Language valueOf(String name) {
        Objects.requireNonNull(name, "Name is null");
        for (Language lang : ALL_LANGUAGES) {
            if (lang.name().equals(name)) {
                return lang;
            }
        }
        throw new IllegalArgumentException("No language constant " + Language.class.getCanonicalName() + "." + name);
    }
}
