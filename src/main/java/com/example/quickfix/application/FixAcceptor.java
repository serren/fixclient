package com.example.quickfix.application;

import com.example.quickfix.handler.CancelOrderHandler;
import com.example.quickfix.handler.NewSingleOrderHandler;
import com.example.quickfix.handler.ReplaceOrderHandler;
import com.example.quickfix.service.ExecutionReportService;
import com.example.quickfix.service.IExecutionReportService;
import com.example.quickfix.service.IncomingMessageProcessor;
import quickfix.ConfigError;
import quickfix.RuntimeError;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.SocketAcceptor;
import quickfix.field.MsgType;

import java.util.Iterator;
import java.util.Scanner;

/**
 * Wrapper around {@link SocketAcceptor} for managing a FIX Acceptor session.
 * <p>
 * Creates all necessary QuickFIX/J components (store, log, message factory)
 * and provides methods for starting, stopping, and managing the session.
 * <p>
 * Acceptor is the server side of a FIX connection. It listens for incoming
 * TCP connections from Initiator clients and accepts Logon requests.
 *
 * <h3>Usage example:</h3>
 * <pre>{@code
 *   SessionSettings settings = Starter.loadSettings("acceptor-session.cfg");
 *   FixAcceptor acceptor = new FixAcceptor(settings);
 *   acceptor.start();
 *   // ... accepting connections from Initiator clients ...
 *   acceptor.stop();
 * }</pre>
 */
public class FixAcceptor extends FixApplication {

    private final SocketAcceptor acceptor;

    /** Execution report service for simulating venue responses (Acceptor mode only). Set externally. */
    private final IExecutionReportService executionReportService;

    /**
     * Creates a FIX Acceptor based on the provided session settings.
     *
     * @param settings session settings {@link SessionSettings}
     * @throws ConfigError if the configuration is invalid
     */
    public FixAcceptor(SessionSettings settings) throws ConfigError {
        super(settings);
        this.executionReportService = new ExecutionReportService();
        this.incomingMessageProcessor = new IncomingMessageProcessor();

        this.acceptor = new SocketAcceptor(
                this,
                storeFactory,
                settings,
                logFactory,
                messageFactory
        );

        printSessionInfo();
    }

    @Override
    protected void initHandlers() {
        super.initHandlers();
        handlers.put(MsgType.ORDER_SINGLE, new NewSingleOrderHandler(executionReportService));
        handlers.put(MsgType.ORDER_CANCEL_REQUEST, new CancelOrderHandler(executionReportService));
        handlers.put(MsgType.ORDER_CANCEL_REPLACE_REQUEST, new ReplaceOrderHandler(executionReportService));
    }

    @Override
    public void onLogon(SessionID sessionId) {
        super.onLogon(sessionId);
        System.out.println("\n[FIX Acceptor] NEW CLIENT CONNECTED: " + sessionId.getTargetCompID()
                + " → " + sessionId.getSenderCompID() + " [" + sessionId.getBeginString() + "]");
    }

    @Override
    public void onLogout(SessionID sessionId) {
        super.onLogout(sessionId);
        System.out.println("\n[FIX Acceptor] CLIENT DISCONNECTED: " + sessionId.getTargetCompID()
                + " → " + sessionId.getSenderCompID() + " [" + sessionId.getBeginString() + "]");
    }

    /**
     * Starts the Acceptor — begins listening for incoming connections.
     * <p>
     * After invocation, QuickFIX/J automatically:
     * <ol>
     *   <li>Opens a server TCP socket on the specified port</li>
     *   <li>Waits for incoming connections from Initiator clients</li>
     *   <li>Upon receiving Logon (35=A) — validates SenderCompID/TargetCompID</li>
     *   <li>Confirms Logon and starts Heartbeat message exchange</li>
     * </ol>
     *
     * @throws RuntimeError if the acceptor failed to start
     * @throws ConfigError  if the configuration is invalid
     */
    public void start() throws RuntimeError, ConfigError {
        System.out.println("\n[FIX Acceptor] Starting...");
        acceptor.start();
        System.out.println("[FIX Acceptor] Started. Listening for incoming connections on port " + getAcceptPort() + "...");
        runAcceptorCommandLoop();
    }

    /**
     * Stops the Acceptor — sends Logout to all connected clients
     * and closes the server socket.
     * <p>
     * QuickFIX/J automatically:
     * <ol>
     *   <li>Sends Logout (35=5) to each connected Initiator</li>
     *   <li>Waits for Logout confirmation</li>
     *   <li>Closes all TCP connections</li>
     *   <li>Closes the server socket</li>
     * </ol>
     */
    public void stop() {
        System.out.println("\n[FIX Acceptor] Stopping...");
        executionReportService.shutdown();
        incomingMessageProcessor.shutdown();
        acceptor.stop();
        System.out.println("[FIX Acceptor] Stopped.");
    }

    /**
     * Initiates Logout for a specific session with the given reason.
     *
     * @param reason logout reason (will be sent in the Text (58) field)
     */
    public void logout(String reason) {
        Iterator<SessionID> it = acceptor.getSessions().iterator();
        if (it.hasNext()) {
            SessionID sessionId = it.next();
            initiateLogout(sessionId, reason);
        } else {
            System.out.println("[FIX Acceptor] No active sessions to logout.");
        }
    }

    /**
     * Returns the number of active (logged on) sessions.
     *
     * @return number of logged on sessions
     */
    public int getLoggedOnSessionCount() {
        int count = 0;
        for (SessionID sessionId : acceptor.getSessions()) {
            if (isLoggedOn(sessionId)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns the total number of configured sessions.
     *
     * @return number of sessions
     */
    public int getTotalSessionCount() {
        return acceptor.getSessions().size();
    }

    /**
     * Interactive command loop for Acceptor mode.
     */
    private void runAcceptorCommandLoop() {
        System.out.println("\n");
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║      FIX Acceptor — Available commands:      ║");
        System.out.println("║  status  — check session status & clients    ║");
        System.out.println("║  clients — show connected clients            ║");
        System.out.println("║  logout  — send Logout to connected client   ║");
        System.out.println("║  quit    — stop acceptor and exit            ║");
        System.out.println("╚══════════════════════════════════════════════╝");

        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("\nfix-acceptor> ");
                String command = scanner.nextLine().trim().toLowerCase();

                switch (command) {
                    case "status" -> {
                        int loggedOn = getLoggedOnSessionCount();
                        int total = getTotalSessionCount();
                        System.out.println("Sessions: " + loggedOn + "/" + total + " logged on"
                                + (loggedOn > 0 ? " ✓" : " ✗"));
                    }
                    case "clients" -> printConnectedClients();
                    case "logout" -> {
                        System.out.print("Logout reason (optional, press ENTER to skip): ");
                        String reason = scanner.nextLine().trim();
                        logout(reason.isEmpty() ? "Server initiated logout" : reason);
                        System.out.println("Logout request sent.");
                    }
                    case "quit", "exit", "q" -> {
                        stop();
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

    /**
     * Prints information about the current number of connected clients.
     */
    public void printConnectedClients() {
        int loggedOn = getLoggedOnSessionCount();
        int total = getTotalSessionCount();
        
        System.out.println("\n[FIX Acceptor] Connected clients: " + loggedOn + "/" + total);
        
        if (loggedOn > 0) {
            System.out.println("[FIX Acceptor] Active sessions:");
            for (SessionID sessionId : acceptor.getSessions()) {
                if (isLoggedOn(sessionId)) {
                    System.out.println("  - " + sessionId.getTargetCompID() + " → " 
                            + sessionId.getSenderCompID() + " [" + sessionId.getBeginString() + "]");
                }
            }
        }
    }

    /**
     * Returns the port on which the Acceptor is listening.
     * If there are multiple sessions with different ports, returns the first one.
     *
     * @return port or "N/A" if it could not be determined
     */
    private String getAcceptPort() {
        Iterator<SessionID> it = acceptor.getSessions().iterator();
        if (it.hasNext()) {
            SessionID sessionId = it.next();
            try {
                return settings.getSessionProperties(sessionId, true)
                        .getProperty("SocketAcceptPort", "N/A");
            } catch (ConfigError e) {
                return "N/A";
            }
        }
        return "N/A";
    }

    /**
     * Prints information about configured sessions.
     */
    private void printSessionInfo() {
        System.out.println("\n");
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║        FIX Acceptor — Session Info           ║");
        System.out.println("╠══════════════════════════════════════════════╣");

        for (SessionID sessionId : acceptor.getSessions()) {
            System.out.printf("║  BeginString   : %-27s ║%n", sessionId.getBeginString());
            System.out.printf("║  SenderCompID  : %-27s ║%n", sessionId.getSenderCompID());
            System.out.printf("║  TargetCompID  : %-27s ║%n", sessionId.getTargetCompID());

            try {
                String port = settings.getSessionProperties(sessionId, true)
                        .getProperty("SocketAcceptPort", "N/A");
                System.out.printf("║  Listen port   : %-27s ║%n", port);
            } catch (ConfigError e) {
                System.out.printf("║  Listen port   : %-27s ║%n", "N/A");
            }
        }

        System.out.println("╚══════════════════════════════════════════════╝");
    }
}