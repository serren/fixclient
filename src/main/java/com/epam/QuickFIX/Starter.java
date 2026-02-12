package com.epam.QuickFIX;

import quickfix.ConfigError;
import quickfix.SessionSettings;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Scanner;

/**
 * Entry point of the console FIX application.
 * <p>
 * Supports all FIX protocol versions: 4.0, 4.1, 4.2, 4.3, 4.4, 5.0, 5.0SP1, 5.0SP2 (FIXT 1.1).
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
        runInitiatorCommandLoop(fixInitiator);
    }
    
    /**
     * Starts the application in Acceptor mode.
     */
    private static void startAcceptor(SessionSettings settings) throws Exception {
        FixAcceptor fixAcceptor = new FixAcceptor(settings);
        fixAcceptor.start();
        runAcceptorCommandLoop(fixAcceptor);
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
    
    // ── Interactive command loops ──────────────────────────────────────

    /**
     * Interactive command loop for Initiator mode.
     */
    private static void runInitiatorCommandLoop(FixInitiator fixInitiator) {
        System.out.println("\n╔══════════════════════════════════════════════╗");
        System.out.println("║     FIX Initiator — Available commands:      ║");
        System.out.println("║  status  — check session status              ║");
        System.out.println("║  logout  — send Logout and disconnect        ║");
        System.out.println("║  quit    — stop initiator and exit           ║");
        System.out.println("╚══════════════════════════════════════════════╝");

        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("\nfix-initiator> ");
                String command = scanner.nextLine().trim().toLowerCase();

                switch (command) {
                    case "status" -> {
                        boolean loggedOn = fixInitiator.isLoggedOn();
                        System.out.println("Session status: " + (loggedOn ? "LOGGED ON ✓" : "DISCONNECTED ✗"));
                    }
                    case "logout" -> {
                        System.out.print("Logout reason (optional, press ENTER to skip): ");
                        String reason = scanner.nextLine().trim();
                        fixInitiator.logout(reason.isEmpty() ? "User initiated logout" : reason);
                        System.out.println("Logout request sent.");
                    }
                    case "quit", "exit", "q" -> {
                        fixInitiator.stop();
                        System.out.println("Goodbye!");
                        return;
                    }
                    case "help" -> System.out.println("Commands: status, logout, quit, help");
                    case "" -> { /* ignore empty input */ }
                    default ->
                            System.out.println("Unknown command: '" + command + "'. Type 'help' for available commands.");
                }
            }
        }
    }

    /**
     * Interactive command loop for Acceptor mode.
     */
    private static void runAcceptorCommandLoop(FixAcceptor fixAcceptor) {
        System.out.println("\n╔══════════════════════════════════════════════╗");
        System.out.println("║      FIX Acceptor — Available commands:      ║");
        System.out.println("║  status  — check session status & clients    ║");
        System.out.println("║  clients — show connected clients           ║");
        System.out.println("║  logout  — send Logout to connected client   ║");
        System.out.println("║  quit    — stop acceptor and exit            ║");
        System.out.println("╚══════════════════════════════════════════════╝");

        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("\nfix-acceptor> ");
                String command = scanner.nextLine().trim().toLowerCase();

                switch (command) {
                    case "status" -> {
                        int loggedOn = fixAcceptor.getLoggedOnSessionCount();
                        int total = fixAcceptor.getTotalSessionCount();
                        System.out.println("Sessions: " + loggedOn + "/" + total + " logged on"
                                + (loggedOn > 0 ? " ✓" : " ✗"));
                    }
                    case "clients" -> fixAcceptor.printConnectedClients();
                    case "logout" -> {
                        System.out.print("Logout reason (optional, press ENTER to skip): ");
                        String reason = scanner.nextLine().trim();
                        fixAcceptor.logout(reason.isEmpty() ? "Server initiated logout" : reason);
                        System.out.println("Logout request sent.");
                    }
                    case "quit", "exit", "q" -> {
                        fixAcceptor.stop();
                        System.out.println("Goodbye!");
                        return;
                    }
                    case "help" -> System.out.println("Commands: status, clients, logout, quit, help");
                    case "" -> { /* ignore empty input */ }
                    default ->
                            System.out.println("Unknown command: '" + command + "'. Type 'help' for available commands.");
                }
            }
        }
    }
}