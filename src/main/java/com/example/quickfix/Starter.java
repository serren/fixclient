package com.example.quickfix;

import com.example.quickfix.application.FixAcceptor;
import com.example.quickfix.application.FixInitiator;
import quickfix.ConfigError;
import quickfix.SessionSettings;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;

/**
 * Entry point of the console FIX application.
 * <p>
 * Supports FIX protocol versions: 4.4.
 * The actual version is determined from the configuration file ({@code BeginString} field in the {@code [SESSION]} section).
 * <p>
 * Supports two operating modes:
 * <ul>
 *   <li><b>Initiator</b> — client that connects to a server (ConnectionType=initiator)</li>
 *   <li><b>Acceptor</b>  — server that accepts incoming connections (ConnectionType=acceptor)</li>
 * </ul>
 * <p>
 * The mode is determined automatically by the {@code ConnectionType} value in the .cfg file.
 * <p>
 * Launch: {@code java Starter <path-to-config.cfg>}
 * <p>
 * Examples:
 * <pre>
 *   java Starter initiator-session.cfg   — start in Initiator mode
 *   java Starter acceptor-session.cfg    — start in Acceptor mode
 * </pre>
 */
public class Starter {

    public static final String CONNECTION_TYPE_INITIATOR = "initiator";
    public static final String CONNECTION_TYPE_ACCEPTOR = "acceptor";

    public static void main(String[] args) {
        try {
            String configPath = validateConfigPath(args);
            if (configPath == null) {
                return;
            }
    
            SessionSettings settings = loadSettings(configPath);
            String connectionType = detectConnectionType(settings);
            if (connectionType.isEmpty()) {
                throw new IllegalArgumentException(
                        "ConnectionType is not specified in config file: " + configPath
                                + "\n  Add 'ConnectionType=initiator' or 'ConnectionType=acceptor' to [DEFAULT] section.");
            }

            System.out.println("[Starter] Detected ConnectionType: " + connectionType);

            switch (connectionType) {
                case CONNECTION_TYPE_INITIATOR -> startInitiator(settings);
                case CONNECTION_TYPE_ACCEPTOR -> startAcceptor(settings);
                default -> {
                    System.err.println("[ERROR] Unknown ConnectionType: '" + connectionType + "'");
                    System.err.println("  Supported values: initiator, acceptor");
                }
            }
        } catch (Exception e) {
            System.err.println("\n[ERROR] " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Starts the application in Initiator mode.
     */
    private static void startInitiator(SessionSettings settings) throws Exception {
        FixInitiator fixInitiator = new FixInitiator(settings);
        fixInitiator.start();
    }
    
    /**
     * Starts the application in Acceptor mode.
     */
    private static void startAcceptor(SessionSettings settings) throws Exception {
        FixAcceptor fixAcceptor = new FixAcceptor(settings);
        fixAcceptor.start();
    }

    /**
     * Detects the connection type (initiator/acceptor) from the loaded settings.
     *
     * @param settings   loaded session settings
     * @return ConnectionType value in lowercase
     */
    private static String detectConnectionType(SessionSettings settings) {
        return settings.getDefaultProperties()
                .getProperty("ConnectionType", "")
                .trim()
                .toLowerCase();
    }

    /**
     * Validates that the configuration file path is provided and the file exists.
     *
     * @param args command line arguments
     * @return path to the .cfg file, or {@code null} if validation failed
     */
    private static String validateConfigPath(String[] args) {
        if (args.length == 0) {
            System.err.println("[WARNING] Configuration file is not specified.");
            System.err.println();
            System.err.println("  Usage: java Starter <path-to-config.cfg>");
            System.err.println();
            System.err.println("  Examples:");
            System.err.println("    java Starter initiator-session.cfg   — start as Initiator (client)");
            System.err.println("    java Starter acceptor-session.cfg    — start as Acceptor (server)");
            System.err.println();
            System.err.println("  Sample configuration files:");
            System.err.println("    initiator-session.cfg  — Initiator config");
            System.err.println("    acceptor-session.cfg   — Acceptor config");
            return null;
        }

        String configPath = args[0];
        File configFile = new File(configPath);

        if (!configFile.exists()) {
            System.err.println("[WARNING] Configuration file not found: " + configFile.getAbsolutePath());
            System.err.println();
            System.err.println("  Please verify the path and try again.");
            System.err.println("  Sample configuration files:");
            System.err.println("    initiator-session.cfg  — Initiator config");
            System.err.println("    acceptor-session.cfg   — Acceptor config");
            return null;
        }

        if (!configFile.isFile()) {
            System.err.println("[WARNING] Specified path is not a file: " + configFile.getAbsolutePath());
            return null;
        }

        if (!configFile.canRead()) {
            System.err.println("[WARNING] Configuration file is not readable: " + configFile.getAbsolutePath());
            return null;
        }

        System.out.println("[Starter] Using config file: " + configFile.getAbsolutePath());
        return configPath;
    }

    // ── Configuration loading ────────────────────────────────────────
    
    /**
     * Loads {@link SessionSettings} from a file.
     * <p>
     * First looks for the file in the filesystem, then in the classpath.
     *
     * @param configFilePath path to the .cfg file
     * @return loaded session settings
     * @throws ConfigError           if the configuration is invalid
     * @throws FileNotFoundException if the file is not found
     */
    public static SessionSettings loadSettings(String configFilePath) throws ConfigError, FileNotFoundException {
        InputStream inputStream;
        try {
            inputStream = new FileInputStream(configFilePath);
            System.out.println("[Starter] Loaded config from file: " + configFilePath);
        } catch (FileNotFoundException e) {
            inputStream = Starter.class.getClassLoader().getResourceAsStream(configFilePath);
            if (inputStream == null) {
                throw new FileNotFoundException("Config file not found: " + configFilePath
                        + " (checked filesystem and classpath)");
            }
            System.out.println("[Starter] Loaded config from classpath: " + configFilePath);
        }
        return new SessionSettings(inputStream);
    }
}