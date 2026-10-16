package com.ytmusicdesktop;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.io.IOException;
import java.nio.file.Path;

/** Settings window, opened from the tray. Changes apply and save immediately. */
final class SettingsDialog {

    private final JDialog dialog = new JDialog((Frame) null, "YT Music Settings");

    SettingsDialog(Settings settings, Path customDir, Runnable reloadPage) {
        JRadioButton toTray = new JRadioButton("Minimize to tray", settings.closeToTray);
        JRadioButton quit = new JRadioButton("Quit the app", !settings.closeToTray);
        ButtonGroup closeGroup = new ButtonGroup();
        closeGroup.add(toTray);
        closeGroup.add(quit);
        toTray.addActionListener(e -> {
            settings.closeToTray = true;
            settings.save();
        });
        quit.addActionListener(e -> {
            settings.closeToTray = false;
            settings.save();
        });

        JCheckBox customEnabled = new JCheckBox("Load .css and .js files from the custom folder",
                settings.customCodeEnabled);
        customEnabled.addActionListener(e -> {
            settings.customCodeEnabled = customEnabled.isSelected();
            settings.save();
        });
        JButton openFolder = new JButton("Open folder");
        openFolder.addActionListener(e -> openFolder(customDir));
        JButton reload = new JButton("Reload page");
        reload.addActionListener(e -> reloadPage.run());
        JPanel customButtons = row(openFolder, reload);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 0, 12));
        content.add(section("When closing the main window", toTray, quit));
        content.add(Box.createVerticalStrut(10));
        content.add(section("Custom CSS/JS", customEnabled,
                new JLabel("Changes take effect after reloading the page."), customButtons));

        JButton close = new JButton("Close");
        close.addActionListener(e -> dialog.setVisible(false));
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        footer.add(close);

        dialog.getContentPane().add(content, BorderLayout.CENTER);
        dialog.getContentPane().add(footer, BorderLayout.SOUTH);
        dialog.getRootPane().setDefaultButton(close);
        dialog.setIconImages(Icons.appIcons());
        dialog.setResizable(false);
        dialog.pack();
        dialog.setLocationRelativeTo(null);
    }

    void show() {
        dialog.setVisible(true);
        dialog.toFront();
    }

    private void openFolder(Path dir) {
        try {
            Desktop.getDesktop().open(dir.toFile());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(dialog, "Could not open " + dir + ":\n" + e.getMessage(),
                    "YT Music", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static JPanel section(String title, JComponent... rows) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(title),
                BorderFactory.createEmptyBorder(4, 6, 6, 6)));
        panel.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        for (JComponent row : rows) {
            row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
            panel.add(row);
        }
        return panel;
    }

    private static JPanel row(JComponent... components) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 4));
        for (int i = 0; i < components.length; i++) {
            if (i > 0) {
                row.add(Box.createHorizontalStrut(6));
            }
            row.add(components[i]);
        }
        return row;
    }
}
