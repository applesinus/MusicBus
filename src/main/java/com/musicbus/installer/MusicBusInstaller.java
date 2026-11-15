package com.musicbus.installer;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public final class MusicBusInstaller {

    private MusicBusInstaller() {}

    public static void main(String[] args) {
        if (args != null && args.length > 0) {
            int exitCode = runCli(args);
            if (exitCode != 0) {
                System.exit(exitCode);
            }
            return;
        }

        if (GraphicsEnvironment.isHeadless()) {
            printHelp();
            return;
        }

        SwingUtilities.invokeLater(MusicBusInstaller::showGui);
    }

    // -------------------------------------------------------------------------
    // CLI mode
    // -------------------------------------------------------------------------

    private static int runCli(String[] args) {
        boolean install = false;
        boolean uninstall = false;
        boolean createProfile = true;
        Path mcDir = MinecraftLocator.findDefaultMinecraftDir();
        String requestedVersion = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--help", "-h" -> {
                    printHelp();
                    return 0;
                }
                case "--install" -> install = true;
                case "--uninstall" -> uninstall = true;
                case "--no-profile" -> createProfile = false;
                case "--mc-dir" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("Missing value for --mc-dir");
                        return 1;
                    }
                    mcDir = Paths.get(args[++i]);
                }
                case "--version" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("Missing value for --version");
                        return 1;
                    }
                    requestedVersion = args[++i];
                }
                default -> {
                    System.err.println("Unknown option: " + arg);
                    printHelp();
                    return 1;
                }
            }
        }

        if (install == uninstall) {
            System.err.println("Specify either --install or --uninstall.");
            printHelp();
            return 1;
        }

        try {
            if (install) {
                List<MinecraftLocator.BaseVersion> versions = MinecraftLocator.findBaseVersions(mcDir);
                if (versions.isEmpty()) {
                    System.err.println("No installed Minecraft versions found in: " + mcDir.resolve("versions"));
                    return 1;
                }

                MinecraftLocator.BaseVersion target = versions.getFirst();
                if (requestedVersion != null) {
                    String wanted = requestedVersion;
                    target = versions.stream()
                            .filter(v -> v.id().equals(wanted))
                            .findFirst()
                            .orElse(null);
                    if (target == null) {
                        System.err.println("Base version not found: " + requestedVersion);
                        return 1;
                    }
                }

                MusicBusInstallService.InstallResult result =
                        MusicBusInstallService.install(mcDir, target, createProfile);
                System.out.println("Installed MusicBus version: " + result.versionId());
                System.out.println("Version JSON: " + result.versionJsonFile());
                System.out.println("Agent JAR: " + result.libraryJarFile());
                for (Path p : result.updatedProfileFiles()) {
                    System.out.println("Updated launcher profile: " + p);
                }
                return 0;
            } else {
                List<MinecraftLocator.InstalledMusicBusVersion> installed =
                        MinecraftLocator.findInstalledMusicBusVersions(mcDir);
                if (installed.isEmpty()) {
                    System.err.println("No installed MusicBus versions found in: " + mcDir.resolve("versions"));
                    return 1;
                }

                MinecraftLocator.InstalledMusicBusVersion target = installed.getFirst();
                if (requestedVersion != null) {
                    String wanted = requestedVersion;
                    target = installed.stream()
                            .filter(v -> v.id().equals(wanted))
                            .findFirst()
                            .orElse(null);
                    if (target == null) {
                        System.err.println("Installed MusicBus version not found: " + requestedVersion);
                        return 1;
                    }
                }

                MusicBusInstallService.UninstallResult result =
                        MusicBusInstallService.uninstall(mcDir, target);
                System.out.println("Removed MusicBus version: " + result.removedVersionId());
                if (result.removedLibraryJar()) {
                    System.out.println("Removed unused MusicBus library JAR.");
                }
                for (Path p : result.updatedProfileFiles()) {
                    System.out.println("Updated launcher profile: " + p);
                }
                return 0;
            }
        } catch (Exception e) {
            System.err.println("Operation failed: " + e.getMessage());
            return 1;
        }
    }

    private static void printHelp() {
        System.out.println("MusicBus Installer " + MusicBusInstallService.getModVersion());
        System.out.println("Usage:");
        System.out.println("  java -jar MusicBus.jar                        Open graphical installer");
        System.out.println("  java -jar MusicBus.jar --install [options]    Install MusicBus");
        System.out.println("  java -jar MusicBus.jar --uninstall [options]  Uninstall MusicBus");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --mc-dir <path>     Path to .minecraft directory");
        System.out.println("  --version <id>      Base Minecraft version (for --install) or installed MusicBus ID (for --uninstall)");
        System.out.println("  --no-profile        Do not modify launcher_profiles.json");
        System.out.println("  --help, -h          Show this help");
    }

    // -------------------------------------------------------------------------
    // Swing GUI mode
    // -------------------------------------------------------------------------

    private static void showGui() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Fall back to default Swing L&F.
        }

        new InstallerWindow().show();
    }

    private static final class InstallerWindow {

        private final JFrame frame;
        private final JTextField mcDirField;
        private final JComboBox<MinecraftLocator.BaseVersion> versionCombo;
        private final JCheckBox profileCheckBox;
        private final JLabel launcherHintLabel;
        private final JLabel statusLabel;
        private final JButton browseButton;
        private final JButton installButton;
        private final JButton uninstallButton;
        private final JButton closeButton;

        private List<MinecraftLocator.InstalledMusicBusVersion> installedVersions = List.of();
        private final Timer refreshDebounceTimer;

        InstallerWindow() {
            String modVersion = MusicBusInstallService.getModVersion();
            frame = new JFrame("MusicBus Installer " + modVersion);
            frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            frame.setResizable(false);

            BufferedImage windowIcon = loadResourceImage("/assets/musicbus/iconbg.png");
            if (windowIcon != null) {
                frame.setIconImage(windowIcon);
            }

            JPanel root = new JPanel(new BorderLayout(0, 14));
            root.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));

            // Header
            JPanel headerPanel = new JPanel(new BorderLayout(12, 0));
            BufferedImage headerImage = loadResourceImage("/assets/musicbus/icon.png");
            if (headerImage != null) {
                Image scaled = headerImage.getScaledInstance(40, 40, Image.SCALE_SMOOTH);
                JLabel iconLabel = new JLabel(new ImageIcon(scaled));
                headerPanel.add(iconLabel, BorderLayout.WEST);
            }

            JPanel titleBox = new JPanel();
            titleBox.setLayout(new BoxLayout(titleBox, BoxLayout.Y_AXIS));
            JLabel titleLabel = new JLabel("MusicBus " + modVersion);
            titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 17f));
            JLabel subtitleLabel = new JLabel("Isolate Minecraft background music into a separate audio process");
            subtitleLabel.setForeground(new Color(95, 95, 95));
            titleBox.add(titleLabel);
            titleBox.add(Box.createVerticalStrut(2));
            titleBox.add(subtitleLabel);
            headerPanel.add(titleBox, BorderLayout.CENTER);
            root.add(headerPanel, BorderLayout.NORTH);

            // Form
            JPanel formPanel = new JPanel(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(5, 0, 5, 0);
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.anchor = GridBagConstraints.WEST;

            // Row 0: Folder label
            gbc.gridx = 0;
            gbc.gridy = 0;
            gbc.weightx = 0;
            gbc.gridwidth = 2;
            formPanel.add(new JLabel("Minecraft Folder:"), gbc);

            // Row 1: Folder field + Browse
            gbc.gridy = 1;
            gbc.gridwidth = 1;
            gbc.weightx = 1.0;
            mcDirField = new JTextField(MinecraftLocator.findDefaultMinecraftDir().toString(), 32);
            formPanel.add(mcDirField, gbc);

            gbc.gridx = 1;
            gbc.weightx = 0;
            gbc.insets = new Insets(5, 8, 5, 0);
            browseButton = new JButton("Browse...");
            formPanel.add(browseButton, gbc);

            // Row 2: Version label
            gbc.gridx = 0;
            gbc.gridy = 2;
            gbc.gridwidth = 2;
            gbc.weightx = 1.0;
            gbc.insets = new Insets(8, 0, 3, 0);
            formPanel.add(new JLabel("Minecraft Version:"), gbc);

            // Row 3: Version combo
            gbc.gridy = 3;
            gbc.insets = new Insets(2, 0, 6, 0);
            versionCombo = new JComboBox<>();
            versionCombo.setPreferredSize(new Dimension(380, versionCombo.getPreferredSize().height + 4));
            formPanel.add(versionCombo, gbc);

            // Row 4: Launcher profile checkbox
            gbc.gridy = 4;
            gbc.insets = new Insets(4, 0, 2, 0);
            profileCheckBox = new JCheckBox("Create / update \"MusicBus\" profile in Minecraft Launcher", true);
            formPanel.add(profileCheckBox, gbc);

            // Row 5: Launcher warning / hint
            gbc.gridy = 5;
            gbc.insets = new Insets(6, 0, 2, 0);
            launcherHintLabel = new JLabel(" ");
            formPanel.add(launcherHintLabel, gbc);

            // Row 6: Status label
            gbc.gridy = 6;
            gbc.insets = new Insets(2, 0, 0, 0);
            statusLabel = new JLabel(" ", SwingConstants.LEFT);
            statusLabel.setForeground(new Color(80, 80, 80));
            formPanel.add(statusLabel, gbc);

            root.add(formPanel, BorderLayout.CENTER);

            // Buttons
            JPanel buttonBar = new JPanel(new BorderLayout());
            JPanel leftButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            uninstallButton = new JButton("Uninstall");
            leftButtons.add(uninstallButton);

            JPanel rightButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            installButton = new JButton("Install");
            closeButton = new JButton("Close");
            rightButtons.add(installButton);
            rightButtons.add(closeButton);

            buttonBar.add(leftButtons, BorderLayout.WEST);
            buttonBar.add(rightButtons, BorderLayout.EAST);
            root.add(buttonBar, BorderLayout.SOUTH);

            frame.setContentPane(root);
            frame.getRootPane().setDefaultButton(installButton);

            refreshDebounceTimer = new Timer(180, e -> refreshVersions());
            refreshDebounceTimer.setRepeats(false);

            wireEvents();
            refreshVersions();

            frame.pack();
            frame.setMinimumSize(new Dimension(470, frame.getHeight()));
            frame.setLocationRelativeTo(null);
        }

        void show() {
            frame.setVisible(true);
        }

        private void wireEvents() {
            browseButton.addActionListener(e -> chooseDirectory());
            installButton.addActionListener(e -> performInstall());
            uninstallButton.addActionListener(e -> performUninstall());
            closeButton.addActionListener(e -> frame.dispose());

            mcDirField.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    refreshDebounceTimer.restart();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    refreshDebounceTimer.restart();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    refreshDebounceTimer.restart();
                }
            });
        }

        private void chooseDirectory() {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("Select .minecraft Folder");

            Path current = currentMcDir();
            if (current != null && Files.isDirectory(current)) {
                chooser.setCurrentDirectory(current.toFile());
            }

            if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
                mcDirField.setText(chooser.getSelectedFile().getAbsolutePath());
                refreshVersions();
            }
        }

        private void refreshVersions() {
            Path mcDir = currentMcDir();
            List<MinecraftLocator.BaseVersion> baseVersions = MinecraftLocator.findBaseVersions(mcDir);
            installedVersions = MinecraftLocator.findInstalledMusicBusVersions(mcDir);

            DefaultComboBoxModel<MinecraftLocator.BaseVersion> model = new DefaultComboBoxModel<>();
            for (MinecraftLocator.BaseVersion v : baseVersions) {
                model.addElement(v);
            }
            versionCombo.setModel(model);
            versionCombo.setEnabled(!baseVersions.isEmpty());
            installButton.setEnabled(!baseVersions.isEmpty());
            uninstallButton.setEnabled(!installedVersions.isEmpty());

            updateLauncherHint();

            if (mcDir == null || !Files.isDirectory(mcDir)) {
                statusLabel.setText("Folder does not exist yet. Select your .minecraft folder.");
            } else if (baseVersions.isEmpty()) {
                statusLabel.setText("No installed base versions found. Launch Minecraft once in the Launcher.");
            } else if (!installedVersions.isEmpty()) {
                statusLabel.setText("Installed: " + installedVersions.getFirst().id()
                        + (installedVersions.size() > 1 ? " (+" + (installedVersions.size() - 1) + " more)" : ""));
            } else {
                statusLabel.setText("Ready to install (" + baseVersions.size() + " base version(s) found).");
            }
        }

        private void updateLauncherHint() {
            if (MusicBusInstallService.isMinecraftLauncherRunning()) {
                launcherHintLabel.setText("Minecraft Launcher is running! Close it before installing.");
                launcherHintLabel.setForeground(new Color(180, 60, 0));
            } else {
                launcherHintLabel.setText("Make sure Minecraft Launcher is closed before installing.");
                launcherHintLabel.setForeground(new Color(100, 100, 100));
            }
        }

        private boolean confirmIfLauncherRunning() {
            updateLauncherHint();
            if (!profileCheckBox.isSelected() || !MusicBusInstallService.isMinecraftLauncherRunning()) {
                return true;
            }
            int choice = JOptionPane.showConfirmDialog(
                    frame,
                    "Minecraft Launcher appears to be running.\n"
                            + "An open launcher may overwrite launcher_profiles.json when it closes.\n\n"
                            + "Please close Minecraft Launcher and click Yes to continue anyway, or No to cancel.",
                    "Minecraft Launcher Running",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE
            );
            return choice == JOptionPane.YES_OPTION;
        }

        private void performInstall() {
            MinecraftLocator.BaseVersion selected =
                    (MinecraftLocator.BaseVersion) versionCombo.getSelectedItem();
            Path mcDir = currentMcDir();
            if (mcDir == null || selected == null) {
                return;
            }
            if (!confirmIfLauncherRunning()) {
                return;
            }

            boolean updateProfile = profileCheckBox.isSelected();
            setBusy(true, "Installing MusicBus for " + selected.id() + "...");

            new SwingWorker<MusicBusInstallService.InstallResult, Void>() {
                @Override
                protected MusicBusInstallService.InstallResult doInBackground() throws Exception {
                    return MusicBusInstallService.install(mcDir, selected, updateProfile);
                }

                @Override
                protected void done() {
                    setBusy(false, null);
                    try {
                        MusicBusInstallService.InstallResult result = get();
                        refreshVersions();
                        statusLabel.setText("Installed: " + result.versionId());

                        String message = "MusicBus was successfully installed!\n\n"
                                + "Version: " + result.versionId() + "\n"
                                + (result.updatedProfileFiles().isEmpty()
                                        ? "Select this version manually in your launcher."
                                        : "Profile \"MusicBus\" is ready in Minecraft Launcher.");

                        JOptionPane.showMessageDialog(
                                frame,
                                message,
                                "Installation Complete",
                                JOptionPane.INFORMATION_MESSAGE
                        );
                    } catch (Exception ex) {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        statusLabel.setText("Installation failed.");
                        JOptionPane.showMessageDialog(
                                frame,
                                "Failed to install MusicBus:\n" + cause.getMessage(),
                                "Installation Error",
                                JOptionPane.ERROR_MESSAGE
                        );
                    }
                }
            }.execute();
        }

        private void performUninstall() {
            Path mcDir = currentMcDir();
            if (mcDir == null || installedVersions.isEmpty()) {
                return;
            }

            MinecraftLocator.InstalledMusicBusVersion target;
            if (installedVersions.size() == 1) {
                target = installedVersions.getFirst();
                int confirm = JOptionPane.showConfirmDialog(
                        frame,
                        "Remove installed MusicBus version \"" + target.id() + "\"?",
                        "Confirm Uninstall",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE
                );
                if (confirm != JOptionPane.YES_OPTION) {
                    return;
                }
            } else {
                Object chosen = JOptionPane.showInputDialog(
                        frame,
                        "Select MusicBus version to remove:",
                        "Uninstall MusicBus",
                        JOptionPane.QUESTION_MESSAGE,
                        null,
                        installedVersions.toArray(),
                        installedVersions.getFirst()
                );
                if (!(chosen instanceof MinecraftLocator.InstalledMusicBusVersion selected)) {
                    return;
                }
                target = selected;
            }

            if (!confirmIfLauncherRunning()) {
                return;
            }

            setBusy(true, "Uninstalling " + target.id() + "...");

            new SwingWorker<MusicBusInstallService.UninstallResult, Void>() {
                @Override
                protected MusicBusInstallService.UninstallResult doInBackground() throws Exception {
                    return MusicBusInstallService.uninstall(mcDir, target);
                }

                @Override
                protected void done() {
                    setBusy(false, null);
                    try {
                        MusicBusInstallService.UninstallResult result = get();
                        refreshVersions();
                        statusLabel.setText("Uninstalled: " + result.removedVersionId());
                        JOptionPane.showMessageDialog(
                                frame,
                                "Removed MusicBus version: " + result.removedVersionId(),
                                "Uninstall Complete",
                                JOptionPane.INFORMATION_MESSAGE
                        );
                    } catch (Exception ex) {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        statusLabel.setText("Uninstall failed.");
                        JOptionPane.showMessageDialog(
                                frame,
                                "Failed to uninstall MusicBus:\n" + cause.getMessage(),
                                "Uninstall Error",
                                JOptionPane.ERROR_MESSAGE
                        );
                    }
                }
            }.execute();
        }

        private void setBusy(boolean busy, String message) {
            mcDirField.setEnabled(!busy);
            browseButton.setEnabled(!busy);
            versionCombo.setEnabled(!busy && versionCombo.getItemCount() > 0);
            profileCheckBox.setEnabled(!busy);
            installButton.setEnabled(!busy && versionCombo.getItemCount() > 0);
            uninstallButton.setEnabled(!busy && !installedVersions.isEmpty());
            closeButton.setEnabled(!busy);
            if (message != null) {
                statusLabel.setText(message);
            }
        }

        private Path currentMcDir() {
            String raw = mcDirField.getText();
            if (raw == null || raw.isBlank()) {
                return null;
            }
            try {
                return Paths.get(raw.trim());
            } catch (Exception ignored) {
                return null;
            }
        }

        private static BufferedImage loadResourceImage(String resourcePath) {
            try (InputStream in = MusicBusInstaller.class.getResourceAsStream(resourcePath)) {
                if (in != null) {
                    return ImageIO.read(in);
                }
            } catch (Exception ignored) {
                // Ignore missing or unreadable image.
            }
            return null;
        }
    }
}