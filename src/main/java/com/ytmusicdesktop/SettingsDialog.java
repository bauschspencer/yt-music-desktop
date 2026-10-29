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
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

/** Settings window, opened from the tray. Changes apply and save immediately. */
final class SettingsDialog {

    private final JDialog dialog = new JDialog((Frame) null, "YT Music Settings");
    private final JLabel updateStatus = new JLabel("Updates: checking...");
    private final JButton releasePage = new JButton("Open release page");
    private URI releasePageUri;

    /** {@code dataFolder} is opened by "Open data folder"; {@code deleteData} signs out, quits and deletes it all. */
    SettingsDialog(Settings settings, Path dataFolder, Runnable deleteData) {
        JCheckBox autostart = new JCheckBox("Start with Windows (in the tray)", Autostart.isEnabled());
        autostart.addActionListener(e -> {
            Autostart.setEnabled(autostart.isSelected());
            autostart.setSelected(Autostart.isEnabled()); // Show what actually happened.
        });
        autostart.setEnabled(Autostart.isAvailable());
        JPanel startup = Autostart.isAvailable()
                ? section("Startup", autostart)
                : section("Startup", autostart, new JLabel("Only available in the installed app."));

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

        JCheckBox pauseOnLock = new JCheckBox("Pause when the PC locks", settings.pauseOnLock);
        JCheckBox resumeOnUnlock = new JCheckBox("Resume when unlocked", settings.resumeOnUnlock);
        resumeOnUnlock.setBorder(BorderFactory.createEmptyBorder(2, 22, 2, 2)); // Indented under the first.
        resumeOnUnlock.setEnabled(settings.pauseOnLock);
        pauseOnLock.addActionListener(e -> {
            settings.pauseOnLock = pauseOnLock.isSelected();
            settings.save();
            resumeOnUnlock.setEnabled(settings.pauseOnLock);
        });
        resumeOnUnlock.addActionListener(e -> {
            settings.resumeOnUnlock = resumeOnUnlock.isSelected();
            settings.save();
        });

        JButton signOut = new JButton("Sign out and delete local data...");
        signOut.addActionListener(e -> {
            if (confirmDeleteData()) {
                signOut.setEnabled(false);
                dialog.setVisible(false);
                deleteData.run();
            }
        });

        JButton openData = new JButton("Open data folder");
        openData.addActionListener(e -> openFolder(dataFolder));
        releasePage.addActionListener(e -> openReleasePage());
        releasePage.setVisible(false);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 0, 12));
        content.add(startup);
        content.add(Box.createVerticalStrut(10));
        content.add(section("When closing the main window", toTray, quit));
        content.add(Box.createVerticalStrut(10));
        content.add(section("Playback", pauseOnLock, resumeOnUnlock));
        content.add(Box.createVerticalStrut(10));
        content.add(section("Sign-in and data",
                new JLabel("Removes your sign-in and everything else this app keeps on this PC."), row(signOut)));
        content.add(Box.createVerticalStrut(10));
        content.add(section("About", new JLabel("YT Music Desktop " + App.VERSION), updateStatus,
                row(openData, releasePage)));

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

    /** Shows the startup update check's result in the About section. */
    void showUpdateResult(UpdateCheck.Result result) {
        updateStatus.setText("Updates: " + result.summary());
        releasePageUri = result.page();
        releasePage.setVisible(releasePageUri != null);
        dialog.pack();
    }

    private void openReleasePage() {
        try {
            Desktop.getDesktop().browse(releasePageUri);
        } catch (IOException e) {
            System.err.println("Could not open " + releasePageUri + ": " + e.getMessage());
        }
    }

    private boolean confirmDeleteData() {
        String[] options = {"Sign out and delete", "Cancel"};
        int choice = JOptionPane.showOptionDialog(dialog,
                "YT Music will sign out of Google, delete everything it keeps on this PC (your sign-in,\n"
                        + "cache, settings, window positions and log), turn off Start with Windows, and quit.\n\n"
                        + "Your Google account, library and playlists aren't affected. To remove the app itself\n"
                        + "as well, uninstall it afterwards from Settings > Apps > Installed apps.",
                "Sign out and delete local data", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                null, options, options[1]);
        return choice == 0;
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
        // Stretch to the dialog's width; sized to its rows alone, a short section cuts off its title.
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));
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
