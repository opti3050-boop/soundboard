package com.soundboard;

import javax.swing.*;

public class Main {
    public static void main(String[] args) {
		
		// Setup the logger at application startup
        AppLogger.initialize();
        
        
        // Use the system look-and-feel so it feels native on Linux Mint / GNOME
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> {
            MainWindow win = new MainWindow();
            win.setVisible(true);
        });
        
        AppLogger.info("Windows Application has started up.");
		
    }
}
