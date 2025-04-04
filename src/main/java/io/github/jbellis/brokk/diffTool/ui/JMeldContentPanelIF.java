
package io.github.jbellis.brokk.diffTool.ui;

public interface JMeldContentPanelIF {

    boolean isUndoEnabled();

    void doUndo();

    boolean isRedoEnabled();

    void doRedo();


    void doUp();

    void doDown();

}
