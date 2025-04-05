package io.github.jbellis.brokk.difftool.doc;

import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.Element;
import javax.swing.text.PlainDocument;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * Implementation of BufferDocumentIF backed by a String.
 * It uses PlainDocument internally for text management and Swing integration.
 */
public class StringDocument implements BufferDocumentIF, DocumentListener {
    private final String name;
    private final String shortName;
    private String content;
    private final boolean readOnly;
    private PlainDocument document;
    private boolean changed = false;

    public StringDocument(String content, String name) {
        this(content, name, true);
    }

    public StringDocument(String content, String name, boolean readOnly) {
        this.name = name;
        this.shortName = name; // Use full name as short name
        this.content = (content != null) ? content : "";
        this.readOnly = readOnly;
        initializeAndRead(); // Read content during construction
    }

    // Called internally to load/reload the document content
    private void initializeAndRead() {
        if (document != null) {
            document.removeDocumentListener(this);
        }
        document = new PlainDocument(); // Use default content
        try (Reader reader = new StringReader(this.content)) {
            new DefaultEditorKit().read(reader, document, 0);
        } catch (IOException | BadLocationException readEx) {
            System.err.println("Error reading content for StringDocument " + getName() + ": " + readEx.getMessage());
            document = new PlainDocument(); // Fall back to empty document
        } catch (Exception e) {
             System.err.println("Unexpected error during StringDocument initialization for " + getName() + ": " + e.getMessage());
             document = new PlainDocument();
        }
        document.addDocumentListener(this);
        changed = false; // Reset changed status after load/reload
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getShortName() {
        return shortName;
    }

    @Override
    public boolean isChanged() {
        return changed;
    }

    @Override
    public void setChanged(boolean changed) {
        this.changed = changed;
    }

    @Override
    public PlainDocument getDocument() {
        return document;
    }

    @Override
    public boolean isReadonly() {
        return readOnly;
    }

    @Override
    public Reader getReader() {
        // Return a new reader each time
        return new StringReader(this.content);
    }

    @Override
    public Writer getWriter() {
        if (isReadonly()) {
            // Return a writer that throws exception for read-only docs
            return new Writer() {
                @Override public void write(char[] cbuf, int off, int len) throws IOException { throw new IOException("Document is read-only: " + getName()); }
                @Override public void flush() { /* No-op */ }
                @Override public void close() { /* No-op */ }
            };
        }

        // Return a StringWriter that updates the internal content AND document on close
        return new StringWriter(content.length() + 50) {
            @Override
            public void close() throws IOException {
                super.close();
                String newContent = this.toString();
                // Only update if content actually changed to avoid unnecessary work
                if (!StringDocument.this.content.equals(newContent)) {
                    StringDocument.this.updateContent(newContent);
                }
            }
        };
    }

    // Method to update content and underlying document
    private void updateContent(String newContent) {
        if (isReadonly()) {
            System.err.println("Attempting to update read-only StringDocument: " + getName());
            return;
        }
        this.content = newContent;
        // Re-initialize the PlainDocument with the new content
        initializeAndRead();
        this.changed = true; // Mark as changed after update
    }

    @Override
    public void read() {
        // Re-initialize based on the current internal string content
        initializeAndRead();
    }

    @Override
    public void write() {
        // For StringDocument, 'write' doesn't do anything external,
        // but we might want to reset the 'changed' flag if called.
        if (!isReadonly()) {
            // If editable, writing implies the current state is 'saved' (in memory)
             changed = false;
        }
         // If read-only, write is a no-op.
    }

    @Override
    public int getOffsetForLine(int lineNumber) {
        if (lineNumber < 0 || document == null) {
            return -1;
        }
        Element root = document.getDefaultRootElement();
        if (lineNumber >= root.getElementCount()) {
             if (lineNumber == root.getElementCount()) {
                 return document.getLength();
             } else {
                 return -1;
             }
        }
        Element lineElement = root.getElement(lineNumber);
        return lineElement.getStartOffset();
    }

    @Override
    public int getLineForOffset(int offset) {
        if (offset < 0 || document == null) {
            return 0; // Or -1 for error?
        }
        Element root = document.getDefaultRootElement();
        return root.getElementIndex(offset);
    }

    @Override
    public String getLineText(int lineNumber) {
        if (document == null || lineNumber < 0) return "<INVALID LINE>";

        Element root = document.getDefaultRootElement();
        if (lineNumber >= root.getElementCount()) {
             System.err.println("getLineText: Invalid line number " + lineNumber + " for document " + getName());
             return "<INVALID LINE>";
        }

        Element lineElement = root.getElement(lineNumber);
        int startOffset = lineElement.getStartOffset();
        int endOffset = lineElement.getEndOffset();
        try {
            return document.getText(startOffset, endOffset - startOffset);
        } catch (BadLocationException e) {
            System.err.println("Error getting text for line " + lineNumber + " in " + getName() + ": " + e.getMessage());
            return "<ERROR>";
        }
    }

    @Override
    public int getNumberOfLines() {
        if (document == null) return 0;
        return document.getDefaultRootElement().getElementCount();
    }

    @Override
    public List<String> getLineList() {
        if (document == null) return List.of();

        int lineCount = getNumberOfLines();
        List<String> lines = new ArrayList<>(lineCount);
        Element root = document.getDefaultRootElement();
        try {
            for (int i = 0; i < lineCount; i++) {
                Element lineElement = root.getElement(i);
                int start = lineElement.getStartOffset();
                int end = lineElement.getEndOffset();
                String line = document.getText(start, end - start);
                 // DiffUtils works best without trailing newlines
                 if (line.endsWith("\n")) {
                     if (line.endsWith("\r\n")) {
                         lines.add(line.substring(0, line.length() - 2));
                     } else {
                         lines.add(line.substring(0, line.length() - 1));
                     }
                 } else {
                     lines.add(line);
                 }
            }
        } catch (BadLocationException e) {
            throw new RuntimeException("Error constructing line list for " + getName(), e);
        }
        return lines;
    }

    // --- DocumentListener Methods --- Implementing Change Tracking ---
    @Override
    public void insertUpdate(DocumentEvent e) {
        changed = true;
    }

    @Override
    public void removeUpdate(DocumentEvent e) {
        changed = true;
    }

    @Override
    public void changedUpdate(DocumentEvent e) {
        // Attribute changes don't affect string content
    }

    /** Gets the current string content. */
    public String getContent() {
        // To ensure consistency, get text directly from the PlainDocument
        try {
            return document != null ? document.getText(0, document.getLength()) : this.content;
        } catch (BadLocationException e) {
             System.err.println("Error retrieving content from document: " + e.getMessage());
             return this.content; // Fallback
        }
    }

    /** Sets the string content directly and re-initializes the document. */
    public void setContent(String newContent) {
         updateContent((newContent != null) ? newContent : "");
    }

    @Override
    public String toString() {
        return "StringDocument[name=" + getName() + ", readonly=" + readOnly + ", changed=" + changed + "]";
    }
}
