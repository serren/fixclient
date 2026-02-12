package com.epam.QuickFIX;

import quickfix.ConfigError;
import quickfix.SessionSettings;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Scanner;

/**
 * Точка входа консольного FIX 4.4 приложения.
 * <p>
 * Поддерживает два режима работы:
 * <ul>
 *   <li><b>Initiator</b> — клиент, подключающийся к серверу (ConnectionType=initiator)</li>
 *   <li><b>Acceptor</b>  — сервер, принимающий входящие подключения (ConnectionType=acceptor)</li>
 * </ul>
 * <p>
 * Режим определяется автоматически по значению {@code ConnectionType} в .cfg файле.
 * <p>
 * Запуск: {@code java Starter <path-to-config.cfg>}
 * <p>
 * Примеры:
 * <pre>
 *   java Starter initiator-session.cfg   — запуск в режиме Initiator
 *   java Starter acceptor-session.cfg    — запуск в режиме Acceptor
 * </pre>
 */
public class Starter {

    private static final String CONNECTION_TYPE_INITIATOR = "initiator";
    private static final String CONNECTION_TYPE_ACCEPTOR = "acceptor";

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
     * Запускает приложение в режиме Initiator.
     */
    private static void startInitiator(SessionSettings settings) throws Exception {
        FixInitiator fixInitiator = new FixInitiator(settings);
        fixInitiator.start();
        runInitiatorCommandLoop(fixInitiator);
    }
    
    /**
     * Запускает приложение в режиме Acceptor.
     */
    private static void startAcceptor(SessionSettings settings) throws Exception {
        FixAcceptor fixAcceptor = new FixAcceptor(settings);
        fixAcceptor.start();
        runAcceptorCommandLoop(fixAcceptor);
    }

    /**
     * Определяет тип подключения (initiator/acceptor) из загруженных настроек.
     *
     * @param settings   загруженные настройки сессии
     * @return значение ConnectionType в нижнем регистре
     */
    private static String detectConnectionType(SessionSettings settings) {
        return settings.getDefaultProperties()
                .getProperty("ConnectionType", "")
                .trim()
                .toLowerCase();
    }

    /**
     * Проверяет, что путь к конфигурационному файлу передан и файл существует.
     *
     * @param args аргументы командной строки
     * @return путь к .cfg файлу или {@code null}, если валидация не пройдена
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

    // ── Загрузка конфигурации ────────────────────────────────────────
    
    /**
     * Загружает {@link SessionSettings} из файла.
     * <p>
     * Сначала ищет файл в файловой системе, затем в classpath.
     *
     * @param configFilePath путь к .cfg файлу
     * @return загруженные настройки сессии
     * @throws ConfigError           если конфигурация невалидна
     * @throws FileNotFoundException если файл не найден
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
    
    // ── Интерактивные циклы команд ──────────────────────────────────────

    /**
     * Интерактивный цикл команд для Initiator-режима.
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
                    case "help" -> {
                        System.out.println("Commands: status, logout, quit, help");
                    }
                    case "" -> { /* ignore empty input */ }
                    default ->
                            System.out.println("Unknown command: '" + command + "'. Type 'help' for available commands.");
                }
            }
        }
    }

    /**
     * Интерактивный цикл команд для Acceptor-режима.
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
                    case "clients" -> {
                        fixAcceptor.printConnectedClients();
                    }
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
                    case "help" -> {
                        System.out.println("Commands: status, clients, logout, quit, help");
                    }
                    case "" -> { /* ignore empty input */ }
                    default ->
                            System.out.println("Unknown command: '" + command + "'. Type 'help' for available commands.");
                }
            }
        }
    }
}