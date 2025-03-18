package io.github.jbellis.brokk.analyzer;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Path;

/**
 * A BrokkFile that represents a file not relative to any repo, just specified by its absolute path.
 */
public class ExternalFile implements BrokkFile {
    private static final long serialVersionUID = 1L;
    private transient Path path;

    // Constructor validation
    public ExternalFile(Path path) {
        if (path != null) {
            if (!path.isAbsolute()) {
                throw new IllegalArgumentException("Path must be absolute");
            }
            if (!path.equals(path.normalize())) {
                throw new IllegalArgumentException("Path must be normalized");
            }
        }
        this.path = path;
    }

    @Override
    public Path absPath() {
        return path;
    }

    @Override
    public String toString() {
        return path.toString();
    }

    private void writeObject(ObjectOutputStream oos) throws IOException {
        oos.defaultWriteObject();
        oos.writeUTF(path.toString());
    }

    private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException {
        ois.defaultReadObject();
        String pathStr = ois.readUTF();
        path = Path.of(pathStr);
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("Path must be absolute");
        }
    }
}
