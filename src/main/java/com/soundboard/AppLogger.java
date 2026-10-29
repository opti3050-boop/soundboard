package com.soundboard;

import java.io.IOException;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class AppLogger {
    // Obtain a global tracker name for the application
    private static final Logger logger = Logger.getLogger("WindowsAppGlobalLogger");
    private static boolean isInitialized = false;

    // Static initialization block or method to configure the file destination
    public static synchronized void initialize() {
        if (isInitialized) return;

        try {
            // Logs will save to 'app_output.log' in the application's root directory
            // Changing 'true' to 'false' overwrites the file instead of appending
            FileHandler fileHandler = new FileHandler("app_output.log", true);
            fileHandler.setFormatter(new SimpleFormatter());
            
            // Add file handler and set the base logging threshold level
            logger.addHandler(fileHandler);
            logger.setLevel(Level.ALL); // Capture all levels (INFO, WARNING, SEVERE, etc.)

            // Optional: Ensure logs also stream to the console/IDE terminal
            ConsoleHandler consoleHandler = new ConsoleHandler();
            consoleHandler.setLevel(Level.ALL);
            logger.addHandler(consoleHandler);

            isInitialized = true;
            logger.info("Logging framework initialized successfully.");
        } catch (IOException e) {
            System.err.println("Failed to initialize log file handler: " + e.getMessage());
        }
    }

    // Static helper methods accessible anywhere in the application
    public static void info(String message) {
        logger.info(message);
    }

    public static void warn(String message) {
        logger.warning(message);
    }

    public static void error(String message, Throwable throwable) {
        logger.log(Level.SEVERE, message, throwable);
    }
}
