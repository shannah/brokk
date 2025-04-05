package io.github.jbellis.brokk.difftool.doc;

import com.ibm.icu.text.CharsetDetector;
import com.ibm.icu.text.CharsetMatch;

import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.Element;
import javax.swing.text.PlainDocument;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Implementation of BufferDocumentIF backed by a file.
 * It uses PlainDocument internally for text management and Swing integration.
 */
public class FileDocument implements BufferDocumentIF, DocumentListener {
    private final File file;
    private final String name;
    private final String shortName;
    private Charset charset;
    private final Map<String, Charset> charsetMap;
    private boolean readOnly;
    private PlainDocument document;
    private boolean changed = false;

    public FileDocument(File file, String name) {
        this.file = file;
        this.name = name;
        this.shortName = (file != null) ? file.getName() : name;
        this.readOnly = (file == null) || !file.canWrite();
        this.charsetMap = Charset.availableCharsets();
        initializeAndRead(); // Read content during construction
    }

    // Called internally to load/reload the document content
    private void initializeAndRead() {
        if (document != null) {
            document.removeDocumentListener(this);
        }
        document = new PlainDocument(); // Use default content
        try (Reader reader = getReaderInternal()) { // Use internal reader method
            if (reader != null) {
                new DefaultEditorKit().read(reader, document, 0);
            } else {
                System.err.println("Warning: Could not obtain reader for " + getName() + ", initializing empty document.");
            }
        } catch (IOException | BadLocationException readEx) {
            System.err.println("Error reading content for " + getName() + ": " + readEx.getMessage());
            document = new PlainDocument(); // Fall back to empty document
        } catch (Exception e) {
            System.err.println("Unexpected error during document initialization for " + getName() + ": " + e.getMessage());
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

    // Internal method to get reader, used during initialization
    private Reader getReaderInternal() throws IOException {
        if (file == null || !file.exists() || !file.canRead()) {
            System.err.println("Warning: File does not exist or cannot be read: " + (file != null ? file.getAbsolutePath() : "<null>"));
            return new StringReader(""); // Return reader for empty string
        }

        // Detect charset first
        try (InputStream fisDetect = new FileInputStream(file);
             BufferedInputStream bisDetect = new BufferedInputStream(fisDetect)) {
            bisDetect.mark(1024 * 1024); // Mark for reset
            this.charset = detectCharset(bisDetect);
            bisDetect.reset(); // Reset not needed as we reopen below
            if (this.charset == null) {
                System.err.println("Warning: Could not detect charset for " + file.getName() + ", using default UTF-8.");
                this.charset = StandardCharsets.UTF_8;
            }
        } catch (IOException ex) {
            System.err.println("Error detecting charset for " + file.getName() + ", using default UTF-8. Error: " + ex.getMessage());
            this.charset = StandardCharsets.UTF_8; // Fallback on error
        }

        // Return a NEW InputStreamReader with the detected/default charset
        return new BufferedReader(new InputStreamReader(new FileInputStream(file), this.charset));
    }

    @Override
    public Reader getReader() throws Exception {
         // Public method returns a fresh reader based on current state
         return getReaderInternal();
    }

    private Charset detectCharset(BufferedInputStream bis) {
        try {
            CharsetDetector detector = new CharsetDetector();
            detector.setText(bis);
            CharsetMatch match = detector.detect();
            if (match != null) {
                String charsetName = match.getName();
                if (Charset.isSupported(charsetName)) {
                    return Charset.forName(charsetName);
                } else {
                    System.err.println("Detected charset '" + charsetName + "' is not supported by Java runtime.");
                }
            }
        } catch (IOException ex) {
            System.err.println("IOException during charset detection for " + file.getName() + ": " + ex.getMessage());
        }
        return null;
    }

    @Override
    public Writer getWriter() throws IOException {
        if (isReadonly()) {
            throw new IOException("Cannot get writer for read-only file: " + getName());
        }
        if (file == null) {
            throw new IOException("Cannot get writer, file object is null for: " + getName());
        }
        // Ensure the detected or default charset is used for writing
        Charset effectiveCharset = (this.charset != null) ? this.charset : StandardCharsets.UTF_8;
        // Use try-with-resources for the output streams
        FileOutputStream fos = new FileOutputStream(file); // Opens file for writing (truncates)
        BufferedOutputStream bos = new BufferedOutputStream(fos);
        return new BufferedWriter(new OutputStreamWriter(bos, effectiveCharset));
    }

    @Override
    public void read() {
        // Re-initialize and read the file content again
        initializeAndRead();
    }

    @Override
    public void write() throws IOException {
        if (document == null) {
            throw new IllegalStateException("Cannot write document, it was not initialized: " + getName());
        }
        if (isReadonly()) {
             System.err.println("Attempted to write read-only document: " + getName());
             return; // Or throw?
        }
        try (Writer out = getWriter()) { // getWriter handles file opening
            new DefaultEditorKit().write(out, document, 0, document.getLength());
            out.flush();
            changed = false; // Mark as not changed after successful write
        } catch (IOException | BadLocationException ex) {
            throw new RuntimeException("Failed to write document: " + getName(), ex);
        } catch (Exception e) {
             throw new RuntimeException("Unexpected error writing document: " + getName(), e);
        }
    }

    @Override
    public int getOffsetForLine(int lineNumber) {
        if (lineNumber < 0 || document == null) {
            return -1;
        }
        Element root = document.getDefaultRootElement();
        if (lineNumber >= root.getElementCount()) {
            // Requesting offset for line *after* the last valid line index
            // Return the document length (offset after the very last character)
             if (lineNumber == root.getElementCount()) {
                return document.getLength();
             } else {
                 return -1; // Invalid line number way past the end
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
                // PlainDocument lines often include the newline, need to handle this
                String line = document.getText(start, end - start);
                // DiffUtils works best without trailing newlines in the list elements
                if (line.endsWith("\n")) {
                     // Handle CRLF as well
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
        // Attribute changes usually don't affect file content 'changed' status
        // but we might mark it changed for safety or if styles were persisted.
        // For now, treat attribute changes as non-content changes.
    }

    @Override
    public String toString() {
        return "FileDocument[name=" + getName() + ", file=" + (file != null ? file.getPath() : "<null>") + ", readonly=" + readOnly + ", changed=" + changed + "]";
    }
}
