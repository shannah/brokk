
package io.github.jbellis.brokk.diffTool.ui;

import io.github.jbellis.brokk.diffTool.diff.JMChunk;
import io.github.jbellis.brokk.diffTool.diff.JMDelta;
import io.github.jbellis.brokk.diffTool.diff.JMRevision;
import io.github.jbellis.brokk.diffTool.doc.BufferDocumentChangeListenerIF;
import io.github.jbellis.brokk.diffTool.doc.BufferDocumentIF;
import io.github.jbellis.brokk.diffTool.doc.JMDocumentEvent;
import io.github.jbellis.brokk.diffTool.search.SearchBarDialog;
import io.github.jbellis.brokk.diffTool.search.SearchCommand;
import io.github.jbellis.brokk.diffTool.search.SearchHit;
import io.github.jbellis.brokk.diffTool.search.SearchHits;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.Highlighter;
import javax.swing.text.PlainDocument;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;

public class FilePanel implements BufferDocumentChangeListenerIF {
    private static final int MAXSIZE_CHANGE_DIFF = 1000;

    private final BufferDiffPanel diffPanel;
    private final String name;
    private JScrollPane scrollPane;
    private JTextArea editor;
    private BufferDocumentIF bufferDocument;
    private Timer timer;
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

        editor.getDocument().addUndoableEditListener(diffPanel.getUndoHandler()); // Add undo listener

        // Wrap editor inside a scroll pane with optimized scrolling
        scrollPane = new JScrollPane(editor);
        scrollPane.getViewport().setScrollMode(JViewport.BLIT_SCROLL_MODE);

        // If the document is "ORIGINAL", reposition the scrollbar to the left
        if (BufferDocumentIF.ORIGINAL.equals(name)) {
            LeftScrollPaneLayout layout = new LeftScrollPaneLayout();
            scrollPane.setLayout(layout);
            layout.syncWithScrollPane(scrollPane);
        }

        // Setup a one-time timer to refresh the UI after 100ms
        timer = new Timer(100, refresh());
        timer.setRepeats(false);

//        diffPanel.getCaseSensitiveCheckBox().addActionListener(e -> {
//            doSearch()
//        });
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
        Document previousDocument;
        Document document;
        try {
            if (bufferDocument != null) {
                bufferDocument.removeChangeListener(this);
                previousDocument = bufferDocument.getDocument();
                if (previousDocument != null) {
                    previousDocument.removeUndoableEditListener(diffPanel
                            .getUndoHandler());
                }
            }

            bufferDocument = bd;

            document = bufferDocument.getDocument();
            if (document != null) {
                editor.setDocument(document);
                editor.setTabSize(4);
                bufferDocument.addChangeListener(this);
                document.addUndoableEditListener(diffPanel.getUndoHandler());
            }

            initConfiguration();
        } catch (Exception ex) {
            ex.printStackTrace();

            JOptionPane.showMessageDialog(diffPanel, "Could not read file: "
                            + bufferDocument.getName()
                            + "\n" + ex.getMessage(),
                    "Error opening file", JOptionPane.ERROR_MESSAGE);
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

    private void paintRevisionHighlights() {

        if (bufferDocument == null) {
            return;
        }

        JMRevision revision = diffPanel.getCurrentRevision();
        if (revision == null) {
            return;
        }

        for (JMDelta delta : revision.getDeltas()) {
            if (BufferDocumentIF.ORIGINAL.equals(name)) {
                new HighlightOriginal(delta).highlight();
            } else if (BufferDocumentIF.REVISED.equals(name)) {
                new HighlightRevised(delta).highlight();
            }
        }
    }

    abstract class AbstractHighlight {
        protected JMDelta delta;

        public AbstractHighlight(JMDelta delta) {
            this.delta = delta;
        }

        protected void highlight() {
            int fromOffset;
            int toOffset;
            JMRevision changeRev;
            JMChunk changeOriginal;
            int fromOffset2;
            int toOffset2;
            fromOffset = bufferDocument.getOffsetForLine(getPrimaryChunk().getAnchor());
            if (fromOffset < 0) {
                return;
            }

            toOffset = bufferDocument.getOffsetForLine(getPrimaryChunk().getAnchor() + getPrimaryChunk().getSize());
            if (toOffset < 0) {
                return;
            }

            boolean isEndAndIsLastNewLine = isEndAndIsLastNewLine(toOffset);

            JMHighlightPainter highlight = null;
            if (delta.isChange()) {
                if (delta.getOriginal().getSize() < MAXSIZE_CHANGE_DIFF
                        && delta.getRevised().getSize() < MAXSIZE_CHANGE_DIFF) {
                    changeRev = delta.getChangeRevision();
                    if (changeRev != null) {
                        for (JMDelta changeDelta : changeRev.getDeltas()) {
                            changeOriginal = getPrimaryChunk(changeDelta);
                            if (changeOriginal.getSize() <= 0) {
                                continue;
                            }

                            fromOffset2 = fromOffset + changeOriginal.getAnchor();
                            toOffset2 = fromOffset2 + changeOriginal.getSize();

                            setHighlight(JMHighlighter.LAYER1, fromOffset2, toOffset2,
                                    JMHighlightPainter.CHANGED_LIGHTER);
                        }
                    }
                }

                highlight = isEndAndIsLastNewLine ? JMHighlightPainter.CHANGED_NEWLINE : JMHighlightPainter.CHANGED;
            } else {
                if (isEmptyLine()) {
                    toOffset = fromOffset + 1;
                }
                if (delta.isAdd()) {
                    highlight = getAddedHighlightPainter(isOriginal(), isEndAndIsLastNewLine);
                } else if (delta.isDelete()) {
                    highlight = getDeleteHighlightPainter(!isOriginal(), isEndAndIsLastNewLine);
                }
            }

            setHighlight(fromOffset, toOffset, highlight);
        }


        private boolean isEndAndIsLastNewLine(int toOffset) {
            boolean isEndAndIsLastNewLine = false;
            try {
                PlainDocument document = bufferDocument.getDocument();
                int endOffset = toOffset - 1;
                boolean changeReachEnd = endOffset == document.getLength();
                if (endOffset < 0 || endOffset >= document.getLength()) {
                    return false;
                }
                boolean lastCharIsNewLine = "\n".equals(document.getText(endOffset, 1));
                isEndAndIsLastNewLine = changeReachEnd && lastCharIsNewLine;
            } catch (BadLocationException e) {
                e.printStackTrace();
            }
            return isEndAndIsLastNewLine;
        }


        private JMChunk getPrimaryChunk() {
            return getPrimaryChunk(delta);
        }

        private boolean isOriginal() {
            return delta.getOriginal() == getPrimaryChunk();
        }

        private JMHighlightPainter getAddedHighlightPainter(boolean line, boolean isLastNewLine) {
            return line
                    ? JMHighlightPainter.ADDED_LINE
                    : isLastNewLine
                    ? JMHighlightPainter.ADDED_NEWLINE
                    : JMHighlightPainter.ADDED;
        }

        private JMHighlightPainter getDeleteHighlightPainter(boolean line, boolean isLastNewLine) {
            return line
                    ? JMHighlightPainter.DELETED_LINE
                    : isLastNewLine
                    ? JMHighlightPainter.DELETED_NEWLINE
                    : JMHighlightPainter.DELETED;
        }

        protected abstract JMChunk getPrimaryChunk(JMDelta changeDelta);

        public abstract boolean isEmptyLine();
    }

    class HighlightOriginal extends AbstractHighlight {

        public HighlightOriginal(JMDelta delta) {
            super(delta);
        }

        public boolean isEmptyLine() {
            return delta.isAdd();
        }

        protected JMChunk getPrimaryChunk(JMDelta changeDelta) {
            return changeDelta.getOriginal();
        }
    }

    class HighlightRevised extends AbstractHighlight {

        public HighlightRevised(JMDelta delta) {
            super(delta);
        }

        public boolean isEmptyLine() {
            return delta.isDelete();
        }

        protected JMChunk getPrimaryChunk(JMDelta changeDelta) {
            return changeDelta.getRevised();
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

    private void setHighlight(int offset, int size,
                              Highlighter.HighlightPainter highlight) {

        setHighlight(JMHighlighter.LAYER0, offset, size, highlight);
    }

    private void setHighlight(Integer layer, int offset, int size,
                              Highlighter.HighlightPainter highlight) {
        try {
            getHighlighter().addHighlight(layer, offset, size, highlight);
        } catch (BadLocationException ex) {
            ex.printStackTrace();
        }
    }

    boolean isDocumentChanged() {
        return bufferDocument != null && bufferDocument.isChanged();
    }

    public ActionListener getSaveButtonAction() {
        return ae -> {
            try {
                bufferDocument.write();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(SwingUtilities.getRoot(editor),
                        "Could not save file: " + bufferDocument.getName() + "\n"
                                + ex.getMessage(), "Error saving file",
                        JOptionPane.ERROR_MESSAGE);
            }
        };
    }


    public void documentChanged(JMDocumentEvent de) {
        if (de.getStartLine() == -1 && de.getDocumentEvent() == null) {
            // Refresh the diff of whole document.
            timer.restart();
        } else {
//             Try to update the revision instead of doing a full diff.
            if (!diffPanel.revisionChanged(de)) {
                timer.restart();
            }
        }
    }


    private ActionListener refresh() {
        return ae -> diffPanel.diff();
    }

    public FocusListener getFocusListener() {
        return new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent fe) {
                diffPanel.setSelectedPanel(FilePanel.this);
            }
        };
    }


    private void initConfiguration() {
        Font font = new Font("Arial", Font.PLAIN, 14);
        editor.setBorder(new LineNumberBorder(this));
        FontMetrics fm = editor.getFontMetrics(font);
        scrollPane.getHorizontalScrollBar().setUnitIncrement(fm.getHeight());
        editor.setEditable(true);
    }


    public static class LeftScrollPaneLayout
            extends ScrollPaneLayout
    {
        public void layoutContainer(Container parent)
        {
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
            numberOfLines = doc.getNumberOfLines();

            searchHits = new SearchHits();

            if (!searchText.isEmpty()) {
                for (int line = 0; line < numberOfLines; line++) {
                    text = doc.getLineText(line);

                    // Adjust case based on case-sensitive flag
                    if (!caseSensitive) {
                        textToSearch = text.toLowerCase();
                        searchTextToCompare = searchText.toLowerCase();
                    } else {
                        textToSearch = text;
                        searchTextToCompare = searchText;
                    }

                    fromIndex = 0;
                    while ((index = textToSearch.indexOf(searchTextToCompare, fromIndex)) != -1) {
                        offset = bufferDocument.getOffsetForLine(line);
                        if (offset < 0) {
                            continue;
                        }

                        searchHit = new SearchHit(line, offset + index, searchText.length());
                        searchHits.add(searchHit);

                        fromIndex = index + searchHit.getSize();
                    }
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
                        sh.getToOffset(),
                        searchHits.isCurrent(sh)
                                ? JMHighlightPainter.CURRENT_SEARCH: JMHighlightPainter.SEARCH);
            }
        }
    }




    public void doPreviousSearch() {
        SearchHits searchHits = getSearchHits();
        if (searchHits==null) {
            return;
        }
        searchHits.previous();
        reDisplay();

        scrollToSearch(this, searchHits);
    }

    private void scrollToSearch(FilePanel fp, SearchHits searchHits) {
        SearchHit currentHit;
        int line;

        if (searchHits == null) {
            return;
        }

        currentHit = searchHits.getCurrent();
        if (currentHit != null) {
            line = currentHit.getLine();

            diffPanel.getScrollSynchronizer().scrollToLine(fp, line);
            diffPanel.setSelectedLine(line);
        }
    }

    public void doNextSearch() {
        SearchHits searchHits = getSearchHits();
       if (searchHits==null) {
           return;
       }
        searchHits.next();
        reDisplay();

        scrollToSearch(this, searchHits);
    }
}
