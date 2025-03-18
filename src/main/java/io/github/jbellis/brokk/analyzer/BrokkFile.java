package io.github.jbellis.brokk.analyzer;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;

public interface BrokkFile extends Serializable {
    Path absPath();

    default String read() throws IOException {
        return Files.readString(absPath());
    }

    default boolean exists() {
        return Files.exists(absPath());
    }

    /**
     * Just the filename, no path at all
     */
    default String getFileName() {
        return absPath().getFileName().toString();
    }

    @Override
    String toString();
}
