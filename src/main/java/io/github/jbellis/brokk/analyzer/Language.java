package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.Project;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;


public interface Language {
    Logger logger = LogManager.getLogger(Language.class);

    List<String> getExtensions();
    String name();
    IAnalyzer createAnalyzer(Project project);
    IAnalyzer loadAnalyzer(Project project);
    default List<Path> getDependencyCandidates(Project project) {
        return List.of();
    }

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

        @Override
        public List<Path> getDependencyCandidates(Project project) {
            return List.of();
        }
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
        public List<Path> getDependencyCandidates(Project project) {
            long startTime = System.currentTimeMillis();

            String userHome = System.getProperty("user.home");
            if (userHome == null) {
                logger.warn("Could not determine user home directory.");
                return List.of();
            }
            Path homePath = Path.of(userHome);

            List<Path> rootsToScan = new ArrayList<>();

            /* ---------- default locations that exist on all OSes ---------- */
            rootsToScan.add(homePath.resolve(".m2").resolve("repository"));
            rootsToScan.add(homePath.resolve(".gradle").resolve("caches")
                                    .resolve("modules-2").resolve("files-2.1"));
            rootsToScan.add(homePath.resolve(".ivy2").resolve("cache"));
            rootsToScan.add(homePath.resolve(".cache").resolve("coursier")
                                    .resolve("v1").resolve("https"));
            rootsToScan.add(homePath.resolve(".sbt"));

            /* ---------- honour user-supplied overrides ---------- */
            Optional.ofNullable(System.getenv("MAVEN_REPO"))
                    .map(Path::of)
                    .ifPresent(rootsToScan::add);

            Optional.ofNullable(System.getProperty("maven.repo.local"))
                    .map(Path::of)
                    .ifPresent(rootsToScan::add);

            Optional.ofNullable(System.getenv("GRADLE_USER_HOME"))
                    .map(Path::of)
                    .map(p -> p.resolve("caches")
                            .resolve("modules-2").resolve("files-2.1"))
                    .ifPresent(rootsToScan::add);

            /* ---------- Windows-specific cache roots ---------- */
            boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ENGLISH).contains("win");

            if (isWindows) {
                Optional.ofNullable(System.getenv("LOCALAPPDATA")).ifPresent(localAppData -> {
                    Path lad = Path.of(localAppData);
                    rootsToScan.add(lad.resolve("Coursier").resolve("cache")
                                            .resolve("v1").resolve("https"));
                    rootsToScan.add(lad.resolve("Gradle").resolve("caches")
                                            .resolve("modules-2").resolve("files-2.1"));
                });
            }

            /* ---------- de-duplicate & scan ---------- */
            List<Path> uniqueRoots = rootsToScan.stream().distinct().toList();

            var jarFiles = uniqueRoots.parallelStream()
                    .filter(Files::isDirectory)
                    .peek(root -> logger.debug("Scanning for JARs under: {}", root))
                    .flatMap(root -> {
                        try {
                            return Files.walk(root, FileVisitOption.FOLLOW_LINKS);
                        } catch (IOException e) {
                            logger.warn("Error walking directory {}: {}", root, e.getMessage());
                            return Stream.empty();
                        } catch (SecurityException e) {
                            logger.warn("Permission denied accessing directory {}: {}", root, e.getMessage());
                            return Stream.empty();
                        }
                    })
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ENGLISH);
                        return name.endsWith(".jar")
                                && !name.endsWith("-sources.jar")
                                && !name.endsWith("-javadoc.jar");
                    })
                    .toList();

            long duration = System.currentTimeMillis() - startTime;
            logger.info("Found {} JAR files in common dependency locations in {} ms",
                        jarFiles.size(), duration);

            return jarFiles;
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

        @Override
        public List<Path> getDependencyCandidates(Project project) {
            return List.of();
        }
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

        @Override
        public List<Path> getDependencyCandidates(Project project) {
            return List.of();
        }
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
