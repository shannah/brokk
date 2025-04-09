package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.BuildAgent; // Import BuildAgent
import io.github.jbellis.brokk.Project;
import io.github.jbellis.brokk.gui.Chrome;

import javax.swing.*;
import java.awt.*;

public class SettingsDialog extends JDialog {

    private final Chrome chrome;
    private final JTabbedPane tabbedPane;
    private final JPanel projectPanel; // Keep a reference to enable/disable
    // Brokk Key field (Global)
    private JTextField brokkKeyField;
    // Project fields
    private JComboBox<Project.CpgRefresh> cpgRefreshComboBox; // ComboBox for CPG refresh
    private JTextField buildCleanCommandField;
    private JTextField allTestsCommandField;
    private JTextArea buildInstructionsArea;

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
        var panel = new JPanel(new GridBagLayout()); // Use GridBagLayout for better alignment
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 2, 2, 2);
        gbc.anchor = GridBagConstraints.WEST; // Align components to the left
        int row = 0;

        // Brokk Key Input
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        panel.add(new JLabel("Brokk Key:"), gbc);

        brokkKeyField = new JTextField(20); // Initialize the field
        brokkKeyField.setText(Project.getBrokkKey()); // Populate with existing key
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL; // Make field expand horizontally
        panel.add(brokkKeyField, gbc);

        // Reset fill for the label
        gbc.fill = GridBagConstraints.NONE;

        // Theme Selection (now on one line)
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        panel.add(new JLabel("Theme:"), gbc);

        var lightRadio = new JRadioButton("Light");
        var darkRadio = new JRadioButton("Dark");
        var themeGroup = new ButtonGroup();
        themeGroup.add(lightRadio);
        themeGroup.add(darkRadio);

        // Select the currently active theme
        if (Project.getTheme().equals("dark")) { // Use Project.getTheme()
            darkRadio.setSelected(true);
        } else {
            lightRadio.setSelected(true);
        }

        // Store theme value (false for light, true for dark)
        lightRadio.putClientProperty("theme", false);
        darkRadio.putClientProperty("theme", true);

        // Panel to hold radio buttons together
        var themeRadioPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)); // No gaps
        themeRadioPanel.add(lightRadio);
        themeRadioPanel.add(darkRadio);

        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0; // Let the radio button panel take remaining space if needed
        // No fill needed here as FlowLayout handles sizing
        panel.add(themeRadioPanel, gbc);

        // Add vertical glue to push components to the top
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 2; // Span across both columns
        gbc.weighty = 1.0; // Take up remaining vertical space
        gbc.fill = GridBagConstraints.VERTICAL;
        panel.add(Box.createVerticalGlue(), gbc);

        return panel;
    }

    private JPanel createProjectPanel() {
        var project = chrome.getProject();
        var outerPanel = new JPanel(new BorderLayout());
        if (project == null) {
            outerPanel.add(new JLabel("No project is open."), BorderLayout.CENTER);
            return outerPanel;
        }
        
        // Create a sub-tabbed pane for Project settings: Build and Other
        var subTabbedPane = new JTabbedPane(JTabbedPane.TOP);
        
        // ----- Build Tab -----
        var buildPanel = new JPanel(new GridBagLayout());
        buildPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 2, 2, 2);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;

        // Initialize Build fields for new BuildDetails
        buildCleanCommandField = new JTextField(); // used for Build/Lint Command
        allTestsCommandField = new JTextField(); // used for Test All Command
        // Set the text area for build instructions to be 10 lines tall
        buildInstructionsArea = new JTextArea(10, 20);
        buildInstructionsArea.setWrapStyleWord(true);
        buildInstructionsArea.setLineWrap(true);
        var instructionsScrollPane = new JScrollPane(buildInstructionsArea);
        instructionsScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        instructionsScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        
        var details = project.getBuildDetails();
        // Build/Lint Command
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        buildPanel.add(new JLabel("Build/Lint Command:"), gbc);
        buildCleanCommandField.setText(details != null ? details.buildLintCommand() : ""); // Existing line
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        buildPanel.add(buildCleanCommandField, gbc);

        // Test All Command
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        buildPanel.add(new JLabel("Test All Command:"), gbc);
        allTestsCommandField.setText(details != null ? details.testAllCommand() : ""); // Existing line
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 1.0;
        buildPanel.add(allTestsCommandField, gbc);

        // Build Instructions
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        buildPanel.add(new JLabel("Build Instructions:"), gbc);
        buildInstructionsArea.setText(details != null ? details.instructions() : ""); // Set initial text
        gbc.gridx = 1;
        gbc.gridy = row;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        buildPanel.add(instructionsScrollPane, gbc);

        // Add vertical glue to push components to the top
        gbc.gridx = 0;
        gbc.gridy = ++row; // Move to next row
        gbc.gridwidth = 2; // Span across both columns
        gbc.weighty = 1.0; // Take up remaining vertical space
        gbc.fill = GridBagConstraints.VERTICAL;
        buildPanel.add(Box.createVerticalGlue(), gbc);

        // ----- Other Tab -----
        var otherPanel = new JPanel(new GridBagLayout());
        otherPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 2, 2, 2);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        row = 0;
        
        // Code Intelligence Refresh
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0.0;
        otherPanel.add(new JLabel("Code Intelligence Refresh:"), gbc);
        cpgRefreshComboBox = new JComboBox<>(new Project.CpgRefresh[]{
            Project.CpgRefresh.AUTO, Project.CpgRefresh.MANUAL
        });
        var currentRefresh = project.getCpgRefresh();
        cpgRefreshComboBox.setSelectedItem(currentRefresh == Project.CpgRefresh.UNSET ?
            Project.CpgRefresh.AUTO : currentRefresh);
        gbc.gridx = 1;
        gbc.gridy = row++; // Increment row after adding combo box
        gbc.weightx = 1.0;
        otherPanel.add(cpgRefreshComboBox, gbc);

        // Add vertical glue to push components to the top
        gbc.gridx = 0;
        gbc.gridy = row; // Use the incremented row
        gbc.gridwidth = 2; // Span across both columns
        gbc.weighty = 1.0; // Take up remaining vertical space
        gbc.fill = GridBagConstraints.VERTICAL;
        otherPanel.add(Box.createVerticalGlue(), gbc);


        subTabbedPane.addTab("Build", buildPanel);
        subTabbedPane.addTab("Other", otherPanel);
        
        var outerPanelContainer = new JPanel(new BorderLayout());
        outerPanelContainer.add(subTabbedPane, BorderLayout.CENTER);
        return outerPanelContainer;
    }

    private void applySettings() {
        // Apply Global Settings
        var globalPanel = (JPanel) tabbedPane.getComponentAt(0); // Assuming Global is the first tab

        // -- Apply Brokk Key --
        String currentBrokkKey = Project.getBrokkKey();
        String newBrokkKey = brokkKeyField.getText().trim(); // Read from the new field
        if (!newBrokkKey.equals(currentBrokkKey)) {
            Project.setBrokkKey(newBrokkKey);
        }

        // -- Apply Theme --
        // Find the themeRadioPanel (it's the component at gridx=1, gridy=1 based on GridBagLayout)
        Component themeComponent = null;
        for (Component comp : globalPanel.getComponents()) {
            var constraints = ((GridBagLayout) globalPanel.getLayout()).getConstraints(comp);
            if (constraints.gridx == 1 && constraints.gridy == 1 && comp instanceof JPanel) { // Find the panel holding radio buttons
                 themeComponent = comp;
                 break;
            }
        }

        if (themeComponent instanceof JPanel themeRadioPanel) {
            for (Component radioComp : themeRadioPanel.getComponents()) {
                if (radioComp instanceof JRadioButton radio && radio.isSelected()) {
                    boolean useDark = (Boolean) radio.getClientProperty("theme");
                    // Only switch theme if it actually changed
                    if (useDark != Project.getTheme().equals("dark")) {
                        chrome.switchTheme(useDark); // switchTheme calls Project.setTheme internally
                    }
                    break;
                }
            }
        } else {
            // Log error or handle case where theme panel wasn't found as expected
             System.err.println("Could not find theme radio button panel in SettingsDialog.");
        }


        // Apply Project Settings (if project is open and tab is enabled)
        var project = chrome.getProject();
        if (project != null && tabbedPane.isEnabledAt(1)) {
            // Get current details to compare against and preserve non-editable fields
            var currentDetails = project.getBuildDetails();
            if (currentDetails == null) {
                currentDetails = BuildAgent.BuildDetails.EMPTY; // Use empty if somehow null
            }

            // Read potentially edited values from Build tab
            var newBuildLint = buildCleanCommandField.getText();
            var newTestAll = allTestsCommandField.getText();
            var newInstructions = buildInstructionsArea.getText();

            // Create a new BuildDetails record with updated fields
            var newDetails = new BuildAgent.BuildDetails(
                    currentDetails.buildfiles().isEmpty() ? java.util.List.of() : currentDetails.buildfiles(),
                    currentDetails.dependencies(),
                    newBuildLint,
                    newTestAll,
                    newInstructions
            );

            // Only save if details have actually changed
            if (!newDetails.equals(currentDetails)) {
                project.saveBuildDetails(newDetails);
            }

            // Apply CPG Refresh Setting
            var selectedRefresh = (Project.CpgRefresh) cpgRefreshComboBox.getSelectedItem();
            if (selectedRefresh != project.getCpgRefresh()) {
                project.setCpgRefresh(selectedRefresh);
            }
        }
    }
}
