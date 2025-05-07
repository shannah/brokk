package io.github.jbellis.brokk.analyzer;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

public interface BrokkFile extends Serializable, Comparable<BrokkFile> {
    Path absPath();

    default String read() throws IOException {
        return Files.readString(absPath());
    }

    default boolean exists() {
        return Files.exists(absPath());
    }

    /** best guess as to whether a file is text and hence eligible for substring search */
    default boolean isText() {
        try {
            return Files.isRegularFile(absPath())
                    && FileExtensions.IMAGE_EXTENSIONS.stream().noneMatch(ext -> FileExtensions.matches(absPath(), ext))
                    && (FileExtensions.TEXT_EXTENSIONS.stream().anyMatch(ext -> FileExtensions.matches(absPath(), ext))
                        || Files.size(absPath()) < 128 * 1024);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Just the filename, no path at all
     */
    default String getFileName() {
        return absPath().getFileName().toString();
    }

    @Override
    String toString();

    @Override
    default int compareTo(@NotNull BrokkFile o) {
        return absPath().compareTo(o.absPath());
    }

    default long mtime() throws IOException {
        return Files.getLastModifiedTime(absPath()).toMillis();
    }
}
