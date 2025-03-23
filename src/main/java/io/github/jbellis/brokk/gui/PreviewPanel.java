package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.ContextManager;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.fife.ui.rtextarea.SearchContext;
import org.fife.ui.rtextarea.SearchEngine;
import org.fife.ui.rtextarea.SearchResult;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;

/**
 * A panel that displays text (typically code) in an RSyntaxTextArea with
 * a search bar at the top (case-insensitive, as-you-type). Next/Previous
 * buttons navigate matches. Ctrl+F focuses the search field.
 */
public class PreviewPanel extends JPanel
{
    private final PreviewTextArea textArea;
    private final JTextField searchField;
    private final JButton nextButton;
    private final JButton previousButton;
    private final ContextManager contextManager;
    
    /**
     * Updates the theme of this panel
     * @param guiTheme The theme manager to use
     */
    public void updateTheme(GuiTheme guiTheme) {
        if (guiTheme != null) {
            guiTheme.applyCurrentThemeToComponent(textArea);
        }
    }
    
    /**
     * Constructs a new PreviewPanel with the given content and syntax style.
     *
     * @param content     The text content to display
     * @param syntaxStyle For example, SyntaxConstants.SYNTAX_STYLE_JAVA
     * @param guiTheme    The theme manager to use for styling the text area
     */
    /**
     * Custom RSyntaxTextArea implementation for preview panels with custom popup menu
     */
    public static class PreviewTextArea extends RSyntaxTextArea {
        
        public PreviewTextArea(String content, String syntaxStyle) {
            setSyntaxEditingStyle(syntaxStyle != null ? syntaxStyle : SyntaxConstants.SYNTAX_STYLE_NONE);
            setCodeFoldingEnabled(true);
            setAntiAliasingEnabled(true);
            setHighlightCurrentLine(false);
            setEditable(false);
            setText(content);
        }
        
        @Override
        protected JPopupMenu createPopupMenu() {
            JPopupMenu menu = new JPopupMenu();
            
            // Add Copy option
            Action copyAction = new AbstractAction("Copy") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    copy();
                }
            };
            menu.add(copyAction);
            
            // Add Quick Edit option (disabled by default, will be enabled when text is selected)
            Action quickEditAction = new AbstractAction("Quick Edit") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    // Implementation will be added later
                }
            };
            quickEditAction.setEnabled(false);
            menu.add(quickEditAction);
            
            // Update Quick Edit enabled state when popup becomes visible
            menu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
                @Override
                public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                    quickEditAction.setEnabled(getSelectedText() != null);
                }
                
                @Override
                public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {}
                
                @Override
                public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {}
            });
            
            return menu;
        }
    }
    
    public PreviewPanel(ContextManager contextManager, String content, String syntaxStyle, GuiTheme guiTheme)
    {
        super(new BorderLayout());
        this.contextManager = contextManager;

        // Register ESC key to close the dialog
        registerEscapeKey();

        // === Top search bar ===
        JPanel searchPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        searchField = new JTextField(20);
        nextButton = new JButton("↓");
        previousButton = new JButton("↑");

        searchPanel.add(new JLabel("Search:"));
        searchPanel.add(searchField);
        searchPanel.add(previousButton);
        searchPanel.add(nextButton);

        // === Text area with syntax highlighting ===
        textArea = new PreviewTextArea(content, syntaxStyle);

        // Put the text area in a scroll pane
        RTextScrollPane scrollPane = new RTextScrollPane(textArea);
        scrollPane.setFoldIndicatorEnabled(true);
        
        // Apply the current theme to the text area
        if (guiTheme != null) {
            guiTheme.applyCurrentThemeToComponent(textArea);
        }

        // Add top search panel + text area to this panel
        add(searchPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);

        // === Hook up the search as you type ===
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                updateSearchHighlights();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                updateSearchHighlights();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                updateSearchHighlights();
            }
        });
        
        // === Enter key in search field triggers next match ===
        searchField.addActionListener(e -> findNext(true));
        
        // === Arrow keys for navigation ===
        InputMap inputMap = searchField.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap actionMap = searchField.getActionMap();
        
        // Down arrow for next match
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "findNext");
        actionMap.put("findNext", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                findNext(true);
            }
        });
        
        // Up arrow for previous match
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "findPrevious");
        actionMap.put("findPrevious", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                findNext(false);
            }
        });

        // === Next / Previous buttons ===
        nextButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                findNext(true);
            }
        });
        
        previousButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                findNext(false);
            }
        });

        // === Cmd/Ctrl+F focuses the search field ===
        KeyStroke ctrlF = KeyStroke.getKeyStroke(KeyEvent.VK_F, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(ctrlF, "focusSearch");
        getActionMap().put("focusSearch", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                searchField.requestFocusInWindow();
            }
        });
        
        // Scroll to the beginning of the document
        textArea.setCaretPosition(0);
    }
    
    /**
     * Registers ESC key to close the preview panel
     */
    private void registerEscapeKey() {
        KeyStroke escapeKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(escapeKeyStroke, "closePreview");
        getActionMap().put("closePreview", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                Window window = SwingUtilities.getWindowAncestor(PreviewPanel.this);
                if (window != null) {
                    window.dispose();
                }
            }
        });
    }

    /**
     * Called whenever the user types in the search field, to highlight all matches (case-insensitive).
     */
    private void updateSearchHighlights()
    {
        String query = searchField.getText();
        if (query == null || query.trim().isEmpty()) {
            return;
        }

        SearchContext context = new SearchContext(query);
        context.setMatchCase(false);
        context.setMarkAll(true);
        context.setWholeWord(false);
        context.setRegularExpression(false);
        context.setSearchForward(true);
        context.setSearchWrap(true);

        // Mark all occurrences
        SearchEngine.markAll(textArea, context);
        
        // Jump to the first occurrence as the user types
        int originalCaretPosition = textArea.getCaretPosition();
        textArea.setCaretPosition(0); // Start search from beginning
        SearchResult result = SearchEngine.find(textArea, context);
        if (!result.wasFound() && originalCaretPosition > 0) {
            // If not found from beginning, restore caret position
            textArea.setCaretPosition(originalCaretPosition);
        } else if (result.wasFound()) {
            // Center the match in the viewport
            centerCurrentMatchInView();
        }
    }
    
    /**
     * Centers the current match in the viewport
     */
    private void centerCurrentMatchInView() {
        try {
            Rectangle matchRect = textArea.modelToView(textArea.getCaretPosition());
            JViewport viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, textArea);
            if (viewport != null && matchRect != null) {
                // Calculate the target Y position (1/3 from the top)
                int viewportHeight = viewport.getHeight();
                int targetY = Math.max(0, (int)(matchRect.y - viewportHeight * 0.33));

                // Create a new point for scrolling
                Rectangle viewRect = viewport.getViewRect();
                viewRect.y = targetY;
                textArea.scrollRectToVisible(viewRect);
            }
        } catch (Exception ex) {
            // Silently ignore any view transformation errors
        }
    }

    /**
     * Finds the next or previous match relative to the current caret position.
     * @param forward true = next match; false = previous match
     */
    private void findNext(boolean forward)
    {
        String query = searchField.getText();
        if (query == null || query.trim().isEmpty()) {
            return;
        }

        // Our context for next/previous. We'll ignore case, no regex, wrap around.
        SearchContext context = new SearchContext(query);
        context.setMatchCase(false);
        context.setMarkAll(true);
        context.setWholeWord(false);
        context.setRegularExpression(false);
        context.setSearchForward(forward);
        context.setSearchWrap(true);

        SearchResult result = SearchEngine.find(textArea, context);
        if (result.wasFound()) {
            centerCurrentMatchInView();
        }
    }
}
