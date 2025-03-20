package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.analyzer.CodeUnitType;
import io.github.jbellis.brokk.analyzer.IAnalyzer;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.Set;

/**
 * Dialog for configuring call graph analysis with method selection and depth control
 */
public class CallGraphDialog extends JDialog {

    private final SymbolSelectionPanel selectionPanel;
    private final JButton okButton;
    private final JButton cancelButton;
    private final JSpinner depthSpinner;

    // The selected method
    private String selectedMethod = null;

    // The selected depth
    private int depth = 5;

    // Indicates if the user confirmed the selection
    private boolean confirmed = false;

    public CallGraphDialog(Frame parent, IAnalyzer analyzer, String title) {
        super(parent, title, true); // modal dialog
        assert parent != null;
        assert title != null;
        assert analyzer != null;

        var mainPanel = new JPanel(new BorderLayout(8, 8));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // Create the symbol selection panel - limited to methods only and single selection
        selectionPanel = new SymbolSelectionPanel(analyzer, Set.of(CodeUnitType.FUNCTION), 1);

        // Create the depth panel with spinner
        var depthPanel = new JPanel(new GridBagLayout());
        depthPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        var gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.NONE;
        gbc.insets = new Insets(0, 0, 0, 5);

        // First row - depth spinner
        var depthLabel = new JLabel("Depth: ");
        var spinnerModel = new SpinnerNumberModel(5, 1, 10, 1);
        depthSpinner = new JSpinner(spinnerModel);

        gbc.gridx = 0;
        gbc.gridy = 0;
        depthPanel.add(depthLabel, gbc);

        gbc.gridx = 1;
        gbc.gridy = 0;
        depthPanel.add(depthSpinner, gbc);

        // Second row - help text
        var helpLabel = new JLabel("Maximum depth of the call graph");
        helpLabel.setFont(helpLabel.getFont().deriveFont(Font.ITALIC));

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.gridwidth = 2;
        gbc.insets = new Insets(4, 0, 0, 0);
        depthPanel.add(helpLabel, gbc);

        // Add components directly to the main panel
        mainPanel.add(selectionPanel, BorderLayout.NORTH);
        mainPanel.add(depthPanel, BorderLayout.WEST);

        // Buttons at the bottom
        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
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
            selectedMethod = null;
            dispose();
        }, escapeKeyStroke, JComponent.WHEN_IN_FOCUSED_WINDOW);

        // Set OK as the default button (responds to Enter key)
        getRootPane().setDefaultButton(okButton);

        // Add a tooltip to indicate Enter key functionality
        selectionPanel.getInputField().setToolTipText("Enter a method name and press Enter to confirm");

        // Listen to spinner changes
        spinnerModel.addChangeListener(e -> depth = (Integer) depthSpinner.getValue());

        setContentPane(mainPanel);
        pack();
        setLocationRelativeTo(parent);
    }

    /**
     * When OK is pressed, get the method and depth values.
     */
    private void doOk() {
        confirmed = true;
        selectedMethod = null;

        String typed = selectionPanel.getSymbolText();
        if (!typed.isEmpty()) {
            selectedMethod = typed;
        }

        depth = (Integer) depthSpinner.getValue();
        dispose();
    }

    /**
     * Return true if user confirmed the selection.
     */
    public boolean isConfirmed() {
        return confirmed;
    }

    /**
     * Return the selected method or null if none.
     */
    public String getSelectedMethod() {
        return selectedMethod;
    }

    /**
     * Return the selected depth.
     */
    public int getDepth() {
        return depth;
    }
}
