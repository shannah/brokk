package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.Project;

import javax.swing.*;
import java.awt.*;

public class SettingsDialog extends JDialog {

    private final Chrome chrome;
    private final JTabbedPane tabbedPane;
    private final JPanel projectPanel; // Keep a reference to enable/disable
    private JTextField buildCommandField; // Field for build command
    private JComboBox<Project.CpgRefresh> cpgRefreshComboBox; // ComboBox for CPG refresh

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

        gbc.fill = GridBagConstraints.HORIZONTAL; // Allow horizontal expansion

        var project = chrome.getProject();
        if (project != null) {
            // Build Command
            var buildCommandLabel = new JLabel("Build Command:");
            gbc.gridx = 0;
            gbc.gridy = 0;
            gbc.weightx = 0.0; // Label doesn't expand
            panel.add(buildCommandLabel, gbc);

            buildCommandField = new JTextField(project.getBuildCommand(), 20);
            gbc.gridx = 1;
            gbc.gridy = 0;
            gbc.weightx = 1.0; // Text field expands
            panel.add(buildCommandField, gbc);

            // Code Intelligence Refresh
            var cpgRefreshLabel = new JLabel("Code Intelligence Refresh:");
            gbc.gridx = 0;
            gbc.gridy = 1;
            gbc.weightx = 0.0;
            panel.add(cpgRefreshLabel, gbc);

            // Filter out UNSET from the dropdown
            cpgRefreshComboBox = new JComboBox<>(new Project.CpgRefresh[]{
                Project.CpgRefresh.AUTO, Project.CpgRefresh.MANUAL
            });
            var currentRefresh = project.getCpgRefresh();
            // If current setting is UNSET, default to AUTO
            cpgRefreshComboBox.setSelectedItem(currentRefresh == Project.CpgRefresh.UNSET ? 
                Project.CpgRefresh.AUTO : currentRefresh);
            gbc.gridx = 1;
            gbc.gridy = 1;
            gbc.weightx = 1.0;
            panel.add(cpgRefreshComboBox, gbc);

        } else {
            gbc.gridx = 0;
            gbc.gridy = 0;
            gbc.gridwidth = 2; // Span across two columns
            panel.add(new JLabel("No project is open."), gbc);
            gbc.gridwidth = 1; // Reset gridwidth
        }

        // Add a glue component to push everything to the top-left
        gbc.gridy = 2; // Adjust gridy based on added components
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
        var project = chrome.getProject();
        if (project != null && tabbedPane.isEnabledAt(1)) {
            // Apply Build Command
            var newBuildCommand = buildCommandField.getText();
            if (!newBuildCommand.equals(project.getBuildCommand())) {
                project.setBuildCommand(newBuildCommand);
            }

            // Apply CPG Refresh Setting
            var selectedRefresh = (Project.CpgRefresh) cpgRefreshComboBox.getSelectedItem();
            if (selectedRefresh != project.getCpgRefresh()) {
                project.setCpgRefresh(selectedRefresh);
            }
        }
    }
}
