package com.example.quickfix.application;

import com.example.quickfix.latency.LatencyTracker;
import com.example.quickfix.service.IncomingMessageProcessor;
import com.example.quickfix.service.OrderGeneratorService;
import com.example.quickfix.service.OrderService;
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
import quickfix.SocketInitiator;

import java.util.Iterator;

/**
 * Wrapper around {@link SocketInitiator} for managing a FIX Initiator session.
 * <p>
 * Creates all necessary QuickFIX/J components (store, log, message factory)
 * and provides methods for starting, stopping, and managing the session.
 *
 * <h3>Usage example:</h3>
 * <pre>{@code
 *   SessionSettings settings = Starter.loadSettings("sample-initiator-session.cfg");
 *   FixInitiator initiator = new FixInitiator(settings);
 *   initiator.start();
 *   // ... working with the session ...
 *   initiator.stop();
 * }</pre>
 */
public class FixInitiator {

    private final SocketInitiator initiator;
    private final FixApplication application;
    private final SessionSettings settings;
    private final LatencyTracker latencyTracker;
    private final OrderService orderService;
    private final OrderGeneratorService orderGeneratorService;
    private final IncomingMessageProcessor incomingMessageProcessor;

    /**
     * Creates a FIX Initiator based on the provided session settings.
     *
     * @param settings session settings {@link SessionSettings}
     * @throws ConfigError if the configuration is invalid
     */
    public FixInitiator(SessionSettings settings) throws ConfigError {
        this.settings = settings;
        this.latencyTracker = new LatencyTracker();
        this.orderService = new OrderService(latencyTracker);
        this.orderGeneratorService = new OrderGeneratorService(orderService);
        this.incomingMessageProcessor = new IncomingMessageProcessor();
        this.application = new FixApplication();
        this.application.setConnectionType("initiator");
        this.application.setLatencyTracker(latencyTracker);
        this.application.setOrderService(orderService);
        this.application.setIncomingMessageProcessor(incomingMessageProcessor);
    
        MessageStoreFactory storeFactory = new FileStoreFactory(settings);
        LogFactory logFactory = new FileLogFactory(settings);
        MessageFactory messageFactory = new DefaultMessageFactory();

        this.initiator = new SocketInitiator(
                application,
                storeFactory,
                settings,
                logFactory,
                messageFactory
        );

        printSessionInfo();
    }

    /**
     * Starts the Initiator — begins connecting to the Acceptor server.
     * <p>
     * After invocation, QuickFIX/J automatically:
     * <ol>
     *   <li>Establishes a TCP connection</li>
     *   <li>Sends Logon (35=A)</li>
     *   <li>Waits for Logon confirmation from the server</li>
     *   <li>Starts Heartbeat message exchange</li>
     * </ol>
     *
     * @throws RuntimeError if the initiator failed to start
     * @throws ConfigError  if the configuration is invalid
     */
    public void start() throws RuntimeError, ConfigError {
        System.out.println("\n[FIX Initiator] Starting...");
        initiator.start();
        System.out.println("[FIX Initiator] Started. Connecting to server...");
    }

    /**
     * Stops the Initiator — sends Logout and closes the connection.
     * <p>
     * QuickFIX/J automatically:
     * <ol>
     *   <li>Sends Logout (35=5)</li>
     *   <li>Waits for Logout confirmation from the server</li>
     *   <li>Closes the TCP connection</li>
     * </ol>
     */
    public void stop() {
        System.out.println("\n[FIX Initiator] Stopping...");
        if (orderGeneratorService.isRunning()) {
            orderGeneratorService.stop();
        }
        incomingMessageProcessor.shutdown();
        initiator.stop();
        System.out.println("[FIX Initiator] Stopped.");
    }

    /**
     * Initiates Logout for a specific session with the given reason.
     *
     * @param reason logout reason (will be sent in the Text (58) field)
     */
    public void logout(String reason) {
        Iterator<SessionID> it = initiator.getSessions().iterator();
        if (it.hasNext()) {
            SessionID sessionId = it.next();
            application.initiateLogout(sessionId, reason);
        } else {
            System.out.println("[FIX Initiator] No active sessions to logout.");
        }
    }

    /**
     * Checks whether at least one session is logged on.
     *
     * @return {@code true} if there is an active logged-on session
     */
    public boolean isLoggedOn() {
        for (SessionID sessionId : initiator.getSessions()) {
            if (application.isLoggedOn(sessionId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the {@link OrderService} for sending orders.
     */
    public OrderService getOrderService() {
        return orderService;
    }
    
    /**
     * Returns the {@link OrderGeneratorService} for automated order generation.
     */
    public OrderGeneratorService getOrderGeneratorService() {
        return orderGeneratorService;
    }
    
    /**
     * Returns the {@link LatencyTracker} for viewing latency statistics.
     */
    public LatencyTracker getLatencyTracker() {
        return latencyTracker;
    }
    
    /**
     * Returns the first available {@link SessionID}, or {@code null} if none.
     */
    public SessionID getSessionId() {
        Iterator<SessionID> it = initiator.getSessions().iterator();
        return it.hasNext() ? it.next() : null;
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

    // ── Internal methods ────────────────────────────────────────────────
    
    /**
     * Prints information about configured sessions.
     */
    private void printSessionInfo() {
        System.out.println("\n");
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║       FIX Initiator — Session Info           ║");
        System.out.println("╠══════════════════════════════════════════════╣");

        for (SessionID sessionId : initiator.getSessions()) {
            System.out.printf("║  BeginString   : %-27s ║%n", sessionId.getBeginString());
            System.out.printf("║  SenderCompID  : %-27s ║%n", sessionId.getSenderCompID());
            System.out.printf("║  TargetCompID  : %-27s ║%n", sessionId.getTargetCompID());

            try {
                String host = settings.getSessionProperties(sessionId, true)
                        .getProperty("SocketConnectHost", "N/A");
                String port = settings.getSessionProperties(sessionId, true)
                        .getProperty("SocketConnectPort", "N/A");
                System.out.printf("║  Server        : %-27s ║%n", host + ":" + port);
            } catch (ConfigError e) {
                System.out.printf("║  Server        : %-27s ║%n", "N/A");
            }
        }

        System.out.println("╚══════════════════════════════════════════════╝");
    }
}
