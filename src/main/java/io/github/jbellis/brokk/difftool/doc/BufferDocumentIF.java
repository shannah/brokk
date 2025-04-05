package io.github.jbellis.brokk.difftool.doc;

import javax.swing.text.PlainDocument;
import java.io.Reader;
import java.io.Writer;
import java.util.List;

public interface BufferDocumentIF
{
    String ORIGINAL = "Original";
    String REVISED = "Revised";

    String getName();
    String getShortName();

    boolean isChanged();
    void setChanged(boolean changed);

    PlainDocument getDocument();

    /**
     * Gets the character offset for the start of the given line number (0-based).
     * Returns -1 if the line number is invalid.
     */
    int getOffsetForLine(int lineNumber);

    /**
     * Gets the line number (0-based) containing the given character offset.
     */
    int getLineForOffset(int offset);

    /** Reloads the content from the source (e.g., file). */
    void read() throws Exception;
    /** Writes the current content to the destination (e.g., file). */
    void write() throws Exception;
    /** Gets a reader for the underlying content. */
    Reader getReader() throws Exception;
    /** Gets a writer for the underlying content. */
    Writer getWriter() throws Exception;

    boolean isReadonly();

    /** Gets the text of a specific line (0-based). */
    String getLineText(int lineNumber);
    /** Gets the total number of lines in the document. */
    int getNumberOfLines();

    /**
     * Returns the contents of this document as a list of strings, one entry per line.
     * This is the primary input for the diff algorithm.
     */
    List<String> getLineList();
}
