package io.github.jbellis.brokk.prompts;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.analyzer.ProjectFile;

import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EditBlockUtilsTest {

    @Test
    void findFilenameNearby_acceptsCommentPrefixedFilename() {
        var filename = "app/src/main/java/io/github/jbellis/brokk/analyzer/Language.java";
        var lines = ("""
            // %s
            <<<<<<< SEARCH
            some before
            =======
            some after
            >>>>>>> REPLACE
            """).formatted(filename).split("\n");
        int headIndex = 1; // index of the "<<<<<<< SEARCH" line
        Set<ProjectFile> projectFiles = Set.of(new ProjectFile(Path.of(System.getProperty("user.dir")), Path.of(filename)));

        var result = EditBlockUtils.findFilenameNearby(lines, headIndex, projectFiles, null);
        assertEquals(filename, result);
    }
}
