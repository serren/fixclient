package com.epam.QuickFIX;

import quickfix.ConfigError;
import quickfix.DefaultMessageFactory;
import quickfix.FileLogFactory;
import quickfix.FileStoreFactory;
import quickfix.LogFactory;
import quickfix.MessageFactory;
import quickfix.MessageStoreFactory;
import quickfix.RuntimeError;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.SocketAcceptor;

import java.util.Iterator;

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
public class FixAcceptor {

    private final SocketAcceptor acceptor;
    private final FixApplication application;
    private final SessionSettings settings;

    /**
     * Creates a FIX Acceptor based on the provided session settings.
     *
     * @param settings session settings {@link SessionSettings}
     * @throws ConfigError if the configuration is invalid
     */
    public FixAcceptor(SessionSettings settings) throws ConfigError {
        this.settings = settings;
        this.application = new FixApplication();
        this.application.setConnectionType("acceptor");
    
        MessageStoreFactory storeFactory = new FileStoreFactory(settings);
        LogFactory logFactory = new FileLogFactory(settings);
        MessageFactory messageFactory = new DefaultMessageFactory();

        this.acceptor = new SocketAcceptor(
                application,
                storeFactory,
                settings,
                logFactory,
                messageFactory
        );

        printSessionInfo();
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
            application.initiateLogout(sessionId, reason);
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
            if (application.isLoggedOn(sessionId)) {
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
     * Prints information about the current number of connected clients.
     */
    public void printConnectedClients() {
        int loggedOn = getLoggedOnSessionCount();
        int total = getTotalSessionCount();
        
        System.out.println("\n[FIX Acceptor] Connected clients: " + loggedOn + "/" + total);
        
        if (loggedOn > 0) {
            System.out.println("[FIX Acceptor] Active sessions:");
            for (SessionID sessionId : acceptor.getSessions()) {
                if (application.isLoggedOn(sessionId)) {
                    System.out.println("  - " + sessionId.getTargetCompID() + " → " 
                            + sessionId.getSenderCompID() + " [" + sessionId.getBeginString() + "]");
                }
            }
        }
    }

    /**
     * Returns the {@link FixApplication} object for direct access to callbacks.
     */
    public FixApplication getApplication() {
        return application;
    }

    /**
     * Returns the session settings.
     */
    public SessionSettings getSettings() {
        return settings;
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

    // ── Internal methods ────────────────────────────────────────────────
    
    /**
     * Prints information about configured sessions.
     */
    private void printSessionInfo() {
        System.out.println("\n╔══════════════════════════════════════════════╗");
        System.out.println("║        FIX Acceptor — Session Info            ║");
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