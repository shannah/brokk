
package io.github.jbellis.brokk.diffTool.search;

import io.github.jbellis.brokk.diffTool.ui.BrokkDiffPanel;
import io.github.jbellis.brokk.diffTool.ui.BufferDiffPanel;
import io.github.jbellis.brokk.diffTool.ui.FilePanel;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Objects;

public class SearchBarDialog extends JPanel {
    // class variables:
    private static final String CP_FOREGROUND = "JMeld.foreground";
    // Instance variables:
    private JTextField searchField;
    private JLabel searchResult;
    private Timer timer;
    private final BufferDiffPanel bufferDiffPanel;
    private FilePanel filePanel;

    public SearchBarDialog(BrokkDiffPanel brokkDiffPanel, BufferDiffPanel bufferDiffPanel) {
        this.bufferDiffPanel = bufferDiffPanel;
        init();

    }

    public void setFilePanel(FilePanel filePanel) {
        this.filePanel = filePanel;
    }


    protected void init() {
        JButton previousButton;
        JButton nextButton;

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS)); // Vertical layout

        // Incremental search:
        searchField = new JTextField(15);
        searchField.getDocument().addDocumentListener(getSearchAction());

        // Panel for search field and label
        JPanel searchPanel = new JPanel(new FlowLayout(FlowLayout.LEADING));
        searchPanel.add(new JLabel("Find:"));
        searchPanel.add(searchField);

        // Panel for buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));

        // Find previous match:
        previousButton = new JButton("Previous", new ImageIcon(Objects.requireNonNull(getClass().getResource("/images/prev.png"))));
        previousButton.addActionListener(getPreviousAction());
        initButton(previousButton);

        // Find next match:
        nextButton = new JButton("Next", new ImageIcon(Objects.requireNonNull(getClass().getResource("/images/next.png"))));
        nextButton.addActionListener(getNextAction());
        initButton(nextButton);

        // Add buttons to their own row
        buttonPanel.add(previousButton);
        buttonPanel.add(Box.createHorizontalStrut(10));
        buttonPanel.add(nextButton);

        // Search result label
        searchResult = new JLabel();

        // Add components to the main panel
        add(Box.createVerticalStrut(5));
        add(searchPanel);
        add(Box.createVerticalStrut(5));
        add(buttonPanel);
        add(Box.createVerticalStrut(10));
        add(searchResult);

        bufferDiffPanel.getCaseSensitiveCheckBox().addActionListener(e -> filePanel.doSearch());

        timer = new Timer(500, executeSearch());
        timer.setRepeats(false);
    }



    private void initButton(AbstractButton button) {
        button.setFocusable(false);
        button.setBorderPainted(false);
        button.setBorder(new EmptyBorder(0, 5, 0, 5));
    }

    public SearchCommand getCommand() {
        return new SearchCommand(searchField.getText(), bufferDiffPanel.getCaseSensitiveCheckBox().isSelected());
    }

    private DocumentListener getSearchAction() {
        return new DocumentListener() {
            public void changedUpdate(DocumentEvent e) {
                timer.restart();
            }

            public void insertUpdate(DocumentEvent e) {
                timer.restart();
            }

            public void removeUpdate(DocumentEvent e) {
                timer.restart();
            }
        };
    }

    private ActionListener executeSearch() {
        return new ActionListener() {
            public void actionPerformed(ActionEvent ae) {
                boolean notFound;
                Color color;
                SearchHits searchHits;
                searchHits = filePanel.doSearch();
                notFound = (searchHits == null || searchHits.getSearchHits().isEmpty());

                if (notFound && !searchField.getText().isEmpty()) {
                    if (searchField.getForeground() != Color.red) {
                        // Remember the original colors:
                        searchField.putClientProperty(CP_FOREGROUND, searchField
                                .getForeground());

                        // Set the new colors:
                        searchField.setForeground(Color.red);
                    }

                    searchResult.setIcon(new ImageIcon(Objects.requireNonNull(getClass().getResource("/images/result.png"))));
                    searchResult.setText("Phrase not found");
                } else {
                    // Set the original colors:
                    color = (Color) searchField.getClientProperty(CP_FOREGROUND);
                    if (color != null) {
                        searchField.setForeground(color);
                        searchField.putClientProperty(CP_FOREGROUND, null);
                    }

                    if (!searchResult.getText().isEmpty()) {
                        searchResult.setIcon(null);
                        searchResult.setText("");
                    }
                }
            }
        };
    }

    private ActionListener getCloseAction() {
        return ae -> filePanel.doStopSearch();
    }

    private ActionListener getPreviousAction() {
        return ae -> filePanel.doPreviousSearch();
    }

    private ActionListener getNextAction() {
        return ae -> filePanel.doNextSearch();
    }
}
