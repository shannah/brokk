package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.ContextFragment;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.gui.Chrome;
import javax.swing.*;
import java.awt.*;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.RTextScrollPane;
import java.awt.event.KeyEvent;

/**
 * Dialog for editing the long-term project plan.
 */
public class PlanDialog extends JDialog {

    private final ContextManager contextManager;
    private final RSyntaxTextArea planTextArea;
    private boolean confirmed = false;

    public PlanDialog(Chrome chrome, ContextManager contextManager, ContextFragment.PlanFragment currentPlan) {
        super(chrome.getFrame(), "Edit Project Plan", true); // Modal dialog
        this.contextManager = contextManager;

        // Explanation Label
        JLabel explanationLabel = new JLabel(
                "<html>You can provide an optional long-term plan here.<br>" +
                "It will be provided to the Code Agent so it can see the big picture.<br>" +
                "Leave blank to remove the plan.</html>");
        explanationLabel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Text Area
        planTextArea = new RSyntaxTextArea(20, 80); // Rows, Columns
        planTextArea.setSyntaxEditingStyle(org.fife.ui.rsyntaxtextarea.SyntaxConstants.SYNTAX_STYLE_MARKDOWN);
        planTextArea.setCodeFoldingEnabled(true);
        planTextArea.setAntiAliasingEnabled(true);
        planTextArea.setMarkOccurrences(true);
        planTextArea.setHighlightCurrentLine(false);

        // Removed theme application block due to access restrictions in Chrome

        // Set initial text from current plan, handling the EMPTY case
        String initialText = currentPlan == ContextFragment.PlanFragment.EMPTY ? "" : currentPlan.text();
        planTextArea.setText(initialText);
        planTextArea.setCaretPosition(0);

        RTextScrollPane scrollPane = new RTextScrollPane(planTextArea);

        // Buttons
        JButton okButton = new JButton("Okay");
        okButton.addActionListener(e -> saveAndClose());

        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> dispose());

        // Button Panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(cancelButton);
        buttonPanel.add(okButton);

        // Layout
        setLayout(new BorderLayout());
        add(explanationLabel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.SOUTH);

        getRootPane().setDefaultButton(okButton);
        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        pack();
        setSize(Math.max(600, getWidth()), Math.max(400, getHeight()));
        setLocationRelativeTo(chrome.getFrame());
    }

    private void saveAndClose() {
        String planText = planTextArea.getText();
        ContextFragment.PlanFragment newPlan;
        if (planText == null || planText.isBlank()) {
            newPlan = ContextFragment.PlanFragment.EMPTY;
        } else {
            newPlan = new ContextFragment.PlanFragment(planText.stripIndent());
        }
        // Call the synchronous setPlan method in ContextManager
        contextManager.setPlan(newPlan);
        confirmed = true;
        dispose(); // Close the dialog
    }

    public boolean isConfirmed() {
        return confirmed;
    }
}
