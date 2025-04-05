package io.github.jbellis.brokk.difftool.ui;

import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Chunk;
import io.github.jbellis.brokk.difftool.doc.BufferDocumentIF;
import io.github.jbellis.brokk.difftool.utils.Colors;
// Removed JMDocumentEvent import
import io.github.jbellis.brokk.difftool.search.SearchBarDialog;
import io.github.jbellis.brokk.difftool.search.SearchCommand;
import io.github.jbellis.brokk.difftool.search.SearchHit;
import io.github.jbellis.brokk.difftool.search.SearchHits;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.Highlighter;
import javax.swing.text.PlainDocument;
import java.awt.*;
// Removed ActionListener import (timer gone)
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;

// Removed BufferDocumentChangeListenerIF implementation
public class FilePanel {
    private static final int MAXSIZE_CHANGE_DIFF = 1000;

    private final BufferDiffPanel diffPanel;
    private final String name;
    private JScrollPane scrollPane;
    private JTextArea editor;
    private BufferDocumentIF bufferDocument;
    // Removed Timer timer;
    private boolean selected;
    private SearchHits searchHits;
    private final SearchBarDialog bar;

    public FilePanel(BufferDiffPanel diffPanel, String name, SearchBarDialog bar) {
        this.diffPanel = diffPanel;
        this.name = name;
        this.bar = bar;
        init();
    }

    private void init() {
        // Initialize text editor with custom highlighting
        editor = new JTextArea();
        editor.setHighlighter(new JMHighlighter());
        editor.addFocusListener(getFocusListener());
        bar.setFilePanel(this);

        // Wrap editor inside a scroll pane with optimized scrolling
        scrollPane = new JScrollPane(editor);
        scrollPane.getViewport().setScrollMode(JViewport.BLIT_SCROLL_MODE);

        // If the document is "ORIGINAL", reposition the scrollbar to the left
        if (BufferDocumentIF.ORIGINAL.equals(name)) {
            LeftScrollPaneLayout layout = new LeftScrollPaneLayout();
            scrollPane.setLayout(layout);
            layout.syncWithScrollPane(scrollPane);
        }

        // Removed Timer setup
    }


    public JScrollPane getScrollPane() {
        return scrollPane;
    }

    public JTextArea getEditor() {
        return editor;
    }

    public BufferDocumentIF getBufferDocument() {
        return bufferDocument;
    }


    public void setBufferDocument(BufferDocumentIF bd) {
        PlainDocument previousDocument = null; // Use PlainDocument type
        PlainDocument document = null;
        try {
            if (bufferDocument != null) {
                // bufferDocument.removeChangeListener(this); // Listener removed
                previousDocument = bufferDocument.getDocument();
                if (previousDocument != null) {
                    previousDocument.removeUndoableEditListener(diffPanel.getUndoHandler());
                }
            }

            bufferDocument = bd;

            if (bufferDocument != null) { // Check if new document is not null
                 document = bufferDocument.getDocument();
                 if (document != null) {
                     editor.setDocument(document);
                     editor.setTabSize(4);
                     // bufferDocument.addChangeListener(this); // Listener removed
                     document.addUndoableEditListener(diffPanel.getUndoHandler());
                 } else {
                     // Set an empty document if the bufferDocument's doc is null
                     editor.setDocument(new PlainDocument());
                 }
            } else {
                // Set an empty document if the bufferDocument itself is null
                editor.setDocument(new PlainDocument());
            }

            // Initialize configuration including theme-specific highlight painters
            initConfiguration();
        } catch (Exception ex) {
            // Log or show error if document access fails
            System.err.println("Error setting buffer document " + (bd != null ? bd.getName() : "<null>") + ": " + ex.getMessage());
            editor.setDocument(new PlainDocument()); // Ensure editor has a document
            JOptionPane.showMessageDialog(diffPanel, "Could not set document: "
                                                  + (bd != null ? bd.getName() : "<null>")
                                                  + "\n" + ex.getMessage(),
                                          "Error setting document", JOptionPane.ERROR_MESSAGE);
        }
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public void reDisplay() {
        removeHighlights();
        paintSearchHighlights();
        paintRevisionHighlights();
        getHighlighter().repaint();
    }

    /**
     * Repaint highlights: we get the patch from BufferDiffPanel, then highlight
     * each delta's relevant lines in *this* panel (ORIGINAL or REVISED).
     */
    private void paintRevisionHighlights()
    {
        var doc = bufferDocument;
        if (doc == null) return;

        // Access the shared patch from the parent BufferDiffPanel
        var patch = diffPanel.getPatch();
        if (patch == null) return;

        for (var delta : patch.getDeltas()) {
            // Are we the "original" side or the "revised" side?
            if (BufferDocumentIF.ORIGINAL.equals(name)) {
                new HighlightOriginal(delta).highlight();
            } else if (BufferDocumentIF.REVISED.equals(name)) {
                new HighlightRevised(delta).highlight();
            }
        }
    }

    abstract class AbstractHighlight {
        protected final AbstractDelta<String> delta;

        AbstractHighlight(AbstractDelta<String> delta) {
            this.delta = delta;
        }

        public void highlight() {
            if (bufferDocument == null) return; // Ensure document exists
            // Retrieve the chunk relevant to this side
            var chunk = getChunk(delta);
            var fromOffset = bufferDocument.getOffsetForLine(chunk.getPosition());
            if (fromOffset < 0) {
                 // Handle invalid offset - maybe log or skip
                 System.err.printf("Highlight %s: Invalid start offset for line %d in %s%n", delta.getType(), chunk.getPosition(), bufferDocument.getName());
                 return;
            }
            // For end offset, use the start offset of the *next* line, or doc length if it's the last line.
            int nextLine = chunk.getPosition() + chunk.size();
            var toOffset = bufferDocument.getOffsetForLine(nextLine);
            if (toOffset < 0) { // If next line is invalid (e.g., beyond EOF), use document length
                 toOffset = bufferDocument.getDocument().getLength();
            }

            // Check if chunk is effectively "empty line" (e.g., insertion point)
            boolean isEmpty = (chunk.size() == 0);

            // Check for trailing newline indication
            boolean isEndAndNewline = false;
            if (toOffset > fromOffset && toOffset <= bufferDocument.getDocument().getLength()) {
                try {
                     // Check if the last character *before* toOffset is a newline
                     if (toOffset > 0) {
                          String lastChar = bufferDocument.getDocument().getText(toOffset - 1, 1);
                          isEndAndNewline = "\n".equals(lastChar);
                     }
                 } catch (BadLocationException e) {
                     // Should not happen with valid offsets
                     System.err.println("Error checking for trailing newline: " + e.getMessage());
                 }
            }

            // Decide color. For Insert vs Delete vs Change we do:
            var isDark = diffPanel.isDarkTheme();
            var type = delta.getType(); // DeltaType.INSERT, DELETE, CHANGE
            var painter = switch (type) {
                case INSERT ->
                        isEmpty
                                ? new JMHighlightPainter.JMHighlightLinePainter(Colors.getAdded(isDark)) // Indicate insertion point
                                : isEndAndNewline
                                ? new JMHighlightPainter.JMHighlightNewLinePainter(Colors.getAdded(isDark))
                                : new JMHighlightPainter(Colors.getAdded(isDark));

                case DELETE ->
                        isEmpty
                                ? new JMHighlightPainter.JMHighlightLinePainter(Colors.getDeleted(isDark)) // Indicate deletion point
                                : isEndAndNewline
                                ? new JMHighlightPainter.JMHighlightNewLinePainter(Colors.getDeleted(isDark))
                                : new JMHighlightPainter(Colors.getDeleted(isDark));

                case CHANGE ->
                        isEndAndNewline
                                ? new JMHighlightPainter.JMHighlightNewLinePainter(Colors.getChanged(isDark))
                                : new JMHighlightPainter(Colors.getChanged(isDark));
                case EQUAL -> throw new IllegalStateException("Equal delta type encountered in highlight painting"); // EQUAL deltas shouldn't be highlighted this way
            };
            setHighlight(fromOffset, toOffset, painter);
        }

        protected abstract Chunk<String> getChunk(AbstractDelta<String> d);
    }

    class HighlightOriginal extends AbstractHighlight {
        HighlightOriginal(AbstractDelta<String> delta) {
            super(delta);
        }

        @Override
        protected Chunk<String> getChunk(AbstractDelta<String> d) {
            return d.getSource(); // For the original side
        }
    }

    class HighlightRevised extends AbstractHighlight {
        HighlightRevised(AbstractDelta<String> delta) {
            super(delta);
        }

        @Override
        protected Chunk<String> getChunk(AbstractDelta<String> d) {
            return d.getTarget(); // For the revised side
        }
    }


    private JMHighlighter getHighlighter() {
        return (JMHighlighter) editor.getHighlighter();
    }

    private void removeHighlights() {
        JMHighlighter jmhl = getHighlighter();
        jmhl.removeHighlights(JMHighlighter.LAYER0);
        jmhl.removeHighlights(JMHighlighter.LAYER1);
        jmhl.removeHighlights(JMHighlighter.LAYER2);
    }

    private void setHighlight(int offset, int endOffset, // Use end offset instead of size
                              Highlighter.HighlightPainter highlight) {

        setHighlight(JMHighlighter.LAYER0, offset, endOffset, highlight);
    }

    private void setHighlight(Integer layer, int offset, int endOffset, // Use end offset
                              Highlighter.HighlightPainter highlight) {
        try {
             // Add highlight using start and end offsets
            getHighlighter().addHighlight(layer, offset, endOffset, highlight);
        } catch (BadLocationException ex) {
            // This usually indicates a logic error in calculating offsets/sizes
            throw new RuntimeException("Error adding highlight from offset " + offset + " to " + endOffset, ex);
        } catch (IllegalArgumentException iae) {
             // Catch potential issue if end offset is before start offset
             System.err.println("Warning: Attempted to add highlight with invalid offsets: start=" + offset + ", end=" + endOffset + ". Skipping highlight.");
        }
    }

    boolean isDocumentChanged() {
        return bufferDocument != null && bufferDocument.isChanged();
    }

    // Removed documentChanged method and BufferDocumentChangeListenerIF logic

    // Removed refresh() ActionListener method

    public FocusListener getFocusListener() {
        return new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent fe) {
                diffPanel.setSelectedPanel(FilePanel.this);
            }
        };
    }


    private void initConfiguration() {
        Font font = new Font("Monospaced", Font.PLAIN, 12); // Adjusted font
        editor.setFont(font);
        editor.setBorder(new LineNumberBorder(this));
        FontMetrics fm = editor.getFontMetrics(font);
        // Use average char width for horizontal scroll increment?
        // int charWidth = fm.stringWidth("W"); // Example width
        // scrollPane.getHorizontalScrollBar().setUnitIncrement(charWidth);
        scrollPane.getHorizontalScrollBar().setUnitIncrement(fm.getHeight()); // Keep vertical for now
        editor.setEditable(bufferDocument != null && !bufferDocument.isReadonly());
    }


    public static class LeftScrollPaneLayout
            extends ScrollPaneLayout {
        public void layoutContainer(Container parent) {
            ComponentOrientation originalOrientation;

            // Dirty trick to get the vertical scrollbar to the left side of
            //  a scroll-pane.
            originalOrientation = parent.getComponentOrientation();
            parent.setComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT);
            super.layoutContainer(parent);
            parent.setComponentOrientation(originalOrientation);
        }
    }

    public void doStopSearch() {
        searchHits = null;
        reDisplay();
    }

    SearchCommand getSearchCommand() {
        return bar.getCommand();
    }

    public SearchHits doSearch() {
        int numberOfLines;
        BufferDocumentIF doc;
        String text;
        int index, fromIndex;
        boolean caseSensitive;
        String searchText, searchTextToCompare, textToSearch;
        SearchHit searchHit;
        int offset;
        SearchCommand searchCommand;

        searchCommand = getSearchCommand();
        if (searchCommand == null) {
            return null;
        }

        searchText = searchCommand.searchText();
        caseSensitive = searchCommand.isCaseSensitive(); // Get case-sensitive flag

        doc = getBufferDocument();
        if (doc == null) return null; // No document to search
        numberOfLines = doc.getNumberOfLines();

        searchHits = new SearchHits();

        if (!searchText.isEmpty()) {
            try {
                 String fullText = doc.getDocument().getText(0, doc.getDocument().getLength());
                 // Adjust case based on flag
                 String textToSearchFull = caseSensitive ? fullText : fullText.toLowerCase();
                 String searchTextToCompareFull = caseSensitive ? searchText : searchText.toLowerCase();

                 fromIndex = 0;
                 while ((index = textToSearchFull.indexOf(searchTextToCompareFull, fromIndex)) != -1) {
                     int line = doc.getLineForOffset(index);
                     searchHit = new SearchHit(line, index, searchText.length());
                     searchHits.add(searchHit);
                     fromIndex = index + searchHit.getSize(); // Move past the found hit
                 }
             } catch (BadLocationException e) {
                 System.err.println("Error reading document text for search: " + e.getMessage());
                 return null; // Cannot search if document read fails
             }
        }

        reDisplay();
        scrollToSearch(this, searchHits);
        return getSearchHits();
    }


    SearchHits getSearchHits() {
        return searchHits;
    }

    private void paintSearchHighlights() {
        if (searchHits != null) {
            for (SearchHit sh : searchHits.getSearchHits()) {
                setHighlight(JMHighlighter.LAYER2, sh.getFromOffset(),
                             sh.getToOffset(), // Use end offset
                             searchHits.isCurrent(sh)
                                     ? JMHighlightPainter.CURRENT_SEARCH : JMHighlightPainter.SEARCH);
            }
        }
    }


    public void doPreviousSearch() {
        SearchHits searchHits = getSearchHits();
        if (searchHits == null || searchHits.getSearchHits().isEmpty()) { // Check if empty
            return;
        }
        searchHits.previous();
        reDisplay();

        scrollToSearch(this, searchHits);
    }

    private void scrollToSearch(FilePanel fp, SearchHits searchHits) {
        SearchHit currentHit;
        int line;

        if (searchHits == null || searchHits.getSearchHits().isEmpty()) { // Check if empty
            return;
        }

        currentHit = searchHits.getCurrent();
        if (currentHit != null) {
            line = currentHit.getLine();

            diffPanel.getScrollSynchronizer().scrollToLine(fp, line);
            // Highlight the hit in the editor view
             try {
                 editor.setCaretPosition(currentHit.getFromOffset());
                 editor.moveCaretPosition(currentHit.getToOffset());
                 editor.getCaret().setSelectionVisible(true);
             } catch (IllegalArgumentException e) {
                 System.err.println("Error scrolling to search hit: " + e.getMessage());
             }
            diffPanel.setSelectedLine(line); // Keep selected line tracking if needed
        }
    }

    public void doNextSearch() {
        SearchHits searchHits = getSearchHits();
        if (searchHits == null || searchHits.getSearchHits().isEmpty()) { // Check if empty
            return;
        }
        searchHits.next();
        reDisplay();

        scrollToSearch(this, searchHits);
    }
}
