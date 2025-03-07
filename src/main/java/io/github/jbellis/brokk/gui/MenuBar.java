package io.github.jbellis.brokk.gui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.List;

public class MenuBar {
    /**
     * Builds the menu bar
     * @param chrome
     */
    static JMenuBar buildMenuBar(Chrome chrome) {
        var menuBar = new JMenuBar();

        // File menu
        var fileMenu = new JMenu("File");
        fileMenu.setMnemonic(KeyEvent.VK_F);

        var editKeysItem = new JMenuItem("Edit secret keys");
        editKeysItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_K, InputEvent.ALT_DOWN_MASK));
        editKeysItem.addActionListener(e -> chrome.showSecretKeysDialog());
        fileMenu.add(editKeysItem);

        fileMenu.addSeparator();

        var setAutoContextItem = new JMenuItem("Set autocontext size");
        setAutoContextItem.addActionListener(e -> {
            // Simple spinner dialog
            var dialog = new JDialog(chrome.frame, "Set Autocontext Size", true);
            dialog.setLayout(new BorderLayout());

            var panel = new JPanel(new BorderLayout());
            panel.setBorder(new EmptyBorder(10, 10, 10, 10));

            var label = new JLabel("Enter autocontext size (0-100):");
            panel.add(label, BorderLayout.NORTH);

            var spinner = new JSpinner(new SpinnerNumberModel(
                    chrome.contextManager.currentContext().getAutoContextFileCount(),
                    0, 100, 1
            ));
            panel.add(spinner, BorderLayout.CENTER);

            var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            var okButton = new JButton("OK");
            var cancelButton = new JButton("Cancel");

            okButton.addActionListener(okEvent -> {
                var newSize = (int) spinner.getValue();
                chrome.contextManager.setAutoContextFiles(newSize);
                dialog.dispose();
            });

            cancelButton.addActionListener(cancelEvent -> dialog.dispose());
            buttonPanel.add(okButton);
            buttonPanel.add(cancelButton);
            panel.add(buttonPanel, BorderLayout.SOUTH);

            dialog.getRootPane().setDefaultButton(okButton);
            dialog.getRootPane().registerKeyboardAction(
                    evt -> dialog.dispose(),
                    KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                    JComponent.WHEN_IN_FOCUSED_WINDOW
            );

            dialog.add(panel);
            dialog.pack();
            dialog.setLocationRelativeTo(chrome.frame);
            dialog.setVisible(true);
        });
        fileMenu.add(setAutoContextItem);

        var refreshItem = new JMenuItem("Refresh Code Intelligence");
        refreshItem.addActionListener(e -> {
            chrome.contextManager.requestRebuild();
            chrome.toolOutput("Code intelligence will refresh in the background");
        });
        fileMenu.add(refreshItem);

        menuBar.add(fileMenu);

        // Edit menu
        var editMenu = new JMenu("Edit");
        editMenu.setMnemonic(KeyEvent.VK_E);

        var undoItem = new JMenuItem("Undo");
        undoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK));
        undoItem.addActionListener(e -> {
            chrome.disableUserActionButtons();
            chrome.disableContextActionButtons();
            chrome.currentUserTask = chrome.contextManager.undoContextAsync();
        });
        editMenu.add(undoItem);

        var redoItem = new JMenuItem("Redo");
        redoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z,
                                                       InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        redoItem.addActionListener(e -> {
            chrome.disableUserActionButtons();
            chrome.disableContextActionButtons();
            chrome.currentUserTask = chrome.contextManager.redoContextAsync();
        });
        editMenu.add(redoItem);

        editMenu.addSeparator();

        var copyMenuItem = new JMenuItem("Copy");
        copyMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK));
        copyMenuItem.addActionListener(e -> {
            var selectedFragments = chrome.getSelectedFragments();
            chrome.currentUserTask = chrome.contextManager.performContextActionAsync(Chrome.ContextAction.COPY, selectedFragments);
        });
        editMenu.add(copyMenuItem);

        var pasteMenuItem = new JMenuItem("Paste");
        pasteMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK));
        pasteMenuItem.addActionListener(e -> {
            chrome.currentUserTask = chrome.contextManager.performContextActionAsync(Chrome.ContextAction.PASTE, List.of());
        });
        editMenu.add(pasteMenuItem);

        menuBar.add(editMenu);


        // Help menu
        var helpMenu = new JMenu("Help");
        helpMenu.setMnemonic(KeyEvent.VK_H);

        var aboutItem = new JMenuItem("About");
        aboutItem.addActionListener(e -> {
            JOptionPane.showMessageDialog(chrome.frame,
                                          "Brokk Swing UI\nVersion X\n...",
                                          "About Brokk",
                                          JOptionPane.INFORMATION_MESSAGE);
        });
        helpMenu.add(aboutItem);

        menuBar.add(helpMenu);

        return menuBar;
    }
}
