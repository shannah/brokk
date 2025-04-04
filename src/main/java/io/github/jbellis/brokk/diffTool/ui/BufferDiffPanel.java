
package io.github.jbellis.brokk.diffTool.ui;

import com.jgoodies.forms.layout.CellConstraints;
import com.jgoodies.forms.layout.FormLayout;
import io.github.jbellis.brokk.diffTool.diff.JMChunk;
import io.github.jbellis.brokk.diffTool.diff.JMDelta;
import io.github.jbellis.brokk.diffTool.diff.JMDiff;
import io.github.jbellis.brokk.diffTool.diff.JMRevision;
import io.github.jbellis.brokk.diffTool.doc.AbstractBufferDocument;
import io.github.jbellis.brokk.diffTool.doc.BufferDocumentIF;
import io.github.jbellis.brokk.diffTool.doc.JMDocumentEvent;
import io.github.jbellis.brokk.diffTool.node.BufferNode;
import io.github.jbellis.brokk.diffTool.node.JMDiffNode;
import io.github.jbellis.brokk.diffTool.scroll.DiffScrollComponent;
import io.github.jbellis.brokk.diffTool.scroll.ScrollSynchronizer;
import io.github.jbellis.brokk.diffTool.search.SearchBarDialog;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;
import javax.swing.text.PlainDocument;
import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class BufferDiffPanel extends AbstractContentPanel {
    public static final int LEFT = 0;
    public static final int RIGHT = 2;
    public static final int NUMBER_OF_PANELS = 3;


    private final BrokkDiffPanel mainPanel;

    public FilePanel[] getFilePanels() {
        return filePanels;
    }

    private FilePanel[] filePanels;
    private JMDiffNode diffNode;
    private JMRevision currentRevision;
    private JMDelta selectedDelta;
    private int selectedLine;
    private SearchBarDialog leftBar;
    private SearchBarDialog rightBar;
    private JCheckBox caseSensitiveCheckBox;

    private ScrollSynchronizer scrollSynchronizer;
    private JMDiff diff;
    private JSplitPane splitPane;

    int filePanelSelectedIndex = -1;

    static Color selectionColor = Color.BLUE;
    static Color newColor = Color.CYAN;
    static Color mixColor = Color.WHITE;

    static {
        selectionColor = new Color(selectionColor.getRed() * newColor.getRed() / mixColor.getRed()
                , selectionColor.getGreen() * newColor.getGreen() / mixColor.getGreen()
                , selectionColor.getBlue() * newColor.getBlue() / mixColor.getBlue());
    }


    public ScrollSynchronizer getScrollSynchronizer() {
        return scrollSynchronizer;
    }

    public BrokkDiffPanel getMainPanel() {
        return mainPanel;
    }

    public BufferDiffPanel(BrokkDiffPanel mainPanel) {
        this.mainPanel = mainPanel;
        mainPanel.setBufferDiffPanel(this);
        diff = new JMDiff();

        init();

        setFocusable(true);
    }

    public void setDiffNode(JMDiffNode diffNode) {
        this.diffNode = diffNode;
        refreshDiffNode();
    }

    public JMDiffNode getDiffNode() {
        return diffNode;
    }

    private void refreshDiffNode() {
        BufferNode bnLeft = getDiffNode().getBufferNodeLeft();
        BufferNode bnRight = getDiffNode().getBufferNodeRight();

        BufferDocumentIF leftDocument = bnLeft == null ? null : bnLeft.getDocument();
        BufferDocumentIF rightDocument = bnRight == null ? null : bnRight.getDocument();

        setBufferDocuments(leftDocument, rightDocument, getDiffNode().getDiff(), getDiffNode().getRevision());
    }

    private void setBufferDocuments(BufferDocumentIF bd1, BufferDocumentIF bd2,
                                    JMDiff diff, JMRevision revision) {
        this.diff = diff;
        currentRevision = revision;

        if (bd1 != null) {
            filePanels[LEFT].setBufferDocument(bd1);
        }

        if (bd2 != null) {
            filePanels[RIGHT].setBufferDocument(bd2);
        }

        if (bd1 != null && bd2 != null) {
            reDisplay();
        }
    }

    private void reDisplay() {
        for (FilePanel fp : filePanels) {
            if (fp != null) {
                fp.reDisplay();
            }
        }
        mainPanel.repaint();
    }

    public String getTitle() {
        String title;
        List<String> titles = new ArrayList<>();
        for (FilePanel filePanel : filePanels) {
            if (filePanel == null) {
                continue;
            }

            BufferDocumentIF bd = filePanel.getBufferDocument();
            if (bd == null) {
                continue;
            }

            title = bd.getShortName();

            titles.add(title);
        }

        if (titles.size() == 1) {
            title = titles.getFirst();
        } else {
            if (titles.get(0).equals(titles.get(1))) {
                title = titles.getFirst();
            } else {
                title = titles.get(0) + "-" + titles.get(1);
            }
        }

        return title;
    }

    public boolean revisionChanged(JMDocumentEvent de) {
        FilePanel fp;
        BufferDocumentIF bd1;
        BufferDocumentIF bd2;

        if (currentRevision == null) {
            diff();
        } else {
            fp = getFilePanel(de.getDocument());
            if (fp == null) {
                return false;
            }

            bd1 = filePanels[LEFT].getBufferDocument();
            bd2 = filePanels[RIGHT].getBufferDocument();

            if (!currentRevision.update(bd1 != null ? bd1.getLines() : null,
                                        bd2 != null ? bd2.getLines() : null, fp == filePanels[LEFT], de
                                                .getStartLine(), de.getNumberOfLines())) {
                return false;
            }

            reDisplay();
        }

        return true;
    }

    private FilePanel getFilePanel(AbstractBufferDocument document) {
        for (FilePanel fp : filePanels) {
            if (fp == null) {
                continue;
            }

            if (fp.getBufferDocument() == document) {
                return fp;
            }
        }

        return null;
    }

    public void diff() {
        BufferDocumentIF bd1;
        BufferDocumentIF bd2;

        bd1 = filePanels[LEFT].getBufferDocument();
        bd2 = filePanels[RIGHT].getBufferDocument();

        if (bd1 != null && bd2 != null) {
            try {
                currentRevision = diff.diff(bd1.getLines(), bd2.getLines()
                        , getDiffNode().getIgnore());

                reDisplay();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }

    private void init() {
        String columns = "3px, pref, 3px, 0:grow, 5px, min, 60px, 0:grow, 25px, min, 3px, pref, 3px";
        String rows = "6px, pref, 3px, fill:0:grow, pref";

        setLayout(new BorderLayout());

        if (splitPane != null) {
            remove(splitPane);
        }
        splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, true, activateBarDialog(), buildFilePanel(columns, rows));
        add(splitPane);
        //just synchronizes scrolling, nothing UI building related
        scrollSynchronizer = new ScrollSynchronizer(this, filePanels[LEFT], filePanels[RIGHT]);
        setSelectedPanel(filePanels[LEFT]);
        getMainPanel().updateUndoRedoButtons();
    }


    public JCheckBox getCaseSensitiveCheckBox() {
        return caseSensitiveCheckBox;
    }


    public JPanel activateBarDialog() {
        JPanel barContainer = new JPanel(new BorderLayout()); // Use BorderLayout for left & right placement

        // Case-Sensitive Toggle:
        caseSensitiveCheckBox = new JCheckBox("Case Sensitive");
        caseSensitiveCheckBox.setFocusable(false); // Avoids stealing focus

        leftBar = new SearchBarDialog(getMainPanel(), this);
        rightBar = new SearchBarDialog(getMainPanel(), this);

        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));

        leftPanel.add(Box.createHorizontalStrut(5)); // Add space between checkbox and left bar
        leftPanel.add(leftBar);

        JPanel leftMostPanelUp = new JPanel(new FlowLayout(FlowLayout.LEADING));
        leftMostPanelUp.add(caseSensitiveCheckBox);

        JPanel leftMostPanelDown = new JPanel(new FlowLayout(FlowLayout.CENTER));
        leftMostPanelDown.add(new JLabel(""));

        JPanel leftMostPane = new JPanel();
        leftMostPane.setLayout(new BoxLayout(leftMostPane, BoxLayout.Y_AXIS));
        leftMostPane.add(leftMostPanelUp);
        leftMostPane.add(leftMostPanelDown);
        leftMostPane.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        rightPanel.add(rightBar);

        rightPanel.add(Box.createHorizontalStrut(5)); // Add space between checkbox and left bar
        barContainer.add(leftMostPane, BorderLayout.WEST);
        barContainer.add(leftPanel, BorderLayout.CENTER);
        barContainer.add(rightPanel, BorderLayout.EAST);

        return barContainer;
    }


    private JPanel buildFilePanel(String columns, String rows) {
        FormLayout layout;
        CellConstraints cc;
        JPanel filePanel = new JPanel();
        layout = new FormLayout(columns, rows);
        cc = new CellConstraints();

        filePanel.setLayout(layout);

        filePanels = new FilePanel[NUMBER_OF_PANELS];

        filePanels[LEFT] = new FilePanel(this, BufferDocumentIF.ORIGINAL, leftBar);
        filePanels[RIGHT] = new FilePanel(this, BufferDocumentIF.REVISED, rightBar);

        filePanel.add(new RevisionBar(this, filePanels[LEFT], true), cc.xy(2, 4));
        filePanel.add(new JLabel(""), cc.xy(2, 2));

        filePanel.add(filePanels[LEFT].getScrollPane(), cc.xyw(4, 4, 3));

        //the middle diff panel that holds the curves and pointers to each side of the editor
        DiffScrollComponent diffScrollComponent = new DiffScrollComponent(this, LEFT, RIGHT);
        filePanel.add(diffScrollComponent, cc.xy(7, 4));

        filePanel.add(new RevisionBar(this, filePanels[RIGHT], false), cc.xy(12, 4));
        filePanel.add(filePanels[RIGHT].getScrollPane(), cc.xyw(8, 4, 3));

        filePanel.setMinimumSize(new Dimension(300, 200));
        return filePanel;
    }

    public JMRevision getCurrentRevision() {
        return currentRevision;
    }


    public void runChange(int fromPanelIndex, int toPanelIndex, boolean shift) {
        JMDelta delta;
        BufferDocumentIF fromBufferDocument;
        BufferDocumentIF toBufferDocument;
        PlainDocument from;
        String s;
        int fromLine;
        int fromOffset;
        int toOffset;
        int size;
        JMChunk fromChunk;
        JMChunk toChunk;
        JTextComponent toEditor;

        delta = getSelectedDelta();
        if (delta == null) {
            return;
        }

        if (fromPanelIndex < 0 || fromPanelIndex >= filePanels.length) {
            return;
        }

        if (toPanelIndex < 0 || toPanelIndex >= filePanels.length) {
            return;
        }

        try {
            fromBufferDocument = filePanels[fromPanelIndex].getBufferDocument();
            toBufferDocument = filePanels[toPanelIndex].getBufferDocument();

            if (fromPanelIndex < toPanelIndex) {
                fromChunk = delta.getOriginal();
                toChunk = delta.getRevised();
            } else {
                fromChunk = delta.getRevised();
                toChunk = delta.getOriginal();
            }
            toEditor = filePanels[toPanelIndex].getEditor();

            if (fromBufferDocument == null || toBufferDocument == null) {
                return;
            }

            fromLine = fromChunk.getAnchor();
            size = fromChunk.getSize();
            fromOffset = fromBufferDocument.getOffsetForLine(fromLine);
            if (fromOffset < 0) {
                return;
            }

            toOffset = fromBufferDocument.getOffsetForLine(fromLine + size);
            if (toOffset < 0) {
                return;
            }

            from = fromBufferDocument.getDocument();
            s = from.getText(fromOffset, toOffset - fromOffset);

            fromLine = toChunk.getAnchor();
            size = toChunk.getSize();
            fromOffset = toBufferDocument.getOffsetForLine(fromLine);
            if (fromOffset < 0) {
                return;
            }

            toOffset = toBufferDocument.getOffsetForLine(fromLine + size);
            if (toOffset < 0) {
                return;
            }
            toEditor.setSelectionStart(fromOffset);
            toEditor.setSelectionEnd(toOffset);
            if (!shift) {
                toEditor.replaceSelection(s);
            } else {
                toEditor.getDocument().insertString(toOffset, s, null);
            }

            setSelectedDelta(null);
            setSelectedLine(delta.getOriginal().getAnchor());
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public void runDelete(int fromPanelIndex, int toPanelIndex) {
        JMDelta delta;
        BufferDocumentIF bufferDocument;

        int fromLine;
        int fromOffset;
        int toOffset;
        int size;
        JMChunk chunk;
        JTextComponent toEditor;

        try {
            delta = getSelectedDelta();
            if (delta == null) {
                return;
            }

            // Some sanity checks.
            if (fromPanelIndex < 0 || fromPanelIndex >= filePanels.length) {
                return;
            }

            if (toPanelIndex < 0 || toPanelIndex >= filePanels.length) {
                return;
            }

            bufferDocument = filePanels[fromPanelIndex].getBufferDocument();
            if (fromPanelIndex < toPanelIndex) {
                chunk = delta.getOriginal();
            } else {
                chunk = delta.getRevised();
            }
            toEditor = filePanels[fromPanelIndex].getEditor();

            if (bufferDocument == null) {
                return;
            }


            fromLine = chunk.getAnchor();
            size = chunk.getSize();
            fromOffset = bufferDocument.getOffsetForLine(fromLine);
            if (fromOffset < 0) {
                return;
            }

            toOffset = bufferDocument.getOffsetForLine(fromLine + size);
            if (toOffset < 0) {
                return;
            }

            toEditor.setSelectionStart(fromOffset);
            toEditor.setSelectionEnd(toOffset);
            toEditor.replaceSelection("");

            setSelectedDelta(null);
            setSelectedLine(delta.getOriginal().getAnchor());
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    void setSelectedPanel(FilePanel fp) {
        int index;

        index = -1;
        for (int i = 0; i < filePanels.length; i++) {
            if (filePanels[i] == fp) {
                index = i;
            }
        }

        if (index != filePanelSelectedIndex) {
            if (filePanelSelectedIndex != -1) {
                filePanels[filePanelSelectedIndex].setSelected(false);
            }

            filePanelSelectedIndex = index;

            if (filePanelSelectedIndex != -1) {
                filePanels[filePanelSelectedIndex].setSelected(true);
            }
        }
    }


    public void doSave() {
        BufferDocumentIF document;

        for (FilePanel filePanel : filePanels) {
            if (filePanel == null) {
                continue;
            }

            if (!filePanel.isDocumentChanged()) {
                continue;
            }

            document = filePanel.getBufferDocument();

            try {
                document.write();
            } catch (Exception ex) {
                ex.printStackTrace();
                JOptionPane.showMessageDialog(mainPanel, "Can't save file"
                                                      + document.getName(),
                                              "Problem writing file", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    public void setSelectedDelta(JMDelta delta) {
        selectedDelta = delta;
        setSelectedLine(delta == null ? 0 : delta.getOriginal().getAnchor());
    }

    public void setSelectedLine(int line) {
        selectedLine = line;
    }


    public JMDelta getSelectedDelta() {
        List<JMDelta> deltas;

        if (currentRevision == null) {
            return null;
        }

        deltas = currentRevision.getDeltas();
        if (deltas.isEmpty()) {
            return null;
        }

        return selectedDelta;
    }

    public FilePanel getFilePanel(int index) {
        if (index < 0 || index > filePanels.length) {
            return null;
        }

        return filePanels[index];
    }

    public void doGotoDelta(JMDelta delta) {
        setSelectedDelta(delta);
        showSelectedDelta();
    }

    public void doGotoLine(int line) {
        BufferDocumentIF bd;
        int offset;
        int startOffset;
        int endOffset;
        JViewport viewport;
        JTextComponent editor;
        Point p;
        FilePanel fp;
        Rectangle rect;

        setSelectedLine(line);

        fp = getFilePanel(0);

        bd = fp.getBufferDocument();
        if (bd == null) {
            return;
        }

        offset = bd.getOffsetForLine(line);
        viewport = fp.getScrollPane().getViewport();
        editor = fp.getEditor();

        // Don't go anywhere if the line is already visible.
        rect = viewport.getViewRect();
        startOffset = editor.viewToModel(rect.getLocation());
        endOffset = editor.viewToModel(new Point(rect.x, rect.y + rect.height));
        if (offset >= startOffset && offset <= endOffset) {
            return;
        }

        try {
            p = editor.modelToView(offset).getLocation();
            p.x = 0;

            viewport.setViewPosition(p);
        } catch (BadLocationException ex) {
        }
    }

    private void showSelectedDelta() {
        JMDelta delta = getSelectedDelta();
        if (delta == null) {
            return;
        }
        scrollSynchronizer.showDelta(delta);
    }

    @Override
    public void doUndo() {
        super.doUndo();
        getMainPanel().updateUndoRedoButtons(); // Update buttons after performing undo
    }

    @Override
    public void doRedo() {
        super.doRedo();
        getMainPanel().updateUndoRedoButtons();  // Update buttons after performing redo
    }

    @Override
    public void doDown() {
        JMDelta d;

        List<JMDelta> deltas;
        int index;

        if (currentRevision == null) {
            return;
        }

        deltas = currentRevision.getDeltas();
        JMDelta sd = getSelectedDelta();
        index = deltas.indexOf(sd);
        if (index == -1 || sd.getOriginal().getAnchor() != selectedLine) {
            // Find the delta that would have been next to the
            //   disappeared delta:
            d = null;
            for (JMDelta delta : deltas) {
                d = delta;
                if (delta.getOriginal().getAnchor() > selectedLine) {
                    break;
                }
            }
            setSelectedDelta(d);
        } else {
            // Select the next delta if there is any.
            if (index + 1 < deltas.size()) {
                setSelectedDelta(deltas.get(index + 1));
            }
        }
        showSelectedDelta();
    }


    @Override
    public void doUp() {
        JMDelta d;
        if (currentRevision == null) {
            return;
        }

        List<JMDelta> deltas = currentRevision.getDeltas();
        JMDelta sd = getSelectedDelta();
        int index = deltas.indexOf(sd);
        if (index == -1 || sd.getOriginal().getAnchor() != selectedLine) {
            // Find the delta that would have been previous to the
            //   disappeared delta:
            d = null;
            for (JMDelta delta : deltas) {
                d = delta;
                if (delta.getOriginal().getAnchor() > selectedLine) {
                    break;
                }
            }

            setSelectedDelta(d);
        } else {
            // Select the next delta if there is any.
            if (index - 1 >= 0) {
                setSelectedDelta(deltas.get(index - 1));
                if (index - 1 == 0) {
                    Arrays.stream(filePanels).forEach(
                            item ->
                            {
                                if (item != null) {
                                    item.getScrollPane().getVerticalScrollBar()
                                            .setValue(0);
                                }
                            });
                }
            } else {
                setSelectedDelta(deltas.getFirst());
                Arrays.stream(filePanels).forEach(
                        item ->
                        {
                            if (item != null) {
                                item.getScrollPane().getVerticalScrollBar()
                                        .setValue(0);
                            }
                        });
            }
        }
        showSelectedDelta();
    }

    public void toNextDelta(boolean next) {
        if (next) {
            doDown();
        } else {
            doUp();
        }
    }
}
