package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.Project;

import javax.swing.*;
import java.awt.*;
import java.util.Properties;

public class SettingsDialog extends JDialog {

    private final Chrome chrome;
    private final JTabbedPane tabbedPane;
    private final JPanel projectPanel; // Keep a reference to enable/disable

    public SettingsDialog(Frame owner, Chrome chrome) {
        super(owner, "Settings", true); // Modal dialog
        this.chrome = chrome;
        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        setSize(500, 400);
        setLocationRelativeTo(owner);

        tabbedPane = new JTabbedPane(JTabbedPane.LEFT); // Tabs on the left

        // Global Settings Panel
        var globalPanel = createGlobalPanel();
        tabbedPane.addTab("Global", null, globalPanel, "Global application settings");

        // Project Settings Panel
        projectPanel = createProjectPanel();
        tabbedPane.addTab("Project", null, projectPanel, "Settings specific to the current project");

        // Enable/disable project tab based on whether a project is open
        projectPanel.setEnabled(chrome.getProject() != null);
        for (Component comp : projectPanel.getComponents()) {
            comp.setEnabled(projectPanel.isEnabled());
        }
        tabbedPane.setEnabledAt(1, chrome.getProject() != null); // Index 1 is the Project tab

        // Buttons Panel
        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        var okButton = new JButton("OK");
        var cancelButton = new JButton("Cancel");
        var applyButton = new JButton("Apply"); // Added Apply button

        okButton.addActionListener(e -> {
            applySettings();
            dispose();
        });
        cancelButton.addActionListener(e -> dispose());
        applyButton.addActionListener(e -> applySettings());

        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        buttonPanel.add(applyButton);

        // Layout
        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(tabbedPane, BorderLayout.CENTER);
        getContentPane().add(buttonPanel, BorderLayout.SOUTH);
    }

    private JPanel createGlobalPanel() {
        var panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Theme Selection
        panel.add(new JLabel("Theme:"));
        var lightRadio = new JRadioButton("Light");
        var darkRadio = new JRadioButton("Dark");
        var themeGroup = new ButtonGroup();
        themeGroup.add(lightRadio);
        themeGroup.add(darkRadio);

        // Select the currently active theme
        if (UIManager.getLookAndFeel().getName().toLowerCase().contains("dark")) {
            darkRadio.setSelected(true);
        } else {
            lightRadio.setSelected(true);
        }

        // Add listeners later in applySettings to avoid triggering during setup
        lightRadio.putClientProperty("theme", false); // Store theme value
        darkRadio.putClientProperty("theme", true); // Store theme value

        var themePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        themePanel.add(lightRadio);
        themePanel.add(darkRadio);
        panel.add(themePanel);
        panel.add(Box.createVerticalGlue()); // Pushes components to the top

        return panel;
    }

    private JPanel createProjectPanel() {
        var panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        var gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(2, 2, 2, 2);

        var project = chrome.getProject();
        if (project != null) {
            // TODO: Load properties from project.getProperties()
            // TODO: Create JTextFields for each editable property
            // For now, just add a placeholder
            panel.add(new JLabel("Project settings will be loaded here."), gbc);
        } else {
            panel.add(new JLabel("No project is open."), gbc);
        }

        // Add a glue component to push everything to the top-left
        gbc.gridy++;
        gbc.weightx = 1.0;
        gbc.weighty = 1.0;
        panel.add(Box.createGlue(), gbc);

        return panel;
    }

    private void applySettings() {
        // Apply Global Settings
        var globalPanel = (JPanel) tabbedPane.getComponentAt(0); // Assuming Global is the first tab
        var themePanel = (JPanel) globalPanel.getComponent(1); // Assuming themePanel is the second component
        for (Component comp : themePanel.getComponents()) {
            if (comp instanceof JRadioButton radio && radio.isSelected()) {
                boolean useDark = (Boolean) radio.getClientProperty("theme");
                chrome.switchTheme(useDark);
                break;
            }
        }

        // Apply Project Settings (if project is open and tab is enabled)
        if (chrome.getProject() != null && tabbedPane.isEnabledAt(1)) {
            // TODO: Retrieve values from JTextFields in projectPanel
            // TODO: Create a Properties object with the new values
            // TODO: Call a method like chrome.getProject().saveProperties(newProperties)
            System.out.println("Applying project settings (not implemented yet)");
        }
    }
}
