package io.github.jbellis.brokk.gui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * Helper row panel for editing secret keys in a single row
 */
public class KeyValueRowPanel extends JPanel {
    private final JComboBox<String> keyNameCombo;
    private final JTextField keyValueField;

    public KeyValueRowPanel(String[] defaultKeyNames) {
        this(defaultKeyNames, "", "");
    }

    public KeyValueRowPanel(String[] defaultKeyNames, String initialKey, String initialValue) {
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        setBorder(new EmptyBorder(5, 0, 5, 0));

        keyNameCombo = new JComboBox<>(defaultKeyNames);
        keyNameCombo.setEditable(true);

        if (!initialKey.isEmpty()) {
            boolean found = false;
            for (int i = 0; i < defaultKeyNames.length; i++) {
                if (defaultKeyNames[i].equals(initialKey)) {
                    keyNameCombo.setSelectedIndex(i);
                    found = true;
                    break;
                }
            }
            if (!found) {
                keyNameCombo.setSelectedItem(initialKey);
            }
        }

        keyValueField = new JTextField(initialValue);

        keyNameCombo.setPreferredSize(new Dimension(150, 25));
        keyValueField.setPreferredSize(new Dimension(250, 25));

        keyNameCombo.setMaximumSize(new Dimension(150, 25));
        keyValueField.setMaximumSize(new Dimension(Short.MAX_VALUE, 25));

        add(new JLabel("Key: "));
        add(keyNameCombo);
        add(Box.createRigidArea(new Dimension(10, 0)));
        add(new JLabel("Value: "));
        add(keyValueField);
    }

    public String getKeyName() {
        var selected = keyNameCombo.getSelectedItem();
        return (selected != null) ? selected.toString().trim() : "";
    }

    public String getKeyValue() {
        return keyValueField.getText().trim();
    }
}
