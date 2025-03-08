package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.Analyzer;
import io.github.jbellis.brokk.CodeUnit;
import io.github.jbellis.brokk.Project;
import org.fife.ui.autocomplete.AutoCompletion;
import org.fife.ui.autocomplete.CompletionProvider;

import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * A dialog for selecting Java symbols (classes and members) with autocomplete.
 */
public class SymbolSelectionDialog extends JDialog {

    private final Project project;
    private final JTextField symbolInput;
    private final AutoCompletion autoCompletion;
    private final JButton okButton;
    private final JButton cancelButton;

    // The selected symbol
    private String selectedSymbol = null;

    // Indicates if the user confirmed the selection
    private boolean confirmed = false;

    public SymbolSelectionDialog(Frame parent, Project project, String title) {
        super(parent, title, true); // modal dialog
        this.project = project;

        JPanel mainPanel = new JPanel(new BorderLayout(8, 8));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // Build text input with autocomplete at the top
        symbolInput = new JTextField(30);
        var provider = createSymbolCompletionProvider();
        autoCompletion = new AutoCompletion(provider);
        // Trigger with Ctrl+Space
        autoCompletion.setAutoActivationEnabled(false);
        autoCompletion.setTriggerKey(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK));
        autoCompletion.install(symbolInput);

        JPanel inputPanel = new JPanel(new BorderLayout());
        inputPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        inputPanel.add(symbolInput, BorderLayout.CENTER);
        inputPanel.add(new JLabel("Ctrl-space to autocomplete class and member names"), BorderLayout.SOUTH);
        mainPanel.add(inputPanel, BorderLayout.NORTH);

        // Class list in the center
        JPanel listPanel = new JPanel(new BorderLayout());
        listPanel.setBorder(BorderFactory.createTitledBorder("Available Classes"));
        
        DefaultListModel<String> classListModel = new DefaultListModel<>();
        List<String> allClasses = new ArrayList<>(project.getAnalyzerWrapper().get().getAllClasses().stream()
                .map(CodeUnit::reference)
                .sorted()
                .toList());
        for (String className : allClasses) {
            classListModel.addElement(className);
        }
        JList<String> classList = new JList<>(classListModel);
        classList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        classList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                String selected = classList.getSelectedValue();
                if (selected != null) {
                    symbolInput.setText(selected);
                }
            }
        });
        
        JScrollPane listScrollPane = new JScrollPane(classList);
        listPanel.add(listScrollPane, BorderLayout.CENTER);
        listPanel.setPreferredSize(new Dimension(400, 300));
        mainPanel.add(listPanel, BorderLayout.CENTER);

        // Buttons at the bottom
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        okButton = new JButton("OK");
        okButton.addActionListener(e -> doOk());
        cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> {
            confirmed = false;
            dispose();
        });
        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        mainPanel.add(buttonPanel, BorderLayout.SOUTH);

        // Handle escape key to close dialog
        KeyStroke escapeKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
        getRootPane().registerKeyboardAction(e -> {
            confirmed = false;
            selectedSymbol = null;
            dispose();
        }, escapeKeyStroke, JComponent.WHEN_IN_FOCUSED_WINDOW);

        // Set OK as the default button (responds to Enter key)
        getRootPane().setDefaultButton(okButton);

        // Add a tooltip to indicate Enter key functionality
        symbolInput.setToolTipText("Enter a class or member name and press Enter to confirm");
        
        // Add double-click handler to the class list
        classList.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int index = classList.locationToIndex(e.getPoint());
                    if (index >= 0) {
                        symbolInput.setText(classList.getModel().getElementAt(index));
                        doOk();
                    }
                }
            }
        });

        setContentPane(mainPanel);
        pack();
        setLocationRelativeTo(parent);
        // Make the autocomplete popup match the width of the dialog
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                autoCompletion.setChoicesWindowSize((int) (mainPanel.getWidth() * 0.9), 300);
            }
        });
    }

    /**
     * Create the symbol completion provider using Completions.completeClassesAndMembers
     */
    private CompletionProvider createSymbolCompletionProvider() {
        return new SymbolCompletionProvider(project);
    }

    /**
     * When OK is pressed, get the symbol from the text input.
     */
    private void doOk() {
        confirmed = true;
        selectedSymbol = null;

        String typed = symbolInput.getText().trim();
        if (!typed.isEmpty()) {
            selectedSymbol = typed;
        }
        dispose();
    }

    /**
     * Return true if user confirmed the selection.
     */
    public boolean isConfirmed() {
        return confirmed;
    }

    /**
     * Return the selected symbol or null if none.
     */
    public String getSelectedSymbol() {
        return selectedSymbol;
    }
}
